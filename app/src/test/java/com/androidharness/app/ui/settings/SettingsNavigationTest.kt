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
    fun `removed shortcuts do not appear in settings navigation or search`() {
        assertTrue(matchingSettingsPages("automation").isEmpty())
        assertTrue(matchingSettingsPages("build test").isEmpty())
        assertEquals(SettingsPage.entries.toList(), matchingSettingsPages(""))
    }

    @Test
    fun `existing chat shortcuts land on their focused settings pages`() {
        assertEquals(SettingsPage.MODELS, settingsDeepLink("planning"))
        assertEquals(SettingsPage.VOICE, settingsDeepLink("voice"))
        assertNull(settingsDeepLink("unknown"))
    }
}
