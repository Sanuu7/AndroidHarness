package com.androidharness.app.ui.files

/** Which Markdown files get a rendered view next to the source editor. */
internal object MarkdownPreview {
    private val extensions = setOf("md", "markdown")

    /** Rendering lays out every block at once, so very long files stay in source view. */
    const val MAX_CHARS = 400_000

    fun supports(path: String): Boolean =
        path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in extensions

    /**
     * The mode a file opens in when the user has not picked one yet: rendered for
     * reading, source when editing was restored or a specific line was requested.
     */
    fun opensInPreview(path: String, editing: Boolean, jumpToLine: Boolean): Boolean =
        supports(path) && !editing && !jumpToLine

    fun canRender(text: String?): Boolean = text != null && text.length <= MAX_CHARS
}
