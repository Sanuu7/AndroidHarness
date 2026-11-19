package com.androidharness.app.data.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class ManagedTool(
    val name: String, val version: String, val depends: List<String>,
    internal val files: List<StorageStamp> = emptyList(), val complete: Boolean = false,
    val requirements: Set<String> = emptySet(),
    val ownedPaths: List<String> = files.map { it.path },
)

/** Why a Linux package stays installed. The screen turns each one into its own sentence. */
enum class PackageBlock { REQUIRED, UNTRACKED, UNVERIFIED, KEPT_FOR, DEPENDENTS, SHARED_FILES, CHANGED }

data class PackageProtection(val block: PackageBlock, val names: List<String> = emptyList()) {
    /** Plain text for the agent's tool results and logs. */
    val text: String get() = when (block) {
        PackageBlock.REQUIRED -> REQUIRED_TEXT
        PackageBlock.UNTRACKED -> "File ownership is unknown"
        PackageBlock.UNVERIFIED -> "File ownership could not be verified"
        PackageBlock.KEPT_FOR -> "Kept for ${names.joinToString()}"
        PackageBlock.DEPENDENTS -> "Required by ${names.joinToString()}"
        PackageBlock.SHARED_FILES -> "Shares files with another installed package"
        PackageBlock.CHANGED -> "Files changed since install"
    }
    companion object { const val REQUIRED_TEXT = "Required by the app toolchain" }
}

/** Private atomic metadata; a missing/corrupt record never authorizes removal. Caller holds installMutex. */
internal class SharedToolStore(private val root: File, private val prefix: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val recordsFile = File(root, "packages.json")
    private val journal = File(root, "removal.json")
    private val quarantine = File(root, "removing")
    private var records: Map<String, ManagedTool> = load()
    private fun load(): Map<String, ManagedTool> = runCatching {
        json.decodeFromString<List<ManagedTool>>(recordsFile.readText()).associateBy { it.name }
    }.getOrDefault(emptyMap())
    private fun write(file: File, text: String) {
        root.mkdirs()
        val temp = File(root, file.name + ".tmp")
        temp.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    @Synchronized fun all(): Map<String, ManagedTool> = records.toMap()
    @Synchronized fun put(record: ManagedTool) {
        val next = records + (record.name to record)
        write(recordsFile, json.encodeToString(next.values.toList())); records = next
    }
    @Synchronized fun require(names: List<String>, owner: String) {
        names.forEach { name -> records[name]?.let { put(it.copy(requirements = it.requirements + owner)) } }
    }
    @Synchronized fun release(name: String, owner: String) {
        records[name]?.let { put(it.copy(requirements = it.requirements - owner)) }
    }

    /**
     * Why [name] must stay installed, or null when it can be removed. A package installed before file tracking
     * no longer blocks every other removal: the agent can reinstall anything, and its own files stay untouched.
     */
    fun protection(name: String, installed: Set<String>, required: Set<String>): PackageProtection? {
        val all = all()
        if (name in required) return PackageProtection(PackageBlock.REQUIRED)
        val record = all[name] ?: return PackageProtection(PackageBlock.UNTRACKED)
        if (!record.complete) return PackageProtection(PackageBlock.UNVERIFIED)
        if (record.requirements.isNotEmpty()) return PackageProtection(PackageBlock.KEPT_FOR, record.requirements.sorted())
        val dependents = installed.filter { it != name && name in all[it]?.depends.orEmpty() }
        if (dependents.isNotEmpty()) return PackageProtection(PackageBlock.DEPENDENTS, dependents.sorted())
        val sharing = all.filterKeys { it in installed && it != name }.filterValues { other ->
            other.ownedPaths.any { path -> record.files.any { it.path == path } }
        }.keys
        if (sharing.isNotEmpty()) return PackageProtection(PackageBlock.SHARED_FILES, sharing.sorted())
        return null
    }

    /**
     * Installed packages whose recorded files are gone from the prefix. The record lists the regular files the
     * package actually wrote, so this catches any package, not just the few with a known binary to look for.
     * Documentation is skipped: a missing man page does not make a tool unusable. Untracked packages are
     * never reported here because there is nothing to compare against.
     */
    fun missingFiles(installed: Collection<String>): List<String> {
        val all = all()
        return installed.filter { name ->
            all[name]?.files.orEmpty().any { stamp ->
                !stamp.directory && !isDocumentation(stamp.path) && !File(prefix, stamp.path).exists()
            }
        }
    }

    /**
     * How many regular files the install record of [name] lists, docs included (a man-page package is all
     * docs), or null when the package has no record (installed before file tracking). Zero for a package
     * with real content means its payload never landed: a metapackage ships only a copyright link.
     */
    fun payloadFileCount(name: String): Int? =
        all()[name]?.files?.count { !it.directory }

    private fun isDocumentation(path: String): Boolean =
        DOC_DIRS.any { path.startsWith(it) }

    fun reason(name: String, installed: Set<String>, required: Set<String>): String? =
        protection(name, installed, required)?.text

    suspend fun preview(name: String, installed: Set<String>, required: Set<String>): StorageCandidate? {
        if (reason(name, installed, required) != null) return null
        val record = all()[name] ?: return null
        if (record.files.isEmpty() || !unchanged(record)) return null
        return StorageCandidate("package:$name", name, "No recorded requirements or installed dependents", "Managed packages",
            prefix.parentFile!!, prefix, record.files, packageName = name)
    }

    private suspend fun unchanged(record: ManagedTool): Boolean = record.files.all { stamp ->
        val file = File(prefix, stamp.path)
        if (!StorageFiles.contained(prefix, file)) return@all false
        val actual = try { StorageFiles.snapshot(prefix, file).single().copy(path = stamp.path) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        actual == stamp
    }

    /** Quarantine first; a process death rolls back on next launch unless the installed marker committed. */
    suspend fun remove(candidate: StorageCandidate, installed: Set<String>, required: Set<String>, commit: () -> Unit): Long {
        val name = requireNotNull(candidate.packageName)
        check(reason(name, installed, required) == null) { "Package requirements changed. Scan again." }
        val record = requireNotNull(all()[name])
        check(record.files == candidate.stamps && unchanged(record)) { "Package files changed. Scan again." }
        check(!journal.exists()) { "A prior package removal needs recovery. Restart the app." }
        write(journal, json.encodeToString(record))
        try {
            for (stamp in record.files) {
                val source = File(prefix, stamp.path)
                check(StorageFiles.contained(prefix, source)) { "Package path changed." }
                check(StorageFiles.snapshot(prefix, source).single().copy(path = stamp.path) == stamp) { "Package files changed." }
                val destination = File(quarantine, stamp.path)
                destination.parentFile!!.mkdirs()
                Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
            }
            commit()
        } catch (e: Exception) {
            recover(installed); throw e
        }
        // Once committed, failure to delete quarantine is retained for recovery on the next launch.
        recover(installed - name)
        return candidate.bytes
    }

    fun recover(installed: Set<String>) {
        if (!journal.exists()) return
        val record = json.decodeFromString<ManagedTool>(journal.readText())
        for (stamp in record.files) {
            check(stamp.path.isNotEmpty() && !File(stamp.path).isAbsolute && stamp.path.split('/', '\\').none { it == ".." || it == "." }) { "Invalid package recovery path." }
            val saved = File(quarantine, stamp.path)
            check(StorageFiles.contained(root, saved)) { "Package recovery storage changed." }
            if (!saved.exists()) continue
            if (record.name in installed) {
                val destination = File(prefix, stamp.path)
                check(StorageFiles.contained(prefix, destination) && !destination.exists()) { "Package recovery path is occupied." }
                destination.parentFile!!.mkdirs()
                Files.move(saved.toPath(), destination.toPath(), ATOMIC_MOVE)
            } else check(saved.delete()) { "Could not finish package removal." }
        }
        check(quarantine.deleteRecursively() || !quarantine.exists())
        check(journal.delete())
    }

    private companion object {
        val DOC_DIRS = listOf("share/doc/", "share/man/", "share/info/", "share/locale/", "share/LICENSES/")
    }
}
