package com.androidharness.app.workspace

import com.androidharness.app.tools.ToolFailure
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths

/** shellRoot deliberately stays null: remote paths must never reach java.io.File operations. */
class SshFs(val connections: SshConnections, val location: SshLocation) : WorkspaceFs {
    val root = location.root.trimEnd('/').ifEmpty { "/" }
    override val shellRoot: File? = null
    override val isSaf = false
    override val displayPath = "ssh://${location.connectionId}$root"
    fun commandPath(path: String = "."): String {
        val absolute = Paths.get(if (path.startsWith('/')) path else "$root/$path").normalize().toString()
        require(absolute == root || root == "/" || absolute.startsWith("$root/")) { "Path is outside this SSH workspace: $path" }
        return absolute
    }
    override fun resolve(path: String): FsNode = RemoteNode(commandPath(path))
    override fun walk(path: String): Sequence<FsNode> = sequence {
        suspend fun SequenceScope<FsNode>.visit(node: FsNode) {
            yield(node)
            if (node.isDirectory) for (child in node.list()) {
                if (!WorkspaceIgnore.shouldSkip(child.relPath, path)) visit(child)
            }
        }
        visit(resolve(path))
    }
    suspend fun run(command: String, cwd: String = root, timeoutMs: Int = 120_000, maxOutput: Int = 100_000) =
        connections.run(location.connectionId, command, cwd, timeoutMs, maxOutput)

    private fun missing(e: SftpException) = e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE
    private fun attrs(s: ChannelSftp, path: String): SftpATTRS? = try { s.lstat(path) }
        catch (e: SftpException) { if (missing(e)) null else throw e }
    /** Resolve existing ancestors on the server so symlinks cannot escape the selected tree. */
    private fun checked(s: ChannelSftp, path: String) {
        var parent = path
        while (attrs(s, parent) == null && parent != "/") parent = parent.substringBeforeLast('/').ifEmpty { "/" }
        val canonical = s.realpath(parent).trimEnd('/').ifEmpty { "/" }
        require(root == "/" || canonical == root || canonical.startsWith("$root/")) { "Remote symlink leaves the workspace: $path" }
    }
    private fun mkdir(s: ChannelSftp, path: String) {
        checked(s, path)
        val a = attrs(s, path)
        if (a != null) { require(a.isDir) { "Not a directory: $path" }; return }
        mkdir(s, path.substringBeforeLast('/').ifEmpty { "/" })
        s.mkdir(path)
    }
    inner class RemoteNode(private val path: String, private var snapshot: SftpATTRS? = null) : FsNode {
        override val relPath = path.removePrefix(root).trimStart('/')
        override val name = path.substringAfterLast('/').ifEmpty { "/" }
        private fun stat(): SftpATTRS? = snapshot ?: connections.sftp(location.connectionId) { s ->
            checked(s, path)
            attrs(s, path).also { snapshot = it }
        }
        override val exists get() = stat() != null
        override val isDirectory get() = stat()?.let { it.isDir && !it.isLink } == true
        override val isFile get() = stat()?.let { !it.isDir && !it.isLink } == true
        override val length get() = stat()?.size ?: 0L
        override fun list(): List<FsNode> = connections.sftp(location.connectionId) { s ->
            checked(s, path)
            s.ls(path).filterIsInstance<ChannelSftp.LsEntry>()
                .filter { it.filename != "." && it.filename != ".." }
                .map { RemoteNode(path.trimEnd('/') + "/" + it.filename, it.attrs) }
        }
        override fun openInputStream(): java.io.InputStream = connections.sftp(location.connectionId) { s ->
            checked(s, path)
            val size = s.stat(path).size
            require(size <= MAX_TRANSFER) { "Remote file is larger than 32 MiB; use the SSH terminal to transfer it" }
            val bytes = java.io.ByteArrayOutputStream()
            s.get(path).use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    require(bytes.size().toLong() + n <= MAX_TRANSFER) { "Remote file exceeded the transfer limit" }
                    bytes.write(buf, 0, n)
                }
            }
            ByteArrayInputStream(bytes.toByteArray())
        }
        override fun readText() = openInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        override fun writeText(content: String) = writeBytes(content.toByteArray(Charsets.UTF_8))
        override fun writeBytes(data: ByteArray) {
            connections.sftp(location.connectionId) { s ->
                checked(s, path)
                mkdir(s, path.substringBeforeLast('/').ifEmpty { "/" })
                val previous = attrs(s, path)
                require(previous?.isLink != true && previous?.isDir != true) { "Cannot overwrite a remote link or directory" }
                // Upload first, then replace via server rename; a broken upload never truncates the original.
                val temp = path + ".harness-upload-" + java.util.UUID.randomUUID()
                try {
                    s.put(ByteArrayInputStream(data), temp)
                    if (previous != null) s.chmod(previous.permissions and 511, temp)
                    s.rename(temp, path)
                    snapshot = null
                } catch (e: Exception) { runCatching { s.rm(temp) }; throw e }
            }
        }
        override fun mkdirs() { connections.sftp(location.connectionId) { mkdir(it, path) } }
        override fun delete(): Boolean = connections.sftp(location.connectionId) { s ->
            require(path != root) { "Cannot delete the workspace root" }
            checked(s, path)
            fun remove(p: String) {
                val a = attrs(s, p) ?: return
                if (a.isDir && !a.isLink) {
                    s.ls(p).filterIsInstance<ChannelSftp.LsEntry>().filter { it.filename != "." && it.filename != ".." }
                        .forEach { remove("$p/${it.filename}") }
                    s.rmdir(p)
                } else s.rm(p)
            }
            remove(path); true
        }
        override fun renameTo(newName: String): Boolean = connections.sftp(location.connectionId) { s ->
            require(newName.isNotBlank() && '/' !in newName && newName != "." && newName != "..")
            require(path != root) { "Cannot rename the workspace root" }
            checked(s, path)
            val target = path.substringBeforeLast('/') + "/" + newName
            require(attrs(s, target) == null) { "Destination already exists" }
            s.rename(path, target); true
        }
        override fun isBinary(): Boolean = openInputStream().use { input ->
            val bytes = ByteArray(8192)
            val n = input.read(bytes)
            n > 0 && (0 until n).any { bytes[it] == 0.toByte() }
        }
        override fun createFile(name: String): FsNode = child(name).also { require(!it.exists); it.writeBytes(byteArrayOf()) }
        override fun createDir(name: String): FsNode = child(name).also { it.mkdirs() }
        private fun child(name: String): FsNode {
            require(name.isNotBlank() && '/' !in name && name != "." && name != "..")
            return RemoteNode(path.trimEnd('/') + "/" + name)
        }
    }
    private companion object { const val MAX_TRANSFER = 32L * 1024 * 1024 }
}
