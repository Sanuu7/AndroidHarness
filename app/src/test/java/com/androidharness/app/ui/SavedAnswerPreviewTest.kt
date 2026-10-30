package com.androidharness.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedAnswerPreviewTest {
    @Test fun markdownMarkersAndLinksBecomeProse() {
        assertEquals(
            "Title Use the guide for details, bold and code.",
            savedAnswerPreview("# Title\n\nUse the [guide](https://example.com/a_b) for details, **bold** and `code`."),
        )
    }

    @Test fun listsAndQuotesLoseTheirMarkers() {
        assertEquals("one two quoted first", savedAnswerPreview("- one\n* two\n> quoted\n1. first"))
    }

    @Test fun identifiersKeepTheirUnderscores() {
        assertEquals("Call load_saved_answer then wait", savedAnswerPreview("Call load_saved_answer then _wait_"))
    }

    @Test fun blankInputStaysBlank() {
        assertEquals("", savedAnswerPreview("  \n\n "))
    }
}
