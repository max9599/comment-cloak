package io.github.maximgromov.commentcloak

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.LightweightHint
import com.intellij.util.Alarm
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Applies (and removes) the "hidden comment" fold regions of a single editor.
 *
 * All of the state lives in editor user data, so the object itself is stateless.
 */
object CommentFolder {

    /** Marks a [FoldRegion] created by this plugin. */
    @JvmField
    val COMMENT_CLOAK_REGION: Key<Boolean> = Key.create("COMMENT_CLOAK_REGION")

    /** Marks a foreign (IDE-created) fold region that we collapsed and should restore on clear. */
    @JvmField
    val COLLAPSED_FOREIGN: Key<Boolean> = Key.create("COMMENT_CLOAK_COLLAPSED_FOREIGN")

    private val STATE: Key<EditorState> = Key.create("COMMENT_CLOAK_EDITOR_STATE")

    private const val FOLD_PASS_DELAY_MS = 150
    private const val DOCUMENT_DELAY_MS = 300
    private const val CARET_DELAY_MS = 400
    private const val HOVER_DELAY_MS = 250
    private const val TOOLTIP_MAX_LINES = 25

    private class EditorState(val disposable: Disposable) {
        var applying: Boolean = false
        val revealed: MutableList<RangeMarker> = ArrayList()

        /** Ranges the last [apply] left visible only because a caret was sitting inside them. */
        var skippedForCaret: List<TextRange> = emptyList()

        /** Ranges the folding model refused; retried only after the document changes. */
        val failed: MutableSet<TextRange> = HashSet()

        lateinit var foldAlarm: Alarm
        lateinit var docAlarm: Alarm
        lateinit var caretAlarm: Alarm
        var hoverAlarm: Alarm? = null

        var hint: LightweightHint? = null
        var hintRegion: FoldRegion? = null

        fun addRevealed(document: Document, start: Int, end: Int) {
            if (start >= end || end > document.textLength) return
            if (revealed.any { it.isValid && it.startOffset == start && it.endOffset == end }) return
            revealed.add(document.createRangeMarker(start, end))
        }

        fun disposeRevealed() {
            revealed.forEach { if (it.isValid) it.dispose() }
            revealed.clear()
        }

        fun hideHint() {
            hoverAlarm?.cancelAllRequests()
            hint?.hide()
            hint = null
            hintRegion = null
        }
    }

    // ------------------------------------------------------------------ setup

    /** True for editors this plugin should manage. */
    @JvmStatic
    fun isSupported(editor: Editor): Boolean {
        if (editor.isDisposed || editor.isOneLineMode) return false
        return when (editor.editorKind) {
            EditorKind.MAIN_EDITOR, EditorKind.UNTYPED -> true
            else -> false
        }
    }

    /** Installs the folding/document/caret listeners for [editor]. Idempotent. */
    @JvmStatic
    fun install(editor: Editor, project: Project) {
        if (editor.isDisposed || project.isDisposed) return
        if (editor.getUserData(STATE) != null) return
        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return

        val disposable = Disposer.newDisposable("CommentCloak editor listeners")
        val state = EditorState(disposable)
        state.foldAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        state.docAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        state.caretAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        editor.putUserData(STATE, state)

        Disposer.register(disposable) {
            state.hideHint()
            state.disposeRevealed()
            editor.putUserData(STATE, null)
        }
        EditorUtil.disposeWithEditor(editor, disposable)

        foldingModel.addListener(object : FoldingListener {
            override fun onFoldRegionStateChange(region: FoldRegion) {
                if (state.applying) return
                val ours = region.getUserData(COMMENT_CLOAK_REGION) == true
                val foreign = region.getUserData(COLLAPSED_FOREIGN) == true
                // A region we manage can also be an IDE region we merely collapsed - the user
                // expanding *that* must be remembered just the same, otherwise the next repair
                // pass collapses it straight back.
                if (!ours && !foreign) return
                if (region.isExpanded && region.isValid) {
                    if (foreign) region.putUserData(COLLAPSED_FOREIGN, null)
                    state.addRevealed(editor.document, region.startOffset, region.endOffset)
                }
            }

            override fun onFoldProcessingEnd() {
                if (state.applying) return
                if (!CommentCloakSettings.getInstance().enabled) return
                scheduleReapply(state.foldAlarm, FOLD_PASS_DELAY_MS, editor, project, respectCaret = true)
            }
        }, disposable)

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.failed.clear()
                if (!CommentCloakSettings.getInstance().enabled) return
                state.docAlarm.cancelAllRequests()
                state.docAlarm.addRequest({
                    if (editor.isDisposed || project.isDisposed) return@addRequest
                    PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted {
                        apply(editor, project, respectCaret = true)
                    }
                }, DOCUMENT_DELAY_MS)
            }
        }, disposable)

        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                // No-op unless something is currently being held open for the caret: either a block
                // the last pass skipped, or one the user revealed by hand.
                val skipped = state.skippedForCaret
                val revealed = state.revealed.filter { it.isValid }
                if (skipped.isEmpty() && revealed.isEmpty()) return
                if (!CommentCloakSettings.getInstance().enabled) return
                if (editor.isDisposed) return
                if (isUnderCaret(editor, skipped)) return
                val caretOffsets = editor.caretModel.allCarets.map { it.offset }
                if (revealed.any { containsCaret(caretOffsets, it.startOffset, it.endOffset) }) return
                scheduleReapply(state.caretAlarm, CARET_DELAY_MS, editor, project, respectCaret = true)
            }
        }, disposable)

        installMouseSupport(editor, state, disposable)
    }

    /** Drops all per-editor state and listeners. */
    @JvmStatic
    fun release(editor: Editor) {
        val state = editor.getUserData(STATE) ?: return
        Disposer.dispose(state.disposable)
    }

    private fun scheduleReapply(
        alarm: Alarm,
        delay: Int,
        editor: Editor,
        project: Project,
        respectCaret: Boolean
    ) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!editor.isDisposed && !project.isDisposed) apply(editor, project, respectCaret)
        }, delay)
    }

    /** True while a debounced re-apply is queued for [editor]. */
    @JvmStatic
    fun hasPendingReapply(editor: Editor): Boolean {
        val state = editor.getUserData(STATE) ?: return false
        return !state.foldAlarm.isEmpty || !state.docAlarm.isEmpty || !state.caretAlarm.isEmpty
    }

    /**
     * Test hook: runs any pending debounced re-apply immediately.
     *
     * A queued request always came from the document / folding / caret path, so it keeps the caret
     * exemption; with nothing queued this behaves like an explicit "hide everything now".
     */
    @JvmStatic
    fun flushPendingApplies(editor: Editor, project: Project) {
        val pending = hasPendingReapply(editor)
        val state = editor.getUserData(STATE)
        state?.foldAlarm?.cancelAllRequests()
        state?.docAlarm?.cancelAllRequests()
        state?.caretAlarm?.cancelAllRequests()
        apply(editor, project, respectCaret = pending)
    }

    /**
     * Un-cloaks a single block on the user's behalf and remembers it, so the repair passes leave it
     * alone until the global toggle flips.
     *
     * A [CustomFoldRegion] cannot be expanded - the platform keeps custom regions collapsed by
     * definition, `setExpanded(true)` on one is a no-op - so revealing a pill means removing it.
     * Because a removal produces no state-change notification, the reveal is recorded here rather
     * than in the folding listener. The exemption is temporary: it lasts only while a caret is
     * inside the block, so the comment cloaks itself again once the caret leaves.
     */
    @JvmStatic
    fun reveal(editor: Editor, region: FoldRegion) {
        if (editor.isDisposed || !region.isValid) return
        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return
        val state = editor.getUserData(STATE)
        val start = region.startOffset
        val end = region.endOffset

        state?.hideHint()
        val previousApplying = state?.applying ?: false
        state?.applying = true
        try {
            foldingModel.runBatchFoldingOperation {
                if (region is CustomFoldRegion) {
                    foldingModel.removeFoldRegion(region)
                } else {
                    region.putUserData(COLLAPSED_FOREIGN, null)
                    region.isExpanded = true
                }
            }
        } finally {
            state?.applying = previousApplying
        }
        state?.addRevealed(editor.document, start, end)
        // Parking the caret in the block makes the reveal count as "caret inside", so the block
        // stays open for reading or editing and re-cloaks as soon as the caret leaves.
        // Deliberately outside the batch operation, and without scrolling - it is already on screen.
        moveCaretInto(editor, start, end)
    }

    private fun moveCaretInto(editor: Editor, start: Int, end: Int) {
        val document = editor.document
        val chars = document.charsSequence
        val limit = end.coerceAtMost(document.textLength)
        var offset = start.coerceIn(0, limit)
        while (offset < limit && chars[offset].isWhitespace()) offset++
        editor.caretModel.moveToOffset(offset)
    }

    /** Forgets which regions the user manually revealed (used when the global toggle flips). */
    @JvmStatic
    fun clearRevealed(editor: Editor) {
        val state = editor.getUserData(STATE) ?: return
        state.disposeRevealed()
        state.failed.clear()
    }

    // ------------------------------------------------------------------ apply

    /**
     * Brings [editor]'s fold regions in line with the current settings.
     *
     * @param respectCaret when true, a block containing a caret is left visible. Passed for the
     *   document / folding-pass / caret repair paths, where the user may be typing inside a comment.
     *   The user-initiated paths (toggle on, settings apply, file opened) pass false so that
     *   everything hides regardless of where the caret happens to have been restored.
     */
    @JvmStatic
    @JvmOverloads
    fun apply(editor: Editor, project: Project, respectCaret: Boolean = false) {
        if (editor.isDisposed || project.isDisposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication()
                .invokeLater({ apply(editor, project, respectCaret) }, project.disposed)
            return
        }
        val settings = CommentCloakSettings.getInstance()
        if (!settings.enabled) {
            clear(editor, project)
            return
        }
        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return
        val document = editor.document

        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        if (psiDocumentManager.isUncommited(document)) {
            psiDocumentManager.commitDocument(document)
        }
        val psiFile = psiDocumentManager.getPsiFile(document) ?: return

        val state = editor.getUserData(STATE)
        val plans = CommentCollector.collectFolds(psiFile, document, settings)
            .map { toPlan(it, document, settings) }
        val desired = filterDesired(editor, state, plans, respectCaret)
        val placeholder = settings.effectivePlaceholder()

        val valid = foldingModel.allFoldRegions.filter { it.isValid }
        val ours = valid.filter { it.getUserData(COMMENT_CLOAK_REGION) == true }

        val matched = HashSet<FoldRegion>()
        val toAdd = ArrayList<Plan>()
        val toCollapse = ArrayList<FoldRegion>()
        for (plan in desired) {
            val existing = findExisting(plan, valid, document)
            if (existing == null) {
                if (state == null || plan.key !in state.failed) toAdd.add(plan)
                continue
            }
            matched.add(existing)
            if (existing.isExpanded && !isRevealed(state, existing)) toCollapse.add(existing)
        }
        val toRemove = ours.filter { it !in matched }

        if (toRemove.isEmpty() && toAdd.isEmpty() && toCollapse.isEmpty()) return

        val created = HashMap<TextRange, Boolean>()
        val previousApplying = state?.applying ?: false
        state?.applying = true
        try {
            foldingModel.runBatchFoldingOperation {
                for (region in toRemove) {
                    if (region.isValid) foldingModel.removeFoldRegion(region)
                }
                for (region in toCollapse) {
                    if (!region.isValid) continue
                    if (region.getUserData(COMMENT_CLOAK_REGION) != true) {
                        region.putUserData(COLLAPSED_FOREIGN, true)
                    }
                    region.isExpanded = false
                }
                for (plan in toAdd) {
                    val region = createRegion(editor, foldingModel, document, project, plan, placeholder)
                    created[plan.key] = region != null
                    if (region != null) {
                        region.putUserData(COMMENT_CLOAK_REGION, true)
                        try {
                            region.isExpanded = false
                        } catch (_: Throwable) {
                            // Custom regions are collapsed by construction.
                        }
                    }
                }
            }
        } finally {
            state?.applying = previousApplying
        }

        // Remember what the folding model refused, so a hopeless range cannot spin the repair loop.
        if (state != null) {
            created.forEach { (range, ok) -> if (!ok) state.failed.add(range) }
        }
    }

    /**
     * Removes every region this plugin created and restores the ones it collapsed.
     *
     * [project] is used to ask the IDE to rebuild its own folding (the doc-comment regions we had to
     * drop to make room for a custom marker); pass null when no project is available.
     */
    @JvmStatic
    @JvmOverloads
    fun clear(editor: Editor, project: Project? = null) {
        if (editor.isDisposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { clear(editor, project) }
            return
        }
        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return
        val state = editor.getUserData(STATE)
        state?.hideHint()
        state?.disposeRevealed()
        state?.skippedForCaret = emptyList()
        state?.failed?.clear()

        val ours = foldingModel.allFoldRegions.filter { it.getUserData(COMMENT_CLOAK_REGION) == true }
        val restore = foldingModel.allFoldRegions.filter { it.getUserData(COLLAPSED_FOREIGN) == true }
        if (ours.isEmpty() && restore.isEmpty()) return
        val hadCustom = ours.any { it is CustomFoldRegion }

        val previousApplying = state?.applying ?: false
        state?.applying = true
        try {
            foldingModel.runBatchFoldingOperation {
                for (region in ours) {
                    if (region.isValid) foldingModel.removeFoldRegion(region)
                }
                for (region in restore) {
                    if (!region.isValid) continue
                    region.putUserData(COLLAPSED_FOREIGN, null)
                    region.isExpanded = true
                }
            }
        } finally {
            state?.applying = previousApplying
        }

        // A custom region forces the IDE's own fold builders to skip those lines; ask for a rebuild
        // so the doc-comment folds come back once our markers are gone.
        if (hadCustom && project != null && !project.isDisposed) {
            try {
                CodeFoldingManager.getInstance(project).updateFoldRegions(editor)
            } catch (_: Throwable) {
                // Folding rebuild is best effort - never fail a toggle because of it.
            }
        }
    }

    /** The fold regions currently owned by this plugin. */
    @JvmStatic
    fun ourRegions(editor: Editor): List<FoldRegion> =
        editor.foldingModel.allFoldRegions.filter { it.isValid && it.getUserData(COMMENT_CLOAK_REGION) == true }

    // ------------------------------------------------------------------- planning

    private class Plan(
        val fold: CommentCollector.CommentFold,
        val pill: Boolean,
        val key: TextRange,
        val startLine: Int,
        val endLine: Int
    )

    private fun toPlan(
        fold: CommentCollector.CommentFold,
        document: Document,
        settings: CommentCloakSettings
    ): Plan {
        if (settings.markerStyle != MarkerStyle.PILL || !fold.wholeLine) {
            return Plan(fold, pill = false, key = fold.range, startLine = -1, endLine = -1)
        }
        val startLine = document.getLineNumber(fold.rawRange.startOffset)
        val endLine = document.getLineNumber(fold.rawRange.endOffset)
        val key = TextRange(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine))
        return Plan(fold, pill = true, key = key, startLine = startLine, endLine = endLine)
    }

    /**
     * A custom fold region's exact offsets are chosen by the platform, so pills are matched by the
     * lines they cover; plain regions are matched by their exact range.
     */
    private fun findExisting(plan: Plan, valid: List<FoldRegion>, document: Document): FoldRegion? {
        if (plan.pill) {
            return valid.firstOrNull { region ->
                region is CustomFoldRegion &&
                    region.getUserData(COMMENT_CLOAK_REGION) == true &&
                    document.getLineNumber(region.startOffset) == plan.startLine &&
                    document.getLineNumber(region.endOffset) == plan.endLine
            }
        }
        val exact = valid.filter {
            it !is CustomFoldRegion &&
                it.startOffset == plan.key.startOffset &&
                it.endOffset == plan.key.endOffset
        }
        return exact.firstOrNull { it.getUserData(COMMENT_CLOAK_REGION) == true } ?: exact.firstOrNull()
    }

    private fun createRegion(
        editor: Editor,
        foldingModel: FoldingModelEx,
        document: Document,
        project: Project,
        plan: Plan,
        placeholder: String
    ): FoldRegion? {
        if (plan.pill) {
            addPill(editor, foldingModel, document, project, plan)?.let { return it }
        }
        val fold = plan.fold
        return foldingModel.addFoldRegion(plan.key.startOffset, plan.key.endOffset, placeholder)
            ?: foldingModel.addFoldRegion(fold.range.startOffset, fold.range.endOffset, placeholder)
            ?: foldingModel.addFoldRegion(fold.rawRange.startOffset, fold.rawRange.endOffset, placeholder)
    }

    private fun addPill(
        editor: Editor,
        foldingModel: FoldingModelEx,
        document: Document,
        project: Project,
        plan: Plan
    ): FoldRegion? {
        // A custom lines folding cannot coexist with any other region on the same lines. The
        // offender is normally the IDE's own doc-comment fold, so drop the foreign regions that
        // live inside the comment we are cloaking; an enclosing region (a folded method body, say)
        // is deliberately left alone and simply makes addCustomLinesFolding fail.
        val owner = plan.fold.ownerRange
        val ownerFrom = document.getLineStartOffset(document.getLineNumber(owner.startOffset))
        val ownerTo = document.getLineEndOffset(document.getLineNumber(owner.endOffset))
        foldingModel.allFoldRegions
            .filter { region ->
                region.isValid &&
                    region.getUserData(COMMENT_CLOAK_REGION) != true &&
                    region.startOffset >= ownerFrom && region.endOffset <= ownerTo &&
                    region.endOffset > plan.key.startOffset && region.startOffset < plan.key.endOffset
            }
            .forEach {
                it.putUserData(COLLAPSED_FOREIGN, null)
                foldingModel.removeFoldRegion(it)
            }

        val renderer = CloakRenderer(
            editor = editor,
            lineCount = plan.endLine - plan.startLine + 1,
            indentColumns = indentColumns(editor, document, project, plan.startLine),
            commentText = document.getText(plan.key)
        )
        return try {
            foldingModel.addCustomLinesFolding(plan.startLine, plan.endLine, renderer)
        } catch (_: Throwable) {
            null
        }
    }

    private fun indentColumns(editor: Editor, document: Document, project: Project, line: Int): Int {
        val tabSize = try {
            editor.settings.getTabSize(project).coerceAtLeast(1)
        } catch (_: Throwable) {
            4
        }
        val chars = document.charsSequence
        val lineEnd = document.getLineEndOffset(line)
        var offset = document.getLineStartOffset(line)
        var columns = 0
        while (offset < lineEnd) {
            when (chars[offset]) {
                ' ' -> columns++
                '\t' -> columns += tabSize - (columns % tabSize)
                else -> return columns
            }
            offset++
        }
        return columns
    }

    // ----------------------------------------------------------------- helpers

    private fun isRevealed(state: EditorState?, region: FoldRegion): Boolean {
        val markers = state?.revealed ?: return false
        val range = TextRange(region.startOffset, region.endOffset)
        return markers.any { it.isValid && it.textRange.intersectsStrict(range) }
    }

    /** True when at least one caret sits inside one of [ranges]. */
    private fun isUnderCaret(editor: Editor, ranges: List<TextRange>): Boolean {
        val carets = editor.caretModel.allCarets.map { it.offset }
        return ranges.any { range -> carets.any { it > range.startOffset && it <= range.endOffset } }
    }

    private fun filterDesired(
        editor: Editor,
        state: EditorState?,
        plans: List<Plan>,
        respectCaret: Boolean
    ): List<Plan> {
        val caretOffsets = editor.caretModel.allCarets.map { it.offset }
        if (state != null) {
            if (respectCaret) dropStaleReveals(state, caretOffsets) else state.disposeRevealed()
        }

        val carets = if (respectCaret) caretOffsets else emptyList()
        val revealed = state?.revealed?.filter { it.isValid }?.map { it.textRange } ?: emptyList()
        val skippedForCaret = ArrayList<TextRange>()
        val kept = plans.filter { plan ->
            val range = plan.key
            if (carets.any { it > range.startOffset && it <= range.endOffset }) {
                skippedForCaret.add(range)
                return@filter false
            }
            if (revealed.any { it.intersectsStrict(range) }) return@filter false
            true
        }
        state?.skippedForCaret = skippedForCaret
        return kept
    }

    /**
     * A reveal only lasts while the user is actually in the comment. Markers no caret sits in any
     * more are dropped here, which is what makes an opened comment cloak itself again.
     */
    private fun dropStaleReveals(state: EditorState, caretOffsets: List<Int>) {
        val iterator = state.revealed.iterator()
        while (iterator.hasNext()) {
            val marker = iterator.next()
            val held = marker.isValid && containsCaret(caretOffsets, marker.startOffset, marker.endOffset)
            if (!held) {
                if (marker.isValid) marker.dispose()
                iterator.remove()
            }
        }
    }

    /**
     * Reveal markers use inclusive containment on both ends: a pill's marker spans the block's whole
     * lines, and the caret [reveal] parks on the first non-whitespace character can land exactly on
     * the marker's start when the comment is not indented.
     */
    private fun containsCaret(caretOffsets: List<Int>, start: Int, end: Int): Boolean =
        caretOffsets.any { it in start..end }

    // ------------------------------------------------------------- mouse support

    private fun installMouseSupport(editor: Editor, state: EditorState, disposable: Disposable) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return
        state.hoverAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                if (event.area != EditorMouseEventArea.EDITING_AREA) {
                    state.hideHint()
                    return
                }
                val region = customRegionAt(editor, event.mouseEvent.point)
                if (region == null) {
                    state.hideHint()
                    return
                }
                if (state.hintRegion === region && state.hint?.isVisible == true) return
                state.hideHint()
                state.hoverAlarm?.addRequest({ showHint(editor, state, region) }, HOVER_DELAY_MS)
            }
        }, disposable)

        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(event: EditorMouseEvent) = state.hideHint()

            override fun mouseClicked(event: EditorMouseEvent) {
                if (event.isConsumed) return
                val mouse = event.mouseEvent
                if (mouse.button != MouseEvent.BUTTON1 || mouse.isPopupTrigger) return
                if (mouse.modifiersEx and MODIFIER_MASK != 0) return
                val region = customRegionAt(editor, mouse.point) ?: return
                reveal(editor, region)
                event.consume()
            }
        }, disposable)
    }

    private const val MODIFIER_MASK = MouseEvent.SHIFT_DOWN_MASK or MouseEvent.CTRL_DOWN_MASK or
        MouseEvent.ALT_DOWN_MASK or MouseEvent.META_DOWN_MASK

    /** Our custom fold region under [point] (editor content coordinates), if any. */
    private fun customRegionAt(editor: Editor, point: Point): CustomFoldRegion? {
        for (region in editor.foldingModel.allFoldRegions) {
            if (region !is CustomFoldRegion || !region.isValid) continue
            if (region.getUserData(COMMENT_CLOAK_REGION) != true) continue
            val location = region.location ?: continue
            if (point.x < location.x || point.x > location.x + region.widthInPixels) continue
            if (point.y < location.y || point.y > location.y + region.heightInPixels) continue
            return region
        }
        return null
    }

    private fun showHint(editor: Editor, state: EditorState, region: CustomFoldRegion) {
        if (editor.isDisposed || !region.isValid) return
        val renderer = region.renderer as? CloakRenderer ?: return
        val location = region.location ?: return
        try {
            val component = HintUtil.createInformationLabel(tooltipHtml(renderer.commentText))
            component.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            val hint = LightweightHint(component)
            state.hint = hint
            state.hintRegion = region
            HintManagerImpl.getInstanceImpl().showEditorHint(
                hint,
                editor,
                Point(location.x, location.y),
                HintManager.HIDE_BY_ANY_KEY or HintManager.HIDE_BY_TEXT_CHANGE or
                    HintManager.HIDE_BY_SCROLLING,
                0,
                false
            )
        } catch (_: Throwable) {
            state.hint = null
            state.hintRegion = null
        }
    }

    private fun tooltipHtml(text: String): String {
        val lines = text.lines()
        val shown = if (lines.size > TOOLTIP_MAX_LINES) lines.take(TOOLTIP_MAX_LINES) + "…" else lines
        return "<html><pre>" + StringUtil.escapeXmlEntities(shown.joinToString("\n")) + "</pre></html>"
    }
}
