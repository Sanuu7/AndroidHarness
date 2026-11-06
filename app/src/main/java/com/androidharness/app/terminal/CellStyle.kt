package com.androidharness.app.terminal

/**
 * A cell's colours and attributes packed into one Long so a screen row is two
 * flat arrays instead of an object per cell.
 *
 * Bits 0..24 hold the foreground, 25..49 the background, 50..58 the
 * attributes. A colour is a palette index (0..255), [DEFAULT_FG] or
 * [DEFAULT_BG], or a 24-bit RGB value tagged with [TRUECOLOR].
 */
object CellStyle {
    const val DEFAULT_FG = 256
    const val DEFAULT_BG = 257
    const val TRUECOLOR = 0x1000000

    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val INVISIBLE = 64
    const val STRIKE = 128

    private const val COLOR_MASK = 0x1FFFFFFL

    val NORMAL: Long = encode(DEFAULT_FG, DEFAULT_BG, 0)

    fun encode(fg: Int, bg: Int, attrs: Int): Long =
        (fg.toLong() and COLOR_MASK) or ((bg.toLong() and COLOR_MASK) shl 25) or ((attrs.toLong() and 0x1FF) shl 50)

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()
    fun bg(style: Long): Int = ((style shr 25) and COLOR_MASK).toInt()
    fun attrs(style: Long): Int = ((style shr 50) and 0x1FF).toInt()

    fun rgb(r: Int, g: Int, b: Int): Int = TRUECOLOR or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    fun isTrueColor(color: Int): Boolean = color and TRUECOLOR != 0
}
