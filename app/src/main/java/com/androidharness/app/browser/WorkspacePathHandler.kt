package com.androidharness.app.browser

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.androidharness.app.workspace.WorkspaceFs
import java.io.ByteArrayInputStream

/**
 * Serves the active workspace to the agent browser through
 * [WebViewAssetLoader] under the fixed https origin
 * `https://harness.workspace/ws/<relative-path>`.
 *
 * This replaces the loopback TCP server: requests are intercepted inside
 * WebView by hostname, so there are no sockets, ports, or health checks to
 * fail, and both the headless WebView and the preview sheet's WebView route
 * through the same handler. Relative links, form GET submits, css/js/image
 * assets, and back/forward history all behave like a real site because every
 * page is a real navigation on a stable origin.
 */
class WorkspacePathHandler(
    private val workspaceProvider: () -> WorkspaceFs?,
    private val rootDocProvider: () -> String?,
) : WebViewAssetLoader.PathHandler {

    /**
     * Maps a request path (already URL-decoded by AssetLoader, registered
     * prefix "/" stripped) to a workspace node. The synthetic "ws/" prefix is
     * stripped so both /ws/index.html and root-relative /style.css resolve
     * from the workspace root. Blank paths (form GET submits land on "/?...")
     * fall back to the current base document, then index.html. Traversal is
     * rejected with a 404.
     */
    override fun handle(path: String): WebResourceResponse? {
        val ws = workspaceProvider()
            ?: return notFound("No active workspace is set.")
        val rel = sanitizeRelPath(path, rootDocProvider())
            ?: return notFound("Invalid path: /$path")
        val node = runCatching { ws.resolve(rel) }.getOrNull()
        val file = when {
            node == null || !node.exists -> null
            node.isDirectory -> runCatching { ws.resolve("$rel/index.html") }.getOrNull()?.takeIf { it.exists }
            else -> node
        } ?: return notFound("Not found: /$rel")
        val stream = runCatching { file.openInputStream() }.getOrNull()
            ?: return notFound("Read failed: /$rel")
        return WebResourceResponse(mimeFor(rel), null, stream)
    }

    companion object {
        /** Hostname under which workspace pages are served. Pure marker, never resolves. */
        const val HOST = "harness.workspace"
        const val PATH_PREFIX = "/ws/"

        /**
         * Normalizes a request path to a workspace-relative file path: strips
         * the synthetic "ws/" prefix, applies the root-document fallback for
         * the site root, and rejects traversal. Null means reject. Pure for
         * tests.
         */
        fun sanitizeRelPath(path: String, rootDoc: String?): String? {
            var rel = path.trim().removePrefix("/")
            if (rel == "ws" || rel.startsWith("ws/")) {
                rel = rel.removePrefix("ws").removePrefix("/")
            }
            if (rel.isBlank()) {
                rel = rootDoc?.takeIf { it.isNotBlank() } ?: "index.html"
            }
            val segments = mutableListOf<String>()
            for (seg in rel.split('/')) {
                when (seg) {
                    "", "." -> {}
                    "..", "\\" -> return null
                    else -> segments.add(seg)
                }
            }
            if (segments.isEmpty()) return null
            return segments.joinToString("/")
        }

        fun mimeFor(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt", "md" -> "text/plain"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

        private fun notFound(message: String) = WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
        )
    }
}
