package com.androidharness.app.core

import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WebTargetType {
    LOCAL_SERVER,
    WORKSPACE_HTML,
    CHAT_LINK,
}

data class WebPreviewTarget(
    val type: WebTargetType,
    val title: String,
    val subtitle: String,
    val urlOrPath: String,
    val isLive: Boolean = false,
)

object WebResourceExtractor {

    private val markdownLinkRegex = Regex("""\[([^\]]+)\]\((https?://[^\)\s]+|localhost:[^\)\s]+|127\.0\.0\.1:[^\)\s]+)\)""", RegexOption.IGNORE_CASE)
    private val rawUrlRegex = Regex("""\b(https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+)\b""", RegexOption.IGNORE_CASE)
    private val htmlFileRegex = Regex("""\b([a-zA-Z0-9_\-./]+\.(?:html|htm))\b""", RegexOption.IGNORE_CASE)

    /**
     * Extract all unique URLs found in text (both markdown links and bare URLs).
     */
    fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        markdownLinkRegex.findAll(text).forEach { match ->
            urls += match.groupValues[2]
        }
        rawUrlRegex.findAll(text).forEach { match ->
            urls += match.groupValues[1]
        }
        return urls.map { it.trimEnd('.', ',', ';', ':', ')', ']') }.distinct()
    }

    /**
     * Extracts referenced HTML file names in a text message.
     */
    fun extractHtmlFileReferences(text: String): List<String> {
        return htmlFileRegex.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { !it.startsWith("http://") && !it.startsWith("https://") }
            .distinct()
            .toList()
    }

    /**
     * Collect all HTML files present in the current workspace.
     */
    suspend fun findWorkspaceHtmlFiles(workspace: WorkspaceFs?): List<String> =
        withContext(Dispatchers.IO) {
            if (workspace == null) return@withContext emptyList()
            try {
                workspace.walk(".")
                    .filter { it.isFile && (it.name.endsWith(".html", ignoreCase = true) || it.name.endsWith(".htm", ignoreCase = true)) }
                    .map { it.relPath }
                    .take(50)
                    .toList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Determine if a message has previewable web targets (URLs or HTML files) to show a preview action chip.
     */
    fun findPrimaryPreviewTarget(text: String): WebPreviewTarget? {
        val urls = extractUrls(text)
        val localhost = urls.firstOrNull { LocalPortProbe.isLocalhostUrl(it) }
        if (localhost != null) {
            return WebPreviewTarget(
                type = WebTargetType.LOCAL_SERVER,
                title = "Open Web Preview",
                subtitle = localhost,
                urlOrPath = localhost,
                isLive = true,
            )
        }

        val htmlFiles = extractHtmlFileReferences(text)
        if (htmlFiles.isNotEmpty()) {
            val file = htmlFiles.first()
            return WebPreviewTarget(
                type = WebTargetType.WORKSPACE_HTML,
                title = "Preview $file",
                subtitle = "Local workspace file",
                urlOrPath = file,
                isLive = false,
            )
        }

        if (urls.isNotEmpty()) {
            val firstUrl = urls.first()
            return WebPreviewTarget(
                type = WebTargetType.CHAT_LINK,
                title = "Open Link Preview",
                subtitle = firstUrl,
                urlOrPath = firstUrl,
                isLive = false,
            )
        }

        return null
    }
}
