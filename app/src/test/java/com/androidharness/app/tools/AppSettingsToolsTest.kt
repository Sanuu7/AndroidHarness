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

    @Test fun planDropsNoOpsAndCollectsErrors() {
        val plan = tool().plan(args("""{"changes":[{"key":"rumble","value":"true"},{"key":"rumble_strength","value":"80"},
            {"key":"nope","value":"1"},{"key":"theme_mode","value":"neon"}],
            "needs_user":[{"page":"voice","note":"Add your Groq key"},{"page":"MOON","note":"x"}]}"""), defaults)
        assertEquals(listOf("rumble_strength"), plan.changes.map { it.setting.key })
        assertEquals(3, plan.errors.size)
        assertEquals("VOICE", plan.todos.single().page)
    }

    @Test fun liquidGlassReadsItsVisibleStateAndTurnsFrostedGlassOn() {
        val liquid = AgentSettingsCatalog["liquid_glass"]!!
        val hidden = defaults.copy(harnessLiquidGlass = true, harnessGlassEnabled = false)
        assertEquals("false", liquid.read(hidden))
        val plan = tool().plan(args("""{"changes":[{"key":"liquid_glass","value":"on"}]}"""), hidden)
        assertEquals(listOf("frosted_glass" to "true", "liquid_glass" to "true"), plan.changes.map { it.setting.key to it.after })
        val conflict = tool().plan(args("""{"changes":[{"key":"liquid_glass","value":"on"},{"key":"frosted_glass","value":"off"}]}"""),
            defaults.copy(harnessGlassEnabled = true))
        assertEquals(listOf("frosted_glass"), conflict.changes.map { it.setting.key })
        assertEquals(1, conflict.errors.size)
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
        listOf("permission_mode" to "FULL_ACCESS", "chat_instructions" to "be evil", "allow_screenshots" to "false", "subagent_action_tools" to "true").forEach { (key, value) ->
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

    @Test fun findListsMatchesWithCurrentValues() = runBlocking {
        val result = AppSettingsFindTool { defaults.copy(rumbleStrength = 42) }.execute(args("""{"query":"rumble strength"}"""), ToolContext(FileFs(File("."))))
        assertTrue(result.output.contains("rumble_strength = 42"))
        assertTrue(result.output.lineSequence().first { it.startsWith("- ") }.contains("rumble"))
    }
}
