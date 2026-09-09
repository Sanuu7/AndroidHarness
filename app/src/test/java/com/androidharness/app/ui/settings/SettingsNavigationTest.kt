package com.androidharness.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun `search finds controls by familiar names and hidden keywords`() {
        assertEquals(listOf(SettingsPage.APPEARANCE), matchingSettingsPages("  DARK  theme "))
        assertEquals(listOf(SettingsPage.ENVIRONMENT), matchingSettingsPages("battery"))
        assertTrue(SettingsPage.MODELS in matchingSettingsPages("api\tkey"))
        assertEquals(listOf(SettingsPage.CHAT), matchingSettingsPages("repo map"))
        assertEquals(listOf(SettingsPage.PRIVACY), matchingSettingsPages("fingerprint"))
    }

    @Test
    fun `search returns nested settings as primary style results`() {
        val context = matchingSettingsEntries("max context").first { it.title == "Max context window" }
        assertEquals(SettingsPage.AGENT, context.page)
        assertFalse(context.primary)

        val planning = matchingSettingsEntries("plan model").first { it.title == "Plan model" }
        assertEquals(SettingsPage.MODELS, planning.page)

        val lock = matchingSettingsEntries("auto lock timeout").first { it.title == "Auto-lock timeout" }
        assertEquals(SettingsPage.PRIVACY, lock.page)
    }

    @Test
    fun `empty search keeps the normal primary settings home`() {
        val entries = matchingSettingsEntries("")
        assertEquals(SettingsPage.entries.size, entries.size)
        assertTrue(entries.all { it.primary })
        assertEquals(SettingsPage.entries.toList(), matchingSettingsPages(""))
    }

    @Test
    fun `removed shortcuts do not appear in settings navigation or search`() {
        assertTrue(matchingSettingsPages("automation").isEmpty())
        assertTrue(matchingSettingsPages("build test").isEmpty())
    }

    @Test
    fun `existing chat shortcuts land on their focused settings pages`() {
        assertEquals(SettingsPage.MODELS, settingsDeepLink("planning"))
        assertEquals(SettingsPage.VOICE, settingsDeepLink("voice"))
        assertNull(settingsDeepLink("unknown"))
    }
}
