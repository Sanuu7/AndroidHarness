package com.androidharness.app.plugins

import com.androidharness.app.workspace.FsNode
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * Archive members are data only. Escaping, ambiguous, oversized and special entries are skipped instead of
 * failing the whole bundle; links are kept only when they point at another member of the same bundle.
 */
object PluginArchives {
    const val MAX_DOWNLOAD = 200L * 1024 * 1024
    const val MAX_UNPACKED = 512L * 1024 * 1024
    const val MAX_FILE = 64L * 1024 * 1024
    const val MAX_FILES = 20_000
    private const val MAX_ENTRIES = 200_000
    private const val MAX_DEPTH = 32
    /** Folders that never carry plugin content and often hold most of a repository's files. */
    val SKIPPED = setOf(".git", "node_modules", "__MACOSX", ".DS_Store", ".hg", ".svn", "__pycache__", ".venv", ".pytest_cache", ".gradle", ".idea")

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
        var entries = 0
        var kept = 0
        val skipped = mutableListOf<String>()
        private val links = mutableListOf<Triple<String, String, Boolean>>()
        private val paths = mutableSetOf<String>()
        fun skip(name: String, reason: String): File? { if (skipped.size < 50) skipped += "$name ($reason)"; return null }
        fun clean(name: String): String? {
            val cleaned = name.replace('\\', '/').removeSuffix("/").removePrefix("./")
            if (cleaned.isBlank() || '\u0000' in cleaned) return null
            return cleaned
        }
        fun target(name: String, directory: Boolean): File? {
            require(++entries <= MAX_ENTRIES) { "Plugin bundle has more than $MAX_ENTRIES entries." }
            val cleaned = clean(name) ?: return skip(name, "invalid path")
            val segments = cleaned.split('/')
            if (segments.any { it in SKIPPED }) return null
            if (segments.size > MAX_DEPTH) return skip(cleaned, "too deep")
            val file = runCatching { PluginManifest.path(root, cleaned) }.getOrNull() ?: return skip(cleaned, "leaves the bundle")
            if (!paths.add(cleaned.lowercase(java.util.Locale.ROOT))) return if (directory) file.takeIf { it.isDirectory } else skip(cleaned, "duplicate")
            if (directory) { if (!file.mkdirs() && !file.isDirectory) return skip(cleaned, "conflicts with a file"); return file }
            if (file.exists() || !(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)) return skip(cleaned, "conflicting entry")
            require(++kept <= MAX_FILES) { "Plugin bundle has more than $MAX_FILES files." }
            return file
        }
        fun write(name: String, size: Long, input: InputStream) {
            if (size > MAX_FILE) { skip(name, "larger than 64 MiB"); return }
            val file = target(name, false) ?: return
            val limit = minOf(MAX_FILE, MAX_UNPACKED - total)
            try {
                file.outputStream().use { out -> total += copyLimited(input, out, limit) }
            } catch (e: IllegalArgumentException) {
                file.delete()
                require(limit == MAX_FILE) { "Plugin bundle expands beyond 512 MiB." }
                skip(name, "larger than 64 MiB")
            }
        }
        /** [fromRoot] targets (tar hard links) are relative to the archive root rather than to the link. */
        fun link(name: String, target: String, fromRoot: Boolean = false) { clean(name)?.let { links += Triple(it, target, fromRoot) } }
        /** Copies in-bundle link targets once every regular member exists. Anything else is dropped. */
        fun resolveLinks() {
            links.forEach { (name, target, fromRoot) ->
                val parent = if (fromRoot) "" else name.substringBeforeLast('/', "")
                val joined = if (target.startsWith('/')) "" else listOf(parent, target).filter(String::isNotEmpty).joinToString("/")
                val normalized = ArrayDeque<String>()
                var escaped = joined.isEmpty()
                joined.split('/').forEach { part ->
                    when (part) { "", "." -> Unit; ".." -> if (normalized.isEmpty()) escaped = true else normalized.removeLast(); else -> normalized.addLast(part) }
                }
                if (escaped || normalized.isEmpty()) { skip(name, "link leaves the bundle"); return@forEach }
                val source = runCatching { PluginManifest.path(root, normalized.joinToString("/")) }.getOrNull()
                if (source == null || !source.exists()) { skip(name, "link target missing"); return@forEach }
                if (source.isDirectory) source.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = name + "/" + file.relativeTo(source).invariantSeparatorsPath
                    file.inputStream().use { write(relative, file.length(), it) }
                } else source.inputStream().use { write(name, source.length(), it) }
            }
        }
    }
    fun extract(archive: File, destination: File): List<String> {
        require(archive.length() <= MAX_DOWNLOAD) { "Plugin archive exceeds 200 MiB." }
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
                            if (entry.rawName.any { it == 0.toByte() }) { budget.target("\u0000", false); continue }
                            when {
                                entry.isUnixSymlink -> budget.link(entry.name, zip.getInputStream(entry).use { String(readLimited(it, 4096)) })
                                entry.isDirectory -> budget.target(entry.name, true)
                                else -> zip.getInputStream(entry).use { input -> budget.write(entry.name, entry.size, input) }
                            }
                        }
                    }
                    n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                        TarArchiveInputStream(GzipCompressorInputStream(buffered)).use { tar ->
                            while (true) {
                                val entry = tar.nextTarEntry ?: break
                                when {
                                    entry.isSymbolicLink -> budget.link(entry.name, entry.linkName)
                                    entry.isLink -> budget.link(entry.name, entry.linkName, fromRoot = true)
                                    entry.isDirectory -> budget.target(entry.name, true)
                                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> budget.skip(entry.name, "special file")
                                    entry.isFile && !entry.isSparse -> budget.write(entry.name, entry.size, tar)
                                    else -> budget.skip(entry.name, "special file") // Devices, FIFOs and sparse members carry no plugin content.
                                }
                            }
                        }
                    else -> error("Choose a ZIP or .tar.gz/.tgz plugin archive.")
                }
            }
            budget.resolveLinks()
        } catch (e: Exception) { destination.deleteRecursively(); throw e }
        return budget.skipped
    }
    fun copyFolder(source: FsNode, destination: File): List<String> {
        val budget = Budget(destination)
        require(source.isDirectory) { "Choose a plugin folder." }
        require(destination.mkdirs() || destination.isDirectory)
        fun copy(node: FsNode, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            node.list().sortedBy { it.name }.forEach { child ->
                if (child.name in SKIPPED) return@forEach
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) { budget.target(relative, true); copy(child, relative, depth + 1) }
                else if (child.isFile) child.openInputStream()?.use { budget.write(relative, child.length, it) }
            }
        }
        try { copy(source, "", 0) } catch (e: Exception) { destination.deleteRecursively(); throw e }
        return budget.skipped
    }
    /** Moves a finished local folder (for example an installer's output) into a bundle with the same rules. */
    fun copyLocal(source: File, destination: File): List<String> {
        val budget = Budget(destination)
        require(destination.mkdirs() || destination.isDirectory)
        fun copy(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                if (child.name in SKIPPED || java.nio.file.Files.isSymbolicLink(child.toPath())) return@forEach
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) { budget.target(relative, true); copy(child, relative, depth + 1) }
                else if (child.isFile) child.inputStream().use { budget.write(relative, child.length(), it) }
            }
        }
        try { copy(source, "", 0) } catch (e: Exception) { destination.deleteRecursively(); throw e }
        return budget.skipped
    }
}
