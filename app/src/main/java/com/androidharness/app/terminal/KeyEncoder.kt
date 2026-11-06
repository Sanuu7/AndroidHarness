package com.androidharness.app.terminal

enum class TermKey {
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    ENTER, TAB, ESCAPE, BACKSPACE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

data class KeyModifiers(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    val any: Boolean get() = ctrl || alt || shift

    /** xterm modifier parameter: 1 + Shift(1) + Alt(2) + Ctrl(4). */
    val xtermParam: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    companion object { val NONE = KeyModifiers() }
}

/** Turns key presses into the bytes an xterm sends, following the current terminal modes. */
object KeyEncoder {
    private const val ESC = "\u001b"

    fun key(key: TermKey, mods: KeyModifiers = KeyModifiers.NONE, applicationCursor: Boolean = false): ByteArray =
        keyText(key, mods, applicationCursor).toByteArray(Charsets.UTF_8)

    fun keyText(key: TermKey, mods: KeyModifiers = KeyModifiers.NONE, applicationCursor: Boolean = false): String {
        val m = mods.xtermParam
        fun cursor(final: Char): String = when {
            mods.any -> "$ESC[1;$m$final"
            applicationCursor -> "${ESC}O$final"
            else -> "$ESC[$final"
        }
        fun tilde(code: Int): String = if (mods.any) "$ESC[$code;$m~" else "$ESC[$code~"
        fun ss3(final: Char): String = if (mods.any) "$ESC[1;$m$final" else "${ESC}O$final"
        return when (key) {
            TermKey.UP -> cursor('A')
            TermKey.DOWN -> cursor('B')
            TermKey.RIGHT -> cursor('C')
            TermKey.LEFT -> cursor('D')
            TermKey.HOME -> cursor('H')
            TermKey.END -> cursor('F')
            TermKey.INSERT -> tilde(2)
            TermKey.DELETE -> tilde(3)
            TermKey.PAGE_UP -> tilde(5)
            TermKey.PAGE_DOWN -> tilde(6)
            TermKey.F1 -> ss3('P')
            TermKey.F2 -> ss3('Q')
            TermKey.F3 -> ss3('R')
            TermKey.F4 -> ss3('S')
            TermKey.F5 -> tilde(15)
            TermKey.F6 -> tilde(17)
            TermKey.F7 -> tilde(18)
            TermKey.F8 -> tilde(19)
            TermKey.F9 -> tilde(20)
            TermKey.F10 -> tilde(21)
            TermKey.F11 -> tilde(23)
            TermKey.F12 -> tilde(24)
            TermKey.ENTER -> if (mods.alt) "$ESC\r" else "\r"
            TermKey.TAB -> when {
                mods.shift -> "$ESC[Z"
                mods.alt -> "$ESC\t"
                else -> "\t"
            }
            TermKey.ESCAPE -> if (mods.alt) "$ESC$ESC" else ESC
            TermKey.BACKSPACE -> when {
                mods.ctrl && mods.alt -> "$ESC\b"
                mods.ctrl -> "\b"
                mods.alt -> "$ESC\u007f"
                else -> "\u007f"
            }
        }
    }

    /** A typed character, with Ctrl folding it to a control code and Alt adding an Escape prefix. */
    fun char(codePoint: Int, mods: KeyModifiers = KeyModifiers.NONE): ByteArray {
        var cp = codePoint
        if (mods.ctrl) cp = controlCode(cp) ?: cp
        val text = String(Character.toChars(cp))
        return (if (mods.alt) ESC + text else text).toByteArray(Charsets.UTF_8)
    }

    /** The C0 code Ctrl+[codePoint] produces, or null when Ctrl does not change it. */
    fun controlCode(codePoint: Int): Int? = when (val c = codePoint.toChar()) {
        in 'a'..'z' -> c - 'a' + 1
        in 'A'..'Z' -> c - 'A' + 1
        ' ', '@', '2' -> 0
        '[', '3' -> 27
        '\\', '4' -> 28
        ']', '5' -> 29
        '^', '6', '~' -> 30
        '_', '-', '7', '/' -> 31
        '?', '8' -> 127
        else -> null
    }.takeIf { codePoint < 0x80 }

    /** Wraps pasted text in bracketed-paste markers when the program asked for them, and uses CR for newlines. */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val body = text.replace("\r\n", "\r").replace('\n', '\r')
        val safe = if (bracketed) body.replace("$ESC[201~", "") else body
        return (if (bracketed) "$ESC[200~$safe$ESC[201~" else safe).toByteArray(Charsets.UTF_8)
    }
}
