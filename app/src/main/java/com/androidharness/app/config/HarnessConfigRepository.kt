package com.androidharness.app.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.workspace.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File

/** App-owned bindings stay off the shared project file. Secrets use the existing encrypted store. */
class HarnessConfigRepository(private val context: Context, private val keys: KeyStoreManager,
    private val plugins: com.androidharness.app.plugins.PluginManager? = null,
    val revision: kotlinx.coroutines.flow.MutableStateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(0L)) {
    private val home = configFileAccess(FileFs(context.filesDir))
    private val bindingsFile = File(context.filesDir, "config-bindings.json")
    private val saveLock = Mutex()
    private val json = Json { prettyPrint = true }
    private fun bindings(): JsonObject {
        if (!bindingsFile.exists()) return JsonObject(emptyMap())
        require(bindingsFile.length() <= 512 * 1024) { "Configuration bindings exceed 512 KiB." }
        return json.parseToJsonElement(bindingsFile.readText()).jsonObject
    }
    private fun saveBindings(value: JsonObject) {
        val atomic = android.util.AtomicFile(bindingsFile)
        val stream = atomic.startWrite()
        try { stream.write(json.encodeToString(JsonObject.serializer(), value).toByteArray()); atomic.finishWrite(stream); revision.value += 1 }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun folders(): Map<String, String> = bindings()["folders"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
    fun variables(): Map<String, String> = bindings()["variables"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
    fun secretNames(): List<String> = bindings()["secrets"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    suspend fun addFolder(name: String, uri: Uri) = saveLock.withLock {
        require(Regex("[a-zA-Z0-9_-]{1,32}").matches(name)) { "Use 1–32 letters, digits, '-' or '_'." }
        require(folders().size < 32 || name in folders()) { "At most 32 connected configuration folders." }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val before = bindings()
        saveBindings(JsonObject(before + ("folders" to JsonObject(before["folders"]?.jsonObject.orEmpty() + (name to JsonPrimitive(uri.toString()))))))
    }
    suspend fun removeFolder(name: String) = saveLock.withLock {
        val before = bindings()
        // Another project may still use this persisted grant. Remove only the config alias.
        saveBindings(JsonObject(before + ("folders" to JsonObject(before["folders"]?.jsonObject.orEmpty() - name))))
    }
    suspend fun putVariable(name: String, value: String, secret: Boolean) = saveLock.withLock {
        require(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(name)) { "Use a variable name such as MY_TOKEN." }
        require(value.toByteArray().size <= if (name == "HARNESS_CONFIG_CONTENT" && !secret) ConfigDocument.MAX_BYTES else 16 * 1024) { "Value exceeds its size limit." }
        val before = bindings()
        if (secret) {
            require(secretNames().size < 64 || name in secretNames()) { "At most 64 configuration secrets." }
            keys.putLocalSecret("harness_config_$name", value)
            saveBindings(JsonObject(before + ("secrets" to JsonArray((secretNames() + name).distinct().map(::JsonPrimitive)))))
        } else saveBindings(JsonObject(before + ("variables" to JsonObject(before["variables"]?.jsonObject.orEmpty() + (name to JsonPrimitive(value))))))
    }
    suspend fun removeVariable(name: String, secret: Boolean) = saveLock.withLock {
        val before = bindings()
        if (secret) {
            keys.removeLocalSecret("harness_config_$name")
            saveBindings(JsonObject(before + ("secrets" to JsonArray(secretNames().filterNot { it == name }.map(::JsonPrimitive)))))
        } else saveBindings(JsonObject(before + ("variables" to JsonObject(before["variables"]?.jsonObject.orEmpty() - name))))
    }
    fun load(workspace: WorkspaceFs, projectDraft: Pair<String, String>? = null, globalDraft: Pair<String, String>? = null): HarnessConfig {
        val variables = variables()
        fun draft(fs: WorkspaceFs, content: Pair<String, String>?): WorkspaceFs = if (content == null) fs else object : WorkspaceFs by fs {
            override fun resolve(path: String): FsNode {
                val node = fs.resolve(path)
                if (path != content.first) return node
                return object : FsNode by node {
                    override val exists = true
                    override val isFile = true
                    override val isDirectory = false
                    override val length get() = content.second.toByteArray().size.toLong()
                    override fun readText() = content.second
                    override fun openInputStream() = content.second.byteInputStream()
                }
            }
        }
        val primary = if (workspace is ChatWorkspaceFs) workspace.fileAccess(workspace.primary) else workspace
        val result = HarnessConfigLoader(draft(home, globalDraft), folders().mapValues { ConfigDocumentFs(context, Uri.parse(it.value)) },
            appHome = FileFs(File(context.filesDir, "config/home")), managedPlugins = plugins?.configurationSources(workspace).orEmpty(), environment = { variables[it] ?: System.getenv(it) }, secret = { keys.localSecret("harness_config_$it")?.takeIf(String::isNotEmpty) })
            .load(draft(primary, projectDraft), variables["HARNESS_CONFIG"] ?: System.getenv("HARNESS_CONFIG"),
                variables["HARNESS_CONFIG_CONTENT"] ?: System.getenv("HARNESS_CONFIG_CONTENT"))
        if (result.autoLoad.isNotEmpty()) {
            // Bundled/user skills are checked by the engine and settings preview, which own the skill library.
            require(result.autoLoad.size <= 32) { "At most 32 auto_load patterns are supported." }
        }
        return result
    }
    fun document(workspace: WorkspaceFs, global: Boolean): Pair<String, String?> {
        val fs = if (global) home else configFileAccess(if (workspace is ChatWorkspaceFs) workspace.fileAccess(workspace.primary) else workspace)
        val prefix = if (global) "config/" else ""
        val path = listOf("${prefix}harness.json", "${prefix}harness.jsonc").firstOrNull { fs.resolve(it).exists } ?: "${prefix}harness.jsonc"
        val node = fs.resolve(path)
        require(!node.exists || node.length <= ConfigDocument.MAX_BYTES) { "Configuration exceeds 256 KiB." }
        return path to if (node.exists) node.openInputStream()?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= ConfigDocument.MAX_BYTES) {
                val n = input.read(buffer, 0, minOf(buffer.size, ConfigDocument.MAX_BYTES + 1 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            require(output.size() <= ConfigDocument.MAX_BYTES) { "Configuration exceeds 256 KiB." }
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        } ?: error("Cannot read configuration.") else null
    }
    suspend fun save(workspace: WorkspaceFs, global: Boolean, path: String, text: String, previous: String?, expectedFingerprint: String? = null) = saveLock.withLock {
        require(path in if (global) setOf("config/harness.json", "config/harness.jsonc") else setOf("harness.json", "harness.jsonc"))
        ConfigValidation.validate(ConfigDocument.parse(text, path), mapOf(path to text), complete = false)
        // Save validates this document; combined/dependency validation remains explicit and run-blocking.
        // Otherwise two broken layers could prevent the user repairing either one.
        val fs = if (global) home else configFileAccess(if (workspace is ChatWorkspaceFs) workspace.fileAccess(workspace.primary) else workspace)
        val node = fs.resolve(path)
        val current = if (node.exists) node.readText() else null
        check(current == previous && (expectedFingerprint == null || expectedFingerprint == HarnessConfig.digest(
            if (current == null) "absent" else "present:$current"))) {
            "This file changed outside the editor. Reload before saving."
        }
        node.writeText(text.trimEnd() + "\n")
        revision.value += 1
    }
    companion object {
        val TEMPLATE = """{
  // Project instructions and background documents
  "instructions": [],
  "references": [],
  "skills": { "paths": [], "enable": [], "disable": [], "auto_load": [] },
  "mcp": {}
}
"""
    }
}
