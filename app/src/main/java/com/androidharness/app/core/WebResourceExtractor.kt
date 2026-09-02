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

    private val markdownLinkRegex = Regex("""\[([^\]]+)\]\((https?://[^\)\s]+|localhost:[^\)\s]+|127\.0\.0\.1:[^\)\s]+|preview:[^\)\s]+)\)""", RegexOption.IGNORE_CASE)
    private val rawUrlRegex = Regex("""\b(https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+)\b""", RegexOption.IGNORE_CASE)
    private val htmlFileRegex = Regex("""\b([a-zA-Z0-9_\-./]+\.(?:html|htm))\b""", RegexOption.IGNORE_CASE)
    private val explicitPreviewDirectiveRegex = Regex("""(?:::web-preview|\{preview|\{web-preview)\{target=["']([^"']+)["'](?:,\s*title=["']([^"']+)["'])?\}""", RegexOption.IGNORE_CASE)

    /**
     * Strips explicit preview directives from text so they don't display as raw markdown markup.
     */
    fun stripDirectives(text: String): String {
        return text.replace(explicitPreviewDirectiveRegex, "").trimEnd()
    }

    /**
     * Extracts an explicit preview directive if emitted by the agent (e.g. ::web-preview{target="index.html"}).
     */
    fun extractExplicitDirective(text: String): WebPreviewTarget? {
        val match = explicitPreviewDirectiveRegex.find(text) ?: return null
        val target = match.groupValues[1].trim()
        val customTitle = match.groupValues.getOrNull(2)?.trim()?.ifBlank { null }

        return when {
            LocalPortProbe.isLocalhostUrl(target) -> WebPreviewTarget(
                type = WebTargetType.LOCAL_SERVER,
                title = customTitle ?: "Open Web Preview",
                subtitle = target,
                urlOrPath = target,
                isLive = true,
            )
            target.endsWith(".html", ignoreCase = true) || target.endsWith(".htm", ignoreCase = true) -> WebPreviewTarget(
                type = WebTargetType.WORKSPACE_HTML,
                title = customTitle ?: "Preview ${target.substringAfterLast('/')}",
                subtitle = "Local workspace file",
                urlOrPath = target,
                isLive = false,
            )
            else -> WebPreviewTarget(
                type = WebTargetType.CHAT_LINK,
                title = customTitle ?: "Open Link Preview",
                subtitle = target,
                urlOrPath = target,
                isLive = false,
            )
        }
    }

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
     * Determine if a message has previewable web targets (only live localhost servers).
     * Raw HTML files and external non-localhost URLs are ignored so only real local servers get the button.
     */
    fun findPrimaryPreviewTarget(text: String): WebPreviewTarget? {
        // 0. Explicit agent directive if pointing to localhost
        val explicit = extractExplicitDirective(text)
        if (explicit != null && explicit.type == WebTargetType.LOCAL_SERVER) return explicit

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

        return null
    }
}
