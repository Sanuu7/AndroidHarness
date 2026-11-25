package com.androidharness.app.ui.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import com.androidharness.app.ui.chat.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashMenuTest {
    private fun entry(command: String, title: String = "", subtitle: String = "", aliases: List<String> = emptyList()) =
        SlashEntry(command, title, subtitle, SlashCommands.Kind.GOAL, Icons.Outlined.Flag, aliases = aliases)

    @Test fun `exact names rank above prefixes, which rank above other matches`() {
        assertEquals(0, slashRank(entry("/goal"), "goal"))
        assertEquals(1, slashRank(entry("/goal"), "go"))
        assertEquals(2, slashRank(entry("/autocompact"), "compact"))
        assertEquals(3, slashRank(entry("/btw", title = "By the way"), "by"))
        assertEquals(4, slashRank(entry("/cost", subtitle = "Token totals"), "token"))
        assertNull(slashRank(entry("/cost"), "zzz"))
    }

    @Test fun `aliases match like the command itself`() {
        assertEquals(0, slashRank(entry("/autocompact", aliases = listOf("/auto-compact")), "auto-compact"))
    }
}
