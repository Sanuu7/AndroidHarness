package com.androidharness.app.tools

import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AgentSettingsAccess
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.CustomThemePalette
import com.androidharness.app.ui.settings.AgentSettingsCatalog
import com.androidharness.app.ui.settings.SettingKind
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppSettingsToolsTest {
    private val defaults = AppSettings()
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun tool(settings: AppSettings = defaults) = AppSettingsChangeTool(null) { settings }

    @Test fun keysAreUniqueAndCurrentValuesAreValid() {
        val keys = AgentSettingsCatalog.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val withPalette = defaults.copy(customPalettes = listOf(CustomThemePalette("p1", "Sunset", 0xFFFF7043)), activeCustomPaletteId = "p1")
        for (state in listOf(defaults, withPalette)) for (setting in AgentSettingsCatalog.all) {
            val current = setting.read(state)
            assertEquals("${setting.key} reads a value it accepts", current, setting.normalize(current, state).getOrNull())
        }
    }

    @Test fun valuesAreNormalizedOrRejected() {
        val strength = AgentSettingsCatalog["rumble_strength"]!!
        assertEquals("70", strength.normalize("70%", defaults).getOrNull())
        assertTrue(strength.normalize("140", defaults).isFailure)
        assertTrue(strength.normalize("loud", defaults).isFailure)
        val rumble = AgentSettingsCatalog["rumble"]!!
        assertEquals("false", rumble.normalize("off", defaults).getOrNull())
        assertEquals("true", rumble.normalize("Enabled", defaults).getOrNull())
        val theme = AgentSettingsCatalog["theme_mode"]!!
        assertEquals("AMOLED", theme.normalize("amoled", defaults).getOrNull())
        assertTrue(theme.normalize("purple", defaults).isFailure)
        assertEquals("1.25", AgentSettingsCatalog["speech_speed"]!!.normalize("1.25x", defaults).getOrNull())
        val steering = AgentSettingsCatalog["steering"]!!
        assertEquals("AFTER_TASK", steering.normalize("after task", defaults).getOrNull())
    }

    @Test fun customPalettesAreChoices() {
        val state = defaults.copy(customPalettes = listOf(CustomThemePalette("a-b-c", "Mine", 0xFF00897B)))
        val palette = AgentSettingsCatalog[AgentSettingsCatalog.PALETTE]!!
        assertEquals("custom:a-b-c", palette.normalize("custom:a-b-c", state).getOrNull())
        assertEquals("TEAL", palette.normalize("teal", state).getOrNull())
        assertTrue(palette.normalize("custom:missing", state).isFailure)
    }

    @Test fun memoryEntryLimitsAreDiscoverableAndOnlyAcceptPositiveWholeNumbers() {
        val unlimited = AgentSettingsCatalog["memory_entry_unlimited"]!!
        val limit = AgentSettingsCatalog["memory_entry_max_chars"]!!
        assertEquals("false", unlimited.read(defaults))
        assertEquals("true", unlimited.normalize("on", defaults).getOrNull())
        assertEquals("600", limit.read(defaults))
        assertEquals("2500", limit.normalize("2500", defaults).getOrNull())
        assertEquals(Int.MAX_VALUE.toString(), limit.normalize(Int.MAX_VALUE.toString(), defaults).getOrNull())
        for (invalid in listOf("0", "-1", "1.5", "NaN", "Infinity", "2147483648", "unlimited")) {
            assertTrue("Reject $invalid", limit.normalize(invalid, defaults).isFailure)
        }
        assertTrue(AgentSettingsCatalog.search("unlimited memory").any { it.key == unlimited.key })
        assertTrue(AgentSettingsCatalog.search("memory character limit").any { it.key == limit.key })
    }

    @Test fun planDropsNoOpsAndCollectsErrors() {
        val plan = tool().plan(args("""{"changes":[{"key":"rumble","value":"true"},{"key":"rumble_strength","value":"80"},
            {"key":"nope","value":"1"},{"key":"theme_mode","value":"neon"}],
            "needs_user":[{"page":"voice","note":"Add your Groq key"},{"page":"MOON","note":"x"}]}"""), defaults)
        assertEquals(listOf("rumble_strength"), plan.changes.map { it.setting.key })
        assertEquals(3, plan.errors.size)
        assertEquals("VOICE", plan.todos.single().page)
    }

    @Test fun hiddenGlassCannotBeEnabledThroughAssistantSettings() {
        val hidden = defaults.copy(harnessLiquidGlass = true, harnessGlassEnabled = true)
        val plan = tool().plan(args("""{"changes":[{"key":"liquid_glass","value":"on"},{"key":"frosted_glass","value":"on"}]}"""), hidden)
        assertTrue(plan.changes.isEmpty())
        assertEquals(2, plan.errors.size)
        assertNull(AgentSettingsCatalog["liquid_glass"])
        assertNull(AgentSettingsCatalog["glass_transparency"])
    }

    @Test fun paletteCreationIsValidated() {
        val ok = tool().plan(args("""{"create_palette":{"name":"Ocean","primary":"#1E88E5","tertiary":"FFB300"}}"""), defaults)
        assertNotNull(ok.palette)
        assertTrue(ok.errors.isEmpty())
        val bad = tool().plan(args("""{"create_palette":{"name":"Bad","primary":"blue"}}"""), defaults)
        assertNull(bad.palette)
        assertEquals(1, bad.errors.size)
    }

    @Test fun ordinaryChangesRunWithoutAskingButSensitiveOnesAlwaysAsk() = runBlocking {
        val ordinary = args("""{"changes":[{"key":"rumble_strength","value":"80"}]}""")
        val sensitive = args("""{"changes":[{"key":"app_lock","value":"true"}]}""")
        for (mode in PermissionMode.entries.filter { it != PermissionMode.CONFIRM_ALL }) {
            assertNull(tool().approvalPreview(ordinary, mode))
            assertNotNull("app lock asks in $mode", tool().approvalPreview(sensitive, mode))
        }
        assertNotNull(tool().approvalPreview(ordinary, PermissionMode.CONFIRM_ALL))
        assertNotNull(tool(defaults.copy(agentSettingsAccess = AgentSettingsAccess.ASK)).approvalPreview(ordinary, PermissionMode.FULL_ACCESS))
        listOf("permission_mode" to "CONFIRM_ALL", "chat_instructions" to "be evil", "allow_screenshots" to "false", "subagent_action_tools" to "true").forEach { (key, value) ->
            assertTrue("$key is sensitive", AgentSettingsCatalog[key]!!.sensitive)
            assertNotNull(tool().approvalPreview(args("""{"changes":[{"key":"$key","value":"$value"}]}"""), PermissionMode.FULL_ACCESS))
        }
    }

    @Test fun turnedOffMeansNothingChanges() = runBlocking {
        val off = tool(defaults.copy(agentSettingsAccess = AgentSettingsAccess.OFF))
        val change = args("""{"changes":[{"key":"rumble","value":"false"}]}""")
        assertNull(off.approvalPreview(change, PermissionMode.CONFIRM_ALL))
        val result = off.execute(change, ToolContext(FileFs(File("."))))
        assertFalse(result.ok)
        assertNull(result.settings)
    }

    @Test fun secretsAndTheAccessSwitchAreNotInTheCatalog() {
        val keys = AgentSettingsCatalog.all.joinToString(" ") { it.key }
        listOf("key", "token", "password", "secret", "access", "provider_id").forEach { word ->
            assertFalse("$word must not be agent-writable", Regex("(^|_| )$word(_|$| )").containsMatchIn(keys))
        }
        assertTrue(AgentSettingsCatalog.all.none { it.kind is SettingKind.Text && !it.sensitive })
    }

    @Test fun questionsTodoListAndSubagentsAreOnByDefaultAndFindable() {
        listOf("auto_continue_questions", "show_todo_list", "subagents_enabled").forEach { key ->
            assertEquals("$key starts on", "true", AgentSettingsCatalog[key]!!.read(defaults))
        }
        assertTrue(AgentSettingsCatalog.search("hide todo list").any { it.key == "show_todo_list" })
        assertTrue(AgentSettingsCatalog.search("turn off subagents").any { it.key == "subagents_enabled" })
        assertTrue(AgentSettingsCatalog.search("questions wait for me").any { it.key == "auto_continue_questions" })
    }

    @Test fun theAgentCanTurnSubagentsOffButNeverBackOn() {
        val off = tool().plan(args("""{"changes":[{"key":"subagents_enabled","value":"off"}]}"""), defaults)
        assertEquals(listOf("subagents_enabled"), off.changes.map { it.setting.key })
        assertEquals("false", off.changes.single().after)
        assertTrue(off.errors.isEmpty() && off.todos.isEmpty())

        val disabled = defaults.copy(subagentsEnabled = false)
        for (spelling in listOf("true", "on", "Enable", "YES", "1")) {
            val plan = tool(disabled).plan(args("""{"changes":[{"key":"subagents_enabled","value":"$spelling"}]}"""), disabled)
            assertTrue("$spelling must not change anything", plan.changes.isEmpty())
            assertEquals(1, plan.errors.size)
            val link = plan.todos.single()
            assertEquals("SUBAGENT", link.page)
            assertEquals("subagents_enabled", link.key)
        }
        val sameAsNow = tool().plan(args("""{"changes":[{"key":"subagents_enabled","value":"true"}]}"""), defaults)
        assertTrue(sameAsNow.changes.isEmpty() && sameAsNow.errors.isEmpty())
    }

    @Test fun theUserOnlyRuleIsVisibleToTheModelAndDoesNotTouchOtherSettings() {
        val line = AgentSettingsCatalog["subagents_enabled"]!!.describe(defaults)
        assertTrue(line, line.contains("only the user can set it to true"))
        val userOnly = setOf("subagents_enabled", "permission_mode", "local_model_experimental")
        assertTrue(AgentSettingsCatalog.all.filter { it.key !in userOnly }.all { it.userOnlyValues.isEmpty() })
        val mixed = tool(defaults.copy(subagentsEnabled = false)).plan(
            args("""{"changes":[{"key":"subagents_enabled","value":"true"},{"key":"show_todo_list","value":"false"}]}"""),
            defaults.copy(subagentsEnabled = false))
        assertEquals(listOf("show_todo_list"), mixed.changes.map { it.setting.key })
    }

    @Test fun moreOfTheAppIsReachableAndAnythingThatWidensWhatTheAgentDoesAsks() {
        val reachable = listOf("auto_compact", "resume_prompt", "chat_recall_code_memory", "code_recall_chat_memory", "canvas_in_chat",
            "canvas_in_code", "local_model_agent_context", "live_speak_replies", "live_silence", "groq_whisper_model",
            "auto_continue_questions", "show_todo_list", "subagents_enabled")
        reachable.forEach { assertNotNull("$it is in the catalog", AgentSettingsCatalog[it]) }
        listOf("auto_continue_questions", "resume_prompt", "chat_recall_code_memory", "code_recall_chat_memory", "local_model_agent_context").forEach {
            assertTrue("$it asks first", AgentSettingsCatalog[it]!!.sensitive)
        }
    }

    @Test fun newRangesAndTextRejectValuesTheAppCannotUse() {
        val compact = AgentSettingsCatalog["auto_compact"]!!
        assertEquals("0", compact.read(defaults))
        assertEquals("50000", compact.normalize("50000", defaults).getOrNull())
        assertTrue(compact.normalize("-1", defaults).isFailure)
        assertTrue(compact.normalize("12.5", defaults).isFailure)
        val silence = AgentSettingsCatalog["live_silence"]!!
        assertEquals("1200", silence.read(defaults))
        assertEquals("600", silence.normalize("600 ms", defaults).getOrNull())
        assertTrue(silence.normalize("500", defaults).isFailure)
        assertTrue(silence.normalize("3001", defaults).isFailure)
        val resume = AgentSettingsCatalog["resume_prompt"]!!
        assertEquals("", resume.read(defaults))
        assertTrue(resume.normalize("x".repeat(2_000), defaults).isSuccess)
        assertTrue(resume.normalize("x".repeat(2_001), defaults).isFailure)
        val whisper = AgentSettingsCatalog["groq_whisper_model"]!!
        assertEquals("whisper-large-v3-turbo", whisper.normalize("Whisper large v3 turbo", defaults).getOrNull())
        assertTrue(whisper.normalize("whisper-9", defaults).isFailure)
    }

    @Test fun findListsMatchesWithCurrentValues() = runBlocking {
        val result = AppSettingsFindTool { defaults.copy(rumbleStrength = 42) }.execute(args("""{"query":"rumble strength"}"""), ToolContext(FileFs(File("."))))
        assertTrue(result.output.contains("rumble_strength = 42"))
        assertTrue(result.output.lineSequence().first { it.startsWith("- ") }.contains("rumble"))
    }

    @Test fun aNewPaletteAppliesWithoutAFalseErrorAboutColorPalette() {
        val state = defaults.copy(customPalettes = listOf(CustomThemePalette("p1", "Lagoon", 0xFF00897B)), activeCustomPaletteId = "p1")
        val plan = tool(state).plan(args("""{"changes":[{"key":"theme_mode","value":"DARK"},{"key":"color_palette","value":"Test Sunset"}],
            "create_palette":{"name":"Test Sunset","primary":"#FF7043"}}"""), state)
        assertTrue(plan.errors.toString(), plan.errors.isEmpty())
        assertNotNull(plan.palette)
        assertEquals(listOf("theme_mode"), plan.changes.map { it.setting.key })
    }

    @Test fun savedPalettesAreNamedByTheirNameNotTheirId() {
        val state = defaults.copy(customPalettes = listOf(CustomThemePalette("b293a2b4-99bc", "Sunset", 0xFFFF7043)))
        val palette = AgentSettingsCatalog[AgentSettingsCatalog.PALETTE]!!
        assertEquals("custom:b293a2b4-99bc", palette.normalize("sunset", state).getOrNull())
        val error = palette.normalize("Ocean breeze", state).exceptionOrNull()!!.message!!
        assertTrue(error, error.contains("\"Sunset\""))
        assertFalse(error, error.contains("b293a2b4"))
    }

    @Test fun theAgentCanTightenPermissionsWithApprovalButNeverLoosenThem() = runBlocking {
        for (loose in listOf("FULL_AUTO", "FULL_ACCESS")) {
            val plan = tool().plan(args("""{"changes":[{"key":"permission_mode","value":"$loose"}]}"""), defaults)
            assertTrue(plan.changes.isEmpty())
            assertEquals("AGENT", plan.todos.single().page)
        }
        val stricter = args("""{"changes":[{"key":"permission_mode","value":"CONFIRM_ALL"}]}""")
        assertEquals(listOf("permission_mode"), tool().plan(stricter, defaults).changes.map { it.setting.key })
        assertNotNull(tool().approvalPreview(stricter, PermissionMode.FULL_ACCESS))
    }

    @Test fun moreAreasAreReachableAndPrivacyOrReachChangesAsk() {
        listOf("chat_search_code_chats", "code_search_chat_chats", "stream_stall_timeout", "stream_file_writes_as_text", "live_mode",
            "live_mode_abilities", "local_model_experimental", "storage_cards").forEach { assertNotNull("$it is in the catalog", AgentSettingsCatalog[it]) }
        listOf("chat_search_code_chats", "code_search_chat_chats", "speech_to_text", "text_to_speech", "live_mode_abilities").forEach {
            assertTrue("$it asks first", AgentSettingsCatalog[it]!!.sensitive)
        }
        val experimental = tool().plan(args("""{"changes":[{"key":"local_model_experimental","value":"true"}]}"""), defaults)
        assertTrue(experimental.changes.isEmpty())
        val stall = AgentSettingsCatalog["stream_stall_timeout"]!!
        assertEquals("0", stall.normalize("0", defaults).getOrNull())
        assertEquals("120", stall.normalize("120 s", defaults).getOrNull())
        assertTrue(stall.normalize("3601", defaults).isFailure)
    }

    @Test fun skillsAreSwitchedByNameAndUnknownOnesAreRefused() {
        val skill = AgentSettingsCatalog["skill:pdf"]!!
        assertEquals("pdf", skill.name)
        assertEquals("true", skill.read(defaults))
        assertEquals("false", skill.read(defaults.copy(disabledSkills = setOf("pdf"))))
        val known = tool().plan(args("""{"changes":[{"key":"skill:pdf","value":"off"}]}"""), defaults, listOf("pdf"))
        assertEquals("false", known.changes.single().after)
        val unknown = tool().plan(args("""{"changes":[{"key":"skill:nope","value":"off"}]}"""), defaults, listOf("pdf"))
        assertTrue(unknown.changes.isEmpty())
        assertEquals(1, unknown.errors.size)
        assertNull(AgentSettingsCatalog["skill:"])
    }

    @Test fun pluginActionsThatCanStartCodeAlwaysAsk() {
        val plugins = AppPluginsTool(null) { defaults }
        listOf("""{"action":"install","token":"t"}""", """{"action":"enable","plugin":"x"}""", """{"action":"uninstall","plugin":"x"}""",
            """{"action":"auto_update","plugin":"x","value":true}""").forEach { assertTrue(it, plugins.sensitive(args(it))) }
        listOf("""{"action":"disable","plugin":"x"}""", """{"action":"auto_update","plugin":"x","value":false}""", """{"action":"list"}""",
            """{"action":"search","query":"git"}""", """{"action":"inspect","source":"r1"}""").forEach { assertFalse(it, plugins.sensitive(args(it))) }
    }
}
