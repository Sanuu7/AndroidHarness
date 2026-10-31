package com.androidharness.app.config

import com.androidharness.app.skills.SkillStore
import com.androidharness.app.tools.mcp.McpServerConfig
import com.androidharness.app.workspace.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

data class ConfigSource(val id: String, val text: String, val fs: WorkspaceFs, val directory: String = "")
data class ConfigFile(val source: String, val path: String, val text: String)
data class ConfigReference(val title: String, val path: String, val source: String, val fs: WorkspaceFs)
data class ConfigAgent(val name: String, val description: String, val instructions: List<ConfigFile>, val tools: Map<String, Boolean>)
data class ConfigPlugin(val name: String, val script: String, val hooks: Set<String>)
data class ConfigMemory(val file: String = ".harness/memory.md", val topics: String = ".harness/memory",
    val autoRecall: Boolean = true, val maxLines: Int = 120)

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
        if (agents.isNotEmpty()) append("\nAvailable named Code agents for task(agent=...): " + agents.keys.joinToString(", ") + "\n")
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
) {
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
        require(readBytes <= 2 * 1024 * 1024) { "Configuration files exceed the 2 MiB total limit." }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun source(fs: WorkspaceFs, path: String, id: String): ConfigValue {
        val text = read(fs, path)
        val src = ConfigSource(id, text, fs, path.substringBeforeLast('/', ""))
        sources[id] = src
        return ConfigDocument.parse(text, id, path.endsWith(".jsonc"))
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
            .map { (fs, file, src) -> ConfigFile(src, file, read(fs, file, 64 * 1024)) }
            .also { list -> require(list.sumOf { it.text.length } <= 64_000) { "Instruction files exceed 64,000 characters. Move background documents to references." } }
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
        var merged: ConfigValue? = home?.let { discover(it, "config/", "Global") }
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
        val instructions = instructionFiles(root["instructions"]?.items.orEmpty(), automatic)
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
        fun skillDir(fs: WorkspaceFs, directory: String) {
            if (!scannedSkillDirs.add(fs.displayPath to directory)) return
            val dir = fs.resolve(directory)
            require(dir.exists && dir.isDirectory) { "Skill folder is missing: $directory" }
            require(dir.list().size <= 128) { "A skill folder may have at most 128 entries." }
            dir.list().filter { it.isDirectory }.sortedBy { it.name }.forEach { child ->
                val base = "$directory/${child.name}"; val md = "$base/SKILL.md"
                if (fs.resolve(md).let { it.exists && it.isFile }) {
                    val content = read(fs, md, 64 * 1024)
                    val parsed = com.androidharness.app.skills.SkillParser.parse(content)
                    val support = linkedMapOf<String, String>()
                    fun scan(folder: String, depth: Int) {
                        require(depth <= 8 && support.size < 128) { "Too many supporting skill files: $base" }
                        val n = fs.resolve("$base/$folder")
                        if (!n.exists || !n.isDirectory) return
                        n.list().sortedBy { it.name }.forEach { f ->
                            require(support.size < 128) { "Too many supporting skill files: $base" }
                            if (f.isFile) support["$folder/${f.name}"] = read(fs, "$base/$folder/${f.name}", 64 * 1024)
                            else if (f.isDirectory) scan("$folder/${f.name}", depth + 1)
                        }
                    }
                    SkillStore.SUPPORT_DIRS.forEach { scan(it, 0) }
                    skillFiles[parsed.name] = SkillStore.BundledSkill(base, content, support)
                }
            }
        }
        // Explicit paths supplement the conventional project directory, with deterministic precedence.
        root["skills"]?.get("paths")?.items.orEmpty().forEach { v -> path(v).let { skillDir(it.first, it.second) } }
        val plugins = mutableListOf<ConfigPlugin>()
        val pluginSettings = root["plugins"]
        fun plugin(baseFs: WorkspaceFs, base: String) {
            val manifestPath = "$base/plugin.json"
            if (!baseFs.resolve(manifestPath).exists) return
            val id = "Plugin/$base"
            val manifest = source(baseFs, manifestPath, id)
            require(manifest.fields.keys.all { it in setOf("name", "version", "entry", "hooks", "skills") }) { "$manifestPath has an unknown property." }
            require(manifest["hooks"] == null || manifest["hooks"]!!.json is JsonArray && manifest["hooks"]!!.items.all { it.string() != null }) { "$manifestPath hooks must be an array of event names." }
            require(manifest["skills"] == null || manifest["skills"]!!.json is JsonArray && manifest["skills"]!!.items.all { it.string() != null }) { "$manifestPath skills must be an array of paths." }
            val name = manifest["name"]?.string() ?: error("$manifestPath needs a name.")
            val enable = pluginSettings?.get("enable")?.strings().orEmpty()
            val disable = pluginSettings?.get("disable")?.strings().orEmpty()
            if (disable.any { ConfigGlob.matches(it, name) } || enable.isNotEmpty() && enable.none { ConfigGlob.matches(it, name) }) return
            manifest["skills"]?.items.orEmpty().forEach { v -> path(v).let { skillDir(it.first, it.second) } }
            val hooks = manifest["hooks"]?.strings().orEmpty().toSet()
            require(hooks.all { it in setOf("session.start", "message.received", "prompt.before", "tool.before", "tool.after", "session.end") }) { "$manifestPath has an unsupported hook." }
            if (hooks.isNotEmpty()) {
                val entry = manifest["entry"] ?: error("$manifestPath needs an entry for hooks.")
                val target = path(entry)
                plugins += ConfigPlugin(name, read(target.first, target.second, 64 * 1024), hooks)
            }
        }
        pluginSettings?.get("paths")?.items.orEmpty().forEach { v ->
            val (fs, base) = path(v); val dir = fs.resolve(base)
            require(dir.exists && dir.isDirectory) { "Plugin folder is missing: $base" }
            require(dir.list().size <= 32) { "At most 32 plugins per folder." }
            if (fs.resolve("$base/plugin.json").exists) plugin(fs, base)
            else dir.list().filter { it.isDirectory }.sortedBy { it.name }.forEach { plugin(fs, "$base/${it.name}") }
        }
        pluginSettings?.get("npm")?.items.orEmpty().forEach { v ->
            val name = v.string().orEmpty()
            require(Regex("(@[a-z0-9_-]+/)?[a-z0-9_-]+").matches(name)) { "plugins.npm must name an installed package, without a version or URL." }
            val (fs, base) = path(v, "node_modules/$name")
            require(fs.resolve("$base/plugin.json").exists) { "Plugin package $name is not installed. Install it in the config folder's node_modules, then reload." }
            plugin(fs, base)
        }
        require(plugins.map { it.name }.distinct().size == plugins.size && plugins.size <= 16) { "Plugin names must be unique; at most 16 executable plugins." }
        val standardSkills = primary.resolve(".harness/skills")
        if (standardSkills.exists && standardSkills.isDirectory) skillDir(primary, ".harness/skills")
        val agents = root["agents"]?.fields.orEmpty().filterValues { it.json !is JsonNull }.mapValues { (name, v) ->
            ConfigAgent(name, v["description"]?.string().orEmpty(), instructionFiles(v["instructions"]?.items.orEmpty()),
                v["tools"]?.fields.orEmpty().mapValues { it.value.json.jsonPrimitive.boolean })
        }
        val servers = root["mcp"]?.fields.orEmpty().filterValues { it.json !is JsonNull }.map { (name, v) ->
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
                type, url, v["headers"]?.fields.orEmpty().mapValues { resolved(it.value) })
        }
        val mem = root["memory"]
        return HarnessConfig(root, sources.values.filter { it.id != "Override base" }, instructions, refs, skillFiles,
            root["skills"]?.get("enable")?.strings().orEmpty(), root["skills"]?.get("disable")?.strings().orEmpty(),
            root["skills"]?.get("auto_load")?.strings().orEmpty(), servers,
            root["permissions"]?.fields.orEmpty().filterValues { it.json !is JsonNull }.mapValues { it.value.string()!! },
            agents, root["default_agent"]?.string() ?: "default",
            ConfigMemory(mem?.get("file")?.string() ?: ".harness/memory.md", mem?.get("topics_dir")?.string() ?: ".harness/memory",
                mem?.get("auto_recall")?.json?.jsonPrimitive?.booleanOrNull ?: true, mem?.get("max_core_lines")?.json?.jsonPrimitive?.intOrNull ?: 120),
            plugins, warnings.toList(), sources.values.joinToString("\n") { "${it.id}\n${it.text}" } + plugins.joinToString { it.script }, sensitiveValues.toSet())
    }
}
