package com.androidharness.app.data

import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.WorkspaceFs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FileVersion(
    val id: String, val sessionId: String, val workspace: String, val path: String,
    val sha256: String, val bytes: Int, val createdAt: Long, val reason: String,
    val deleted: Boolean = false,
)

/** Durable, private copies survive rewinds, chat truncation and process recreation. */
class WorkspaceFileHistory(private val directory: File) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun location(fs: WorkspaceFs, path: String): Pair<String, String> {
        fs.resolve(path) // Enforce the current workspace grant before inspecting history.
        return if (fs is ChatWorkspaceFs) {
            val (binding, relative) = fs.target(path)
            binding.fs.displayPath to com.androidharness.app.workspace.normalizeRelPath(relative)
        } else fs.displayPath to com.androidharness.app.workspace.normalizeRelPath(path)
    }

    private fun entries(): List<FileVersion> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.map { json.decodeFromString<FileVersion>(it.readText()) }

    suspend fun missingFiles(sessionId: String, fs: WorkspaceFs): List<String> = withContext(Dispatchers.IO) {
        lock.withLock {
            entries().filter { it.sessionId == sessionId }
                .groupBy { it.workspace to it.path }.values.map { versions -> versions.maxBy { it.createdAt } }
                .filterNot { it.deleted }.mapNotNull { version ->
                    val path = if (fs is ChatWorkspaceFs) {
                        val binding = fs.bindings.firstOrNull { it.fs.displayPath == version.workspace } ?: return@mapNotNull null
                        fs.key(binding.id, version.path)
                    } else {
                        if (fs.displayPath != version.workspace) return@mapNotNull null
                        version.path
                    }
                    // Full access saves absolute paths outside the root, which the
                    // bounded workspace refuses to resolve. That is not a lost file,
                    // and one such entry must not block every later send (#121).
                    val node = runCatching { fs.resolve(path) }.getOrNull() ?: return@mapNotNull null
                    path.takeIf { !node.exists }
                }
        }
    }

    suspend fun versions(sessionId: String, fs: WorkspaceFs, path: String): List<FileVersion> = withContext(Dispatchers.IO) {
        lock.withLock {
            val (workspace, relative) = location(fs, path)
            entries().filter { it.sessionId == sessionId && it.workspace == workspace && it.path == relative }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun record(sessionId: String, fs: WorkspaceFs, path: String, reason: String, recordMissing: Boolean = false): FileVersion? = withContext(Dispatchers.IO) {
        lock.withLock { recordLocked(sessionId, fs, path, reason, recordMissing) }
    }

    private fun recordLocked(sessionId: String, fs: WorkspaceFs, path: String, reason: String, recordMissing: Boolean = false): FileVersion? {
        val (workspace, relative) = location(fs, path)
        val node = fs.resolve(path)
        val previous = entries().filter { it.sessionId == sessionId && it.workspace == workspace && it.path == relative }
            .maxByOrNull { it.createdAt }
        val deleted = !node.exists
        if (deleted && (!recordMissing || previous == null)) return null
        if (!deleted && !node.isFile) return null
        check(deleted || node.length <= MAX_BYTES) { "File is too large to back up safely: $path" }
        val data = if (deleted) byteArrayOf() else requireNotNull(node.openInputStream()) { "Could not back up $path" }
            .use { readLimited(it) }
        val digest = if (deleted) "missing" else hash(data)
        if (previous?.sha256 == digest) return previous
        check(directory.isDirectory || directory.mkdirs()) { "Could not create file history" }
        val version = FileVersion(UUID.randomUUID().toString(), sessionId, workspace, relative, digest,
            data.size, maxOf(System.currentTimeMillis(), (previous?.createdAt ?: 0) + 1), reason, deleted)
        val content = File(directory, "${version.id}.gz")
        FileOutputStream(content).use { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(data)
                gzip.finish()
                output.fd.sync()
            }
        }
        val temporary = File(directory, "${version.id}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(version).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(File(directory, "${version.id}.json"))) { "Could not save file history" }
        return version
    }

    /** Missing-only by default. Overwrites require a hash from inspecting the current file. */
    suspend fun restore(sessionId: String, fs: WorkspaceFs, path: String, versionId: String,
                        expectedHash: String = "missing"): FileVersion = withContext(Dispatchers.IO) {
        lock.withLock {
            require(versionId.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid version id" }
            val (workspace, relative) = location(fs, path)
            val record = File(directory, "$versionId.json")
            check(record.isFile) { "Unknown version '$versionId'. Call file_history for this path to list the saved versions." }
            val version = json.decodeFromString<FileVersion>(record.readText())
            check(version.sessionId == sessionId && version.workspace == workspace && version.path == relative) {
                "This version belongs to a different chat, workspace or path"
            }
            check(!version.deleted) { "This version records a deletion. Choose an earlier saved version." }
            check(version.bytes in 0..MAX_BYTES) { "Invalid saved file size" }
            val node = fs.resolve(path)
            val current = if (node.exists) {
                check(node.isFile && node.length <= MAX_BYTES) { "Current target cannot be safely replaced" }
                requireNotNull(node.openInputStream()).use { hash(readLimited(it)) }
            } else "missing"
            check(current.equals(expectedHash.trim(), ignoreCase = true)) {
                when {
                    expectedHash.trim() == "missing" ->
                        "$path already exists, so restoring would overwrite it. Inspect it, then pass its current SHA-256 as expected_sha256."
                    current == "missing" ->
                        "$path no longer exists, so expected_sha256 does not match. Omit expected_sha256 to restore the missing file."
                    else -> "expected_sha256 does not match $path: you passed ${expectedHash.trim().take(12)}…, the file now hashes to ${current.take(12)}…. " +
                        "It changed since you inspected it, or the hash was copied wrong. Inspect it again before overwriting it."
                }
            }
            val data = GZIPInputStream(File(directory, "$versionId.gz").inputStream()).use { readLimited(it, version.bytes) }
            check(data.size == version.bytes && hash(data) == version.sha256) { "Saved version failed its integrity check" }
            recordLocked(sessionId, fs, path, "before restore")
            node.writeBytes(data)
            recordLocked(sessionId, fs, path, "restored")
            version
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            check(output.size() + n <= limit) { "File exceeds the recovery size limit" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_BYTES = 32 * 1024 * 1024
        fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
    }
}
