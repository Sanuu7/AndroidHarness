package com.androidharness.app.repomap

import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class RepoMapCache {

    private data class CachedFile(
        val length: Long,
        val symbols: List<SymbolInfo>,
    )

    private val fileCache = ConcurrentHashMap<String, CachedFile>()

    fun invalidate(relPath: String) {
        fileCache.remove(relPath)
    }

    fun clear() {
        fileCache.clear()
    }

    suspend fun getMap(workspace: WorkspaceFs, maxChars: Int = 10_000): String =
        withContext(Dispatchers.IO) {
            try {
                val nodes = workspace.walk(".")
                    .filter { it.isFile && isIndexable(it.name) }
                    .take(200)
                    .toList()

                val entries = mutableListOf<FileEntry>()
                for (node in nodes) {
                    val path = node.relPath
                    val len = node.length
                    val cached = fileCache[path]

                    val symbols = if (cached != null && cached.length == len) {
                        cached.symbols
                    } else {
                        val content = runCatching { node.readText() }.getOrDefault("")
                        val extracted = RepoSymbolExtractor.extract(path, content)
                        fileCache[path] = CachedFile(len, extracted)
                        extracted
                    }
                    entries += FileEntry(path, symbols)
                }

                RepoMapGenerator.generate(entries, maxChars)
            } catch (_: Exception) {
                ""
            }
        }

    private fun isIndexable(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in INDEXABLE_EXTENSIONS
    }

    companion object {
        private val INDEXABLE_EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "htm",
            "xml", "svg", "css", "scss", "json", "sh", "bash", "zsh", "gradle",
            "yaml", "yml", "toml",
        )
    }
}
