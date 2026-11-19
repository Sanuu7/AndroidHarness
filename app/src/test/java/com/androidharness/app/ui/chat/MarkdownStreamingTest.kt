package com.androidharness.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MarkdownStreamingTest {
    @Test fun completeParagraphsMoveToStablePrefixWithoutLosingTheirSeparation() {
        val tracker = MarkdownStreamTracker()
        val start = "First paragraph.\n\nSecond **paragraph**"
        val window = tracker.update(start)
        assertEquals("First paragraph.\n\n", start.substring(0, window.stableEnd))
        val finished = start + ".\n\n"
        assertEquals(finished.length, tracker.update(finished).stableEnd)
        assertNull(tracker.update(finished).openFenceStart)
    }

    @Test fun byteSizedUpdatesNeverSplitBlankLinesInsideCodeOrDisplayMath() {
        val tracker = MarkdownStreamTracker()
        val prefix = "Intro.\n\n"
        val code = "```kotlin\nval x = 1\n\nval y = 2\n```\n\n"
        val math = "$$\na + b\n\nc + d\n$$\n\n"
        val body = prefix + code + math + "A final paragraph"
        for (end in 1..body.length) {
            val window = tracker.update(body.take(end))
            if (end > prefix.length && end < prefix.length + code.length) assertEquals(prefix.length, window.stableEnd)
            if (end > prefix.length + code.length && end < prefix.length + code.length + math.length)
                assertEquals(prefix.length + code.length, window.stableEnd)
        }
        assertEquals(prefix.length + code.length + math.length, tracker.update(body).stableEnd)
    }

    @Test fun longerFenceKeepsLiteralInnerBackticksInsideCode() {
        val tracker = MarkdownStreamTracker()
        val text = "Before\n\n````markdown\n```kotlin\n\nval x = 1\n```\n\n"
        val window = tracker.update(text)
        assertEquals(8, window.stableEnd)
        assertEquals(8, window.openFenceStart)
        assertEquals(text.indexOf("```kotlin"), window.fenceBodyStart)
        assertNull(tracker.update(text + "````\n\n").openFenceStart)
    }

    @Test fun replacementAtSameBoundaryInvalidatesCachedPrefixAndResetsFenceState() {
        val tracker = MarkdownStreamTracker()
        val before = tracker.update("First\n\n```kotlin\nwork")
        val after = tracker.update("Other\n\nNew content")
        assertEquals(before.stableEnd, after.stableEnd)
        assertTrue(after.revision > before.revision)
        assertNull(after.openFenceStart)
        assertEquals(0, tracker.update("").stableEnd)
    }

    @Test fun tableRowsStayInOneLiveBlockUntilTheFollowingBlankLine() {
        val tracker = MarkdownStreamTracker()
        val intro = "Compare the values.\n\n"
        val table = "| Name | Value |\n| --- | ---: |\n| A | 1 |\n| B | 2 |"
        assertEquals(intro.length, tracker.update(intro + table).stableEnd)
        assertEquals(intro.length + table.length + 2, tracker.update(intro + table + "\n\nNotes").stableEnd)
    }
    @Test fun closingFenceIsRecognizedBeforeItsFinalNewlineArrives() {
        val tracker = MarkdownStreamTracker()
        assertNotNull(tracker.update("```kotlin\nval x = 1\n").openFenceStart)
        assertNull(tracker.update("```kotlin\nval x = 1\n```").openFenceStart)
        assertNull(tracker.update("```kotlin\nval x = 1\n```\n\nNext").openFenceStart)
    }


    @Test fun bareBlockMarkersWaitForTheirText() {
        assertEquals("Intro\n", stabilizeLiveTail("Intro\n#"))
        assertEquals("Intro\n", stabilizeLiveTail("Intro\n- "))
        assertEquals("Intro\n", stabilizeLiveTail("Intro\n12."))
        assertEquals("Intro\n# Ti", stabilizeLiveTail("Intro\n# Ti"))
    }

    @Test fun tablesAppearOnceTheDelimiterRowArrives() {
        assertEquals("", stabilizeLiveTail("| a | b |\n"))
        assertEquals("", stabilizeLiveTail("| a | b |\n|--"))
        val table = "| a | b |\n|---|---|\n"
        assertEquals(table, stabilizeLiveTail(table + "| 1 |"))
        assertEquals(table + "| 1 | 2 |\n", stabilizeLiveTail(table + "| 1 | 2 |\n"))
        assertEquals("Text\n", stabilizeLiveTail("Text\n| a"))
    }

    @Test fun openInlineMarkersAreClosedEarly() {
        assertEquals("Some **bol**", stabilizeLiveTail("Some **bol"))
        assertEquals("Some **bold**", stabilizeLiveTail("Some **bold*"))
        assertEquals("Some **bold** ", stabilizeLiveTail("Some **bold "))
        assertEquals("Some ", stabilizeLiveTail("Some **"))
        assertEquals("Run `ls`", stabilizeLiveTail("Run `ls"))
        assertEquals("Run ", stabilizeLiveTail("Run `"))
        assertEquals("Code `a**b` done", stabilizeLiveTail("Code `a**b` done"))
        assertEquals("~~old~~", stabilizeLiveTail("~~old"))
        assertEquals("```kot", stabilizeLiveTail("```kot"))
        assertEquals("Done **bold** text", stabilizeLiveTail("Done **bold** text"))
    }

    @Test fun pacingStopsOnWordBoundaries() {
        val text = "Hello brave world"
        assertEquals(5, wordEnd(text, 0, 2))
        assertEquals(11, wordEnd(text, 6, 8))
        assertEquals(12, wordEnd(text, 6, text.length))
        assertEquals(text.length + 1, wordEnd(text + " ", 6, text.length + 1))
        val long = "x".repeat(40)
        assertEquals(10, wordEnd(long, 0, 10))
        assertEquals(0, wordEnd("", 0, 3))
    }
}
