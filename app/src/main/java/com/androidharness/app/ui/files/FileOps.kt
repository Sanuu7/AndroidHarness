package com.androidharness.app.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.workspace.FsNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-manager operations layered on [FsNode] primitives so they behave the
 * same on real directories and SAF trees. Copy conflicts never overwrite —
 * they auto-suffix "(2)"-style; moves are copy+delete across roots.
 */
object FileOps {

    /** Path segments never copied in/out regardless of source. */
    private fun skip(name: String): Boolean = name == ".git" || name == "node_modules"

    /**
     * Copies [src] into [destDir] as [name]. Returns the created node.
     * Directories are cloned recursively (ignoring [.git]/node_modules).
     */
    suspend fun copy(src: FsNode, destDir: FsNode, name: String): FsNode =
        withContext(Dispatchers.IO) {
            if (!destDir.isDirectory) throw ToolFailure("Target is not a folder")
            val targetName = uniqueChildName(destDir, name)
            val target = if (src.isDirectory) destDir.createDir(targetName) else destDir.createFile(targetName)
            transferInto(src, target)
            target
        }

    /**
     * Moves [src] into [destDir]. Same-parent rename short-circuits;
     * otherwise a full copy runs and the source is removed on success.
     */
    suspend fun move(src: FsNode, destDir: FsNode, name: String): FsNode =
        withContext(Dispatchers.IO) {
            require(destDir.isDirectory) { "Target is not a folder" }
            val srcParent = src.relPath.substringBeforeLast('/', missingDelimiterValue = "")
            val dstParent = destDir.relPath.trim('.').trim('/')
            if (srcParent == dstParent) {
                // Plain rename within the same directory — no copy involved,
                // and ignore-lists must never apply to a user-requested move.
                val finalName =
                    if (name != src.name && destDir.list().any { it.name == name }) {
                        uniqueChildName(destDir, name)
                    } else {
                        name
                    }
                if (!src.renameTo(finalName)) throw ToolFailure("Rename failed for ${src.name}")
                return@withContext src.resolveSiblingIn(destDir, finalName)
            }
            val moved = copy(src, destDir, name)
            if (!src.delete()) {
                // Partially moved directories can't be rolled back safely — report.
                throw ToolFailure("Copied to destination but could not delete the original")
            }
            moved
        }

    /** Reads [node]'s bytes and stages them under cache/shared for sharing. */
    suspend fun stageForShare(context: Context, node: FsNode): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Prune stale stages from earlier shares (older than a day).
        val cutoff = System.currentTimeMillis() - 86_400_000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val staged = File(dir, uniqueCacheName(dir, node.name))
        runCatching {
            node.openInputStream()?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ToolFailure("Could not read ${node.name}")
        }.onFailure {
            staged.delete()
            throw ToolFailure("Could not read ${node.name}: ${it.message}")
        }
        FileProvider.getUriForFile(context, "${context.packageName}.update", staged)
    }

    /** Plain filesystem path behind a node when it is not SAF-backed. */
    fun realFileOrNull(node: FsNode): File? = (node as? com.androidharness.app.workspace.FileFsNode)?.file

    /** Stages [node] into the share cache and fires a system share sheet. */
    suspend fun share(context: Context, node: FsNode) {
        val uri = stageForShare(context, node)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeForName(node.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, node.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Share ${node.name}"))
    }

    /** Stages [node] and lets another app open/view it. */
    suspend fun openWith(context: Context, node: FsNode) {
        val uri = stageForShare(context, node)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(uri, mimeForName(node.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(view) }.onFailure {
            throw ToolFailure("No app can open ${node.name}")
        }
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "gradle",
        "kts", "toml", "yaml", "yml", "json", "xml", "sh", "bash", "md", "txt", "log", "ini",
        -> "text/plain"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    // -- internals ----------------------------------------------------------

    private fun transferInto(src: FsNode, target: FsNode, depth: Int = 0) {
        if (src.isFile) {
            val bytes = src.openInputStream()?.use { it.readBytes() }
                ?: throw ToolFailure("Could not read ${src.name}")
            target.writeBytes(bytes)
        } else {
            for (child in src.list()) {
                // Ignore-lists apply below the operation's own root so an
                // explicit .git/node_modules selection still moves whole.
                if (depth > 0 && skip(child.name)) continue
                val childTarget = if (child.isDirectory) {
                    target.createDir(child.name)
                } else {
                    target.createFile(child.name)
                }
                transferInto(child, childTarget, depth + 1)
            }
        }
    }

    /** Appends " (n)" before the extension until free in [dir]. */
    fun uniqueChildName(dir: FsNode, requested: String): String {
        val taken = dir.list().mapTo(HashSet()) { it.name }
        return uniqueWithin(requested) { candidate -> candidate !in taken }
    }

    private fun uniqueCacheName(dir: File, requested: String): String =
        uniqueWithin(requested) { c -> !File(dir, c).exists() }

    private inline fun uniqueWithin(requested: String, isFree: (String) -> Boolean): String {
        if (isFree(requested)) return requested
        val base = requested.substringBeforeLast('.', missingDelimiterValue = requested)
        val ext = requested.substringAfterLast('.', "")
            .takeIf { requested.contains('.') }?.let { ".$it" } ?: ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (isFree(candidate)) return candidate
            n++
        }
    }

    /**
     * After an in-place rename we need the renamed node; resolve through the
     * parent listing rather than guessing backend behavior.
     */
    private fun FsNode.resolveSiblingIn(parentDir: FsNode, newName: String): FsNode =
        parentDir.list().firstOrNull { it.name == newName } ?: this
}
