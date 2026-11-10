package com.androidharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSettingsSectionsTest {
    private val inner = CustomSettingsSection("inner", "Inner", items = listOf(CustomSettingsItem(setting = "rumble")))
    private val outer = CustomSettingsSection("outer", "Outer", items = listOf(
        CustomSettingsItem(setting = "theme_mode"), CustomSettingsItem(page = "VOICE"), CustomSettingsItem(section = inner),
    ))
    private val sections = listOf(outer, CustomSettingsSection("other", "Other"))

    @Test fun findsNestedSectionsByPath() {
        assertEquals(inner, CustomSettingsSections.find(sections, listOf("outer", "inner")))
        assertNull(CustomSettingsSections.find(sections, listOf("inner")))
        assertNull(CustomSettingsSections.find(sections, emptyList()))
    }

    @Test fun updatesOnlyTheSectionAtThePath() {
        val next = CustomSettingsSections.update(sections, listOf("outer", "inner")) { it.copy(name = "Renamed") }
        assertEquals("Renamed", CustomSettingsSections.find(next, listOf("outer", "inner"))?.name)
        assertEquals("Outer", next[0].name)
        assertEquals(sections[1], next[1])
    }

    @Test fun removesTopLevelAndNestedSections() {
        assertEquals(listOf("other"), CustomSettingsSections.remove(sections, listOf("outer")).map { it.id })
        val next = CustomSettingsSections.remove(sections, listOf("outer", "inner"))
        assertEquals(listOf("setting:theme_mode", "page:VOICE"), next[0].items.map { it.key })
    }

    @Test fun addSkipsDuplicatesAndSwapReorders() {
        assertEquals(outer, CustomSettingsSections.add(outer, CustomSettingsItem(setting = "theme_mode")))
        val swapped = CustomSettingsSections.swap(outer, "setting:theme_mode", "section:inner")
        assertEquals(listOf("section:inner", "page:VOICE", "setting:theme_mode"), swapped.items.map { it.key })
        assertEquals(listOf("page:VOICE", "section:inner"), CustomSettingsSections.drop(outer, "setting:theme_mode").items.map { it.key })
    }

    @Test fun rejectsBrokenTrees() {
        assertTrue(CustomSettingsSections.valid(sections))
        assertFalse(CustomSettingsSections.valid(listOf(outer, outer)))
        assertFalse(CustomSettingsSections.valid(listOf(outer.copy(name = " "))))
        assertFalse(CustomSettingsSections.valid(listOf(CustomSettingsSection("a", "A", items = listOf(CustomSettingsItem())))))
        assertFalse(CustomSettingsSections.valid(listOf(CustomSettingsSection("a", "A", items = listOf(CustomSettingsItem(setting = "x", page = "VOICE"))))))
        var deep = CustomSettingsSection("d0", "Deep")
        repeat(CustomSettingsSections.MAX_DEPTH) { deep = CustomSettingsSection("d${it + 1}", "Deep", items = listOf(CustomSettingsItem(section = deep))) }
        assertFalse(CustomSettingsSections.valid(listOf(deep)))
    }

    @Test fun headingsAndShortcutsHaveTheirOwnRules() {
        fun ok(vararg items: CustomSettingsItem) = CustomSettingsSections.valid(listOf(CustomSettingsSection("a", "A", items = items.toList())))
        assertTrue(ok(CustomSettingsItem(header = "Daily"), CustomSettingsItem(page = "VOICE"), CustomSettingsItem(page = "VOICE", anchor = "settings_anchor_stt")))
        assertFalse(ok(CustomSettingsItem(header = " ")))
        assertFalse(ok(CustomSettingsItem(setting = "rumble", anchor = "x")))
        assertFalse(ok(CustomSettingsItem(header = "Daily"), CustomSettingsItem(header = "Daily")))
    }

    @Test fun movesTopLevelSectionsWithinBounds() {
        assertEquals(listOf("other", "outer"), CustomSettingsSections.move(sections, "outer", 1).map { it.id })
        assertEquals(sections, CustomSettingsSections.move(sections, "outer", -1))
        assertEquals(sections, CustomSettingsSections.move(sections, "missing", 1))
    }

    @Test fun survivesEncodingAndBadInput() {
        assertEquals(sections, CustomSettingsSections.decode(CustomSettingsSections.encode(sections)))
        assertEquals(emptyList<CustomSettingsSection>(), CustomSettingsSections.decode("not json"))
        assertEquals(emptyList<CustomSettingsSection>(), CustomSettingsSections.decode(null))
    }
}
