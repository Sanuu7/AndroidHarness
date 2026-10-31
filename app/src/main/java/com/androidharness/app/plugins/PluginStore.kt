package com.androidharness.app.plugins

import com.androidharness.app.config.ConfigSource
import com.androidharness.app.config.HarnessConfig
import com.androidharness.app.workspace.FileFs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** An app-owned registry. Shared harness.json files are never rewritten by package operations. */
class PluginStore(private val root: File, private val onChanged: () -> Unit = {}) {
    private val lock = Any()
    private val index = File(root, "installed.json")
    private val packages = File(root, "packages").apply { mkdirs() }
    private val verified = mutableMapOf<String, String>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    init {
        // Called before runs resume at process startup. Old snapshots keep immutable files until then.
        runCatching { synchronized(lock) {
            val active = read().map { it.directory }.toSet()
            packages.listFiles().orEmpty().filter { it.name !in active }.forEach { folder ->
                Files.walk(folder.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        } } // A damaged registry must not crash app startup; list/load report it for repair.
    }
    private fun read(): List<InstalledPlugin> {
        if (!index.exists()) return emptyList()
        require(index.length() <= 1024 * 1024) { "Plugin registry exceeds 1 MiB." }
        return json.decodeFromString(ListSerializer(InstalledPlugin.serializer()), index.readText()).also { records ->
            require(records.size <= 128 && records.map { it.id }.distinct().size == records.size) { "Invalid plugin registry." }
            records.forEach { require(Regex("[a-f0-9-]{36}").matches(it.directory) && Regex("[a-f0-9-]{36}").matches(it.id)) { "Invalid plugin installation identity." } }
        }
    }
    private fun write(records: List<InstalledPlugin>) {
        require(records.size <= 128) { "At most 128 plugin installations are supported." }
        root.mkdirs()
        val temporary = File(root, ".installed-${UUID.randomUUID()}.tmp")
        try {
            java.io.FileOutputStream(temporary).use { out -> out.write(json.encodeToString(ListSerializer(InstalledPlugin.serializer()), records).toByteArray()); out.fd.sync() }
            Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        onChanged()
    }
    fun registryBroken(): Boolean = synchronized(lock) { runCatching { read() }.isFailure }
    fun resetBrokenRegistry() = synchronized(lock) {
        check(runCatching { read() }.isFailure) { "The plugin registry is readable; reset was not needed." }
        if (index.length() <= 1024 * 1024) Files.copy(index.toPath(), File(root, "installed-damaged-${UUID.randomUUID()}.json").toPath())
        write(emptyList())
    }
    private fun checkedDirectory(record: InstalledPlugin): File {
        val folder = directory(record)
        require(folder.isDirectory) { "Plugin '${record.name}' is missing. Reinstall it in Plugins." }
        val files = folder.walkTopDown().onEnter { require(!Files.isSymbolicLink(it.toPath())) { "Plugin contains a symbolic link." }; true }.take(PluginArchives.MAX_FILES + 2).toList()
        require(files.size <= PluginArchives.MAX_FILES + 1) { "Plugin contains too many files." }
        val signature = HarnessConfig.digest(files.sortedBy { it.path }.joinToString("\n") { "${it.relativeTo(folder).invariantSeparatorsPath}:${it.length()}:${it.lastModified()}" })
        if (verified[record.directory] != signature) {
            require(PluginManifest.digest(folder) == record.digest) { "Plugin '${record.name}' changed outside the manager. Disable or reinstall it in Plugins." }
            verified[record.directory] = signature
        }
        return folder
    }
    fun list(): List<InstalledPlugin> = synchronized(lock) { read().sortedWith(compareBy({ it.name }, { it.scopeLabel })) }
    fun directory(record: InstalledPlugin): File = synchronized(lock) {
        require(Regex("[a-f0-9-]{36}").matches(record.directory))
        packages.resolve(record.directory).canonicalFile.also { require(it.parentFile == packages.canonicalFile) }
    }
    fun contents(record: InstalledPlugin): PluginContents = synchronized(lock) {
        val current = read().firstOrNull { it.id == record.id } ?: error("Plugin was removed. Reload the list.")
        require(current.digest == record.digest) { "Plugin changed. Reload the list." }
        PluginManifest.inspect(checkedDirectory(current))
    }
    fun install(prepared: PreparedPlugin, scope: String, scopeLabel: String): InstalledPlugin = synchronized(lock) {
        val records = read()
        val old = prepared.replacing?.let { id -> records.firstOrNull { it.id == id } ?: error("Plugin was removed during update.") }
        if (old != null) {
            require(old.digest == prepared.previousDigest && old.scope == scope) { "Plugin changed while its update was being reviewed. Reload it." }
            require(old.name == prepared.contents.name) { "An update cannot change the plugin identity." }
        }
        require(records.none { it.scope == scope && it.name == prepared.contents.name && it.id != old?.id }) { "This plugin is already installed in this scope. Use Update." }
        require(PluginManifest.digest(prepared.root) == prepared.digest) { "Plugin files changed after the preview. Inspect the bundle again." }
        val checked = PluginManifest.inspect(prepared.root)
        require(checked == prepared.contents) { "Plugin metadata changed after the preview." }
        val dirName = UUID.randomUUID().toString()
        val destination = packages.resolve(dirName)
        require(prepared.root.renameTo(destination)) { "Cannot store plugin files. Nothing was changed." }
        val record = InstalledPlugin(old?.id ?: UUID.randomUUID().toString(), checked.name, checked.version, checked.description,
            prepared.origin, prepared.revision, scope, scopeLabel, dirName, prepared.digest, old?.enabled ?: true)
        try { write(records.filterNot { it.id == old?.id } + record) }
        catch (e: Exception) { destination.deleteRecursively(); throw e }
        record
    }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val records = read(); require(records.any { it.id == id }) { "Plugin no longer exists." }
        write(records.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }
    fun uninstall(id: String) = synchronized(lock) {
        val records = read(); require(records.any { it.id == id }) { "Plugin no longer exists." }
        write(records.filterNot { it.id == id })
        // Do not delete files captured by an active run or its MCP process. Startup prunes unused revisions.
    }
    fun configurationSources(scope: String): List<ConfigSource> = synchronized(lock) {
        val records = read().filter { it.enabled && it.scope in setOf("global", scope) }
        val winners = records.sortedBy { if (it.scope == "global") 0 else 1 }.associateBy { it.name }.values
        winners.sortedWith(compareBy({ if (it.scope == "global") 0 else 1 }, { it.name })).map { record ->
            val folder = checkedDirectory(record)
            val config = buildJsonObject { put("plugins", buildJsonObject { put("paths", buildJsonArray { add(".") }) }) }
            ConfigSource("Managed/${if (record.scope == "global") "Global" else "Project"}/${record.name}/${record.digest}", config.toString(), FileFs(folder))
        }
    }
}
