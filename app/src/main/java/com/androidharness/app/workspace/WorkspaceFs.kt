package com.androidharness.app.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.androidharness.app.tools.ToolFailure
import java.io.File

/**
 * Abstraction over the agent workspace so tools work identically on a real
 * directory (app-private storage) and on a user-picked SAF document tree.
 */
interface WorkspaceFs {
    /** Human-readable location shown in the UI and the system prompt. */
    val displayPath: String

    /** Real directory usable as a shell cwd, or null for SAF workspaces. */
    val shellRoot: File?

    val isSaf: Boolean

    /** Resolve [path] (relative to the root) into a node. Throws on escape. */
    fun resolve(path: String): FsNode

    fun walk(path: String): Sequence<FsNode>
}

interface FsNode {
    val relPath: String
    val name: String
    val exists: Boolean
    val isDirectory: Boolean
    val isFile: Boolean
    val length: Long

    fun list(): List<FsNode>
    fun readText(): String
    /** Writes content, creating the file and parent directories as needed. */
    fun writeText(content: String)

    /** Creates a directory at this node (including parents). */
    fun mkdirs()

    /** Deletes this node (recursively for directories). Returns success. */
    fun delete(): Boolean

    /** Renames/moves this node to [newName] within the same parent. */
    fun renameTo(newName: String): Boolean

    /** Opens an InputStream for reading raw bytes if this node is a file. */
    fun openInputStream(): java.io.InputStream?

    /** Checks whether the file is binary (non-UTF8 or contains NUL bytes). */
    fun isBinary(): Boolean

    /** Writes raw bytes to this file node, creating parent directories as needed. */
    fun writeBytes(data: ByteArray)

    /** Creates a new empty file [name] inside this (directory) node. */
    fun createFile(name: String): FsNode

    /** Creates a new directory [name] inside this (directory) node. */
    fun createDir(name: String): FsNode
}

// ---------------------------------------------------------------------------
// Real-filesystem implementation (app-private storage)
// ---------------------------------------------------------------------------

class FileFs(private val root: File) : WorkspaceFs {
    override val displayPath: String = root.absolutePath
    override val shellRoot: File = root
    override val isSaf = false

    init {
        root.mkdirs()
    }

    override fun resolve(path: String): FsNode {
        val rootPath = root.canonicalFile.toPath()
        val resolved = rootPath.resolve(path).normalize()
        if (!resolved.startsWith(rootPath)) {
            throw ToolFailure("Path is outside the workspace and was blocked: $path")
        }
        return FileFsNode(resolved.toFile(), rootPath)
    }

    override fun walk(path: String): Sequence<FsNode> {
        val node = resolve(path)
        if (!node.exists) throw ToolFailure("Path does not exist: $path")
        if (node.isFile) return sequenceOf(node)
        val rootPath = root.canonicalFile.toPath()
        return (node as FileFsNode).file.walkTopDown()
            .onEnter { dir -> !WorkspaceIgnore.shouldSkipEnter(path, dir.name) }
            .asSequence()
            .map { FileFsNode(it, rootPath) }
            .filter { n -> !n.isFile || !WorkspaceIgnore.shouldSkip(n.relPath, path) }
    }
}

class FileFsNode(val file: File, private val rootPath: java.nio.file.Path) : FsNode {
    override val relPath: String
        get() {
            // Full access mode resolves nodes outside this root; relativizing
            // then throws (other drive) or yields an ugly "../.." climb.
            // Render those as their absolute path instead.
            val rel = runCatching {
                rootPath.relativize(file.canonicalFile.toPath()).toString()
            }.getOrNull()
            return when {
                rel == null -> file.absolutePath
                rel.isBlank() -> "."
                rel.startsWith("..") -> file.absolutePath
                else -> rel
            }
        }
    override val name: String get() = file.name
    override val exists: Boolean get() = file.exists()
    override val isDirectory: Boolean get() = file.isDirectory
    override val isFile: Boolean get() = file.isFile
    override val length: Long get() = file.length()

    override fun list(): List<FsNode> =
        file.listFiles().orEmpty().map { FileFsNode(it, rootPath) }

    override fun readText(): String = file.readText()

    override fun writeText(content: String) {
        if (isDirectory) {
            throw ToolFailure("Cannot write file '$relPath': is a directory")
        }
        file.parentFile?.mkdirs()
        try {
            java.io.FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
        } catch (e: Exception) {
            if (isDirectory || e.message?.contains("EISDIR") == true) {
                throw ToolFailure("Cannot write file '$relPath': is a directory")
            }
            val cleanMsg = e.localizedMessage?.replace(file.absolutePath, relPath) ?: e.message
            throw ToolFailure("Cannot write file '$relPath': $cleanMsg")
        }
    }

    override fun mkdirs() {
        file.mkdirs()
    }

    override fun delete(): Boolean =
        if (file.isDirectory) file.deleteRecursively() else file.delete()

    override fun renameTo(newName: String): Boolean =
        file.renameTo(File(file.parentFile, newName))

    override fun writeBytes(data: ByteArray) {
        if (isDirectory) throw ToolFailure("Cannot write file '$relPath': is a directory")
        file.parentFile?.mkdirs()
        try {
            java.io.FileOutputStream(file).use { fos ->
                fos.write(data)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
        } catch (e: Exception) {
            val cleanMsg = e.localizedMessage?.replace(file.absolutePath, relPath) ?: e.message
            throw ToolFailure("Cannot write file '$relPath': $cleanMsg")
        }
    }

    override fun createFile(name: String): FsNode {
        if (!isDirectory) throw ToolFailure("Not a directory: $relPath")
        val target = java.io.File(file, name)
        try {
            if (!target.exists() && !target.createNewFile()) {
                throw ToolFailure("Could not create file '$name' in $relPath")
            }
        } catch (e: java.io.IOException) {
            throw ToolFailure("Could not create file '$name' in $relPath: ${e.message}")
        }
        return FileFsNode(target, rootPath)
    }

    override fun createDir(name: String): FsNode {
        if (!isDirectory) throw ToolFailure("Not a directory: $relPath")
        val created = java.io.File(file, name)
        if (!created.exists() && !created.mkdir()) {
            throw ToolFailure("Could not create folder '$name' in $relPath")
        }
        return FileFsNode(created, rootPath)
    }

    override fun openInputStream(): java.io.InputStream? =
        if (exists && isFile) file.inputStream() else null

    override fun isBinary(): Boolean {
        if (!exists || !isFile || length == 0L) return false
        return try {
            file.inputStream().use { stream -> isBinaryStream(stream) }
        } catch (_: Exception) {
            false
        }
    }
}

// ---------------------------------------------------------------------------
// Unbounded filesystem (Full access mode)
// ---------------------------------------------------------------------------

/**
 * Full access mode's [WorkspaceFs]: behaves like [FileFs] except the path
 * containment check is lifted, `../` escapes resolve to their real
 * locations anywhere the app uid (or Shizuku) can reach, and absolute paths
 * go straight through. Relative paths still anchor at the workspace root so
 * the model's everyday read_file("src/x.kt") calls land where they always
 * did; only the guard is gone. The shell root stays the original workspace.
 */
class UnboundedFileFs(root: File) : WorkspaceFs {
    private val delegate = FileFs(root)
    private val rootPath: java.nio.file.Path = root.canonicalFile.toPath()

    override val displayPath: String get() = delegate.displayPath
    override val shellRoot: File get() = delegate.shellRoot
    override val isSaf: Boolean get() = false

    private fun openResolve(path: String): File {
        if (path.startsWith('/')) return File(path).canonicalFile
        return rootPath.resolve(path).normalize().toFile().canonicalFile
    }

    override fun resolve(path: String): FsNode =
        FileFsNode(openResolve(path), rootPath)

    override fun walk(path: String): Sequence<FsNode> {
        val file = openResolve(path)
        if (!file.exists()) throw ToolFailure("Path does not exist: $path")
        if (file.isFile) return sequenceOf(FileFsNode(file, rootPath))
        return file.walkTopDown()
            .onEnter { dir -> !WorkspaceIgnore.shouldSkipEnter(path, dir.name) }
            .asSequence()
            .map { f -> FileFsNode(f, rootPath) }
            .filter { n -> !n.isFile || !WorkspaceIgnore.shouldSkip(n.relPath, path) }
    }
}

// ---------------------------------------------------------------------------
// SAF (user-picked folder) implementation
// ---------------------------------------------------------------------------

class SafFs(
    private val context: Context,
    treeUri: Uri,
) : WorkspaceFs {
    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override val displayPath: String =
        root?.name?.let { "$it (picked folder)" } ?: treeUri.lastPathSegment ?: "picked folder"
    override val shellRoot: File? = null
    override val isSaf = true

    private fun requireRoot(): DocumentFile =
        root?.takeIf { it.canWrite() }
            ?: throw ToolFailure("The picked workspace folder is no longer accessible. Choose it again in Settings.")

    override fun resolve(path: String): FsNode {
        val rootDoc = requireRoot()
        val segments = path.split('/', '\\')
            .filter { it.isNotBlank() && it != "." }
        if (segments.any { it == ".." }) {
            throw ToolFailure("Path is outside the workspace and was blocked: $path")
        }
        var current = rootDoc
        var consumed = 0
        for (segment in segments) {
            val next = current.findFile(segment)
            if (next == null) break
            current = next
            consumed++
        }
        return SafFsNode(context, current, segments.drop(consumed), segments)
    }

    override fun walk(path: String): Sequence<FsNode> {
        val node = resolve(path)
        if (!node.exists) throw ToolFailure("Path does not exist: $path")
        if (node.isFile) return sequenceOf(node)
        return sequence {
            val stack = ArrayDeque<FsNode>()
            stack.add(node)
            while (stack.isNotEmpty()) {
                val n = stack.removeFirst()
                if (n.isDirectory) {
                    n.list().forEach { child ->
                        if (child.isDirectory && WorkspaceIgnore.shouldSkipEnter(path, child.name)) return@forEach
                        stack.add(child)
                    }
                } else if (!WorkspaceIgnore.shouldSkip(n.relPath, path)) {
                    yield(n)
                }
            }
        }
    }
}

/**
 * A node in a SAF tree. [missingSegments] are the trailing path segments that
 * do not exist yet (for write_text creating nested paths).
 */
class SafFsNode(
    private val context: Context,
    private val doc: DocumentFile,
    private val missingSegments: List<String>,
    private val allSegments: List<String>,
) : FsNode {
    override val relPath: String =
        if (allSegments.isEmpty()) "." else allSegments.joinToString("/")
    override val name: String get() = doc.name ?: allSegments.lastOrNull() ?: "."
    override val exists: Boolean get() = missingSegments.isEmpty() && doc.exists()
    override val isDirectory: Boolean get() = exists && doc.isDirectory
    override val isFile: Boolean get() = exists && doc.isFile
    override val length: Long get() = doc.length()

    override fun list(): List<FsNode> {
        if (!isDirectory) return emptyList()
        return doc.listFiles().map { child ->
            SafFsNode(context, child, emptyList(), allSegments + (child.name ?: "?"))
        }
    }

    override fun readText(): String {
        if (!isFile) throw ToolFailure("Not a file: $relPath")
        return context.contentResolver.openInputStream(doc.uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw ToolFailure("Could not open $relPath")
    }

    override fun writeText(content: String) {
        writeBytes(content.toByteArray(Charsets.UTF_8))
    }

    /** Resolves (or creates) the DocumentFile this node should be written to. */
    private fun resolveWriteTarget(): DocumentFile {
        if (isDirectory) {
            throw ToolFailure("Cannot write file '$relPath': is a directory")
        }
        return when {
            // existing file resolved directly
            missingSegments.isEmpty() && doc.isFile -> doc
            // path resolved to a directory, nothing to write
            missingSegments.isEmpty() -> throw ToolFailure("Not a file: $relPath")
            else -> {
                var dir = doc
                for (segment in missingSegments.dropLast(1)) {
                    dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                        ?: dir.createDirectory(segment)
                        ?: throw ToolFailure("Could not create folder $segment")
                }
                val fileName = missingSegments.last()
                dir.findFile(fileName)?.takeIf { it.isFile }
                    ?: dir.createFile(mimeFor(fileName), fileName)
                    ?: throw ToolFailure("Could not create file $relPath")
            }
        }
    }

    override fun writeBytes(data: ByteArray) {
        val target = resolveWriteTarget()
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(data)
            out.flush()
            if (out is java.io.FileOutputStream) {
                runCatching { out.fd.sync() }
            }
        } ?: throw ToolFailure("Could not write $relPath")
    }

    override fun createFile(name: String): FsNode = createChild(name, isDir = false)

    override fun createDir(name: String): FsNode = createChild(name, isDir = true)

    private fun createChild(name: String, isDir: Boolean): FsNode {
        if (!isDirectory) throw ToolFailure("Not a directory: $relPath")
        val created = if (isDir) {
            doc.createDirectory(name) ?: run {
                doc.findFile(name)?.takeIf { it.isDirectory }
                    ?: throw ToolFailure("Could not create folder '$name' in $relPath")
            }
        } else {
            doc.createFile(mimeFor(name), name) ?: run {
                doc.findFile(name)?.takeIf { it.isFile }
                    ?: throw ToolFailure("Could not create file '$name' in $relPath")
            }
        }
        return SafFsNode(context, created, emptyList(), allSegments + name)
    }

    override fun mkdirs() {
        var dir = doc
        for (segment in missingSegments) {
            dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(segment)
                ?: throw ToolFailure("Could not create folder $segment")
        }
    }

    override fun delete(): Boolean = exists && doc.delete()

    override fun renameTo(newName: String): Boolean = exists && doc.renameTo(newName)

    override fun openInputStream(): java.io.InputStream? =
        if (exists && isFile) context.contentResolver.openInputStream(doc.uri) else null

    override fun isBinary(): Boolean {
        if (!exists || !isFile || length == 0L) return false
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                isBinaryStream(stream)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "")) {
        "kt", "java", "py", "js", "ts", "c", "cpp", "h", "md", "txt", "json", "xml", "yml", "yaml", "toml", "gradle", "kts" -> "text/plain"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        else -> "application/octet-stream"
    }
}

/**
 * Canonical key for change tracking: forward slashes, no "./" prefixes, no
 * duplicate or trailing slashes. Absolute paths (full-access mode) keep their
 * leading slash so they stay distinguishable from workspace-relative paths.
 */
fun normalizeRelPath(path: String): String {
    val p = path.replace('\\', '/')
    val absolute = p.startsWith("/")
    val joined = p.split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")
    return when {
        absolute -> "/$joined"
        joined.isEmpty() -> "."
        else -> joined
    }
}

/** Detects non-UTF-8, NUL bytes, or binary content in the first 1KB of a stream. */
fun isBinaryStream(stream: java.io.InputStream): Boolean {
    val buf = ByteArray(1024)
    val n = stream.read(buf)
    if (n <= 0) return false

    // 1. Check for NUL byte (0x00)
    for (i in 0 until n) {
        if (buf[i] == 0.toByte()) return true
    }

    // 2. Check control characters ratio (excluding \t, \n, \r)
    var controlCount = 0
    for (i in 0 until n) {
        val b = buf[i].toInt() and 0xFF
        if (b < 32 && b != 9 && b != 10 && b != 13) {
            controlCount++
        }
    }
    if (controlCount > n * 0.1) return true

    // 3. UTF-8 decoder check
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        var validLen = n
        while (validLen > 0 && (buf[validLen - 1].toInt() and 0xC0) == 0x80) {
            validLen--
        }
        if (validLen > 0 && (buf[validLen - 1].toInt() and 0x80) != 0) {
            validLen--
        }
        val checkLen = if (validLen > 0) validLen else n
        decoder.decode(java.nio.ByteBuffer.wrap(buf, 0, checkLen))
        false
    } catch (_: java.nio.charset.CharacterCodingException) {
        true
    }
}
