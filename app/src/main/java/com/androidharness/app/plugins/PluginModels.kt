package com.androidharness.app.plugins

import com.androidharness.app.config.ConfigDocument
import com.androidharness.app.config.ConfigValidation
import com.androidharness.app.config.HarnessConfig
import com.androidharness.app.skills.SkillParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/**
 * Where a plugin came from. [entry] holds a marketplace entry's JSON when the marketplace itself declares the
 * plugin's components, and [marketplace] names the marketplace repository it was chosen from.
 */
@Serializable
data class PluginOrigin(val kind: String, val target: String, val ref: String = "", val subdirectory: String = "",
    val entry: String = "", val marketplace: String = "", val name: String = "")

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
    val format: String = "",
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
    val agents: List<String> = emptyList(),
    val commandHooks: List<String> = emptyList(),
    val format: String = "AndroidHarness",
    val runtimes: List<String> = emptyList(),
)

data class PreparedPlugin(val token: String, val root: File, val origin: PluginOrigin, val revision: String,
    val contents: PluginContents, val digest: String, val replacing: String? = null, val previousDigest: String? = null)

/** [kind] is plugin, skill, mcp or marketplace; [source] is the catalog that listed it. */
data class PluginListing(val name: String, val description: String, val version: String, val origin: PluginOrigin,
    val publisher: String = "", val builtIn: Boolean = false, val kind: String = "plugin", val category: String = "",
    val source: String = "", val homepage: String = "")

/** Inspect the actual bundle; registry descriptions alone never establish compatibility. */
object PluginManifest {
    val known = setOf("name", "version", "description", "author", "homepage", "entry", "hooks", "skills", "instructions", "mcp",
        "agents", "commandHooks", "format", "notes")
    val hookNames = setOf("session.start", "message.received", "prompt.before", "tool.before", "tool.after", "session.end")
    private val agentName = Regex("[a-zA-Z0-9_-]{1,48}")

    fun path(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && '\\' !in relative && ':' !in relative && '\u0000' !in relative &&
            relative.split('/').none { it == ".." }) { "A plugin path must stay inside its bundle: $relative" }
        return root.resolve(relative).canonicalFile.also {
            require(it.toPath().startsWith(root.canonicalFile.toPath())) { "Plugin path leaves its bundle: $relative" }
        }
    }
    internal fun text(file: File, cap: Int = 256 * 1024): String {
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
    /** A skills entry is either one skill folder (it holds SKILL.md) or a folder of skill folders. */
    fun skillFolders(root: File, dir: String): List<File> {
        val folder = path(root, dir)
        require(folder.isDirectory) { "Skill folder is missing: $dir" }
        if (folder.resolve("SKILL.md").isFile) return listOf(folder)
        val children = folder.listFiles().orEmpty()
        require(children.size <= 1024) { "A plugin skill folder has too many entries." }
        return children.filter { it.isDirectory && it.resolve("SKILL.md").isFile }.sortedBy { it.name }
    }
    fun inspect(root: File): PluginContents {
        val file = path(root, "plugin.json")
        require(file.isFile) { "No plugin manifest found. Nothing in this bundle looks like a plugin, skill, command, agent or MCP server." }
        val raw = text(file)
        val value = ConfigDocument.parse(raw, "plugin.json")
        val warnings = mutableListOf<String>()
        value.fields.keys.filter { it !in known }.forEach { warnings += "Ignored manifest field '$it'." }
        val name = value["name"]?.string().orEmpty()
        require(Regex("[a-z0-9][a-z0-9-]{0,63}").matches(name)) { "Plugin names need 1–64 lowercase letters, digits or '-'." }
        val version = value["version"]?.string().orEmpty().ifBlank { "0.0.0" }
        require(version.length <= 80 && version.none { it.isISOControl() }) { "plugin.json has an invalid version." }
        val description = value["description"]?.string().orEmpty()
        require(description.length <= 4000) { "Plugin description exceeds 4000 characters." }
        fun paths(key: String, limit: Int = 256): List<String> {
            val field = value[key] ?: return emptyList()
            require(field.json is JsonArray && field.items.all { it.string() != null }) { "$key must be an array of paths." }
            require(field.items.size <= limit) { "At most $limit $key paths per plugin." }
            return field.strings().distinct().onEach { componentPath(root, it) }
        }
        val instructions = paths("instructions", 32).onEach { text(path(root, it), 64 * 1024) }
        require(instructions.sumOf { path(root, it).length() } <= 64_000) { "Plugin instructions exceed 64,000 bytes." }
        val skillNames = mutableListOf<String>()
        paths("skills").forEach { dir ->
            skillFolders(root, dir).forEach { folder ->
                runCatching { SkillParser.parse(text(folder.resolve("SKILL.md"))).name }
                    .onSuccess { skillNames += it }
                    .onFailure { warnings += "Skipped skill ${folder.name}: ${it.message}" }
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
            text(componentPath(root, entry), 64 * 1024)
        }
        val commandHooks = value["commandHooks"]?.let { field ->
            require(field.json is JsonArray && field.items.size <= 64) { "commandHooks must be an array of at most 64 hooks." }
            field.items.map { hook ->
                val event = hook["event"]?.string()
                val command = hook["command"]?.string().orEmpty()
                require(event in hookNames && command.isNotBlank() && command.length <= 4000) { "A command hook needs a supported event and a command." }
                "$event: ${hook["matcher"]?.string()?.let { "[$it] " }.orEmpty()}$command"
            }
        }.orEmpty()
        val agents = value["agents"]?.let { field ->
            require(field.json is JsonObject) { "agents must be an object." }
            field.fields.map { (key, agent) ->
                require(agentName.matches(key)) { "Agent names need 1–48 letters, digits, '-' or '_': $key" }
                agent["instructions"]?.items.orEmpty().forEach { text(componentPath(root, it.string().orEmpty()), 64 * 1024) }
                key
            }
        }.orEmpty()
        val mcp = value["mcp"]
        if (mcp != null) {
            val synthetic = "{\"mcp\":${mcp.json}}"
            ConfigValidation.validate(ConfigDocument.parse(synthetic, "plugin MCP"), mapOf("plugin MCP" to synthetic))
        }
        require(instructions.isNotEmpty() || skillNames.isNotEmpty() || mcp?.fields?.isNotEmpty() == true || hooks.isNotEmpty() ||
            commandHooks.isNotEmpty() || agents.isNotEmpty()) { "The plugin declares no instructions, skills, agents, MCP servers or hooks." }
        value["notes"]?.items.orEmpty().mapNotNull { it.string() }.take(50).forEach { warnings += it }
        val runtimes = PluginRuntimes.detect(root, value)
        val needsRuntime = runtimes.isNotEmpty() || mcp?.fields?.values?.any { it["command"] != null } == true
        if (root.resolve("package.json").isFile) runCatching {
            val pkg = Json.parseToJsonElement(text(root.resolve("package.json"), 1024 * 1024)).jsonObject
            if (pkg["scripts"]?.jsonObject?.keys?.any { it in setOf("install", "postinstall", "preinstall", "prepare") } == true)
                warnings += "This package declares npm installation scripts. They are not run when the plugin installs."
        }
        return PluginContents(name, version, description, instructions, skillNames.sorted(), mcp?.fields?.keys.orEmpty().toList(),
            hooks, needsRuntime, warnings.distinct(), agents.sorted(), commandHooks, value["format"]?.string() ?: "AndroidHarness", runtimes)
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

/** Linux packages a bundle needs at run time, read from its commands and the scripts its skills ship. */
object PluginRuntimes {
    private val byCommand = mapOf(
        "node" to listOf("nodejs"), "npx" to listOf("nodejs", "npm"), "npm" to listOf("nodejs", "npm"),
        "pnpm" to listOf("nodejs", "npm"), "yarn" to listOf("nodejs", "npm"), "bun" to listOf("nodejs", "npm"), "bunx" to listOf("nodejs", "npm"),
        "tsx" to listOf("nodejs", "npm"), "python" to listOf("python"), "python3" to listOf("python"),
        "pip" to listOf("python", "python-pip"), "pip3" to listOf("python", "python-pip"), "pipx" to listOf("python", "python-pip"),
        "uv" to listOf("python", "uv"), "uvx" to listOf("python", "uv"), "deno" to listOf("deno"), "ruby" to listOf("ruby"),
        "go" to listOf("golang"), "java" to listOf("openjdk-17"), "php" to listOf("php"), "git" to listOf("git"),
        "jq" to listOf("jq"), "curl" to listOf("curl"),
    )
    private val byExtension = mapOf("py" to listOf("python"), "js" to listOf("nodejs"), "cjs" to listOf("nodejs"),
        "mjs" to listOf("nodejs"), "ts" to listOf("nodejs", "npm"), "rb" to listOf("ruby"), "php" to listOf("php"))

    fun forCommand(command: String): List<String> {
        val words = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() && !it.contains('=') }
        val first = words.firstOrNull()?.substringAfterLast('/') ?: return emptyList()
        val direct = byCommand[first].orEmpty()
        val script = words.drop(1).firstNotNullOfOrNull { word -> byExtension[word.substringAfterLast('.', "").lowercase()] }.orEmpty()
        return (direct + script).distinct()
    }
    fun detect(root: File, manifest: com.androidharness.app.config.ConfigValue): List<String> {
        val found = linkedSetOf<String>()
        manifest["mcp"]?.fields?.values.orEmpty().forEach { server ->
            val command = server["command"]
            val words = if (command?.json is JsonArray) command.items.mapNotNull { it.string() } else listOfNotNull(command?.string())
            found += forCommand((words + server["args"]?.items.orEmpty().mapNotNull { it.string() }).joinToString(" "))
        }
        manifest["commandHooks"]?.items.orEmpty().forEach { found += forCommand(it["command"]?.string().orEmpty()) }
        manifest["skills"]?.items.orEmpty().mapNotNull { it.string() }.forEach { dir ->
            runCatching { PluginManifest.skillFolders(root, dir) }.getOrDefault(emptyList()).forEach { skill ->
                skill.walkTopDown().maxDepth(4).filter { it.isFile }.take(2000).forEach { file ->
                    found += byExtension[file.extension.lowercase()].orEmpty()
                }
            }
        }
        return found.toList()
    }
}
