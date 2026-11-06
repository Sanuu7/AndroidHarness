package com.androidharness.app.data.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * One measurement. [alreadyCounted] files were claimed by an earlier measurement in the same inventory
 * (hard links, aliases, or a folder listed under two items), so their bytes live in that earlier item.
 */
internal data class StorageMeasure(
    val bytes: Long = 0, val files: Int = 0, val alreadyCounted: Int = 0,
    val denied: Int = 0, val errors: Int = 0, val truncated: Boolean = false,
    val exists: Boolean = true, val rootDenied: Boolean = false,
) {
    val partial get() = denied > 0 || errors > 0
    operator fun plus(o: StorageMeasure) = StorageMeasure(bytes + o.bytes, files + o.files, alreadyCounted + o.alreadyCounted,
        denied + o.denied, errors + o.errors, truncated || o.truncated, exists || o.exists, rootDenied && o.rootDenied)
}

/**
 * Logical file bytes, with links never followed. Every file identity (device and inode) is claimed by the first
 * measurement that sees it, so hard links and overlapping folders count once per inventory.
 */
internal class StorageCounter(
    private val deadline: Long = Long.MAX_VALUE,
    private val now: () -> Long = System::nanoTime,
    private val maxEntries: Int = 3_000_000,
) {
    private val identities = HashSet<Any>()
    var incomplete = false
        private set
    private var visited = 0
    val expired get() = now() > deadline || visited > maxEntries

    /** Legacy total used by cleanup tests; any unreadable or truncated part marks the counter incomplete. */
    suspend fun bytes(root: File, excluded: Set<Path> = emptySet()): Long = measure(root, excluded).bytes

    /** Walks [root] without following links. [excluded] paths (normalized, absolute) are skipped entirely. */
    suspend fun measure(root: File, excluded: Set<Path> = emptySet()): StorageMeasure {
        val start = root.toPath().toAbsolutePath().normalize()
        if (start in excluded) return StorageMeasure()
        val rootAttrs = try { Files.readAttributes(start, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: java.nio.file.NoSuchFileException) { return StorageMeasure(exists = false) }
            catch (_: java.nio.file.AccessDeniedException) { incomplete = true; return StorageMeasure(denied = 1, rootDenied = true) }
            catch (_: SecurityException) { incomplete = true; return StorageMeasure(denied = 1, rootDenied = true) }
            catch (_: java.io.IOException) { incomplete = true; return StorageMeasure(errors = 1) }
        if (rootAttrs.isSymbolicLink) return StorageMeasure()
        var bytes = 0L; var files = 0; var counted = 0; var denied = 0; var errors = 0; var truncated = false
        val stack = ArrayDeque<Pair<Path, Int>>()
        fun take(path: Path, a: BasicFileAttributes, depth: Int) {
            when {
                a.isSymbolicLink -> Unit
                a.isRegularFile -> if (identities.add(a.fileKey() ?: path)) { bytes += a.size(); files++ } else counted++
                a.isDirectory -> if (depth > 64) truncated = true else stack.addLast(path to depth)
            }
        }
        take(start, rootAttrs, 0)
        var first = true
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (expired) { truncated = true; break }
            val (dir, depth) = stack.removeLast()
            try {
                Files.newDirectoryStream(dir).use { entries ->
                    for (child in entries) {
                        if (++visited and 1023 == 0) currentCoroutineContext().ensureActive()
                        val normalized = child.toAbsolutePath().normalize()
                        if (normalized in excluded) continue
                        try { take(normalized, Files.readAttributes(normalized, BasicFileAttributes::class.java, NOFOLLOW_LINKS), depth + 1) }
                        catch (_: java.nio.file.NoSuchFileException) { }
                        catch (_: java.nio.file.AccessDeniedException) { denied++ }
                        catch (_: SecurityException) { denied++ }
                        catch (_: java.io.IOException) { errors++ }
                    }
                }
            } catch (_: java.nio.file.NoSuchFileException) { }
            catch (_: java.nio.file.AccessDeniedException) { denied++; if (first) { incomplete = true; return StorageMeasure(denied = 1, rootDenied = true) } }
            catch (_: SecurityException) { denied++ }
            catch (_: java.io.IOException) { errors++ }
            first = false
        }
        if (denied > 0 || errors > 0 || truncated) incomplete = true
        return StorageMeasure(bytes, files, counted, denied, errors, truncated)
    }

    /** Measures exact files (a package manifest or a pip RECORD). Directories in the list are skipped, not walked. */
    suspend fun measureFiles(paths: Collection<File>): StorageMeasure {
        var bytes = 0L; var files = 0; var counted = 0; var denied = 0; var errors = 0; var truncated = false; var seen = 0
        for (file in paths) {
            if (++seen and 255 == 0) currentCoroutineContext().ensureActive()
            if (expired) { truncated = true; break }
            visited++
            val path = file.toPath().toAbsolutePath().normalize()
            try {
                val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (a.isRegularFile) { if (identities.add(a.fileKey() ?: path)) { bytes += a.size(); files++ } else counted++ }
            } catch (_: java.nio.file.NoSuchFileException) { }
            catch (_: java.nio.file.AccessDeniedException) { denied++ }
            catch (_: SecurityException) { denied++ }
            catch (_: java.io.IOException) { errors++ }
        }
        if (denied > 0 || errors > 0 || truncated) incomplete = true
        return StorageMeasure(bytes, files, counted, denied, errors, truncated)
    }
}

@kotlinx.serialization.Serializable
data class StorageStamp(val path: String, val size: Long, val modified: Long,
    val identity: String?, val directory: Boolean, val sha256: String)

/** A preview is a capability for one exact tree, not a caller-supplied delete path. */
class StorageCandidate internal constructor(
    val id: String, val title: String, val detail: String, val group: String,
    internal val anchor: File, internal val target: File,
    internal val stamps: List<StorageStamp>,
    internal val packageName: String? = null,
) {
    val bytes: Long = stamps.filterNot { it.directory }.sumOf { it.size }
}

data class StorageCleanupResult(val removedBytes: Long, val removedFiles: Int, val skipped: Int)

internal object StorageFiles {
    private const val MAX_ENTRIES = 50_000
    private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    /** Refuse symlinks in or below the selected root; allow Android aliases above it. */
    fun contained(anchor: File, target: File): Boolean {
        val a = anchor.toPath().toAbsolutePath().normalize()
        val t = target.toPath().toAbsolutePath().normalize()
        if (t == a || !t.startsWith(a)) return false
        var p: Path? = t
        while (p != null && p.startsWith(a)) {
            if (Files.isSymbolicLink(p)) return false
            p = p.parent
        }
        // Android's /data/user/0 and /sdcard aliases above the chosen root are legitimate.
        return target.canonicalFile.toPath().startsWith(anchor.canonicalFile.toPath())
    }

    suspend fun snapshot(anchor: File, target: File, withHashes: Boolean = true): List<StorageStamp> {
        require(contained(anchor, target)) { "Storage path changed. Scan again." }
        val out = mutableListOf<StorageStamp>()
        suspend fun visit(path: Path, depth: Int = 0) {
            check(depth <= 64) { "Folder is too deep to review safely." }
            currentCoroutineContext().ensureActive()
            check(out.size < MAX_ENTRIES) { "Too many files to review safely." }
            val a = attrs(path)
            check(a.isDirectory || a.isRegularFile) { "Linked or special files are protected." }
            val hash = if (a.isRegularFile && withHashes) {
                val digest = MessageDigest.getInstance("SHA-256")
                // NOFOLLOW also rejects a last-component symlink swapped after the attribute read.
                Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                    val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = channel.read(buffer)
                        if (n < 0) break
                        digest.update(buffer.array(), 0, n); buffer.clear()
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else ""
            check(attrs(path).let { it.fileKey() == a.fileKey() && it.size() == a.size() && it.lastModifiedTime() == a.lastModifiedTime() }) {
                "Files changed during the scan. Try again."
            }
            out += StorageStamp(target.toPath().relativize(path).toString(), a.size().takeUnless { a.isDirectory } ?: 0,
                a.lastModifiedTime().toMillis(), a.fileKey()?.toString(), a.isDirectory, hash)
            if (a.isDirectory) Files.newDirectoryStream(path).use { entries ->
                for (child in entries.sortedBy { it.fileName.toString() }) visit(child, depth + 1)
            }
        }
        visit(target.toPath())
        return out
    }

    suspend fun candidate(id: String, title: String, detail: String, group: String, anchor: File, target: File,
        olderThan: Long): StorageCandidate? {
        if (!target.exists()) return null
        return try {
            val metadata = snapshot(anchor, target, withHashes = false)
            if (metadata.any { it.modified >= olderThan } || metadata.none { !it.directory && it.size > 0 }) null
            else {
                val stamps = snapshot(anchor, target)
                check(stamps.map { it.copy(sha256 = "") } == metadata) { "Files changed while preparing cleanup." }
                StorageCandidate(id, title, detail, group, anchor, target, stamps)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null } // Unknown ownership/access is never a cleanup candidate.
    }

    suspend fun clean(candidates: List<StorageCandidate>): StorageCleanupResult {
        var bytes = 0L; var files = 0; var skipped = 0
        val seen = mutableSetOf<String>()
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            if (!seen.add(c.target.path)) continue
            val fresh = try { snapshot(c.anchor, c.target) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            if (fresh != c.stamps) { skipped++; continue }
            // Delete ONLY the reviewed files. Never recursively delete a directory: new files stay intact.
            for (s in c.stamps.filterNot { it.directory }) {
                val file = if (s.path.isEmpty()) c.target else File(c.target, s.path)
                if (!contained(c.anchor, file)) { skipped++; break }
                try {
                    val a = attrs(file.toPath())
                    if (!a.isRegularFile || a.fileKey()?.toString() != s.identity || a.size() != s.size ||
                        a.lastModifiedTime().toMillis() != s.modified) { skipped++; continue }
                    Files.delete(file.toPath()); bytes += s.size; files++
                } catch (_: java.io.IOException) { skipped++ }
            }
            c.stamps.filter { it.directory }.asReversed().forEach { s ->
                val dir = if (s.path.isEmpty()) c.target else File(c.target, s.path)
                if (contained(c.anchor, dir)) runCatching { Files.delete(dir.toPath()) }
            }
        }
        return StorageCleanupResult(bytes, files, skipped)
    }
}
