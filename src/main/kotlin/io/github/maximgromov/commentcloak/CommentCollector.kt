package io.github.maximgromov.commentcloak

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser

/**
 * Pure, side-effect free computation of the text ranges that should be folded away.
 *
 * Nothing here touches the editor; [CommentFolder] applies the result.
 */
object CommentCollector {

    enum class Kind { LINE, BLOCK, DOC, HTML }

    /**
     * A range to fold.
     *
     * @param range     the range actually handed to the folding model for a plain placeholder
     * @param rawRange  the un-extended range of the underlying comment (run)
     * @param wholeLine true when the comment (run) occupies complete lines - only whitespace before
     *                  it on its first line and nothing but whitespace after it on its last line
     * @param ownerRange the range of the whole comment this fold came from; for a partially folded
     *                  comment it is larger than [rawRange]
     */
    data class CommentFold(
        val range: TextRange,
        val rawRange: TextRange,
        val wholeLine: Boolean,
        val ownerRange: TextRange
    )

    private class Candidate(
        val range: TextRange,
        val kind: Kind,
        val text: String,
        val startsOwnLine: Boolean,
        val startLine: Int,
        val endLine: Int
    )

    /** Ranges to fold, sorted by start offset and guaranteed non-overlapping. */
    @JvmStatic
    fun collect(psiFile: PsiFile, document: Document, settings: CommentCloakSettings): List<TextRange> =
        collectFolds(psiFile, document, settings).map { it.range }

    @JvmStatic
    fun collectFolds(psiFile: PsiFile, document: Document, settings: CommentCloakSettings): List<CommentFold> {
        val comments = gatherComments(psiFile, document)
        if (comments.isEmpty()) return emptyList()

        val keep = settings.keepRegexes()
        val eligible = comments.filter { candidate ->
            isKindEnabled(candidate.kind, settings) && keep.none { it.containsMatchIn(candidate.text) }
        }

        val merged: List<TextRange> = mergeLineRuns(eligible, document)

        // Multi-line block/doc/HTML comments that match a keep pattern are folded around the
        // matching paragraph instead of being kept whole.
        val owners = HashMap<TextRange, TextRange>()
        merged.forEach { owners[it] = it }
        val partial = ArrayList<TextRange>()
        if (settings.partialKeep) {
            for (candidate in comments) {
                if (candidate.kind == Kind.LINE) continue
                if (!isKindEnabled(candidate.kind, settings)) continue
                if (keep.none { it.containsMatchIn(candidate.text) }) continue
                for (run in partialFoldRanges(candidate, document, keep)) {
                    owners[run] = candidate.range
                    partial.add(run)
                }
            }
        }
        val rawBlocks = if (partial.isEmpty()) merged else (merged + partial).sortedBy { it.startOffset }

        val chars = document.charsSequence
        // A pill is drawn over the comment's own lines, so the block must NOT be extended backwards
        // onto the previous line - the blank/code line above stays exactly where it is.
        val pill = settings.markerStyle == MarkerStyle.PILL
        val result = ArrayList<CommentFold>(rawBlocks.size)
        var lastEnd = -1
        for (raw in rawBlocks) {
            if (raw.length < settings.minLength) continue
            if (raw.isEmpty) continue

            val startLine = document.getLineNumber(raw.startOffset)
            val lineStart = document.getLineStartOffset(startLine)
            val startsOwnLine = chars.subSequence(lineStart, raw.startOffset).isBlank()

            var start = raw.startOffset
            var wholeLine = false
            if (startsOwnLine) {
                wholeLine = endsLine(document, raw.endOffset)
                if (!pill && settings.foldWholeLines && wholeLine && startLine > 0) {
                    // Swallow the preceding line break plus this line's indentation, so the comment
                    // lines vanish completely and the placeholder lands after the previous code line.
                    start = document.getLineEndOffset(startLine - 1)
                }
            } else {
                // Trailing comment: also swallow the spacing that separates it from the code.
                var i = raw.startOffset
                while (i > lineStart && (chars[i - 1] == ' ' || chars[i - 1] == '\t')) i--
                start = i
            }

            // Never let the extension touch or overlap the previously emitted region:
            // the folding model rejects regions that intersect an existing one.
            if (start >= raw.endOffset || start <= lastEnd) start = raw.startOffset
            if (start <= lastEnd || start >= raw.endOffset) continue

            result.add(
                CommentFold(TextRange(start, raw.endOffset), raw, wholeLine, owners[raw] ?: raw)
            )
            lastEnd = raw.endOffset
        }
        return result
    }


    /**
     * Splits a keep-matching multi-line comment into the runs of lines that may still be folded.
     *
     * A line "matches" when a keep regex matches its text; the kept paragraph is that line plus the
     * following ones up to the first blank marker line (a line that is empty once whitespace and
     * comment markers are stripped). Everything else forms fold candidates, but only runs that carry
     * at least one line with real text are folded - a lonely `/**` or ` */` never becomes a marker
     * of its own.
     */
    private fun partialFoldRanges(
        candidate: Candidate,
        document: Document,
        keep: List<Regex>
    ): List<TextRange> {
        val firstLine = candidate.startLine
        val lastLine = candidate.endLine
        if (lastLine <= firstLine) return emptyList()

        val count = lastLine - firstLine + 1
        val chars = document.charsSequence
        val lineTexts = ArrayList<String>(count)
        for (line in firstLine..lastLine) {
            val from = maxOf(document.getLineStartOffset(line), candidate.range.startOffset)
            val to = minOf(document.getLineEndOffset(line), candidate.range.endOffset)
            lineTexts.add(if (to > from) chars.subSequence(from, to).toString() else "")
        }
        val stripped = lineTexts.map { stripCommentMarkers(it) }

        val kept = BooleanArray(count)
        for (i in 0 until count) {
            if (keep.none { it.containsMatchIn(lineTexts[i]) }) continue
            var j = i
            while (j < count) {
                if (j > i && stripped[j].isEmpty()) break
                kept[j] = true
                j++
            }
        }

        val out = ArrayList<TextRange>()
        var i = 0
        while (i < count) {
            if (kept[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < count && !kept[j + 1]) j++
            if ((i..j).any { stripped[it].isNotEmpty() }) {
                val runStart = runStartOffset(document, candidate, firstLine + i)
                val runEnd = minOf(document.getLineEndOffset(firstLine + j), candidate.range.endOffset)
                if (runEnd > runStart) out.add(TextRange(runStart, runEnd))
            }
            i = j + 1
        }
        return out
    }

    private fun runStartOffset(document: Document, candidate: Candidate, line: Int): Int {
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        var offset = maxOf(document.getLineStartOffset(line), candidate.range.startOffset)
        while (offset < lineEnd && (chars[offset] == ' ' || chars[offset] == '\t')) offset++
        return offset
    }

    private val COMMENT_MARKERS = listOf("/**", "/*", "*/", "<!--", "-->", "//", "*", "#")

    /** Strips whitespace and comment decoration so that "blank" comment lines can be recognised. */
    @JvmStatic
    fun stripCommentMarkers(text: String): String {
        var result = text.trim()
        var changed = true
        while (changed && result.isNotEmpty()) {
            changed = false
            for (marker in COMMENT_MARKERS) {
                if (result.startsWith(marker)) {
                    result = result.substring(marker.length).trim()
                    changed = true
                }
                if (result.isNotEmpty() && result.endsWith(marker)) {
                    result = result.substring(0, result.length - marker.length).trim()
                    changed = true
                }
            }
        }
        return result
    }

    private fun gatherComments(psiFile: PsiFile, document: Document): List<Candidate> {
        val seen = HashSet<TextRange>()
        val out = ArrayList<Candidate>()
        val textLength = document.textLength
        val chars = document.charsSequence

        for (root in psiFile.viewProvider.allFiles) {
            for (element in SyntaxTraverser.psiTraverser(root).traverse()) {
                if (element !is PsiComment) continue
                val range = element.textRange ?: continue
                if (range.isEmpty) continue
                if (range.endOffset > textLength) continue
                if (!seen.add(range)) continue

                val text = element.text ?: continue
                val startLine = document.getLineNumber(range.startOffset)
                val lineStart = document.getLineStartOffset(startLine)
                out.add(
                    Candidate(
                        range = range,
                        kind = classify(element, text),
                        text = text,
                        startsOwnLine = chars.subSequence(lineStart, range.startOffset).isBlank(),
                        startLine = startLine,
                        endLine = document.getLineNumber(range.endOffset)
                    )
                )
            }
        }
        out.sortWith(compareBy({ it.range.startOffset }, { -it.range.endOffset }))

        // Drop nested / overlapping duplicates coming from several PSI trees of the same view provider.
        val deduped = ArrayList<Candidate>(out.size)
        var lastEnd = -1
        for (candidate in out) {
            if (candidate.range.startOffset < lastEnd) continue
            deduped.add(candidate)
            lastEnd = candidate.range.endOffset
        }
        return deduped
    }

    @JvmStatic
    fun classify(comment: PsiComment, text: String): Kind = when {
        text.startsWith("<!--") -> Kind.HTML
        comment is PsiDocCommentBase -> Kind.DOC
        text.startsWith("/**") && text != "/**/" -> Kind.DOC
        text.startsWith("/*") -> Kind.BLOCK
        text.contains('\n') -> Kind.BLOCK
        else -> Kind.LINE
    }

    private fun isKindEnabled(kind: Kind, settings: CommentCloakSettings): Boolean = when (kind) {
        Kind.LINE -> settings.hideLineComments
        Kind.BLOCK -> settings.hideBlockComments
        Kind.DOC -> settings.hideDocComments
        Kind.HTML -> settings.hideHtmlComments
    }

    /**
     * Merges runs of consecutive line comments that each sit alone on directly consecutive lines.
     * Comments excluded earlier (kept, or of a disabled kind) break the adjacency and therefore
     * split the run automatically.
     */
    private fun mergeLineRuns(eligible: List<Candidate>, document: Document): List<TextRange> {
        val chars = document.charsSequence
        val out = ArrayList<TextRange>()
        var i = 0
        while (i < eligible.size) {
            var j = i
            if (eligible[i].kind == Kind.LINE && eligible[i].startsOwnLine) {
                while (j + 1 < eligible.size) {
                    val next = eligible[j + 1]
                    val cur = eligible[j]
                    if (next.kind != Kind.LINE || !next.startsOwnLine) break
                    if (next.startLine != cur.endLine + 1) break
                    if (!chars.subSequence(cur.range.endOffset, next.range.startOffset).isBlank()) break
                    j++
                }
            }
            out.add(TextRange(eligible[i].range.startOffset, eligible[j].range.endOffset))
            i = j + 1
        }
        return out
    }

    private fun endsLine(document: Document, endOffset: Int): Boolean {
        if (endOffset > document.textLength) return false
        val line = document.getLineNumber(endOffset)
        val lineEnd = document.getLineEndOffset(line)
        return document.charsSequence.subSequence(endOffset, lineEnd).isBlank()
    }
}
