package com.androidharness.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableLayoutTest {
    @Test
    fun `table copy preserves columns and blank cells for spreadsheet paste`() {
        assertEquals(
            "Name\tPrice\tNotes\nTea\t2\t\nCoffee\t3\tHot drink",
            tableAsTsv(listOf(
                listOf("Name", "Price", "Notes"),
                listOf("Tea", "2", ""),
                listOf("Coffee", "3", "Hot\n\tdrink"),
            )),
        )
        assertEquals("", tableAsTsv(emptyList()))
    }

    @Test
    fun `narrow columns share available space exactly`() {
        assertEquals(listOf(181, 180), markdownColumnWidths(listOf(40, 50), 361, 88, 280))
    }

    @Test
    fun `wide tables keep readable widths and long cells wrap at the cap`() {
        val widths = markdownColumnWidths(listOf(30, 900, 160), 360, 88, 280)
        assertEquals(listOf(88, 280, 160), widths)
        assertTrue(widths.sum() > 360)
    }

    @Test
    fun `escaped pipes cannot shift later columns`() {
        assertEquals(listOf("Pattern", "Meaning"), splitTableRow("| Pattern | Meaning |"))
        assertEquals(listOf("a|b", "either"), splitTableRow("| a\\|b | either |"))
        assertEquals(listOf("`a|b`", "either"), splitTableRow("| `a\\|b` | either |"))
        assertEquals(listOf("one", "", "three"), splitTableRow("one | | three"))
        assertEquals(listOf("one", "two|"), splitTableRow("one | two\\|"))
    }

    @Test
    fun `only matching header and delimiter columns start tables`() {
        assertTrue(isTableStart(listOf("| Name | Price |", "| :--- | ---: |"), 0))
        assertFalse(isTableStart(listOf("| Name | Price |", "| --- |"), 0))
        assertFalse(isTableStart(listOf("| Name | Price |", "| ::: | --- |"), 0))
    }
}
