package io.github.maximgromov.commentcloak

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.PlatformTestUtil
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

class CommentFolderTest : CommentCloakTestBase() {

    private var counter = 0

    private val sample = """
        const a = 1;
        // first explanation line
        // second explanation line
        function f() {
            // an inner note about nothing
            return a;
        }
    """.trimIndent() + "\n"

    private val docSample = """
        const a = 1;

        /**
         * A doc block that explains far too much.
         * With a second line of narration.
         */
        function f() {
            return a;
        }
    """.trimIndent() + "\n"

    private fun configure(text: String = sample): Editor {
        myFixture.configureByText("folder${counter++}.ts", text)
        return myFixture.editor
    }

    private fun ourRegions(editor: Editor): List<FoldRegion> = CommentFolder.ourRegions(editor)

    /** The user-initiated path: hides everything, caret or no caret. */
    private fun applyFolding(editor: Editor) {
        CommentFolder.install(editor, project)
        CommentFolder.apply(editor, project)
    }

    /** The repair path used while the user may be typing: leaves the caret's comment alone. */
    private fun applyRespectingCaret(editor: Editor) {
        CommentFolder.install(editor, project)
        CommentFolder.apply(editor, project, respectCaret = true)
    }

    // ------------------------------------------------------------------- basics

    fun testApplyCreatesCollapsedMarkedRegions() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)

        val regions = ourRegions(editor)
        assertEquals(2, regions.size)
        assertTrue("all of our regions must start collapsed", regions.none { it.isExpanded })
        assertTrue(regions.all { it.getUserData(CommentFolder.COMMENT_CLOAK_REGION) == true })
    }

    fun testApplyIsIdempotent() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        val first = ourRegions(editor).map { it.startOffset to it.endOffset }

        CommentFolder.apply(editor, project)
        val second = ourRegions(editor).map { it.startOffset to it.endOffset }

        assertEquals(first, second)
    }

    fun testRegularFoldingPassLeavesOurRegionsCollapsed() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        val before = ourRegions(editor).map { it.startOffset to it.endOffset }.toSet()

        CodeFoldingManager.getInstance(project).updateFoldRegions(editor)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        CommentFolder.flushPendingApplies(editor, project)

        val after = ourRegions(editor)
        assertEquals(before, after.map { it.startOffset to it.endOffset }.toSet())
        assertTrue("regions must still be collapsed", after.none { it.isExpanded })
    }

    /**
     * The IDE's own folding passes (in particular the one that runs when a file is opened) rebuild
     * the fold model from scratch and drop foreign regions. `CodeFoldingManager.buildInitialFoldings`
     * itself cannot be driven from a `BasePlatformTestCase` - it asserts "not EDT" *and* requires the
     * write-intent lock, which the test thread holds - so the destructive part of that pass is
     * reproduced directly here: every fold region is wiped inside a batch folding operation, which
     * fires exactly the same `onFoldProcessingEnd` notification the real pass fires.
     */
    fun testForeignFoldingPassIsRecoveredFrom() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        val before = ourRegions(editor).map { it.startOffset to it.endOffset }.toSet()
        assertTrue(before.isNotEmpty())

        val foldingModel = editor.foldingModel as FoldingModelEx
        foldingModel.runBatchFoldingOperation { foldingModel.clearFoldRegions() }
        assertEmpty(ourRegions(editor))

        assertTrue("a re-apply must be scheduled after a foreign folding pass",
            CommentFolder.hasPendingReapply(editor))

        CommentFolder.flushPendingApplies(editor, project)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val after = ourRegions(editor)
        assertEquals(before, after.map { it.startOffset to it.endOffset }.toSet())
        assertTrue("regions must be present and collapsed after a foreign folding pass",
            after.isNotEmpty() && after.none { it.isExpanded })
    }

    // -------------------------------------------------------------------- caret

    fun testCaretInsideCommentKeepsThatCommentVisibleOnTheRepairPath() {
        settings.enabled = true
        val editor = configure()
        val caretOffset = editor.document.text.indexOf("inner note") + 3
        editor.caretModel.moveToOffset(caretOffset)

        applyRespectingCaret(editor)

        val regions = ourRegions(editor)
        assertEquals("only the comment run without the caret should be folded", 1, regions.size)
        assertFalse(regions.any { it.startOffset < caretOffset && caretOffset <= it.endOffset })
        assertFalse(regions.first().isExpanded)
    }

    fun testTogglingOnHidesEvenTheCommentUnderTheCaret() {
        settings.enabled = true
        val editor = configure()
        editor.caretModel.moveToOffset(editor.document.text.indexOf("inner note") + 3)

        // The user-initiated path ignores where the caret happens to have been restored.
        applyFolding(editor)

        val regions = ourRegions(editor)
        assertEquals("a toggle-on hides every comment", 2, regions.size)
        assertTrue(regions.none { it.isExpanded })
    }

    fun testCommentReCloaksAfterTheCaretMovesAway() {
        settings.enabled = true
        val editor = configure()
        val caretOffset = editor.document.text.indexOf("inner note") + 3
        editor.caretModel.moveToOffset(caretOffset)

        applyRespectingCaret(editor)
        assertEquals("the comment under the caret stays visible", 1, ourRegions(editor).size)

        editor.caretModel.moveToOffset(0)
        assertTrue("moving the caret out must schedule a re-apply",
            CommentFolder.hasPendingReapply(editor))

        CommentFolder.flushPendingApplies(editor, project)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val regions = ourRegions(editor)
        assertEquals("the comment is cloaked again once the caret leaves", 2, regions.size)
        assertTrue(regions.none { it.isExpanded })
    }

    fun testCaretMovementIsANoOpWhenNothingWasSkipped() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        assertFalse(CommentFolder.hasPendingReapply(editor))

        editor.caretModel.moveToOffset(0)
        assertFalse("no re-apply may be scheduled when no range was skipped for the caret",
            CommentFolder.hasPendingReapply(editor))
    }

    // ------------------------------------------------------------------ reveals

    fun testExpandedPlainRegionReCloaksOnceTheCaretLeaves() {
        settings.enabled = true
        settings.markerStyle = MarkerStyle.PLAIN
        val editor = configure()
        applyFolding(editor)

        val region = ourRegions(editor).first()
        val key = region.startOffset to region.endOffset
        editor.caretModel.moveToOffset(editor.document.text.indexOf("first explanation"))
        // The IDE-native gesture: expanding the placeholder. We do not move the caret ourselves.
        editor.foldingModel.runBatchFoldingOperation { region.isExpanded = true }

        CommentFolder.apply(editor, project, respectCaret = true)
        val open = ourRegions(editor).firstOrNull { (it.startOffset to it.endOffset) == key }
        assertTrue("stays open while the caret is inside it", open == null || open.isExpanded)
        val stillFolded = ourRegions(editor).filter { (it.startOffset to it.endOffset) != key }
        assertTrue(stillFolded.isNotEmpty() && stillFolded.none { it.isExpanded })

        editor.caretModel.moveToOffset(0)
        assertTrue("leaving a revealed comment must schedule the re-cloak",
            CommentFolder.hasPendingReapply(editor))
        CommentFolder.flushPendingApplies(editor, project)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val after = ourRegions(editor).firstOrNull { (it.startOffset to it.endOffset) == key }
        assertNotNull("the comment is cloaked again", after)
        assertFalse(after!!.isExpanded)
    }

    /**
     * When the desired range coincides exactly with a fold region the IDE already owns, we collapse
     * that region instead of creating our own. The user expanding it again must be remembered just
     * like an expansion of one of our own regions - but only for as long as the caret stays in it.
     */
    fun testRevealOfAnIdeOwnedRegionIsRememberedWhileTheCaretIsInIt() {
        settings.enabled = true
        settings.markerStyle = MarkerStyle.PLAIN
        settings.foldWholeLines = false
        val editor = configure(docSample)
        val document = editor.document
        val commentStart = document.text.indexOf("/**")
        val commentEnd = document.text.indexOf("*/") + 2

        // Give the IDE a chance to build its own doc-comment fold; if this build of the JS plugin
        // does not produce one with exactly that range, stand one in so the path is still covered.
        CodeFoldingManager.getInstance(project).updateFoldRegions(editor)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val foldingModel = editor.foldingModel as FoldingModelEx
        if (foldingModel.allFoldRegions.none { it.startOffset == commentStart && it.endOffset == commentEnd }) {
            foldingModel.runBatchFoldingOperation {
                foldingModel.addFoldRegion(commentStart, commentEnd, "/**...*/")?.isExpanded = true
            }
        }

        applyFolding(editor)

        val foreign = foldingModel.allFoldRegions.single {
            it.startOffset == commentStart && it.endOffset == commentEnd
        }
        assertFalse("the IDE's own region is collapsed instead of duplicated", foreign.isExpanded)
        assertTrue(foreign.getUserData(CommentFolder.COLLAPSED_FOREIGN) == true)

        foldingModel.runBatchFoldingOperation { foreign.isExpanded = true }
        editor.caretModel.moveToOffset(document.text.indexOf("A doc block"))
        CommentFolder.apply(editor, project, respectCaret = true)
        assertTrue("a revealed IDE region stays open while the caret is inside", foreign.isExpanded)

        editor.caretModel.moveToOffset(0)
        CommentFolder.flushPendingApplies(editor, project)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse("and is cloaked again once the caret leaves", foreign.isExpanded)
    }

    // ----------------------------------------------------------- marker styles

    fun testPillStyleProducesCustomFoldRegions() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)

        val regions = ourRegions(editor)
        assertEquals(2, regions.size)
        assertTrue("whole-line blocks render as a pill",
            regions.all { it is CustomFoldRegion })
        assertTrue(regions.all { it.getUserData(CommentFolder.COMMENT_CLOAK_REGION) == true })
        assertTrue(regions.none { it.isExpanded })
    }

    fun testPillStyleAlsoCoversWholeLineDocBlocks() {
        settings.enabled = true
        val editor = configure(docSample)
        applyFolding(editor)

        val regions = ourRegions(editor)
        assertEquals(1, regions.size)
        assertTrue(regions.single() is CustomFoldRegion)
        assertFalse(regions.single().isExpanded)
    }

    fun testPillStyleCoversAPartiallyKeptBlock() {
        settings.enabled = true
        val text = """
            const a = 1;
            /**
             * A long narration paragraph.
             * That keeps on going.
             *
             * TODO(PROJ-1234): actually fix this.
             */
            function f() { return a; }
        """.trimIndent() + "\n"
        val editor = configure(text)
        applyFolding(editor)

        val regions = ourRegions(editor)
        assertEquals(1, regions.size)
        assertTrue("a partial run inside a block is rendered as a pill too",
            regions.single() is CustomFoldRegion)
        val todo = editor.document.text.indexOf("TODO(PROJ-1234)")
        assertFalse(regions.any { it.startOffset <= todo && todo < it.endOffset })
    }

    fun testTrailingCommentsKeepThePlainPlaceholder() {
        settings.enabled = true
        val editor = configure("const a = 1; // why though\n")
        applyFolding(editor)

        val region = ourRegions(editor).single()
        assertFalse("a trailing comment is not a whole-line block", region is CustomFoldRegion)
        assertEquals(CommentCloakSettings.DEFAULT_PLACEHOLDER, region.placeholderText)
    }

    fun testPlainStyleReproducesTheTextPlaceholders() {
        settings.enabled = true
        settings.markerStyle = MarkerStyle.PLAIN
        val editor = configure()
        applyFolding(editor)

        val regions = ourRegions(editor)
        assertEquals(2, regions.size)
        assertTrue(regions.none { it is CustomFoldRegion })
        assertTrue(regions.all { it.placeholderText == CommentCloakSettings.DEFAULT_PLACEHOLDER })
        // The whole-line extension pulls the start back to the end of the previous line.
        val text = editor.document.text
        assertEquals(text.indexOf("\n// first explanation"), regions.first().startOffset)
    }

    fun testCustomRegionSurvivesTheIdeFoldingPass() {
        settings.enabled = true
        val editor = configure(docSample)
        applyFolding(editor)
        val before = ourRegions(editor).map { it.startOffset to it.endOffset }.toSet()
        assertTrue(before.isNotEmpty())

        CodeFoldingManager.getInstance(project).updateFoldRegions(editor)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        CommentFolder.flushPendingApplies(editor, project)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val after = ourRegions(editor)
        assertEquals(before, after.map { it.startOffset to it.endOffset }.toSet())
        assertTrue(after.all { it is CustomFoldRegion })
        assertTrue("the pill must still be collapsed", after.none { it.isExpanded })
    }

    /**
     * A [CustomFoldRegion] is always collapsed - `setExpanded(true)` on one is a platform no-op -
     * so revealing a pill removes it. [CommentFolder.reveal] is what the click handler and the
     * gutter icon call.
     */
    fun testRevealingAPillOpensItAndReCloaksWhenTheCaretLeaves() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)

        val region = ourRegions(editor).filterIsInstance<CustomFoldRegion>().first()
        val lines = region.startOffset to region.endOffset
        assertFalse("setExpanded is a no-op on a custom region", region.isExpanded)

        CommentFolder.reveal(editor, region)
        assertNull(ourRegions(editor).firstOrNull { (it.startOffset to it.endOffset) == lines })
        assertTrue("reveal parks the caret inside the comment it opened",
            editor.caretModel.offset in lines.first..lines.second)

        CommentFolder.apply(editor, project, respectCaret = true)
        assertNull("it stays open while the caret is inside",
            ourRegions(editor).firstOrNull { (it.startOffset to it.endOffset) == lines })
        val stillFolded = ourRegions(editor).filter { (it.startOffset to it.endOffset) != lines }
        assertTrue("the other block stays cloaked", stillFolded.isNotEmpty())

        editor.caretModel.moveToOffset(0)
        assertTrue("leaving a revealed pill must schedule the re-cloak",
            CommentFolder.hasPendingReapply(editor))
        CommentFolder.flushPendingApplies(editor, project)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val back = ourRegions(editor).firstOrNull { (it.startOffset to it.endOffset) == lines }
        assertNotNull("the pill comes back once the caret leaves", back)
        assertFalse(back!!.isExpanded)
    }

    fun testRevealedPillComesBackAfterTheToggle() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        CommentFolder.reveal(editor, ourRegions(editor).filterIsInstance<CustomFoldRegion>().first())
        assertEquals(1, ourRegions(editor).size)

        val manager = CommentCloakManager.getInstance(project)
        manager.setEnabled(false)
        manager.applyTo(editor)
        manager.setEnabled(true)
        manager.applyTo(editor)

        assertEquals("flipping the toggle forgets manual reveals", 2, ourRegions(editor).size)
    }

    fun testGutterIconRevealsTheBlock() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)

        val region = ourRegions(editor).filterIsInstance<CustomFoldRegion>().first()
        val gutter = region.renderer.calcGutterIconRenderer(region)
        assertNotNull("a pill must offer a gutter icon to open it", gutter)
        assertNotNull(gutter!!.icon)
        assertNotNull(gutter.clickAction)
    }

    fun testRendererDoesNotThrowWhenMeasuredOrPainted() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)

        val region = ourRegions(editor).filterIsInstance<CustomFoldRegion>().first()
        val renderer = region.renderer
        assertTrue(renderer is CloakRenderer)
        assertTrue(renderer.calcWidthInPixels(region) >= 0)
        assertTrue(renderer.calcHeightInPixels(region) > 0)
        assertEquals("2 lines", (renderer as CloakRenderer).label())

        val image = BufferedImage(320, 40, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            renderer.paint(region, graphics, Rectangle2D.Double(0.0, 0.0, 320.0, 20.0), TextAttributes())
        } finally {
            graphics.dispose()
        }
    }

    // ------------------------------------------------------------------ teardown

    fun testClearRemovesOurRegions() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        assertTrue(ourRegions(editor).any { it is CustomFoldRegion })

        CommentFolder.clear(editor, project)
        assertEmpty(ourRegions(editor))
    }

    fun testDisabledSettingsProduceNoRegions() {
        settings.enabled = false
        val editor = configure()
        applyFolding(editor)
        assertEmpty(ourRegions(editor))
    }

    fun testManagerToggleRoundTripAndPersistence() {
        val editor = configure()
        val manager = CommentCloakManager.getInstance(project)

        manager.setEnabled(true)
        manager.applyTo(editor)
        assertTrue(manager.isEnabled)
        assertTrue(ourRegions(editor).isNotEmpty())
        val restored = CommentCloakSettings()
        restored.loadState(settings.state)
        assertTrue(restored.enabled)

        manager.toggle()
        manager.applyTo(editor)
        assertFalse(manager.isEnabled)
        assertEmpty(ourRegions(editor))
        val restoredOff = CommentCloakSettings()
        restoredOff.loadState(settings.state)
        assertFalse(restoredOff.enabled)

        manager.toggle()
        manager.applyTo(editor)
        assertTrue(settings.enabled)
        assertTrue(ourRegions(editor).isNotEmpty())
    }

    fun testSettingsChangeIsPickedUpOnRefresh() {
        settings.enabled = true
        val editor = configure()
        applyFolding(editor)
        assertEquals(2, ourRegions(editor).size)

        settings.hideLineComments = false
        CommentCloakManager.getInstance(project).refresh()
        CommentFolder.apply(editor, project)

        assertEmpty(ourRegions(editor))
    }
}
