package com.androidharness.app.ui.settings

import com.androidharness.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsSearchTest {
    private val english = settingsTestStrings()

    private fun first(query: String, text: (Int) -> String = english, englishText: ((Int) -> String)? = null) =
        matchingSettingsEntries(query, text, englishText).first()

    @Test fun `root lands on the root control inside the advanced section`() {
        val root = first("root")
        assertEquals(R.string.settings_root_title, root.anchor)
        assertEquals(SettingsPage.ENVIRONMENT, root.page)
        assertEquals(setOf(R.string.settings_device_advanced), settingsRevealFor(root.anchor))
    }

    @Test fun `memory character controls are searchable and open the memory page`() {
        val unlimited = matchingSettingsEntries("unlimited memory entries", english)
            .first { it.anchor == R.string.memory_entry_unlimited }
        assertEquals(SettingsPage.MEMORY, unlimited.page)
        val limit = matchingSettingsEntries("characters per memory", english)
            .first { it.anchor == R.string.memory_entry_max_chars }
        assertEquals(SettingsPage.MEMORY, limit.page)
    }

    @Test fun `a setting outranks the page that holds it`() {
        val results = matchingSettingsEntries("whisper", english)
        assertFalse(results.first().primary)
        assertFalse("the Voice page only matched through its keywords", results.any { it.primary && it.page == SettingsPage.VOICE })
        assertEquals(R.string.settings_anchor_whisper, results.first().anchor)
        // A page found by its own name stays.
        assertTrue(matchingSettingsEntries("appearance", english).first().let { it.primary && it.page == SettingsPage.APPEARANCE })
    }

    @Test fun `typos still find the setting`() {
        assertEquals(R.string.settings_root_title, first("rooot").anchor)
        assertEquals(SettingsPage.APPEARANCE, first("dark thme").page)
        assertTrue(matchingSettingsEntries("notifcations", english).any { it.anchor == R.string.background_notifications_title })
        assertTrue(matchingSettingsEntries("whispr model", english).any { it.anchor == R.string.settings_anchor_whisper })
        assertTrue(matchingSettingsEntries("qqqqzzzz", english).isEmpty())
    }

    @Test fun `exact matches never pull in near misses`() {
        assertTrue(matchingSettingsEntries("glass", english).isEmpty())
        assertTrue(matchingSettingsEntries("theme", english).none { it.page == SettingsPage.TELEGRAM })
    }

    @Test fun `folding ignores case, accents and script variants`() {
        assertEquals(SettingsSearch.fold("Español"), SettingsSearch.fold("espanol"))
        assertEquals(SettingsSearch.fold("Ёлка"), SettingsSearch.fold("елка"))
        assertEquals(SettingsSearch.fold("ＲＯＯＴ"), "root")
        assertEquals(SettingsSearch.fold("カメラ"), SettingsSearch.fold("かめら"))
        assertEquals(SettingsSearch.fold("اللُّغَة"), SettingsSearch.fold("اللغة"))
        assertEquals(SettingsSearch.fold("زبان فارسي"), SettingsSearch.fold("زبان فارسی"))
        assertEquals(SettingsSearch.fold("١٢٣ ۴۵۶"), "123 456")
        assertEquals(SettingsSearch.fold("Straße"), "strasse")
        assertEquals("dark mode", SettingsSearch.fold("  Dark-MODE!! "))
    }

    @Test fun `every shipped language finds a setting by its own label, and English still works`() {
        val anchor = R.string.settings_anchor_max_context
        File("src/main/res").listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-") }
            .filter { File("src/main/res/$it/strings.xml").exists() }
            .forEach { locale ->
                val local = settingsTestStrings(locale)
                val label = local(anchor)
                assertTrue("$locale: $label", matchingSettingsEntries(label, local, english).any { it.anchor == anchor })
                assertTrue("$locale: English", matchingSettingsEntries("max context", local, english).any { it.anchor == anchor })
            }
    }

    @Test fun `scripts without spaces match part of a label`() {
        val chinese = settingsTestStrings("values-zh-rCN")
        val label = chinese(R.string.settings_anchor_max_context)
        assertTrue(matchingSettingsEntries(label.take(2), chinese).any { it.anchor == R.string.settings_anchor_max_context })
    }

    @Test fun `typing a word without its space still matches`() {
        assertTrue(matchingSettingsEntries("darktheme", english).isNotEmpty() || matchingSettingsEntries("maxcontext", english).any {
            it.anchor == R.string.settings_anchor_max_context
        })
        assertTrue(matchingSettingsEntries("maxcontext", english).any { it.anchor == R.string.settings_anchor_max_context })
    }

    @Test fun `every entry resolves and keys stay unique`() {
        allSettingsEntries.forEach { entry ->
            english(entry.title)
            entry.description?.let(english)
            entry.anchor?.let(english)
            entry.within.forEach { english(it) }
        }
        val keys = allSettingsEntries.map { Triple(it.page, it.title, it.anchor) }
        assertEquals(keys.size, keys.toSet().size)
    }

    /**
     * An entry whose anchor no control renders would open the page and stop at
     * the top. Every anchor must appear in an anchoring call, or as the label of
     * a shared row that anchors itself.
     */
    @Test fun `every anchor is marked by a control`() {
        val source = File("src/main/java/com/androidharness/app/ui").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val callers = "SettingsAnchor|settingsAnchor|ToggleRow|SubagentSettingSwitch|BackgroundSwitch|MemoryPreferenceRow|" +
            "CanvasSwitch|CavemanPanel|TransferToggle|SettingsDisclosure|SettingRow|BehaviorValueRow|SettingsGroupHeading"
        val missing = allSettingsEntries.mapNotNull { it.anchor }.distinct().filter { id ->
            val name = names.getValue(id)
            !Regex("""\b($callers)\([\s\S]{0,200}?R\.string\.$name\b""").containsMatchIn(source)
        }.map { names.getValue(it) }
        assertTrue("Anchors without a control: $missing", missing.isEmpty())
    }
}
