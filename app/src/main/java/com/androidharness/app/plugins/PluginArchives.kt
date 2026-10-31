package com.androidharness.app.plugins

import com.androidharness.app.workspace.FsNode
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import org.apache.commons.compress.archivers.zip.ZipFile

/** Archive members are data only. Links, special files and escaping or ambiguous paths are refused. */
object PluginArchives {
    const val MAX_DOWNLOAD = 25L * 1024 * 1024
    const val MAX_UNPACKED = 64L * 1024 * 1024
    const val MAX_FILE = 16L * 1024 * 1024
    const val MAX_FILES = 2048

    fun readLimited(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, limit)
        return output.toByteArray()
    }
    fun copyLimited(input: InputStream, output: OutputStream, limit: Long, progress: (Long) -> Unit = {}): Long {
        val bytes = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = input.read(bytes)
            if (count < 0) return size
            size += count
            require(size <= limit) { "Plugin file or download exceeds its size limit." }
            output.write(bytes, 0, count)
            progress(size)
        }
    }
    private class Budget(val root: File) {
        var total = 0L
        var count = 0
        val paths = mutableSetOf<String>()
        fun target(name: String, directory: Boolean): File {
            val cleaned = name.removeSuffix("/").removePrefix("./")
            require(cleaned.isNotBlank() && cleaned.split('/').size <= 24) { "Plugin archive path is invalid or too deep." }
            require(++count <= MAX_FILES) { "Plugin bundle has more than $MAX_FILES entries." }
            require(paths.add(cleaned.lowercase(java.util.Locale.ROOT))) { "Duplicate plugin archive path: $cleaned" }
            val file = PluginManifest.path(root, cleaned)
            if (directory) require(file.mkdirs() || file.isDirectory) { "Cannot create plugin directory." }
            else { require(!file.exists()) { "Conflicting plugin archive entry." }; require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) }
            return file
        }
        fun write(name: String, size: Long, input: InputStream) {
            require(size <= MAX_FILE) { "Plugin member exceeds 16 MiB: $name" }
            val file = target(name, false)
            file.outputStream().use { out ->
                val written = copyLimited(input, out, minOf(MAX_FILE, MAX_UNPACKED - total))
                total += written
            }
        }
    }
    fun extract(archive: File, destination: File) {
        require(archive.length() <= MAX_DOWNLOAD) { "Plugin archive exceeds 25 MiB." }
        require(destination.mkdirs() || destination.isDirectory)
        require(destination.listFiles().isNullOrEmpty()) { "Extraction directory must be empty." }
        val budget = Budget(destination)
        try {
            archive.inputStream().buffered().use { buffered ->
                buffered.mark(4)
                val magic = ByteArray(4); val n = buffered.read(magic); buffered.reset()
                when {
                    n >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() -> ZipFile.builder().setFile(archive).get().use { zip ->
                        val entries = zip.entries
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            require(!entry.isUnixSymlink) { "Plugin archives cannot contain symbolic links." }
                            require(entry.rawName.none { it == 92.toByte() || it == 0.toByte() }) { "Invalid plugin archive path." }
                            if (entry.isDirectory) budget.target(entry.name, true)
                            else zip.getInputStream(entry).use { input -> budget.write(entry.name, entry.size, input) }
                        }
                    }
                    n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                        TarArchiveInputStream(GzipCompressorInputStream(buffered)).use { tar ->
                            while (true) {
                                val entry = tar.nextTarEntry ?: break
                                require(!entry.isSymbolicLink && !entry.isLink && !entry.isSparse && !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO && (entry.isFile || entry.isDirectory)) {
                                    "Plugin archives cannot contain links or special files: ${entry.name}"
                                }
                                if (entry.isDirectory) budget.target(entry.name, true) else budget.write(entry.name, entry.size, tar)
                            }
                        }
                    else -> error("Choose a ZIP or .tar.gz/.tgz plugin archive.")
                }
            }
        } catch (e: Exception) { destination.deleteRecursively(); throw e }
    }
    fun copyFolder(source: FsNode, destination: File) {
        val budget = Budget(destination)
        require(source.isDirectory) { "Choose a plugin folder." }
        require(destination.mkdirs() || destination.isDirectory)
        fun copy(node: FsNode, prefix: String, depth: Int) {
            require(depth <= 24) { "Plugin folder is too deep." }
            node.list().sortedBy { it.name }.forEach { child ->
                if (child.name in setOf(".git", ".DS_Store")) return@forEach
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) { budget.target(relative, true); copy(child, relative, depth + 1) }
                else {
                    require(child.isFile) { "Plugin folders cannot contain special files." }
                    child.openInputStream()?.use { budget.write(relative, child.length, it) } ?: error("Cannot read $relative")
                }
            }
        }
        try { copy(source, "", 0) } catch (e: Exception) { destination.deleteRecursively(); throw e }
    }
}
