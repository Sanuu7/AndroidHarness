package com.androidharness.app.plugins

import com.androidharness.app.config.ConfigDocument
import com.androidharness.app.config.ConfigValidation
import com.androidharness.app.config.HarnessConfig
import com.androidharness.app.skills.SkillParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class PluginOrigin(val kind: String, val target: String, val ref: String = "", val subdirectory: String = "")

@Serializable
data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val origin: PluginOrigin,
    val revision: String,
    val scope: String,
    val scopeLabel: String,
    val directory: String,
    val digest: String,
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
)

data class PluginContents(
    val name: String,
    val version: String,
    val description: String,
    val instructions: List<String>,
    val skills: List<String>,
    val servers: List<String>,
    val hooks: List<String>,
    val needsRuntime: Boolean,
    val warnings: List<String>,
)

data class PreparedPlugin(val token: String, val root: File, val origin: PluginOrigin, val revision: String,
    val contents: PluginContents, val digest: String, val replacing: String? = null, val previousDigest: String? = null)

data class PluginListing(val name: String, val description: String, val version: String, val origin: PluginOrigin,
    val publisher: String = "", val builtIn: Boolean = false)

/** Inspect the actual bundle; registry descriptions alone never establish compatibility. */
object PluginManifest {
    private val allowed = setOf("name", "version", "description", "author", "homepage", "entry", "hooks", "skills", "instructions", "mcp")
    val hookNames = setOf("session.start", "message.received", "prompt.before", "tool.before", "tool.after", "session.end")

    fun path(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && '\\' !in relative && ':' !in relative && '\u0000' !in relative &&
            relative.split('/').none { it == ".." }) { "A plugin path must stay inside its bundle: $relative" }
        return root.resolve(relative).canonicalFile.also {
            require(it.toPath().startsWith(root.canonicalFile.toPath())) { "Plugin path leaves its bundle: $relative" }
        }
    }
    private fun text(file: File, cap: Int = 64 * 1024): String {
        require(file.isFile && file.length() <= cap) { "Missing or oversized plugin file: ${file.name}" }
        return file.inputStream().use { input ->
            val bytes = PluginArchives.readLimited(input, cap.toLong())
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
    private fun componentPath(root: File, relative: String): File {
        require(!relative.startsWith('@') && !relative.startsWith("~/") && !relative.startsWith('!') &&
            relative.none { it == '*' || it == '?' }) { "Plugin components need literal bundle-relative paths: $relative" }
        return path(root, relative)
    }
    fun inspect(root: File): PluginContents {
        val file = path(root, "plugin.json")
        require(file.isFile) { "No AndroidHarness plugin.json found. This package is not an AndroidHarness plugin bundle." }
        val raw = text(file)
        val value = ConfigDocument.parse(raw, "plugin.json")
        require(value.fields.keys.all { it in allowed }) { "Unsupported plugin component: ${value.fields.keys.first { it !in allowed }}" }
        val name = value["name"]?.string().orEmpty()
        require(Regex("[a-z0-9][a-z0-9-]{0,63}").matches(name)) { "Plugin names need 1–64 lowercase letters, digits or '-'." }
        val version = value["version"]?.string().orEmpty()
        require(version.isNotBlank() && version.length <= 80 && version.none { it.isISOControl() }) { "plugin.json needs a version." }
        val description = value["description"]?.string().orEmpty()
        listOf("description", "author", "homepage", "entry").forEach { key ->
            require(value[key] == null || value[key]?.string() != null) { "$key must be a string." }
        }
        require(description.length <= 2000) { "Plugin description exceeds 2000 characters." }
        fun paths(key: String): List<String> {
            val field = value[key] ?: return emptyList()
            require(field.json is JsonArray && field.items.all { it.string() != null }) { "$key must be an array of paths." }
            require(field.items.size <= 32) { "At most 32 $key paths per plugin." }
            return field.strings().distinct().onEach { componentPath(root, it) }
        }
        val instructions = paths("instructions").onEach { text(path(root, it)) }
        require(instructions.sumOf { path(root, it).length() } <= 64_000) { "Plugin instructions exceed 64,000 bytes." }
        val skills = paths("skills")
        val skillNames = mutableListOf<String>()
        skills.forEach { dir ->
            val folder = path(root, dir)
            require(folder.isDirectory) { "Skill folder is missing: $dir" }
            val children = folder.listFiles().orEmpty()
            require(children.size <= 128) { "A plugin skill folder has too many entries." }
            children.filter { it.isDirectory }.forEach { child ->
                val md = path(root, "$dir/${child.name}/SKILL.md")
                if (md.isFile) skillNames += SkillParser.parse(text(md)).name
            }
        }
        require(skillNames.distinct().size == skillNames.size) { "A plugin declares duplicate skill names." }
        val hooks = value["hooks"]?.let { field ->
            require(field.json is JsonArray && field.items.all { it.string() in hookNames }) { "Plugin hooks contain an unsupported event." }
            field.strings().distinct()
        }.orEmpty()
        if (hooks.isNotEmpty()) {
            val entry = value["entry"]?.string() ?: error("Plugin hooks need a JavaScript entry file.")
            require(entry.endsWith(".js")) { "Plugin hooks need a standalone .js entry file." }
            text(componentPath(root, entry))
        }
        val mcp = value["mcp"]
        if (mcp != null) {
            val synthetic = "{\"mcp\":${mcp.json}}"
            ConfigValidation.validate(ConfigDocument.parse(synthetic, "plugin MCP"), mapOf("plugin MCP" to synthetic))
        }
        require(instructions.isNotEmpty() || skillNames.isNotEmpty() || mcp?.fields?.isNotEmpty() == true || hooks.isNotEmpty()) {
            "The plugin declares no instructions, skills, MCP servers or hooks."
        }
        val needsRuntime = mcp?.fields?.values?.any { it["command"] != null } == true
        return PluginContents(name, version, description, instructions, skillNames.sorted(), mcp?.fields?.keys.orEmpty().toList(),
            hooks, needsRuntime, buildList {
                if (needsRuntime) add("Local MCP servers need a compatible runtime. Installation does not start them.")
                if (root.resolve("package.json").isFile) {
                    val pkg = Json.parseToJsonElement(text(root.resolve("package.json"), 256 * 1024)).jsonObject
                    if (pkg["scripts"]?.jsonObject?.keys?.any { it in setOf("install", "postinstall", "preinstall", "prepare") } == true)
                        add("This package declares installation scripts. AndroidHarness does not run those scripts.")
                }
            })
    }

    fun digest(root: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            path(root, relative)
            digest.update(relative.toByteArray()); digest.update(0.toByte())
            file.inputStream().use { stream -> val buffer = ByteArray(8192); while (true) {
                val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            } }
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
