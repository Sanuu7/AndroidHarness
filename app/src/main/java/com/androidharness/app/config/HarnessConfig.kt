package com.androidharness.app.config

import com.androidharness.app.skills.SkillStore
import com.androidharness.app.tools.mcp.McpServerConfig
import com.androidharness.app.workspace.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

data class ConfigSource(val id: String, val text: String, val fs: WorkspaceFs, val directory: String = "")
/** [fs] is where the file lives, so a file that does not fit the prompt can still be read as a reference. */
data class ConfigFile(val source: String, val path: String, val text: String, val fs: WorkspaceFs? = null)
data class ConfigReference(val title: String, val path: String, val source: String, val fs: WorkspaceFs)
/**
 * A named Code agent. [tools] switches tool groups off; [allowed], when not empty, is the agent's own tool list (Claude Code
 * names such as Read or Bash, app names or globs) and every other tool is off. [model] is the model it asks for, if any.
 */
data class ConfigAgent(val name: String, val description: String, val instructions: List<ConfigFile>, val tools: Map<String, Boolean>,
    val model: String = "", val allowed: List<String> = emptyList())
/**
 * A plugin hook. A command hook runs [command] in the Linux shell when [event] fires; a prompt or agent hook ([type]) asks the
 * model with [command] as its prompt. [source] keeps the original event name for the payload.
 */
data class ConfigCommandHook(val event: String, val command: String, val matcher: String = "", val timeout: Int = 60, val source: String = "",
    val type: String = "command")
/** [options] are the plugin's settings, given to its hooks as CLAUDE_PLUGIN_OPTION_<NAME>. */
data class ConfigPlugin(val name: String, val script: String, val hooks: Set<String>, val commands: List<ConfigCommandHook> = emptyList(), val root: String = "",
    val options: Map<String, String> = emptyMap())
/** A language server a plugin ships. [extensions] maps a file extension such as .ts to its language id. */
data class ConfigLanguageServer(val name: String, val plugin: String, val command: String, val args: List<String>, val extensions: Map<String, String>,
    val env: Map<String, String>, val root: String, val initializationOptions: JsonObject? = null)
data class ConfigMemory(val file: String = ".harness/memory.md", val topics: String = ".harness/memory",
    val autoRecall: Boolean = true, val maxLines: Int = 120)
data class ConfigServerSource(val name: String, val plugin: String? = null, val source: String)

data class HarnessConfig(
    val root: ConfigValue? = null,
    val sources: List<ConfigSource> = emptyList(),
    val instructions: List<ConfigFile> = emptyList(),
    val references: List<ConfigReference> = emptyList(),
    val skills: Map<String, SkillStore.BundledSkill> = emptyMap(),
    val enabledSkills: List<String> = emptyList(), val disabledSkills: List<String> = emptyList(),
    val autoLoad: List<String> = emptyList(),
    val servers: List<McpServerConfig> = emptyList(),
    val permissions: Map<String, String> = emptyMap(),
    val agents: Map<String, ConfigAgent> = emptyMap(), val defaultAgent: String = "default",
    val memory: ConfigMemory = ConfigMemory(),
    val plugins: List<ConfigPlugin> = emptyList(),
    val warnings: List<String> = emptyList(),
    val trustPayload: String = "",
    val sensitiveValues: Set<String> = emptySet(),
    val serverSources: Map<String, ConfigServerSource> = emptyMap(),
    /** Plugins that were skipped or only partly loaded, with the reason, by plugin name. */
    val pluginProblems: Map<String, String> = emptyMap(),
    val languageServers: List<ConfigLanguageServer> = emptyList(),
    /** Plugin bin folders, added to the shell's PATH. */
    val pluginBins: List<String> = emptyList(),
) {
    val present get() = sources.isNotEmpty()
    val fingerprint get() = digest(trustPayload + Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(McpServerConfig.serializer()), servers))
    fun redact(text: String): String {
        var output = com.androidharness.app.tools.SecretRedactor.redact(text)
        val values = sensitiveValues + servers.flatMap { it.env.values + it.headers.values }.filter { it.length >= 4 }
        values.filter(String::isNotBlank).sortedByDescending(String::length).forEach { output = output.replace(it, "[redacted]") }
        return output
    }
    fun skillEnabled(name: String): Boolean =
        (enabledSkills.isEmpty() || enabledSkills.any { ConfigGlob.matches(it, name) }) &&
            disabledSkills.none { ConfigGlob.matches(it, name) }
    fun prompt(agent: String = defaultAgent): String = buildString {
        instructions.forEach { append("\n# Project instructions: ${it.path}\n${it.text}\n") }
        if (agents.isNotEmpty()) {
            append("\n# Agents\nDelegate to one with task(agent=\"name\", prompt=...) when the work matches its description.\n")
            agents.values.forEach { append("- ${it.name}: ${it.description.replace(Regex("\\s+"), " ").trim()}\n") }
        }
        agents[agent]?.let { profile ->
            append("\n# Code agent: ${profile.name}\n${profile.description}\n")
            profile.instructions.forEach { append("\n## ${it.path}\n${it.text}\n") }
        }
        if (references.isNotEmpty()) {
            append("\n# Reference documents\nBackground knowledge; use reference_read(name) when relevant.\n")
            references.forEachIndexed { i, ref -> append("- ${i + 1}: ${ref.title} (${ref.path})\n") }
        }
    }
    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** No shell, network or installation takes place while loading a configuration. */
class HarnessConfigLoader(
    home: WorkspaceFs? = null,
    folders: Map<String, WorkspaceFs> = emptyMap(),
    appHome: WorkspaceFs? = null,
    private val environment: (String) -> String? = { null },
    private val secret: (String) -> String? = { null },
    private val managedPlugins: List<ConfigSource> = emptyList(),
    /** Installed plugins that could not be read, by name, so they are reported instead of silently missing. */
    private val managedProblems: Map<String, String> = emptyMap(),
    /** The folder a plugin keeps data in, as ${CLAUDE_PLUGIN_DATA}. */
    private val pluginData: (String) -> String = { "" },
) {
    companion object {
        /** Characters of instructions placed in the prompt; the rest are read on demand. */
        const val INSTRUCTION_BUDGET = 64_000
        private const val MAX_FILE = 64 * 1024 * 1024
    }
    private val home = home?.let(::bounded)
    private val appHome = (appHome ?: home)?.let(::bounded)
    private val folders = folders.mapValues { bounded(it.value) }
    private fun bounded(fs: WorkspaceFs): WorkspaceFs = object : WorkspaceFs by fs {
        override fun resolve(path: String): FsNode {
            fs.shellRoot?.let { root ->
                require(root.resolve(path).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                    "Configuration path leaves its granted folder: $path"
                }
            }
            return fs.resolve(path)
        }
    }
    private val sources = linkedMapOf<String, ConfigSource>()
    private val warnings = mutableListOf<String>()
    private var readBytes = 0
    private val sensitiveValues = linkedSetOf<String>()
    private fun read(fs: WorkspaceFs, path: String, cap: Int = ConfigDocument.MAX_BYTES): String {
        val node = fs.resolve(path)
        require(node.exists && node.isFile) { "Required file is missing: $path" }
        val bytes = node.openInputStream()?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= cap) {
                val count = input.read(buffer, 0, minOf(buffer.size, cap + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("Cannot read $path")
        require(bytes.size <= cap) { "$path exceeds ${cap / 1024} KiB." }
        readBytes += bytes.size
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun source(fs: WorkspaceFs, path: String, id: String, cap: Int = ConfigDocument.MAX_BYTES): ConfigValue {
        val text = read(fs, path, cap)
        val src = ConfigSource(id, text, fs, path.substringBeforeLast('/', ""))
        sources[id] = src
        return ConfigDocument.parse(text, id, path.endsWith(".jsonc"), cap)
    }
    private fun discover(fs: WorkspaceFs, prefix: String, label: String): ConfigValue? {
        val a = "${prefix}harness.json"; val b = "${prefix}harness.jsonc"
        val plain = fs.resolve(a).exists; val jsonc = fs.resolve(b).exists
        if (plain && jsonc) warnings += "$label: harness.json takes precedence over harness.jsonc."
        return if (plain || jsonc) source(fs, if (plain) a else b, "$label/${if (plain) a else b}").also {
            ConfigValidation.validate(it, sources.mapValues { entry -> entry.value.text }, complete = false)
        } else null
    }
    private fun path(value: ConfigValue, raw: String = value.string().orEmpty()): Pair<WorkspaceFs, String> {
        val src = sources.getValue(value.source)
        val clean = raw.replace('\\', '/')
        require(clean.isNotBlank() && '\u0000' !in clean) { "${value.source}: empty or invalid path." }
        if (clean.startsWith("@")) {
            val name = clean.substringBefore('/').drop(1)
            return requireNotNull(folders[name]) { "Folder @$name is not connected. Add it in Configuration settings." } to relative(clean.substringAfter('/', "."))
        }
        if (clean.startsWith("~/")) return requireNotNull(appHome) { "Configuration home is unavailable." } to relative(clean.drop(2))
        require(!clean.startsWith('/')) { "${value.source}: use workspace-relative paths, ~/ for app home, or a connected @folder." }
        return src.fs to relative(listOf(src.directory, clean).filter { it.isNotBlank() }.joinToString("/"))
    }
    private fun relative(path: String): String {
        require(path.split('/').none { it == ".." }) { "Configuration paths cannot escape their folder: $path" }
        return path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/").ifBlank { "." }
    }
    private fun files(value: ConfigValue, glob: Boolean = true): List<Pair<WorkspaceFs, String>> {
        val (fs, pattern) = path(value)
        if (!glob || pattern.none { it == '*' || it == '?' }) return listOf(fs to pattern)
        val prefix = pattern.takeWhile { it != '*' && it != '?' }.substringBeforeLast('/', "").ifBlank { "." }
        val found = mutableListOf<Pair<WorkspaceFs, String>>()
        var count = 0
        fun visit(dir: String, depth: Int) {
            require(depth <= 24) { "Glob directory nesting exceeds 24 levels: $pattern" }
            val node = fs.resolve(dir)
            if (!node.exists) return
            require(node.isDirectory) { "Glob prefix is not a folder: $dir" }
            node.list().sortedBy { it.name }.forEach { child ->
                require(++count <= 4096) { "Glob scans more than 4096 entries: narrow '$pattern'." }
                val candidate = if (dir == ".") child.name else "$dir/${child.name}"
                if (child.isFile && ConfigGlob.matches(pattern, candidate)) found += fs to candidate
                else if (child.isDirectory && child.name !in setOf(".git", "node_modules", "build", ".gradle")) visit(candidate, depth + 1)
            }
        }
        visit(prefix, 0)
        if (found.isEmpty()) warnings += "${value.source}: no files matched $pattern."
        return found
    }
    private fun instructionFiles(values: List<ConfigValue>, automatic: List<Pair<WorkspaceFs, String>> = emptyList()): List<ConfigFile> {
        val excludes = values.filter { it.string().orEmpty().startsWith('!') }.map { path(it, it.string()!!.drop(1)) }
        val included = values.filterNot { it.string().orEmpty().startsWith('!') }.flatMap { v -> files(v).map { Triple(it.first, it.second, v.source) } }.toMutableList()
        automatic.forEach { (fs, file) -> if (included.none { it.first === fs && it.second == file }) included += Triple(fs, file, "Project") }
        return included.distinctBy { it.first.displayPath to it.second }
            .filterNot { (fs, file) -> excludes.any { (it.first.displayPath == fs.displayPath || file in setOf("AGENTS.md", "HARNESS.md")) && ConfigGlob.matches(it.second, file) } }
            .map { (fs, file, src) -> ConfigFile(src, file, read(fs, file, MAX_FILE), fs) }
    }
    private fun expand(value: ConfigValue): String = Regex("\\{(env|file|secret):([^{}]+)\\}").replace(value.string().orEmpty()) { match ->
        val resolved = when (match.groupValues[1]) {
            "env" -> environment(match.groupValues[2]).orEmpty()
            "secret" -> requireNotNull(secret(match.groupValues[2])) { "A configuration secret is missing. Add it in Configuration settings." }
            else -> path(value, match.groupValues[2]).let { read(it.first, it.second, 16 * 1024).trimEnd('\r', '\n') }
        }
        if (resolved.isNotBlank()) sensitiveValues += resolved
        resolved
    }
    fun load(workspace: WorkspaceFs, overrideFile: String? = null, inline: String? = null): HarnessConfig {
        sources.clear(); warnings.clear(); sensitiveValues.clear(); readBytes = 0
        val primary = bounded(if (workspace is ChatWorkspaceFs) workspace.fileAccess(workspace.primary) else workspace)
        var merged: ConfigValue? = null
        fun managed(project: Boolean) {
            managedPlugins.filter { it.id.startsWith(if (project) "Managed/Project/" else "Managed/Global/") }.forEach { src ->
                sources[src.id] = src.copy(fs = bounded(src.fs))
                val parsed = ConfigDocument.parse(src.text, src.id)
                ConfigValidation.validate(parsed, mapOf(src.id to src.text), complete = false)
                merged = ConfigDocument.merge(merged, parsed)
            }
        }
        managed(false)
        home?.let { discover(it, "config/", "Global") }?.let { merged = ConfigDocument.merge(merged, it) }
        managed(true)
        discover(primary, "", "Project")?.let { merged = ConfigDocument.merge(merged, it) }
        overrideFile?.takeIf { it.isNotBlank() }?.let { file ->
            val base = ConfigSource("Override base", "", primary)
            sources[base.id] = base
            val target = path(ConfigValue(JsonPrimitive(file), base.id))
            val override = source(target.first, target.second, "Override/$file")
            ConfigValidation.validate(override, sources.mapValues { it.value.text }, complete = false)
            merged = ConfigDocument.merge(merged, override)
        }
        inline?.takeIf { it.isNotBlank() }?.let { text ->
            val id = "HARNESS_CONFIG_CONTENT"
            sources[id] = ConfigSource(id, text, primary)
            val override = ConfigDocument.parse(text, id, true)
            ConfigValidation.validate(override, mapOf(id to text), complete = false)
            merged = ConfigDocument.merge(merged, override)
        }
        val root = merged ?: return HarnessConfig()
        ConfigValidation.validate(root, sources.mapValues { it.value.text })
        val automatic = listOf("AGENTS.md", "HARNESS.md").filter { primary.resolve(it).let { n -> n.exists && n.isFile } }.map { primary to it }
        val rootInstructions = instructionFiles(root["instructions"]?.items.orEmpty(), automatic)
        val pluginInstructions = mutableListOf<ConfigFile>()
        val pluginServers = linkedMapOf<String, ConfigValue>()
        val refs = root["references"]?.items.orEmpty().flatMap { ref ->
            val p = ref["path"]!!
            files(p, ref["glob"]?.json?.jsonPrimitive?.booleanOrNull == true).map { (fs, file) ->
                require(fs.resolve(file).let { it.exists && it.isFile }) { "Reference is missing: $file" }
                ConfigReference(ref["title"]?.string() ?: file, file, p.source, fs)
            }
        }.distinctBy { it.fs.displayPath to it.path }
        require(refs.size <= 256) { "At most 256 reference files may be listed." }
        val skillFiles = linkedMapOf<String, SkillStore.BundledSkill>()
        val scannedSkillDirs = mutableSetOf<Pair<String, String>>()
        /**
         * Reads one skill folder, or a folder of skill folders, into [into]. A plugin's skills are keyed as
         * plugin:skill, as Claude Code names them, so two plugins can ship skills with the same name.
         * Skills written for other harnesses are read leniently and their files are never rewritten.
         */
        fun skillDir(fs: WorkspaceFs, directory: String, into: MutableMap<String, SkillStore.BundledSkill> = skillFiles,
            namespace: String? = null, pluginRoot: String? = null) {
            if (!scannedSkillDirs.add(fs.displayPath to directory)) return
            val dir = fs.resolve(directory)
            require(dir.exists && dir.isDirectory) { "Skill folder is missing: $directory" }
            // A skills entry may name one skill folder or a folder of skill folders.
            val single = fs.resolve("$directory/SKILL.md").let { it.exists && it.isFile }
            val bases = if (single) listOf(directory) else dir.list().filter { it.isDirectory }.sortedBy { it.name }.map { "$directory/${it.name}" }
            bases.forEach { base ->
                val md = "$base/SKILL.md"
                if (fs.resolve(md).let { it.exists && it.isFile }) {
                    val content = read(fs, md, MAX_FILE)
                    val parsed = runCatching { com.androidharness.app.skills.SkillParser.lenient(content, base.substringAfterLast('/')) }
                        .getOrElse { warnings += "Skipped skill $base: ${it.message}"; return@forEach }
                    // Real folders are read on demand, so large skills with data files cost nothing at load time.
                    val folder = fs.shellRoot?.resolve(base)?.canonicalFile?.takeIf { it.isDirectory }
                    val support = linkedMapOf<String, String>()
                    fun scan(sub: String, depth: Int) {
                        if (depth > 8 || support.size >= 128) return
                        val n = fs.resolve("$base/$sub")
                        if (!n.exists || !n.isDirectory) return
                        n.list().sortedBy { it.name }.forEach { f ->
                            if (support.size >= 128) return
                            if (f.isFile && f.length <= 64 * 1024) support["$sub/${f.name}"] = read(fs, "$base/$sub/${f.name}", 64 * 1024)
                            else if (f.isDirectory) scan("$sub/${f.name}", depth + 1)
                        }
                    }
                    if (folder == null) SkillStore.SUPPORT_DIRS.forEach { scan(it, 0) }
                    var key = namespace?.let { "$it:${parsed.name}" } ?: parsed.name
                    if (namespace != null && key in into) {
                        val parent = base.substringBeforeLast('/', "").substringAfterLast('/')
                        key = "$namespace:${com.androidharness.app.skills.SkillParser.safeName("${parsed.name}-$parent")}"
                        var n = 2
                        while (key in into) key = "$namespace:${parsed.name}-${n++}"
                    }
                    into[key] = SkillStore.BundledSkill(base, parsed.raw, support, folder?.path, pluginRoot, name = key.takeIf { it != parsed.name })
                }
            }
        }
        // Explicit paths supplement the conventional project directory, with deterministic precedence.
        val plugins = mutableListOf<ConfigPlugin>()
        val pluginServerSources = linkedMapOf<String, ConfigServerSource>()
        val pluginAgents = linkedMapOf<String, ConfigAgent>()
        val pluginProblems = linkedMapOf<String, String>().apply { putAll(managedProblems) }
        managedProblems.forEach { (name, reason) -> warnings += "Plugin $name was skipped: $reason" }
        val languageServers = mutableListOf<ConfigLanguageServer>()
        val pluginBins = mutableListOf<String>()
        val loadedPlugins = mutableSetOf<String>()
        val pluginSettings = root["plugins"]
        /** Loads one plugin. Anything wrong with it skips only this plugin and is reported under its name. */
        fun plugin(baseFs: WorkspaceFs, base: String, configurationSource: String) {
            val manifestPath = "$base/plugin.json"
            if (!baseFs.resolve(manifestPath).exists) return
            val id = "Plugin/${HarnessConfig.digest(baseFs.displayPath).take(12)}/$base"
            var name = configurationSource.takeIf { it.startsWith("Managed/") }?.split('/')?.getOrNull(2)
                ?: base.substringAfterLast('/').takeIf { it.isNotBlank() && it != "." } ?: baseFs.displayPath.substringAfterLast('/')
            val stagedSkills = linkedMapOf<String, SkillStore.BundledSkill>()
            val stagedInstructions = mutableListOf<ConfigFile>()
            val stagedAgents = linkedMapOf<String, ConfigAgent>()
            val stagedServers = linkedMapOf<String, ConfigValue>()
            val stagedServerSources = linkedMapOf<String, ConfigServerSource>()
            val stagedLanguageServers = mutableListOf<ConfigLanguageServer>()
            val stagedBins = mutableListOf<String>()
            val stagedWarnings = mutableListOf<String>()
            var stagedPlugin: ConfigPlugin? = null
            try {
                val manifest = source(baseFs, manifestPath, id, ConfigDocument.MAX_PLUGIN_BYTES)
                name = manifest["name"]?.string() ?: error("$manifestPath needs a name.")
                val enable = pluginSettings?.get("enable")?.strings().orEmpty()
                val disable = pluginSettings?.get("disable")?.strings().orEmpty()
                if (disable.any { ConfigGlob.matches(it, name) } || enable.isNotEmpty() && enable.none { ConfigGlob.matches(it, name) }) { sources.remove(id); return }
                require(name !in loadedPlugins) { "Another plugin named $name is already loaded, so this copy was skipped." }
                require(manifest["hooks"] == null || manifest["hooks"]!!.json is JsonArray && manifest["hooks"]!!.items.all { it.string() != null }) { "$manifestPath hooks must be an array of event names." }
                require(manifest["skills"] == null || manifest["skills"]!!.json is JsonArray && manifest["skills"]!!.items.all { it.string() != null }) { "$manifestPath skills must be an array of paths." }
                val folder = baseFs.shellRoot?.resolve(base)?.canonicalPath
                val namespace = if (configurationSource.startsWith("Managed/") || folder != null) name else null
                manifest["skills"]?.items.orEmpty().forEach { v ->
                    runCatching { path(v).let { skillDir(it.first, it.second, stagedSkills, namespace, folder) } }
                        .onFailure { stagedWarnings += "Plugin $name: ${it.message}" }
                }
                manifest["instructions"]?.let { field ->
                    require(field.json is JsonArray && field.items.all { it.string() != null && !it.string().orEmpty().startsWith('!') }) {
                        "$manifestPath instructions must be an array of bundle-relative files."
                    }
                    stagedInstructions += instructionFiles(field.items)
                }
                manifest["agents"]?.fields.orEmpty().filterValues { it.json !is JsonNull }.forEach { (agent, v) ->
                    val key = "$name:$agent"
                    stagedAgents[key] = ConfigAgent(key, v["description"]?.string().orEmpty(), instructionFiles(v["instructions"]?.items.orEmpty()),
                        v["tools"]?.fields.orEmpty().mapValues { it.value.json.jsonPrimitive.booleanOrNull ?: true },
                        v["model"]?.string().orEmpty(), v["allowedTools"]?.strings().orEmpty())
                }
                val options = manifest["settings"]?.fields.orEmpty().mapNotNull { (key, setting) ->
                    val value = environment(key)?.takeIf(String::isNotEmpty) ?: setting["default"]?.json?.jsonPrimitive?.contentOrNull
                    if (value == null && setting["required"]?.json?.jsonPrimitive?.booleanOrNull == true)
                        stagedWarnings += "Plugin $name needs $key. Set it in Plugins, under the plugin's settings."
                    value?.let { if (it.isNotBlank() && setting["sensitive"]?.json?.jsonPrimitive?.booleanOrNull == true) sensitiveValues += it; key to it }
                }.toMap()
                manifest["mcp"]?.let { servers ->
                    val check = "{\"mcp\":${servers.json}}"
                    ConfigValidation.validate(ConfigDocument.parse(check, manifestPath, maxBytes = ConfigDocument.MAX_PLUGIN_BYTES), mapOf(manifestPath to check))
                    val prefix = name.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(10) + "_" + HarnessConfig.digest(name).take(4)
                    servers.fields.filterValues { it.json !is JsonNull }.forEach { (key, value) ->
                        val qualified = prefix + "_" + key.lowercase().replace('-', '_').take(8) + "_" + HarnessConfig.digest(key).take(6)
                        if (qualified in pluginServers || qualified in stagedServers) { stagedWarnings += "Plugin $name: MCP server $key has the same name as another plugin's server and was skipped."; return@forEach }
                        fun bundleVariables(v: ConfigValue): ConfigValue {
                            if (v.string() != null) {
                                val original = v.string()!!
                                val needsFolder = original.contains("{plugin:root}") || original.contains("{plugin:data}")
                                require(!needsFolder || folder != null) { "Bundled MCP files require a real folder. Install this plugin through the manager." }
                                return if (!needsFolder) v else v.copy(json = JsonPrimitive(original.replace("{plugin:root}", folder!!)
                                    .replace("{plugin:data}", pluginData(name))))
                            }
                            val fields = v.fields.mapValues { bundleVariables(it.value) }
                            val items = v.items.map(::bundleVariables)
                            return v.copy(json = when (v.json) { is JsonObject -> JsonObject(fields.mapValues { it.value.json }); is JsonArray -> JsonArray(items.map { it.json }); else -> v.json }, fields = fields, items = items)
                        }
                        stagedServers[qualified] = bundleVariables(value)
                        stagedServerSources[qualified] = ConfigServerSource(key, name, configurationSource)
                    }
                }
                manifest["lsp"]?.fields.orEmpty().forEach { (key, server) ->
                    val command = server["command"]?.string()?.takeIf(String::isNotBlank) ?: run { stagedWarnings += "Plugin $name: language server $key has no command."; return@forEach }
                    fun local(text: String) = text.replace("{plugin:root}", folder.orEmpty()).replace("{plugin:data}", pluginData(name))
                    stagedLanguageServers += ConfigLanguageServer("$name:$key", name, local(command), server["args"]?.strings().orEmpty().map(::local),
                        server["extensions"]?.fields.orEmpty().mapValues { it.value.string().orEmpty() }.filterValues(String::isNotBlank),
                        server["env"]?.fields.orEmpty().mapValues { local(expand(it.value)) }, folder.orEmpty(),
                        server["initializationOptions"]?.json as? JsonObject)
                }
                if (folder != null) manifest["bin"]?.strings().orEmpty().forEach { dir ->
                    baseFs.shellRoot?.resolve(base)?.resolve(dir)?.canonicalFile?.takeIf { it.isDirectory && it.path.startsWith(folder) }?.let { stagedBins += it.path }
                }
                val hooks = manifest["hooks"]?.strings().orEmpty().toSet()
                require(hooks.all { it in HookEvents.all }) { "$manifestPath has an unsupported hook." }
                val commands = manifest["commandHooks"]?.items.orEmpty().mapNotNull { hook ->
                    val source = hook["source"]?.string().orEmpty()
                    val event = HookEvents.of(hook["event"]?.string().orEmpty(), source) ?: return@mapNotNull null
                    val type = hook["type"]?.string()?.takeIf { it in setOf("command", "prompt", "agent") } ?: "command"
                    val body = (if (type == "command") hook["command"] else hook["prompt"] ?: hook["command"])?.string()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    ConfigCommandHook(event, body, hook["matcher"]?.string().orEmpty(),
                        hook["timeout"]?.json?.jsonPrimitive?.intOrNull?.coerceIn(1, 3600) ?: if (type == "command") 60 else 30, source, type)
                }
                if (hooks.isNotEmpty() || commands.isNotEmpty()) {
                    val script = if (hooks.isEmpty()) "" else path(manifest["entry"] ?: error("$manifestPath needs an entry for hooks.")).let { read(it.first, it.second, MAX_FILE) }
                    // Command hooks need the plugin's real folder; prompt hooks do not.
                    stagedPlugin = ConfigPlugin(name, script, hooks, commands.filter { folder != null || it.type != "command" }, folder.orEmpty(), options)
                } else if (options.isNotEmpty() || stagedBins.isNotEmpty()) stagedPlugin = ConfigPlugin(name, "", emptySet(), emptyList(), folder.orEmpty(), options)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                sources.remove(id)
                val reason = e.message ?: e.javaClass.simpleName
                pluginProblems[name] = reason
                warnings += "Plugin $name was skipped: $reason"
                return
            }
            loadedPlugins += name
            skillFiles.putAll(stagedSkills)
            pluginInstructions += stagedInstructions
            stagedAgents.forEach { (key, agent) -> pluginAgents.putIfAbsent(key, agent) }
            pluginServers.putAll(stagedServers)
            pluginServerSources.putAll(stagedServerSources)
            languageServers += stagedLanguageServers
            pluginBins += stagedBins
            warnings += stagedWarnings
            if (stagedWarnings.isNotEmpty()) pluginProblems.putIfAbsent(name, stagedWarnings.joinToString("\n"))
            stagedPlugin?.let(plugins::add)
        }
        pluginSettings?.get("paths")?.items.orEmpty().forEach { v ->
            runCatching {
                val (fs, base) = path(v); val dir = fs.resolve(base)
                require(dir.exists && dir.isDirectory) { "Plugin folder is missing: $base" }
                if (fs.resolve("$base/plugin.json").exists) plugin(fs, base, v.source)
                else dir.list().filter { it.isDirectory }.sortedBy { it.name }.forEach { plugin(fs, "$base/${it.name}", v.source) }
            }.onFailure { if (it is CancellationException) throw it; warnings += "${v.source}: ${it.message}" }
        }
        pluginSettings?.get("npm")?.items.orEmpty().forEach { v ->
            runCatching {
                val name = v.string().orEmpty()
                require(Regex("(@[a-z0-9_-]+/)?[a-z0-9_-]+").matches(name)) { "plugins.npm must name an installed package, without a version or URL." }
                val (fs, base) = path(v, "node_modules/$name")
                require(fs.resolve("$base/plugin.json").exists) { "Plugin package $name is not installed. Install it in the config folder's node_modules, then reload." }
                plugin(fs, base, v.source)
            }.onFailure { if (it is CancellationException) throw it; warnings += "${v.source}: ${it.message}" }
        }
        root["skills"]?.get("paths")?.items.orEmpty().forEach { v -> path(v).let { skillDir(it.first, it.second) } }
        val standardSkills = primary.resolve(".harness/skills")
        if (standardSkills.exists && standardSkills.isDirectory) skillDir(primary, ".harness/skills")
        val agents = pluginAgents + root["agents"]?.fields.orEmpty().filterValues { it.json !is JsonNull }.mapValues { (name, v) ->
            ConfigAgent(name, v["description"]?.string().orEmpty(), instructionFiles(v["instructions"]?.items.orEmpty()),
                v["tools"]?.fields.orEmpty().mapValues { it.value.json.jsonPrimitive.boolean })
        }
        // Project instructions come first. Whatever does not fit the prompt budget stays readable as a reference.
        val inPrompt = mutableListOf<ConfigFile>()
        val overflow = mutableListOf<ConfigFile>()
        var used = 0
        (rootInstructions + pluginInstructions).distinctBy { it.source to it.path }.forEach { file ->
            if (used + file.text.length <= INSTRUCTION_BUDGET || inPrompt.isEmpty() && file.fs == null) { inPrompt += file; used += file.text.length }
            else overflow += file
        }
        val instructions = (pluginInstructions + rootInstructions).distinctBy { it.source to it.path }.filter { it in inPrompt }
        val overflowRefs = overflow.mapNotNull { file -> file.fs?.let { ConfigReference("Instructions: ${file.path}", file.path, file.source, it) } }
        if (overflowRefs.isNotEmpty()) warnings += "${overflowRefs.size} instruction file(s) did not fit the prompt and are read on demand as references."
        val serverSources = linkedMapOf<String, ConfigServerSource>()
        val servers = (pluginServers + root["mcp"]?.fields.orEmpty()).filterValues { it.json !is JsonNull }.mapNotNull { (name, v) ->
            val fromPlugin = v.source.startsWith("Plugin/")
            try {
            serverSources[name] = pluginServerSources[name]?.takeIf { fromPlugin }
                ?: ConfigServerSource(name, source = v.source)
            val enabled = v["enabled"]?.json?.jsonPrimitive?.booleanOrNull ?: true
            fun resolved(value: ConfigValue): String = if (enabled) expand(value) else value.string().orEmpty()
            val command = v["command"]
            val words = if (command?.json is JsonArray) command.items.map(::resolved) else command?.let { listOf(resolved(it)) }.orEmpty()
            val remote = v["url"] != null
            val url = v["url"]?.let(::resolved)
            if (remote && enabled) {
                val uri = runCatching { java.net.URI(url) }.getOrNull()
                require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                    "MCP server '$name' needs a valid http:// or https:// URL after variable substitution."
                }
            }
            val type = if (remote) (v["type"]?.string()?.takeIf { it in setOf("http", "sse") } ?: "http") else "stdio"
            McpServerConfig(name, words.firstOrNull().orEmpty(), words.drop(1) + v["args"]?.items.orEmpty().map(::resolved),
                v["env"]?.fields.orEmpty().mapValues { resolved(it.value) }, enabled,
                type, url, v["headers"]?.fields.orEmpty().mapValues { resolved(it.value) }, deviceLocal = fromPlugin)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!fromPlugin) throw e
                // One plugin server with a missing setting must not stop the project's other tools.
                val origin = pluginServerSources[name]
                val reason = "MCP server ${origin?.name ?: name}: ${e.message}"
                warnings += "Plugin ${origin?.plugin}: $reason"
                origin?.plugin?.let { pluginProblems[it] = listOfNotNull(pluginProblems[it], reason).joinToString("\n") }
                serverSources.remove(name)
                null
            }
        }
        val mem = root["memory"]
        return HarnessConfig(root, sources.values.filter { it.id != "Override base" }, instructions, refs + overflowRefs, skillFiles,
            root["skills"]?.get("enable")?.strings().orEmpty(), root["skills"]?.get("disable")?.strings().orEmpty(),
            root["skills"]?.get("auto_load")?.strings().orEmpty(), servers,
            root["permissions"]?.fields.orEmpty().filterValues { it.json !is JsonNull }.mapValues { it.value.string()!! },
            agents, root["default_agent"]?.string() ?: "default",
            ConfigMemory(mem?.get("file")?.string() ?: ".harness/memory.md", mem?.get("topics_dir")?.string() ?: ".harness/memory",
                mem?.get("auto_recall")?.json?.jsonPrimitive?.booleanOrNull ?: true, mem?.get("max_core_lines")?.json?.jsonPrimitive?.intOrNull ?: 120),
            plugins, warnings.toList(), sources.values.joinToString("\n") { "${it.id}\n${it.text}" } + plugins.joinToString { it.script + it.commands.joinToString("\n") { c -> "${c.event} ${c.type} ${c.matcher} ${c.command}" } } +
                languageServers.joinToString("\n") { "${it.name} ${it.command} ${it.args}" } + pluginBins.joinToString("\n"),
            sensitiveValues.toSet(), serverSources, pluginProblems, languageServers, pluginBins)
    }
}
