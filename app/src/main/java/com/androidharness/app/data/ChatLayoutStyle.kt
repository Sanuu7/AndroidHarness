package com.androidharness.app.data

/** How much breathing room the chat gets between messages and around the composer. */
@kotlinx.serialization.Serializable
enum class ChatDensity(val scale: Float) { COMPACT(0.5f), DEFAULT(1f), COMFORTABLE(1.5f) }

/**
 * Reader-tunable chat spacing from Appearance settings (#127).
 * [lineSpacing] multiplies answer text size; [sideMargin] is the message column's side margin in dp.
 */
@kotlinx.serialization.Serializable
data class ChatLayoutStyle(
    val density: ChatDensity = ChatDensity.DEFAULT,
    val lineSpacing: Float = DEFAULT_LINE_SPACING,
    val sideMargin: Int = DEFAULT_SIDE_MARGIN,
) {
    companion object {
        const val DEFAULT_LINE_SPACING = 1.6f
        const val DEFAULT_SIDE_MARGIN = 18
        val LINE_SPACING_RANGE = 1.2f..2.0f
        val SIDE_MARGIN_RANGE = 4..40

        fun clampLineSpacing(v: Float?): Float =
            v?.takeIf { it.isFinite() }?.coerceIn(LINE_SPACING_RANGE) ?: DEFAULT_LINE_SPACING
        fun clampSideMargin(v: Int?): Int = v?.coerceIn(SIDE_MARGIN_RANGE) ?: DEFAULT_SIDE_MARGIN
    }
}
