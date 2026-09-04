package io.github.maximgromov.commentcloak

import com.intellij.openapi.util.TextRange

class CommentCollectorTest : CommentCloakTestBase() {

    private var counter = 0

    override fun setUp() {
        super.setUp()
        // Range computation depends on the marker style; these tests pin the plain (extending)
        // style and the PILL-specific cases opt in explicitly.
        settings.markerStyle = MarkerStyle.PLAIN
    }

    private fun collect(text: String, extension: String = "ts"): List<TextRange> =
        collectFolds(text, extension).map { it.range }

    private fun collectFolds(
        text: String,
        extension: String = "ts"
    ): List<CommentCollector.CommentFold> {
        myFixture.configureByText("sample${counter++}.$extension", text)
        return CommentCollector.collectFolds(myFixture.file, myFixture.editor.document, settings)
    }

    fun testConsecutiveLineCommentsMergeIntoOneRange() {
        val text = "const a = 1;\n// one\n// two\n// three\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        // foldWholeLines pulls the start back to the end of the previous line.
        assertEquals(text.indexOf("\n// one"), ranges[0].startOffset)
        assertEquals(text.indexOf("// three") + "// three".length, ranges[0].endOffset)
    }

    fun testKeptCommentSplitsTheRunAndStaysVisible() {
        val text = "const a = 1;\n// one\n// TODO: keep me\n// three\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(2, ranges.size)
        assertEquals(text.indexOf("\n// one"), ranges[0].startOffset)
        assertEquals(text.indexOf("// one") + "// one".length, ranges[0].endOffset)
        assertEquals(text.indexOf("\n// three"), ranges[1].startOffset)
        assertEquals(text.indexOf("// three") + "// three".length, ranges[1].endOffset)

        val todo = text.indexOf("// TODO")
        assertFalse("The kept comment must stay outside every fold range",
            ranges.any { it.containsOffset(todo + 3) })
    }

    fun testCommentOnFirstLineIsNotExtendedBackwards() {
        val text = "// leading explanation\nconst a = 1;\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].startOffset)
        assertEquals("// leading explanation".length, ranges[0].endOffset)
    }

    fun testWholeLineExtensionCanBeDisabled() {
        settings.foldWholeLines = false
        val text = "const a = 1;\n// why though\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf("// why"), ranges[0].startOffset)
    }

    fun testTrailingCommentFoldsCommentPlusItsLeadingSpacing() {
        val text = "const a = 1; // why\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf(" // why"), ranges[0].startOffset)
        assertEquals(text.indexOf("// why") + "// why".length, ranges[0].endOffset)
    }

    fun testDocCommentIsHiddenByDefaultAndKeptWhenDisabled() {
        val text = "const a = 1;\n/** Adds two numbers. */\nfunction f() {}\n"

        val ranges = collect(text)
        assertEquals(1, ranges.size)
        assertEquals(text.indexOf("*/") + 2, ranges[0].endOffset)

        settings.hideDocComments = false
        assertEmpty(collect(text))
    }

    fun testBlockCommentIsHiddenByDefault() {
        val text = "const a = 1;\n/* a plain block comment */\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf("*/") + 2, ranges[0].endOffset)

        settings.hideBlockComments = false
        assertEmpty(collect(text))
    }

    fun testEslintDirectiveIsKeptByTheDefaultPatterns() {
        val text = "const a = 1;\n// eslint-disable-next-line no-console\nconsole.log(a);\n"
        assertEmpty(collect(text))
    }

    fun testMinLengthFiltersShortComments() {
        settings.minLength = 10
        val text = "// ab\nconst a = 1;\n// a much longer comment\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf("// a much longer comment") + "// a much longer comment".length,
            ranges[0].endOffset)
    }

    fun testMinLengthAppliesToTheMergedRun() {
        settings.minLength = 11
        // Neither line reaches 12 characters on its own, but merged they do.
        val text = "const a = 1;\n// ab\n// cd\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf("// cd") + "// cd".length, ranges[0].endOffset)
    }

    fun testDisablingLineCommentsHidesNothing() {
        settings.hideLineComments = false
        assertEmpty(collect("const a = 1;\n// something verbose\n"))
    }

    fun testRangesAreSortedAndNonOverlapping() {
        val text = "const a = 1; // t1\n// standalone\nconst b = 2; // t2\n"
        val ranges = collect(text)

        assertTrue(ranges.isNotEmpty())
        var previousEnd = -1
        for (range in ranges) {
            assertTrue("ranges must be sorted and disjoint", range.startOffset > previousEnd)
            assertTrue(range.endOffset > range.startOffset)
            previousEnd = range.endOffset
        }
    }

    // ------------------------------------------------------------------ marker style

    fun testPillStyleDoesNotExtendOntoThePreviousLine() {
        settings.markerStyle = MarkerStyle.PILL
        val text = "const a = 1;\n// one\n// two\nconst b = 2;\n"
        val folds = collectFolds(text)

        assertEquals(1, folds.size)
        assertTrue("a comment run on its own lines is a whole-line block", folds[0].wholeLine)
        assertEquals("the pill covers the comment's own lines only",
            text.indexOf("// one"), folds[0].range.startOffset)
        assertEquals(folds[0].rawRange, folds[0].range)
    }

    fun testPillStyleStillTrimsTrailingComments() {
        settings.markerStyle = MarkerStyle.PILL
        val text = "const a = 1; // why\n"
        val folds = collectFolds(text)

        assertEquals(1, folds.size)
        assertFalse("a trailing comment is not a whole-line block", folds[0].wholeLine)
        assertEquals(text.indexOf(" // why"), folds[0].range.startOffset)
    }

    // ------------------------------------------------------- partial keep inside a block

    private val jsdocWithTodo = """
        const a = 1;
        /**
         * Does a thing.
         *
         * TODO(PROJ-1234): rework this
         * once the migration lands and
         * the flag is removed.
         *
         * Some trailing note.
         * Another trailing note.
         */
        function f() {}
    """.trimIndent() + "\n"

    fun testKeepPatternInABlockFoldsTheParagraphsAroundIt() {
        val ranges = collect(jsdocWithTodo)

        assertEquals(2, ranges.size)
        assertEquals(jsdocWithTodo.indexOf("\n/**"), ranges[0].startOffset)
        assertEquals(jsdocWithTodo.indexOf(" * TODO") - 1, ranges[0].endOffset)

        val beforeTrailing = " * the flag is removed."
        assertEquals(
            jsdocWithTodo.indexOf(beforeTrailing) + beforeTrailing.length,
            ranges[1].startOffset
        )
        assertEquals(jsdocWithTodo.indexOf("*/") + 2, ranges[1].endOffset)

        val todo = jsdocWithTodo.indexOf("TODO(PROJ-1234)")
        assertFalse("the matching paragraph must stay visible",
            ranges.any { it.containsOffset(todo) })
    }

    fun testKeepPatternOnTheFirstContentLineLeavesOnlyATrailingFold() {
        val text = """
            const a = 1;
            /**
             * TODO: fix it
             * more about it
             *
             * Trailing essay line one.
             * Trailing essay line two.
             */
        """.trimIndent() + "\n"
        val ranges = collect(text)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf("*/") + 2, ranges[0].endOffset)
        assertFalse(ranges.any { it.containsOffset(text.indexOf("TODO")) })
    }

    fun testBlockThatIsOnlyAKeptParagraphIsNotFoldedAtAll() {
        val text = "const a = 1;\n/**\n * TODO x\n */\nfunction f() {}\n"
        assertEmpty("a lone /** or */ line never becomes a marker of its own", collect(text))
    }

    fun testPartialKeepCanBeDisabled() {
        settings.partialKeep = false
        assertEmpty(collect(jsdocWithTodo))
    }

    fun testEveryMatchingParagraphSplitsTheBlock() {
        val text = """
            const a = 1;
            /**
             * Intro essay line.
             *
             * TODO: one
             *
             * Middle essay line.
             *
             * FIXME: two
             *
             * Tail essay line.
             */
        """.trimIndent() + "\n"
        val ranges = collect(text)

        assertEquals(3, ranges.size)
        assertFalse(ranges.any { it.containsOffset(text.indexOf("TODO: one")) })
        assertFalse(ranges.any { it.containsOffset(text.indexOf("FIXME: two")) })
    }

    fun testPartialRunsRespectMinLength() {
        settings.minLength = 500
        assertEmpty(collect(jsdocWithTodo))
    }

    fun testLineCommentRunsAreUnaffectedByPartialKeep() {
        val text = "const a = 1;\n// one\n// TODO: keep\n// three\nconst b = 2;\n"
        val ranges = collect(text)

        assertEquals(2, ranges.size)
        assertFalse(ranges.any { it.containsOffset(text.indexOf("TODO")) })
    }

    fun testStripCommentMarkers() {
        assertEquals("", CommentCollector.stripCommentMarkers("/**"))
        assertEquals("", CommentCollector.stripCommentMarkers("  * "))
        assertEquals("", CommentCollector.stripCommentMarkers(" */"))
        assertEquals("", CommentCollector.stripCommentMarkers("<!--"))
        assertEquals("Does a thing.", CommentCollector.stripCommentMarkers(" * Does a thing."))
        assertEquals("one liner", CommentCollector.stripCommentMarkers("/* one liner */"))
    }
}
