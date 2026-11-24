package com.androidharness.app.tools

import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AgentSettingsAccess
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.CustomSettingsItem
import com.androidharness.app.data.CustomSettingsSection
import com.androidharness.app.data.CustomSettingsSections
import com.androidharness.app.ui.chat.components.SectionsDiff
import com.androidharness.app.ui.chat.components.sectionsDiff
import com.androidharness.app.ui.settings.AgentSettingsCatalog
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SectionStepsTest {
    private val defaults = AppSettings()
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun tool(settings: AppSettings = defaults) = AppSettingsChangeTool(null) { settings }
    private fun after(plan: AppSettingsChangeTool.Plan) = CustomSettingsSections.parse(plan.sections!!.after)!!

    private val mine = CustomSettingsSection("s1", "Mine", "STAR", listOf(
        CustomSettingsItem(setting = "theme_mode"),
        CustomSettingsItem(header = "Sound"),
        CustomSettingsItem(page = "VOICE"),
        CustomSettingsItem(section = CustomSettingsSection("s2", "Deeper", items = listOf(CustomSettingsItem(setting = "rumble")))),
    ))
    private val withMine = defaults.copy(customSections = listOf(mine))

    @Test fun aSectionWithTwoShortcutsIsCreatedInOneStep() {
        val plan = tool().plan(args("""{"sections":[{"action":"create_section","name":"Visual tests","icon":"palette",
            "items":[{"setting":"theme_mode"},{"page":"APPEARANCE"}]}]}"""), defaults)
        assertTrue(plan.errors.toString(), plan.errors.isEmpty())
        val created = after(plan).single()
        assertEquals("Visual tests", created.name)
        assertEquals("PALETTE", created.icon)
        assertEquals(listOf("setting:theme_mode", "page:APPEARANCE"), created.items.map { it.key })
        assertEquals("[]", plan.sections!!.before)
    }

    @Test fun laterStepsReachASectionAnEarlierStepMadeByName() {
        val plan = tool().plan(args("""{"sections":[
            {"action":"create_section","name":"Work"},
            {"action":"create_section","path":["work"],"name":"Inner"},
            {"action":"add_item","path":["Work","Inner"],"item":{"header":"Top"}},
            {"action":"add_item","path":["Work"],"item":{"setting":"rumble"},"position":1}]}"""), defaults)
        assertTrue(plan.errors.toString(), plan.errors.isEmpty())
        val work = after(plan).single()
        assertEquals(listOf("setting:rumble", "section:${work.items[1].section!!.id}"), work.items.map { it.key })
        assertEquals("header:Top", work.items[1].section!!.items.single().key)
    }

    @Test fun itemsAndSectionsAreRemovedAndTheOldTreeIsKeptForUndo() {
        val plan = tool(withMine).plan(args("""{"sections":[
            {"action":"remove_item","path":["Mine"],"item":{"key":"page:VOICE"}},
            {"action":"remove_item","path":["Mine"],"item":{"section":"Deeper"}}]}"""), withMine)
        assertTrue(plan.errors.toString(), plan.errors.isEmpty())
        assertEquals(listOf("setting:theme_mode", "header:Sound"), after(plan).single().items.map { it.key })
        // The card's Undo writes this back, and reading the live settings must match it for Undo to show.
        assertEquals(AgentSettingsCatalog.sections.read(withMine), plan.sections!!.before)
        assertEquals(listOf(mine), CustomSettingsSections.parse(plan.sections!!.before))
        val gone = tool(withMine).plan(args("""{"sections":[{"action":"remove_section","path":["s1"]}]}"""), withMine)
        assertEquals(emptyList<CustomSettingsSection>(), after(gone))
    }

    @Test fun moveAndRenameKeepEverythingElse() {
        val plan = tool(withMine).plan(args("""{"sections":[
            {"action":"move_item","path":["Mine"],"item":{"page":"voice"},"position":1},
            {"action":"rename_section","path":["Mine","Deeper"],"name":"Rumble","icon":"bolt"}]}"""), withMine)
        assertTrue(plan.errors.toString(), plan.errors.isEmpty())
        val section = after(plan).single()
        assertEquals("page:VOICE", section.items.first().key)
        assertEquals("Rumble", section.items.last().section!!.name)
        assertEquals("BOLT", section.items.last().section!!.icon)
    }

    @Test fun badPathsAndLimitsSayWhatIsWrong() {
        fun errors(json: String, state: AppSettings = withMine) = tool(state).plan(args(json), state).errors.joinToString("\n")
        val missing = errors("""{"sections":[{"action":"add_item","path":["Nope"],"item":{"header":"x"}}]}""")
        assertTrue(missing, missing.contains("no section \"Nope\"") && missing.contains("\"Mine\""))
        val inner = errors("""{"sections":[{"action":"remove_item","path":["Mine","Gone"],"item":{"header":"x"}}]}""")
        assertTrue(inner, inner.contains("inside \"Mine\""))
        val full = defaults.copy(customSections = (1..CustomSettingsSections.MAX_SECTIONS).map { CustomSettingsSection("id$it", "S$it") })
        val tooMany = errors("""{"sections":[{"action":"create_section","name":"One more"}]}""", full)
        assertTrue(tooMany, tooMany.contains("already 24 sections"))
        val crowded = defaults.copy(customSections = listOf(CustomSettingsSection("c", "Crowded",
            items = (1..CustomSettingsSections.MAX_ITEMS).map { CustomSettingsItem(header = "h$it") })))
        val noRoom = errors("""{"sections":[{"action":"add_item","path":["Crowded"],"item":{"setting":"rumble"}}]}""", crowded)
        assertTrue(noRoom, noRoom.contains("already has 60 items"))
        val deep = errors("""{"sections":[{"action":"create_section","path":["Mine"],"name":"A"},{"action":"create_section","path":["Mine","A"],"name":"B"},
            {"action":"create_section","path":["Mine","A","B"],"name":"C"}]}""")
        assertTrue(deep, deep.contains("at most 3 levels"))
        assertTrue(errors("""{"sections":[{"action":"add_item","path":["Mine"],"item":{"setting":"theme_mode"}}]}""").contains("already has setting:theme_mode"))
        assertTrue(errors("""{"sections":[{"action":"add_item","path":["Mine"],"item":{"setting":"made_up"}}]}""").contains("cannot be a live control"))
        assertTrue(errors("""{"sections":[{"action":"add_item","path":["Mine"],"item":{"setting":"permission_mode"}}]}""").contains("cannot be a live control"))
        assertTrue(errors("""{"sections":[{"action":"add_item","path":["Mine"],"item":{"page":"NOWHERE"}}]}""").contains("not a settings page"))
        assertTrue(errors("""{"sections":[{"action":"create_section","name":"X","icon":"unicorn"}]}""").contains("icon must be one of"))
        assertTrue(errors("""{"sections":[{"action":"explode"}]}""").contains("unknown action"))
        assertTrue(errors("""{"changes":[{"key":"custom_sections","value":"[]"}]}""").contains("sections steps"))
    }

    @Test fun aFailedStepIsReportedWhileTheOthersStillApply() {
        val plan = tool(withMine).plan(args("""{"sections":[
            {"action":"add_item","path":["Nope"],"item":{"header":"x"}},
            {"action":"add_item","path":["Mine"],"item":{"header":"Later"}}]}"""), withMine)
        assertEquals(1, plan.errors.size)
        assertTrue(plan.errors.single().startsWith("Sections step 1 (add_item)"))
        assertEquals("header:Later", after(plan).single().items.last().key)
    }

    @Test fun nothingChangedMeansNoSectionsRow() {
        val plan = tool(withMine).plan(args("""{"sections":[{"action":"move_item","path":["Mine"],"item":{"setting":"theme_mode"},"position":1}]}"""), withMine)
        assertNull(plan.sections)
    }

    @Test fun sectionEditsAskWhenTheUserWantsEveryChangeApproved() = runBlocking {
        val steps = args("""{"sections":[{"action":"create_section","name":"New"}]}""")
        assertNotNull(tool(defaults.copy(agentSettingsAccess = AgentSettingsAccess.ASK)).approvalPreview(steps, PermissionMode.FULL_ACCESS))
        assertNull(tool(defaults.copy(agentSettingsAccess = AgentSettingsAccess.ALLOWED)).approvalPreview(steps, PermissionMode.FULL_ACCESS))
    }

    @Test fun findShowsTheSectionsWithPathsAndTheSteps() = runBlocking {
        val output = AppSettingsFindTool { withMine }.execute(args("""{"query":"sections"}"""), ToolContext(FileFs(File(".")))).output
        assertTrue(output, output.contains("create_section"))
        assertTrue(output, output.contains("- \"Mine\" id=s1 icon=STAR, items in order: setting:theme_mode, header:Sound, page:VOICE, section:s2"))
        assertTrue(output, output.contains("  - \"Deeper\" id=s2"))
        assertTrue(output, output.contains("Pages a section can link to:"))
    }

    @Test fun theCardDescribesWhatChanged() {
        val plan = tool(withMine).plan(args("""{"sections":[
            {"action":"create_section","name":"Visual tests","items":[{"setting":"theme_mode"}]},
            {"action":"remove_item","path":["Mine"],"item":{"header":"Sound"}},
            {"action":"rename_section","path":["Mine","Deeper"],"name":"Buzz"}]}"""), withMine)
        assertEquals(
            listOf(SectionsDiff.Added("Visual tests"), SectionsDiff.Items("Mine", 1), SectionsDiff.Renamed("Buzz")),
            sectionsDiff(plan.sections!!.before, plan.sections!!.after),
        )
        val moved = tool(withMine).plan(args("""{"sections":[{"action":"move_item","path":["Mine"],"item":{"page":"VOICE"},"position":1}]}"""), withMine)
        assertEquals(listOf(SectionsDiff.Rearranged("Mine")), sectionsDiff(moved.sections!!.before, moved.sections!!.after))
    }
}
