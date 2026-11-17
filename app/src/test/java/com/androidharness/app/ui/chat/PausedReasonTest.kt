package com.androidharness.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class PausedReasonTest {
    /** The exact shape that made issue #102 unreadable: Gemini's raw 429 body. */
    private val gemini429 = """
        HTTP 429: {
          "error": {
            "code": 429,
            "message": "You exceeded your current quota, please check your plan and billing details.\nQuota exceeded for metric: generate_content_free_tier_input_token_count, limit: 250000, model: gemini-4-hydrogen",
            "status": "RESOURCE_EXHAUSTED"
          }
        }
    """.trimIndent()

    @Test fun `raw provider json becomes one readable lead`() {
        val summary = PausedReason.summary(gemini429)!!
        assertTrue(summary, summary.startsWith("HTTP 429 · You exceeded your current quota"))
        assertEquals(summary, 1, summary.lines().size)
        assertTrue(summary, summary.length <= PausedReason.SUMMARY_CHARS + "HTTP 429 · ".length + 1)
        assertTrue(PausedReason.hasMore(gemini429))
    }

    @Test fun `short plain reasons stay verbatim and hide details`() {
        val reason = "Cost unavailable for this model"
        assertEquals(reason, PausedReason.summary(reason))
        assertFalse(PausedReason.hasMore(reason))
        assertNull(PausedReason.summary(null))
        assertNull(PausedReason.summary("  \n "))
        assertFalse(PausedReason.hasMore(null))
    }

    @Test fun `long plain errors truncate and offer details`() {
        val reason = "The provider stopped responding. " + "retry later ".repeat(30)
        val summary = PausedReason.summary(reason)!!
        assertTrue(summary, summary.endsWith("…"))
        assertTrue(summary, summary.length <= PausedReason.SUMMARY_CHARS + 1)
        assertTrue(PausedReason.hasMore(reason))
    }

    @Test fun `multi line errors keep their first line on the card`() {
        val reason = "HTTP 500: upstream failed\nstack frame one\nstack frame two"
        assertEquals("HTTP 500 · upstream failed", PausedReason.summary(reason))
        assertTrue(PausedReason.hasMore(reason))
    }

    @Test fun `a string error field is used as the lead`() {
        val reason = "{\"error\":\"quota exhausted\"}"
        assertEquals("quota exhausted", PausedReason.summary(reason))
        assertTrue(PausedReason.hasMore(reason))
    }
}
