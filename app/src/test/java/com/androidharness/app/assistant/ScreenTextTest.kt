package com.androidharness.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    @Test fun compactDropsBlanksRepeatsAndExtraSpaces() {
        val text = ScreenText.compact(sequenceOf("  Inbox ", "", "Inbox", "inbox", "Hello\t  world", "   "))
        assertEquals("Inbox\nHello world", text)
    }

    @Test fun compactStopsAtTheLimitAndSaysSo() {
        val text = ScreenText.compact(sequenceOf("a".repeat(30), "b".repeat(30)), max = 40)
        assertTrue(text, text.length <= 40 + " [cut]".length)
        assertTrue(text, text.endsWith("[cut]"))
        assertTrue(text, text.startsWith("a".repeat(30)))
    }

    @Test fun describeNamesTheAppAndPageBeforeTheText() {
        val block = ScreenText.describe(ScreenSnapshot(appName = "Chrome", packageName = "com.android.chrome",
            webUri = "https://example.com", text = "Hello"), "Note")
        assertEquals("Note\nApp: Chrome (com.android.chrome)\nPage: https://example.com\n\nHello", block)
    }

    @Test fun describeWithoutTextStillNamesTheApp() {
        val block = ScreenText.describe(ScreenSnapshot(packageName = "com.example"), "Note")
        assertEquals("Note\nApp: com.example", block)
    }
}
