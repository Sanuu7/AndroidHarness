package com.androidharness.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import com.androidharness.app.R

class SettingsNavigationTest {
    @Test fun `chat option follows language and its startup choice is searchable`() {
        assertEquals(SettingsPage.LANGUAGE.ordinal + 1, SettingsPage.CHAT_OPTIONS.ordinal)
        assertTrue(SettingsPage.CHAT_OPTIONS in matchingSettingsPages("last selected", settingsTestStrings()))
        assertTrue(matchingSettingsEntries("start screen", settingsTestStrings()).any { it.page == SettingsPage.CHAT_OPTIONS && it.anchor == R.string.settings_start_screen })
        assertTrue(matchingSettingsEntries("start chat", settingsTestStrings()).any { it.page == SettingsPage.CHAT_OPTIONS && it.anchor == R.string.settings_chat_start })
        assertTrue(SettingsPage.MEMORY in matchingSettingsPages("forget saved facts", settingsTestStrings()))
        assertTrue(matchingSettingsEntries("memory", settingsTestStrings()).any { it.page == SettingsPage.MEMORY })
    }

    @Test
    fun `storage is searchable and supports a direct link`() {
        assertEquals(listOf(SettingsPage.STORAGE), matchingSettingsPages("cleanup disk", settingsTestStrings()))
        assertEquals(SettingsPage.STORAGE, settingsDeepLink("storage"))
    }

    @Test
    fun `search finds controls by familiar names and hidden keywords`() {
        assertEquals(listOf(SettingsPage.APPEARANCE), matchingSettingsPages("  DARK  theme ", settingsTestStrings()))
        assertEquals(SettingsPage.BACKGROUND, matchingSettingsPages("battery", settingsTestStrings()).first())
        assertTrue(SettingsPage.MODELS in matchingSettingsPages("api\tkey", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.CHAT), matchingSettingsPages("repo map", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.PRIVACY), matchingSettingsPages("fingerprint", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.CODE_INTELLIGENCE), matchingSettingsPages("codegraph affected", settingsTestStrings()))
    }

    @Test
    fun `search returns nested settings as primary style results`() {
        val context = matchingSettingsEntries("max context", settingsTestStrings()).first { it.title == R.string.settings_search_max_context_title }
        assertEquals(SettingsPage.AGENT, context.page)
        assertFalse(context.primary)

        val planning = matchingSettingsEntries("plan model", settingsTestStrings()).first { it.title == R.string.settings_search_plan_model_title }
        assertEquals(SettingsPage.MODELS, planning.page)

        val lock = matchingSettingsEntries("auto lock timeout", settingsTestStrings()).first { it.title == R.string.settings_search_autolock_title }
        assertEquals(SettingsPage.PRIVACY, lock.page)
        val subagent = matchingSettingsEntries("subagent model", settingsTestStrings()).first { it.title == R.string.settings_search_subagent_model_title }
        assertEquals(SettingsPage.SUBAGENT, subagent.page)
        assertEquals(R.string.settings_anchor_subagent_model, subagent.anchor)
    }

    @Test
    fun `empty search keeps the normal primary settings home`() {
        val home = settingsHomePages
        assertEquals(SettingsPage.entries.filter { it.onHome }.toSet(), home.toSet())
        assertEquals(home.size, home.toSet().size)
        val entries = matchingSettingsEntries("", settingsTestStrings())
        assertEquals(home.size, entries.size)
        assertTrue(entries.all { it.primary })
        assertEquals(home, matchingSettingsPages("", settingsTestStrings()))
    }

    @Test
    fun `automation search finds integrated GitHub presets and build shortcut stays removed`() {
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("automation", settingsTestStrings()))
        assertTrue(matchingSettingsPages("build test", settingsTestStrings()).isEmpty())
    }

    @Test
    fun `home groups run from setup to help in a fixed order`() {
        val displayGroups = settingsDisplayGroups(matchingSettingsEntries("", settingsTestStrings()))
        assertEquals(listOf(R.string.learn_group, R.string.settings_group_ai_models, R.string.settings_group_your_assistant,
            R.string.settings_group_integrations, R.string.settings_group_look_feel, R.string.settings_group_device_privacy,
            R.string.settings_group_data_sync, R.string.settings_group_help_about), displayGroups.keys.toList())
        assertEquals(listOf(SettingsPage.MODELS, SettingsPage.CONNECTED_ACCOUNTS, SettingsPage.LOCAL_MODELS, SettingsPage.NINE_ROUTER),
            displayGroups.getValue(R.string.settings_group_ai_models).map { it.page })
        assertEquals(listOf(SettingsPage.CHAT_OPTIONS, SettingsPage.CODING, SettingsPage.MEMORY, SettingsPage.VOICE, SettingsPage.LIVE_MODE),
            displayGroups.getValue(R.string.settings_group_your_assistant).map { it.page })
        assertEquals(listOf(SettingsPage.SEARCH, SettingsPage.MCP, SettingsPage.GITHUB, SettingsPage.TELEGRAM),
            displayGroups.getValue(R.string.settings_group_integrations).map { it.page })
        assertEquals(listOf(SettingsPage.APPEARANCE, SettingsPage.LANGUAGE, SettingsPage.RUMBLE),
            displayGroups.getValue(R.string.settings_group_look_feel).map { it.page })
        assertEquals(listOf(SettingsPage.PRIVACY, SettingsPage.BACKGROUND, SettingsPage.STORAGE),
            displayGroups.getValue(R.string.settings_group_device_privacy).map { it.page })
        assertEquals(SettingsPage.ABOUT, displayGroups.getValue(R.string.settings_group_help_about).last().page)
        // Each group is one contiguous run of the home list, so no group is split in two.
        assertEquals(settingsHomePages, displayGroups.values.flatten().map { it.page })
        assertEquals(SettingsPage.LANGUAGE, settingsDeepLink("language"))
        assertEquals(SettingsPage.GITHUB, settingsDeepLink("github"))
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("commit push", settingsTestStrings()))
    }

    @Test
    fun `coding hub owns agent and device pages and memory sits with the assistant`() {
        assertTrue(SettingsPage.CODING.onHome)
        for (page in listOf(SettingsPage.SUBAGENT, SettingsPage.AGENT, SettingsPage.CHAT,
            SettingsPage.SKILLS, SettingsPage.CODE_INTELLIGENCE, SettingsPage.CAVEMAN,
            SettingsPage.WORKSPACE, SettingsPage.ENVIRONMENT)) {
            assertFalse(page.onHome)
            assertEquals(R.string.settings_group_coding, page.group)
            assertTrue(page in matchingSettingsPages(page.keywords.substringBefore(' '), settingsTestStrings()))
        }
        assertEquals(settingsCodingPages.toSet(), SettingsPage.entries.filter { !it.onHome }.toSet())
        assertEquals(settingsCodingPages.size, settingsCodingPages.toSet().size)
        for (page in listOf(SettingsPage.MCP, SettingsPage.TELEGRAM, SettingsPage.GITHUB, SettingsPage.SEARCH)) {
            assertTrue(page.onHome)
            assertEquals(R.string.settings_group_integrations, page.group)
        }
        assertTrue(SettingsPage.TROUBLESHOOTING.onHome)
        assertEquals(R.string.settings_group_help_about, SettingsPage.TROUBLESHOOTING.group)
        assertEquals(R.string.settings_group_your_assistant, SettingsPage.MEMORY.group)
        assertEquals(SettingsPage.CODING, settingsDeepLink("coding"))
        assertTrue(SettingsPage.AGENT in matchingSettingsPages("max context", settingsTestStrings()))
        assertTrue(SettingsPage.CHAT in matchingSettingsPages("repo map", settingsTestStrings()))
    }

    @Test
    fun `battery and keep alive live on the background page`() {
        val keepAlive = matchingSettingsEntries("keep runs alive", settingsTestStrings()).first { it.anchor == R.string.settings_env_keep_alive }
        assertEquals(SettingsPage.BACKGROUND, keepAlive.page)
        assertTrue(keepAlive.within.isEmpty())
    }

    @Test
    fun `translation search opens the contribution section in either language`() {
        val english = settingsTestStrings()
        val chinese = settingsTestStrings("values-zh-rCN")
        val anchor = R.string.settings_language_contribute_title
        for ((query, text) in listOf("poeditor" to english, chinese(anchor) to chinese)) {
            assertTrue(matchingSettingsEntries(query, text).any {
                it.page == SettingsPage.LANGUAGE && it.anchor == anchor
            })
        }
    }

    @Test
    fun `existing chat shortcuts land on their focused settings pages`() {
        assertEquals(SettingsPage.MODELS, settingsDeepLink("planning"))
        assertEquals(SettingsPage.VOICE, settingsDeepLink("voice"))
        assertNull(settingsDeepLink("unknown"))
    }
    @Test
    fun `Chinese labels and English keywords find the same control`() {
        val chinese = settingsTestStrings("values-zh-rCN")
        val english = settingsTestStrings()
        val anchor = R.string.settings_anchor_max_context
        val zhLabel = chinese(anchor)
        assertTrue(zhLabel != english(anchor))
        assertEquals(anchor, matchingSettingsEntries(zhLabel, chinese).first { it.anchor == anchor }.anchor)
        assertTrue(SettingsPage.AGENT in matchingSettingsPages("context tokens", chinese))
        assertTrue(matchingSettingsEntries("max context", chinese, english).any { it.anchor == anchor })
        assertEquals(settingsDeepLinkAnchor("planning"), R.string.settings_anchor_dual_planning)
        assertEquals(settingsDeepLinkAnchor("voice"), R.string.settings_anchor_stt)
    }
}
