package com.androidharness.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MarkdownLinksTest {
    private val url = "https://example.com/docs"

    @Test fun linkTrailingProseIsACitation() {
        assertTrue(isCitationLink("Docs", url, "Python 3.13 removed the GIL option. "))
        assertTrue(isCitationLink("Reuters", url, "Prices fell (see "))
    }

    @Test fun linkOpeningALineOrFollowingAColonStaysVisible() {
        assertFalse(isCitationLink("Docs", url, ""))
        assertFalse(isCitationLink("Docs", url, "**"))
        assertFalse(isCitationLink("Docs", url, "Earlier sentence.\n"))
        assertFalse(isCitationLink("Docs", url, "Official site: "))
        assertFalse(isCitationLink(url, url, "Read more at "))
    }

    @Test fun bareUrlsInProseStopBeforeSentencePunctuation() {
        val text = "Read https://example.com/a, then https://example.com/b."
        assertEquals("https://example.com/a", bareUrlAt(text, text.indexOf("https://example.com/a")))
        assertEquals("https://example.com/b", bareUrlAt(text, text.indexOf("https://example.com/b")))
    }

    @Test fun bareUrlKeepsABalancedParenthesis() {
        val wiki = "See https://en.wikipedia.org/wiki/Mercury_(planet) for more"
        assertEquals("https://en.wikipedia.org/wiki/Mercury_(planet)", bareUrlAt(wiki, wiki.indexOf("https")))
        val aside = "(details at https://example.com/a)"
        assertEquals("https://example.com/a", bareUrlAt(aside, aside.indexOf("https")))
    }

    @Test fun bareUrlNeedsAWordBoundary() {
        val text = "xhttps://example.com"
        assertNull(bareUrlAt(text, 1))
    }

    @Test fun fullSentenceLinesBecomeSeparateParagraphs() {
        val first = "The first paragraph explains the idea in a full sentence that is long enough to stand alone."
        val second = "The second paragraph follows on the very next line without a blank line between them."
        assertEquals(listOf(listOf(first), listOf(second)), splitRunOnParagraphs(listOf(first, second)))
    }

    @Test fun shortLinesStayTogether() {
        val address = listOf("221B Baker Street", "London NW1 6XE", "United Kingdom.")
        assertEquals(listOf(address), splitRunOnParagraphs(address))
    }
}
