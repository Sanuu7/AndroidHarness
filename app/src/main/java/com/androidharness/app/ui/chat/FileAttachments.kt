package com.androidharness.app.ui.chat

/**
 * Composer file attachments. Text-like files ride inline in the message as
 * fenced blocks the model reads directly; everything else is copied into the
 * workspace (under .harness/attachments/) so the agent's shell tools can
 * inspect it. The same object folds those blocks back into compact chips for
 * display, so a user bubble never renders a wall of raw file content.
 */
object FileAttachments {

    /** Inline text above this many characters falls back to a workspace copy. */
    const val INLINE_CHAR_LIMIT = 32_000

    /** Workspace copy cap: attachments larger than this are refused. */
    const val COPY_BYTE_LIMIT = 64L * 1024 * 1024

    data class Block(val name: String, val mime: String, val sizeLabel: String)

    private val headerRegex = Regex("^\\[Attached file: (.+?) \\((.+?), (.+?)\\)]")

    fun header(name: String, mime: String, sizeLabel: String) =
        "[Attached file: $name ($mime, $sizeLabel)]"

    /** The full inline block for a text attachment: header plus fenced body. */
    fun inlineBlock(name: String, mime: String, sizeLabel: String, content: String, lang: String?): String =
        buildString {
            appendLine(header(name, mime, sizeLabel))
            append("```")
            appendLine(lang ?: "")
            appendLine(content.trimEnd('\n'))
            append("```")
        }

    /** One-line note for a binary attachment copied into the workspace. */
    fun workspaceNote(name: String, mime: String, sizeLabel: String, path: String): String =
        "${header(name, mime, sizeLabel)} saved into the workspace at $path; " +
            "inspect it with shell tools (file, unzip, python) as needed."

    /** Appends every attachment as model-readable blocks after the user's text. */
    fun buildMessageSuffix(files: List<FileAttachment>): String =
        files.joinToString("\n\n") { file ->
            file.inlineText?.let { inlineBlock(file.name, file.mime, file.sizeLabel, it, langFor(file.name)) }
                ?: workspaceNote(file.name, file.mime, file.sizeLabel, file.workspacePath.orEmpty())
        }

    /**
     * Splits a user message into the visible text plus one chip per attached
     * file: inline fenced bodies collapse into the chip, binary notes become
     * a chip as well.
     */
    fun splitForDisplay(text: String): Pair<String, List<Block>> {
        val blocks = mutableListOf<Block>()
        val cleaned = StringBuilder()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            // No end anchor: binary notes continue with " saved into…" text.
            val match = headerRegex.find(lines[i])
            if (match == null) {
                cleaned.appendLine(lines[i])
                i++
                continue
            }
            val (name, mime, size) = match.destructured
            // Inline blocks carry a fenced body; skip past its closing fence.
            if (i + 1 < lines.size && lines[i + 1].startsWith("```")) {
                var j = i + 2
                while (j < lines.size && lines[j] != "```") j++
                i = if (j < lines.size) j + 1 else lines.size
            } else {
                i++
            }
            blocks += Block(name, mime, size)
        }
        return cleaned.toString().trimEnd() to blocks
    }

    // --- text detection -------------------------------------------------------

    private val textMimes = setOf(
        "application/json", "application/xml", "application/javascript",
        "application/x-yaml", "application/yaml", "application/toml",
        "application/sql", "application/x-sh", "image/svg+xml",
    )

    private val textExtensions = setOf(
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "json", "md", "markdown",
        "txt", "csv", "tsv", "log", "xml", "yml", "yaml", "sh", "bash", "zsh",
        "gradle", "properties", "toml", "ini", "cfg", "conf", "sql", "html", "htm",
        "css", "scss", "rs", "c", "h", "cpp", "hpp", "go", "rb", "php", "swift",
        "dart", "lua", "pl", "bat", "ps1", "gitignore", "env", "lock",
    )

    fun isPdf(name: String, mime: String?): Boolean {
        if (mime == "application/pdf") return true
        return name.endsWith(".pdf", ignoreCase = true)
    }

    fun isTextLike(name: String, mime: String?): Boolean {
        if (mime != null) {
            if (mime.startsWith("text/")) return true
            if (mime in textMimes) return true
            if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")) return false
            // application/octet-stream and unknown types fall through to the extension.
        }
        return name.substringAfterLast('.', "").lowercase() in textExtensions
    }

    fun langFor(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "text"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "rb" -> "ruby"
            "rs" -> "rust"
            "sh", "bash", "zsh" -> "bash"
            "yml", "yaml" -> "yaml"
            "md", "markdown" -> "markdown"
            in setOf("java", "json", "xml", "html", "css", "go", "sql", "c", "cpp", "h") -> ext
            else -> null
        }
    }

    fun humanBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}

/** One file waiting in the composer to ride with the next message. */
data class FileAttachment(
    val name: String,
    val mime: String,
    val sizeLabel: String,
    /** Decoded content for text-like files; null when the file was copied to the workspace. */
    val inlineText: String?,
    /** Workspace-relative path for binary copies; null for inline attachments. */
    val workspacePath: String?,
)

/**
 * The trailing @path token in the composer text, as (tokenStartIndex, query),
 * or null. Mentions only trigger when @ is at the start of the text or after
 * whitespace (so email addresses never trigger), and only as the last thing
 * typed, since the composer is a plain String without cursor access.
 */
object MentionToken {

    fun parse(text: String): Pair<Int, String>? {
        val lastLine = text.substringAfterLast('\n')
        val match = Regex("(^|\\s)@([A-Za-z0-9._\\-/]*)$").find(lastLine) ?: return null
        val lineOffset = text.length - lastLine.length
        val tokenStart = lineOffset + lastLine.length - match.groupValues[2].length - 1
        return tokenStart to match.groupValues[2]
    }
}
