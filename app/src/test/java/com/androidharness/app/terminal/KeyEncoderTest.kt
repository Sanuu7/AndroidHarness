package com.androidharness.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyEncoderTest {
    private val esc = "\u001b"

    private fun key(k: TermKey, mods: KeyModifiers = KeyModifiers.NONE, app: Boolean = false) = KeyEncoder.keyText(k, mods, app)
    private fun char(c: Char, mods: KeyModifiers = KeyModifiers.NONE) = String(KeyEncoder.char(c.code, mods), Charsets.UTF_8)

    @Test fun arrowsFollowCursorKeyMode() {
        assertEquals("$esc[A", key(TermKey.UP))
        assertEquals("$esc[B", key(TermKey.DOWN))
        assertEquals("$esc[C", key(TermKey.RIGHT))
        assertEquals("$esc[D", key(TermKey.LEFT))
        assertEquals("${esc}OA", key(TermKey.UP, app = true))
        assertEquals("${esc}OD", key(TermKey.LEFT, app = true))
    }

    @Test fun modifiedArrowsUseTheXtermParameter() {
        assertEquals("$esc[1;5A", key(TermKey.UP, KeyModifiers(ctrl = true)))
        assertEquals("$esc[1;3C", key(TermKey.RIGHT, KeyModifiers(alt = true)))
        assertEquals("$esc[1;2D", key(TermKey.LEFT, KeyModifiers(shift = true)))
        assertEquals("$esc[1;8B", key(TermKey.DOWN, KeyModifiers(ctrl = true, alt = true, shift = true)))
        // Modifiers win over application cursor mode, like xterm.
        assertEquals("$esc[1;5A", key(TermKey.UP, KeyModifiers(ctrl = true), app = true))
    }

    @Test fun homeEndAndEditingKeys() {
        assertEquals("$esc[H", key(TermKey.HOME))
        assertEquals("$esc[F", key(TermKey.END))
        assertEquals("${esc}OH", key(TermKey.HOME, app = true))
        assertEquals("$esc[2~", key(TermKey.INSERT))
        assertEquals("$esc[3~", key(TermKey.DELETE))
        assertEquals("$esc[5~", key(TermKey.PAGE_UP))
        assertEquals("$esc[6~", key(TermKey.PAGE_DOWN))
        assertEquals("$esc[3;5~", key(TermKey.DELETE, KeyModifiers(ctrl = true)))
        assertEquals("$esc[5;2~", key(TermKey.PAGE_UP, KeyModifiers(shift = true)))
    }

    @Test fun functionKeys() {
        assertEquals("${esc}OP", key(TermKey.F1))
        assertEquals("${esc}OS", key(TermKey.F4))
        assertEquals("$esc[15~", key(TermKey.F5))
        assertEquals("$esc[17~", key(TermKey.F6))
        assertEquals("$esc[21~", key(TermKey.F10))
        assertEquals("$esc[24~", key(TermKey.F12))
        assertEquals("$esc[1;5P", key(TermKey.F1, KeyModifiers(ctrl = true)))
        assertEquals("$esc[15;2~", key(TermKey.F5, KeyModifiers(shift = true)))
    }

    @Test fun enterTabEscapeBackspace() {
        assertEquals("\r", key(TermKey.ENTER))
        assertEquals("$esc\r", key(TermKey.ENTER, KeyModifiers(alt = true)))
        assertEquals("\t", key(TermKey.TAB))
        assertEquals("$esc[Z", key(TermKey.TAB, KeyModifiers(shift = true)))
        assertEquals(esc, key(TermKey.ESCAPE))
        assertEquals("\u007f", key(TermKey.BACKSPACE))
        assertEquals("\b", key(TermKey.BACKSPACE, KeyModifiers(ctrl = true)))
        assertEquals("$esc\u007f", key(TermKey.BACKSPACE, KeyModifiers(alt = true)))
    }

    @Test fun ctrlLettersBecomeControlCodes() {
        assertEquals("\u0003", char('c', KeyModifiers(ctrl = true)))
        assertEquals("\u0003", char('C', KeyModifiers(ctrl = true)))
        assertEquals("\u0001", char('a', KeyModifiers(ctrl = true)))
        assertEquals("\u001a", char('z', KeyModifiers(ctrl = true)))
        assertEquals("\u0004", char('d', KeyModifiers(ctrl = true)))
    }

    @Test fun ctrlSymbols() {
        assertEquals("\u0000", char(' ', KeyModifiers(ctrl = true)))
        assertEquals("\u0000", char('@', KeyModifiers(ctrl = true)))
        assertEquals("\u001b", char('[', KeyModifiers(ctrl = true)))
        assertEquals("\u001c", char('\\', KeyModifiers(ctrl = true)))
        assertEquals("\u001d", char(']', KeyModifiers(ctrl = true)))
        assertEquals("\u001f", char('_', KeyModifiers(ctrl = true)))
        assertEquals("\u007f", char('?', KeyModifiers(ctrl = true)))
        // Characters Ctrl does not change pass through.
        assertEquals("1", char('1', KeyModifiers(ctrl = true)))
    }

    @Test fun altPrefixesEscape() {
        assertEquals("${esc}x", char('x', KeyModifiers(alt = true)))
        assertEquals("$esc\u0018", char('x', KeyModifiers(ctrl = true, alt = true)))
    }

    @Test fun nonAsciiCharactersAreUtf8() {
        assertEquals("é", char('é'))
        assertEquals("é", char('é', KeyModifiers(ctrl = true)))
        assertEquals("😀", String(KeyEncoder.char(0x1F600), Charsets.UTF_8))
    }

    @Test fun pasteUsesCarriageReturnsAndBrackets() {
        assertEquals("a\rb\rc", String(KeyEncoder.paste("a\nb\r\nc", bracketed = false)))
        assertEquals("$esc[200~ls\r$esc[201~", String(KeyEncoder.paste("ls\n", bracketed = true)))
        // An embedded end marker cannot close the paste early.
        assertEquals("$esc[200~xy$esc[201~", String(KeyEncoder.paste("x$esc[201~y", bracketed = true)))
    }

    @Test fun modifierParameter() {
        assertEquals(1, KeyModifiers.NONE.xtermParam)
        assertEquals(2, KeyModifiers(shift = true).xtermParam)
        assertEquals(3, KeyModifiers(alt = true).xtermParam)
        assertEquals(5, KeyModifiers(ctrl = true).xtermParam)
        assertEquals(7, KeyModifiers(ctrl = true, alt = true).xtermParam)
    }
}
