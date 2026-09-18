package com.androidharness.app.ui.files

import androidx.compose.ui.graphics.Color

/**
 * The GitHub-flavoured token palette, shared by the file editor and the code blocks
 * in chat and tool output so the same snippet is coloured the same way in both.
 *
 * Keys off [TokenType], not the editor's own slot ids, so the two callers stay
 * independent of each other's rendering library.
 */
object CodeTokenColors {

    private val DarkControlKeyword = Color(0xFFFF7B72)
    private val DarkKeyword = Color(0xFF79C0FF)
    private val DarkType = Color(0xFFFFA657)
    private val DarkFunction = Color(0xFFD2A8FF)
    private val DarkTag = Color(0xFF7EE787)
    private val DarkAttribute = Color(0xFF79C0FF)
    private val DarkString = Color(0xFFA5D6FF)
    private val DarkNumber = Color(0xFF7EE787)
    private val DarkComment = Color(0xFF8B949E)
    private val DarkAnnotation = Color(0xFFFF9E64)
    private val DarkOperator = Color(0xFFFF7B72)

    private val LightControlKeyword = Color(0xFFCF222E)
    private val LightKeyword = Color(0xFF0550AE)
    private val LightType = Color(0xFF953800)
    private val LightFunction = Color(0xFF8250DF)
    private val LightTag = Color(0xFF116329)
    private val LightAttribute = Color(0xFF0550AE)
    private val LightString = Color(0xFF0A3069)
    private val LightNumber = Color(0xFF1A7F37)
    private val LightComment = Color(0xFF6E7781)
    private val LightAnnotation = Color(0xFFB35900)
    private val LightOperator = Color(0xFFCF222E)

    /** [plain] is what unhighlighted text uses, normally the theme's onSurface. */
    fun of(type: TokenType, dark: Boolean, plain: Color): Color = when (type) {
        TokenType.KEYWORD_CONTROL -> if (dark) DarkControlKeyword else LightControlKeyword
        TokenType.KEYWORD -> if (dark) DarkKeyword else LightKeyword
        TokenType.TYPE_NAME -> if (dark) DarkType else LightType
        TokenType.FUNCTION_NAME -> if (dark) DarkFunction else LightFunction
        TokenType.HTML_TAG -> if (dark) DarkTag else LightTag
        TokenType.ATTRIBUTE_NAME -> if (dark) DarkAttribute else LightAttribute
        TokenType.STRING -> if (dark) DarkString else LightString
        TokenType.NUMBER -> if (dark) DarkNumber else LightNumber
        TokenType.COMMENT -> if (dark) DarkComment else LightComment
        TokenType.ANNOTATION -> if (dark) DarkAnnotation else LightAnnotation
        TokenType.OPERATOR -> if (dark) DarkOperator else LightOperator
        TokenType.PLAIN -> plain
    }

    /**
     * Whether [scheme surface] is a dark surface, using the same perceptual
     * luminance test the editor theme uses so the two never disagree.
     */
    fun isDarkSurface(surface: Color): Boolean =
        surface.red * 0.299f + surface.green * 0.587f + surface.blue * 0.114f < 0.5f
}
