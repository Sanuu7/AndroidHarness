package com.androidharness.app.config

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.androidharness.app.workspace.*

/** A connected configuration folder needs only a persisted read grant, never a write grant. */
internal class ConfigDocumentFs(private val context: Context, uri: Uri) : WorkspaceFs {
    private val root = DocumentFile.fromTreeUri(context, uri)
    override val displayPath = uri.toString()
    override val shellRoot: java.io.File? = null
    override val isSaf = true
    override fun resolve(path: String): FsNode {
        require(!path.startsWith('/') && path.split('/').none { it == ".." }) { "Path leaves the connected folder." }
        check(root?.canRead() == true) { "Connected folder access was lost. Reconnect it in Configuration settings." }
        val parts = path.split('/').filter { it.isNotBlank() && it != "." }
        var current = root
        for (part in parts) current = current?.findFile(part)
        return Node(current, parts.joinToString("/"))
    }
    override fun walk(path: String): Sequence<FsNode> = sequence {
        suspend fun SequenceScope<FsNode>.visit(node: FsNode, depth: Int) {
            require(depth <= 24)
            yield(node)
            if (node.isDirectory) node.list().forEach { visit(it, depth + 1) }
        }
        visit(resolve(path), 0)
    }
    private inner class Node(private val file: DocumentFile?, override val relPath: String) : FsNode {
        override val name get() = file?.name ?: relPath.substringAfterLast('/')
        override val exists get() = file?.exists() == true
        override val isDirectory get() = file?.isDirectory == true
        override val isFile get() = file?.isFile == true
        override val length get() = file?.length() ?: 0L
        override fun list(): List<FsNode> = file?.listFiles().orEmpty().map { Node(it, listOf(relPath, it.name.orEmpty()).filter(String::isNotBlank).joinToString("/")) }
        override fun openInputStream() = file?.let { context.contentResolver.openInputStream(it.uri) }
        override fun readText(): String = openInputStream()?.bufferedReader()?.use { it.readText() } ?: error("Cannot read $relPath")
        override fun isBinary() = false
        private fun denied(): Nothing = error("Connected configuration folders are read only.")
        override fun writeText(content: String) = denied()
        override fun writeBytes(data: ByteArray) = denied()
        override fun mkdirs() = denied()
        override fun delete(): Boolean = denied()
        override fun renameTo(newName: String): Boolean = denied()
        override fun createFile(name: String): FsNode = denied()
        override fun createDir(name: String): FsNode = denied()
    }
}

/** Check canonical targets, including symlinks, before handing a path to a real filesystem. */
internal fun configFileAccess(fs: WorkspaceFs): WorkspaceFs = object : WorkspaceFs by fs {
    override fun resolve(path: String): FsNode {
        fs.shellRoot?.let { root ->
            require(root.resolve(path).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                "Configuration path leaves its granted folder: $path"
            }
        }
        return fs.resolve(path)
    }
}
