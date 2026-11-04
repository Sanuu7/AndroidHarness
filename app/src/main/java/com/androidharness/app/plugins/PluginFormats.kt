package com.androidharness.app.plugins

import com.androidharness.app.skills.ParsedSkill
import com.androidharness.app.skills.SkillParser
import kotlinx.serialization.json.*
import java.io.File

/** The bundle only works by running its own installer command, which needs the user's approval first. */
class InstallerNeeded(val command: String, message: String) : IllegalStateException(message)

/**
 * Turns any supported bundle layout into one AndroidHarness plugin.json, keeping the original files.
 * Understood: AndroidHarness bundles, Claude Code plugins and marketplaces (also the Codex and Cursor variants),
 * Gemini CLI extensions, single skills and skill collections, slash commands, subagents, command hooks,
 * .mcp.json files, editor rule files and npm MCP server packages.
 */
object PluginAdapter {
    data class Result(val root: File, val choices: List<PluginListing> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val manifestDirs = listOf(".claude-plugin", ".codex-plugin", ".cursor-plugin", ".agents-plugin")
    private val claudeKeys = setOf("commands", "agents", "mcpServers", "outputStyles", "lspServers", "keywords", "license", "repository")
    private val hookEvents = mapOf("SessionStart" to "session.start", "UserPromptSubmit" to "message.received",
        "PreToolUse" to "tool.before", "PostToolUse" to "tool.after", "Stop" to "session.end", "SubagentStop" to "session.end",
        "SessionEnd" to "session.end", "BeforeTool" to "tool.before", "AfterTool" to "tool.after", "BeforeAgent" to "message.received")

    fun sanitizeName(raw: String, fallback: String = "plugin"): String {
        val cleaned = raw.substringAfterLast('/').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64).trim('-')
        return cleaned.ifBlank { fallback }
    }
    private fun read(file: File): JsonObject? = runCatching {
        if (!file.isFile || file.length() > 1024 * 1024) return null
        json.parseToJsonElement(file.readText()).jsonObject
    }.getOrNull()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.paths(): List<String> = when (this) {
        is JsonPrimitive -> listOfNotNull(str())
        is JsonArray -> mapNotNull { it.str() }
        else -> emptyList()
    }
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath.ifBlank { "." }
    private fun clean(path: String) = path.replace('\\', '/').removePrefix("./").trimEnd('/').ifBlank { "." }
    private fun inside(root: File, path: String): File? = runCatching { PluginManifest.path(root, clean(path)) }.getOrNull()

    /** Skips archive envelopes (GitHub's owner-repo-sha/, npm's package/) until something recognizable appears. */
    fun locate(bundle: File, subdirectory: String): File {
        // Android's app-data aliases must use the same spelling as canonical component paths.
        var current = bundle.canonicalFile
        fun recognizable(dir: File) = dir.resolve("plugin.json").isFile || dir.resolve("SKILL.md").isFile || dir.resolve("package.json").isFile ||
            dir.resolve("gemini-extension.json").isFile || manifestDirs.any { dir.resolve(it).isDirectory }
        repeat(4) {
            if (recognizable(current)) return@repeat
            val children = current.listFiles().orEmpty().filterNot { it.name in PluginArchives.SKIPPED }
            if (children.size == 1 && children.single().isDirectory) current = children.single()
        }
        if (subdirectory.isNotBlank() && clean(subdirectory) != ".") {
            current = PluginManifest.path(current, clean(subdirectory))
            require(current.isDirectory) { "Plugin subfolder was not found: $subdirectory" }
        }
        return current
    }

    fun adapt(bundle: File, origin: PluginOrigin, fallbackName: String): Result {
        val root = locate(bundle, origin.subdirectory)
        val entry = origin.entry.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (entry == null) {
            val marketplace = read(root.resolve(".claude-plugin/marketplace.json"))
            if (marketplace != null) {
                val entries = PluginMarketplaces.entries(marketplace, origin, marketplace["name"].str().orEmpty())
                val ownManifest = manifestDirs.any { root.resolve("$it/plugin.json").isFile } || root.resolve("plugin.json").isFile
                val local = entries.singleOrNull()?.takeIf { it.origin.kind == origin.kind && it.origin.target == origin.target }
                when {
                    local != null -> return adapt(bundle, local.origin, fallbackName)
                    entries.size > 1 || (entries.size == 1 && !ownManifest) -> return Result(root, entries)
                }
            }
        }
        val plan = Plan(root)
        plan.name = origin.name
        val nativeManifest = read(root.resolve("plugin.json"))?.takeIf(::isNative)
        if (nativeManifest != null) plan.native(nativeManifest)
        else {
            val claude = manifestDirs.firstNotNullOfOrNull { read(root.resolve("$it/plugin.json"))?.let { m -> it to m } }
                ?: read(root.resolve("plugin.json"))?.let { "plugin.json" to it }
            claude?.let { (where, manifest) -> plan.claude(manifest, if (where == "plugin.json") "Claude Code plugin" else formatName(where)) }
            entry?.let { plan.claude(it, plan.format.ifBlank { "Claude Code marketplace plugin" }, fromEntry = true) }
            if (claude == null && entry == null) read(root.resolve("gemini-extension.json"))?.let(plan::gemini)
            if (claude != null || entry != null) plan.claudeDefaults()
            if (plan.empty()) plan.generic()
            if (plan.empty()) plan.mcpPackage()
            if (plan.empty()) plan.rules()
        }
        if (plan.empty()) {
            val pkg = read(root.resolve("package.json"))
            val name = pkg?.get("name").str()
            if (pkg?.get("bin") != null && name != null) throw InstallerNeeded("npx -y $name",
                "$name does not ship skills or plugin files directly. It installs them by running its own command.")
            error("No skills, commands, agents, rules, hooks or MCP servers were found in this bundle.")
        }
        plan.write(fallbackName)
        return Result(root)
    }
    private fun formatName(dir: String) = when (dir) {
        ".codex-plugin" -> "Codex plugin"; ".cursor-plugin" -> "Cursor plugin"; else -> "Claude Code plugin"
    }
    /** An AndroidHarness manifest declares its parts as arrays and never uses Claude-only keys. */
    private fun isNative(manifest: JsonObject): Boolean =
        manifest.keys.none { it in claudeKeys } && manifest["skills"].let { it == null || it is JsonArray } &&
            manifest["hooks"].let { it == null || it is JsonArray } &&
            listOf("skills", "instructions", "mcp", "hooks", "agents", "commandHooks").any { it in manifest }

    private class Plan(val root: File) {
        var name = ""; var version = ""; var description = ""; var author = ""; var homepage = ""; var format = ""
        val skills = linkedSetOf<String>()
        val instructions = mutableListOf<String>()
        val mcp = linkedMapOf<String, JsonObject>()
        val agents = linkedMapOf<String, JsonObject>()
        val commandHooks = mutableListOf<JsonObject>()
        val notes = mutableListOf<String>()
        var hooks: JsonElement? = null
        var entry: String? = null
        val taken = mutableSetOf<String>()
        private val generated get() = root.resolve(".harness").apply { mkdirs() }

        fun empty() = skills.isEmpty() && instructions.isEmpty() && mcp.isEmpty() && agents.isEmpty() && commandHooks.isEmpty() && hooks == null

        fun meta(manifest: JsonObject) {
            manifest["name"].str()?.takeIf { name.isBlank() }?.let { name = it }
            manifest["version"].str()?.takeIf { version.isBlank() }?.let { version = it }
            manifest["description"].str()?.takeIf { description.isBlank() }?.let { description = it }
            if (author.isBlank()) author = manifest["author"].let { it.str() ?: (it as? JsonObject)?.get("name").str() }.orEmpty()
            if (homepage.isBlank()) homepage = (manifest["homepage"].str() ?: manifest["repository"].let { it.str() ?: (it as? JsonObject)?.get("url").str() }).orEmpty()
        }
        fun native(manifest: JsonObject) {
            format = "AndroidHarness"
            meta(manifest)
            manifest["skills"].paths().forEach { addSkills(it) }
            instructions += manifest["instructions"].paths().map(::clean)
            (manifest["mcp"] as? JsonObject)?.forEach { (key, value) -> (value as? JsonObject)?.let { mcp[key] = it } }
            (manifest["agents"] as? JsonObject)?.forEach { (key, value) -> (value as? JsonObject)?.let { agents[key] = it } }
            (manifest["commandHooks"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(commandHooks::add) }
            manifest["hooks"]?.takeIf { it is JsonArray && it.isNotEmpty() }?.let { hooks = it; entry = manifest["entry"].str() }
        }
        private val declared = mutableSetOf<String>()
        fun claude(manifest: JsonObject, label: String, fromEntry: Boolean = false) {
            format = label
            if (fromEntry) manifest["name"].str()?.let { name = it }
            meta(manifest)
            manifest["skills"].paths().forEach { declared += "skills"; addSkills(it) }
            manifest["commands"].paths().forEach { declared += "commands"; addCommands(it) }
            manifest["agents"].paths().forEach { declared += "agents"; addAgents(it) }
            when (val hooks = manifest["hooks"]) {
                is JsonObject -> { declared += "hooks"; addHooks(hooks) }
                is JsonPrimitive, is JsonArray -> hooks.paths().forEach { declared += "hooks"; read(inside(root, it) ?: return@forEach)?.let(::addHooks) }
                else -> Unit
            }
            when (val servers = manifest["mcpServers"]) {
                is JsonObject -> { declared += "mcp"; addServers(servers) }
                is JsonPrimitive, is JsonArray -> servers.paths().forEach { declared += "mcp"; read(inside(root, it) ?: return@forEach)?.let(::addServers) }
                else -> Unit
            }
            if (manifest["outputStyles"] != null) notes += "Output styles are not used on Android."
            if (manifest["lspServers"] != null) notes += "Language servers are not used on Android."
        }
        /** Conventional folders apply unless the manifest pointed that component somewhere else. */
        fun claudeDefaults() {
            if ("skills" !in declared) root.resolve("skills").takeIf { it.isDirectory }?.let { addSkills("skills") }
            if ("commands" !in declared) root.resolve("commands").takeIf { it.isDirectory }?.let { addCommands("commands") }
            if ("agents" !in declared) root.resolve("agents").takeIf { it.isDirectory }?.let { addAgents("agents") }
            if ("hooks" !in declared) read(root.resolve("hooks/hooks.json"))?.let(::addHooks)
            if ("mcp" !in declared) read(root.resolve(".mcp.json"))?.let(::addServers)
        }
        fun gemini(manifest: JsonObject) {
            format = "Gemini CLI extension"
            meta(manifest)
            (manifest["mcpServers"] as? JsonObject)?.let(::addServers)
            val context = manifest["contextFileName"].paths().ifEmpty { listOf("GEMINI.md") }
            addRules(context.mapNotNull { inside(root, it)?.takeIf(File::isFile) })
            root.resolve("commands").takeIf { it.isDirectory }?.let { addCommands("commands") }
            if (manifest["excludeTools"] != null) notes += "Gemini excludeTools settings are not applied."
        }
        /** Skill collections, project-level Claude/OpenCode folders and loose rule files in any repository. */
        fun generic() {
            format = format.ifBlank { "Skill collection" }
            val found = root.walkTopDown().onEnter { it.name !in PluginArchives.SKIPPED && it.relativeTo(root).invariantSeparatorsPath.count { c -> c == '/' } < 8 }
                .filter { it.isFile && it.name == "SKILL.md" }.take(1000).map { it.parentFile }
                .sortedWith(compareBy({ relative(root, it).count { c -> c == '/' } }, { relative(root, it) })).toList()
            found.forEach { dir -> addSkills(relative(root, dir)) }
            listOf(".claude/commands", ".opencode/command", ".opencode/commands", ".gemini/commands").forEach { dir ->
                if (root.resolve(dir).isDirectory) addCommands(dir)
            }
            listOf(".claude/agents", ".opencode/agent", ".opencode/agents").forEach { dir -> if (root.resolve(dir).isDirectory) addAgents(dir) }
            read(root.resolve(".mcp.json"))?.let(::addServers)
            read(root.resolve(".claude/settings.json"))?.let { (it["hooks"] as? JsonObject)?.let { hooks -> addHooks(buildJsonObject { put("hooks", hooks) }) } }
        }
        /** A package whose only job is to be an MCP server becomes one npx-launched server. */
        fun mcpPackage() {
            val pkg = read(root.resolve("package.json")) ?: return
            val packageName = pkg["name"].str() ?: return
            val keywords = pkg["keywords"].paths().map { it.lowercase() }
            val deps = (pkg["dependencies"] as? JsonObject)?.keys.orEmpty()
            val isServer = pkg["bin"] != null && ("@modelcontextprotocol/sdk" in deps || keywords.any { "mcp" in it || "modelcontextprotocol" in it } ||
                Regex("(^|[-/@])mcp([-/]|$)").containsMatchIn(packageName))
            if (!isServer) return
            meta(pkg)
            format = "npm MCP server"
            val version = pkg["version"].str()?.let { "@$it" }.orEmpty()
            addServers(buildJsonObject { put(sanitizeName(packageName, "server"), buildJsonObject {
                put("command", "npx"); put("args", buildJsonArray { add("-y"); add(packageName + version) })
            }) })
            notes += "The server's own dependencies download the first time it starts."
        }
        fun rules() {
            val candidates = listOf("AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules", ".windsurfrules", ".clinerules", ".github/copilot-instructions.md")
                .map(root::resolve).filter { it.isFile } +
                listOf(".cursor/rules", ".windsurf/rules", ".clinerules", ".continue/rules", ".kiro/steering").map(root::resolve).filter { it.isDirectory }
                    .flatMap { dir -> dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension in setOf("md", "mdc", "txt") }.sortedBy { it.path }.toList() }
            if (candidates.isNotEmpty()) { format = format.ifBlank { "Rules" }; addRules(candidates) }
        }

        fun addSkills(path: String) {
            val dir = inside(root, path)?.takeIf { it.isDirectory } ?: run { notes += "Skill folder not found: $path"; return }
            val folders = if (dir.resolve("SKILL.md").isFile) listOf(dir) else dir.listFiles().orEmpty().filter { it.isDirectory && it.resolve("SKILL.md").isFile }.sortedBy { it.name }
            val accepted = folders.filter { normalizeSkill(it) }
            if (accepted.isEmpty()) return
            if (dir.resolve("SKILL.md").isFile || accepted.size == folders.size) skills += relative(root, dir)
            else accepted.forEach { skills += relative(root, it) }
        }
        /** Rewrites a SKILL.md only when its frontmatter would not load; duplicates are switched off. */
        private fun normalizeSkill(folder: File): Boolean {
            val file = folder.resolve("SKILL.md")
            if (file.length() > 256 * 1024) { notes += "Skipped ${relative(root, folder)}: SKILL.md is larger than 256 KiB."; return false }
            val raw = runCatching { file.readText() }.getOrElse { return false }
            val parsed = runCatching { SkillParser.parse(raw) }.getOrNull()
            val skillName = if (parsed != null && parsed.name !in taken) parsed.name else {
                val text = raw.replace("\r\n", "\n").trimStart('\uFEFF')
                val (front, body) = splitFrontmatter(text)
                val fields = SkillParser.parseSimpleYaml(front)
                var candidate = sanitizeName(fields["name"] ?: folder.name, "skill").take(ParsedSkill.MAX_NAME)
                if (candidate in taken) {
                    if (parsed != null) { disable(file, "Skipped duplicate skill '${parsed.name}' in ${relative(root, folder)}."); return false }
                    candidate = sanitizeName("$candidate-${folder.parentFile?.name.orEmpty()}").take(ParsedSkill.MAX_NAME)
                    if (candidate in taken) { disable(file, "Skipped duplicate skill in ${relative(root, folder)}."); return false }
                }
                val describe = (fields["description"] ?: firstParagraph(body)).ifBlank { "Instructions from ${folder.name}." }
                val content = body.ifBlank { describe }
                file.writeText(frontmatter(candidate, describe, fields["category"]) + content.trim() + "\n")
                runCatching { SkillParser.parse(file.readText()) }.getOrElse { disable(file, "Skipped ${relative(root, folder)}: ${it.message}"); return false }
                candidate
            }
            taken += skillName
            return true
        }
        private fun disable(file: File, note: String) { notes += note; file.renameTo(file.resolveSibling("SKILL.disabled.md")) }

        fun addCommands(path: String) {
            val dir = inside(root, path) ?: return
            val files = if (dir.isFile) listOf(dir) else dir.walkTopDown().maxDepth(4).filter { it.isFile && it.extension in setOf("md", "toml") }.sortedBy { it.path }.toList()
            files.take(200).forEach { file ->
                val namespaced = if (dir.isFile) file.nameWithoutExtension else file.relativeTo(dir).invariantSeparatorsPath.substringBeforeLast('.').replace('/', '-')
                var skill = sanitizeName(namespaced, "command").take(ParsedSkill.MAX_NAME)
                if (skill in taken) skill = sanitizeName("$skill-command").take(ParsedSkill.MAX_NAME)
                if (skill in taken) return@forEach
                val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.length <= 200_000 } ?: return@forEach
                val (fields, body) = if (file.extension == "toml") tomlCommand(text) else splitFrontmatter(text.replace("\r\n", "\n")).let { SkillParser.parseSimpleYaml(it.first) to it.second }
                if (body.isBlank()) return@forEach
                val hint = fields["argument-hint"]?.let { " Arguments: $it." }.orEmpty()
                val describe = (fields["description"] ?: firstParagraph(body)).ifBlank { "The /$skill command." } + hint
                val folder = generated.resolve("commands/$skill").apply { mkdirs() }
                folder.resolve("SKILL.md").writeText(frontmatter(skill, describe, "commands") +
                    "This skill is the /$skill command. When the user runs it, treat the text after the command as \$ARGUMENTS " +
                    "(also {{args}}, with \$1, \$2 for single words). Lines starting with ! name shell commands to run first; " +
                    "@path names a file to read.\n\n" + body.trim() + "\n")
                taken += skill
            }
            if (generated.resolve("commands").listFiles().orEmpty().isNotEmpty()) skills += relative(root, generated.resolve("commands"))
        }
        fun addAgents(path: String) {
            val dir = inside(root, path) ?: return
            val files = if (dir.isFile) listOf(dir) else dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "md" }.sortedBy { it.path }.toList()
            files.take(64).forEach { file ->
                val (front, body) = splitFrontmatter(runCatching { file.readText() }.getOrDefault("").replace("\r\n", "\n"))
                if (body.isBlank()) return@forEach
                val fields = SkillParser.parseSimpleYaml(front)
                val agent = (fields["name"] ?: file.nameWithoutExtension).replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-').take(48)
                if (agent.isBlank() || agent in agents) return@forEach
                val target = generated.resolve("agents/$agent.md").apply { parentFile!!.mkdirs() }
                target.writeText(body.trim().take(60_000) + "\n")
                agents[agent] = buildJsonObject {
                    put("description", fields["description"].orEmpty().take(1000))
                    put("instructions", buildJsonArray { add(relative(root, target)) })
                    fields["tools"]?.let { listed -> put("tools", agentTools(listed)) }
                }
            }
        }
        /** Claude tool lists become this app's tool groups; a group the agent never listed is denied. */
        private fun agentTools(listed: String): JsonObject {
            val tools = listed.split(',', ' ').map { it.trim().substringBefore('(') }.filter(String::isNotEmpty).toSet()
            return buildJsonObject {
                if (tools.none { it in setOf("Write", "Edit", "MultiEdit", "NotebookEdit") }) { put("edit", false); put("delete", false) }
                if ("Bash" !in tools) put("shell", false)
                if (tools.none { it in setOf("WebFetch", "WebSearch") }) put("network", false)
            }
        }
        fun addHooks(document: JsonObject) {
            val events = (document["hooks"] as? JsonObject) ?: document
            events.forEach { (event, groups) ->
                val mapped = hookEvents[event] ?: run { notes += "Hook event $event is not available on Android."; return@forEach }
                (groups as? JsonArray)?.forEach { group ->
                    val matcher = (group as? JsonObject)?.get("matcher").str().orEmpty()
                    (group as? JsonObject)?.get("hooks")?.let { it as? JsonArray }?.forEach hook@{ hook ->
                        val obj = hook as? JsonObject ?: return@hook
                        val command = obj["command"].str()
                        if (obj["type"].str().let { it != null && it != "command" } || command.isNullOrBlank()) {
                            notes += "Only command hooks run on Android; a ${obj["type"].str() ?: "different"} hook for $event was skipped."; return@hook
                        }
                        commandHooks += buildJsonObject {
                            put("event", mapped); put("source", event); put("command", command)
                            if (matcher.isNotBlank()) put("matcher", matcher)
                            obj["timeout"]?.jsonPrimitive?.intOrNull?.let { put("timeout", it.coerceIn(1, 600)) }
                        }
                    }
                }
            }
        }
        fun addServers(document: JsonObject) {
            val servers = (document["mcpServers"] as? JsonObject) ?: (document["servers"] as? JsonObject) ?: document
            servers.forEach { (key, value) ->
                val server = value as? JsonObject ?: return@forEach
                val converted = PluginMcp.convert(server) { notes += "$key: $it" } ?: return@forEach
                var id = key.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-', '_').take(32).ifBlank { "server" }
                if (!id.first().isLetterOrDigit()) id = "s$id".take(32)
                while (mcp.keys.any { it.lowercase().replace('-', '_') == id.lowercase().replace('-', '_') }) id = (id.take(29) + mcp.size).take(32)
                mcp[id] = converted
            }
        }
        /** Small rule files become instructions; anything larger becomes a skill so runs stay within budget. */
        fun addRules(files: List<File>) {
            val texts = files.mapNotNull { file ->
                val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                file to (if (file.extension == "mdc") splitFrontmatter(text.replace("\r\n", "\n")).second else text)
            }
            if (texts.isEmpty()) return
            val total = texts.sumOf { it.second.toByteArray().size }
            if (total <= 16_000) {
                texts.forEachIndexed { index, (file, text) ->
                    val target = if (file.extension == "mdc") generated.resolve("rules/$index-${file.nameWithoutExtension}.md").apply { parentFile!!.mkdirs(); writeText(text) } else file
                    instructions += relative(root, target)
                }
            } else {
                val skill = sanitizeName("${name.ifBlank { root.name }}-rules").take(ParsedSkill.MAX_NAME)
                val body = texts.joinToString("\n\n") { (file, text) -> "## ${relative(root, file)}\n\n${text.trim()}" }.take(250_000)
                generated.resolve("rules/$skill").apply { mkdirs() }.resolve("SKILL.md")
                    .writeText(frontmatter(skill, "Project rules and conventions from ${texts.joinToString { it.first.name }}. Load before changing code in this style.", "rules") + body + "\n")
                taken += skill
                skills += relative(root, generated.resolve("rules"))
            }
        }

        fun write(fallback: String) {
            val pluginName = sanitizeName(name.ifBlank { fallback }, sanitizeName(fallback))
            val manifest = buildJsonObject {
                put("name", pluginName); put("version", version.ifBlank { "0.0.0" }.take(80))
                put("description", description.take(4000)); put("format", format.ifBlank { "AndroidHarness" })
                if (author.isNotBlank()) put("author", author.take(200))
                if (homepage.isNotBlank()) put("homepage", homepage.take(500))
                if (skills.isNotEmpty()) put("skills", buildJsonArray { skills.forEach { add(it) } })
                val ownInstructions = instructions.distinct().filter { inside(root, it)?.isFile == true }
                if (ownInstructions.isNotEmpty()) put("instructions", buildJsonArray { ownInstructions.forEach { add(it) } })
                if (mcp.isNotEmpty()) put("mcp", JsonObject(mcp))
                if (agents.isNotEmpty()) put("agents", JsonObject(agents))
                if (commandHooks.isNotEmpty()) put("commandHooks", JsonArray(commandHooks.take(64)))
                hooks?.let { put("hooks", it); entry?.let { e -> put("entry", e) } }
                if (notes.isNotEmpty()) put("notes", buildJsonArray { notes.distinct().take(50).forEach { add(it.take(300)) } })
            }
            val target = root.resolve("plugin.json")
            if (target.isFile && format != "AndroidHarness") {
                var keep = root.resolve("plugin.original.json"); var n = 1
                while (keep.exists()) keep = root.resolve("plugin.original.${n++}.json")
                target.renameTo(keep)
            }
            target.writeText(manifest.toString())
        }
    }

    private fun splitFrontmatter(text: String): Pair<String, String> {
        val clean = text.trimStart('\uFEFF')
        if (!clean.startsWith("---")) return "" to clean
        val rest = clean.removePrefix("---")
        val close = rest.indexOf("\n---")
        if (close < 0) return "" to clean
        return rest.substring(0, close) to rest.substring(close + 4).substringAfter('\n', "")
    }
    private fun firstParagraph(body: String): String = body.lines().map { it.trim() }
        .dropWhile { it.isEmpty() || it.startsWith("#") || it.startsWith("<") }
        .takeWhile { it.isNotEmpty() }.joinToString(" ").take(500)
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "'").replace(Regex("\\s+"), " ").trim().take(1024) + "\""
    private fun frontmatter(name: String, description: String, category: String?) =
        "---\nname: $name\ndescription: ${quote(description)}\n${category?.let { "category: ${quote(it)}\n" }.orEmpty()}---\n\n"
    /** Gemini CLI commands: description = "..." and prompt = """...""" (or a single-line string). */
    private fun tomlCommand(text: String): Pair<Map<String, String>, String> {
        fun value(key: String): String? {
            Regex("(?m)^\\s*$key\\s*=\\s*(\"\"\"|''')([\\s\\S]*?)\\1").find(text)?.let { return it.groupValues[2] }
            return Regex("(?m)^\\s*$key\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")
        }
        return mapOf("description" to value("description").orEmpty()).filterValues(String::isNotBlank) to value("prompt").orEmpty()
    }
}

/** Claude, Gemini, VS Code and registry MCP entries in this app's harness.json shape. */
object PluginMcp {
    private val variable = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-[^}]*)?\\}|\\$([A-Z_][A-Z0-9_]*)")
    private val rootVariables = setOf("CLAUDE_PLUGIN_ROOT", "extensionPath", "PLUGIN_ROOT", "pluginRoot")
    fun text(value: String): String = variable.replace(value) { match ->
        val name = match.groupValues[1].ifBlank { match.groupValues[2] }
        if (name in rootVariables) "{plugin:root}" else "{env:$name}"
    }
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    fun convert(server: JsonObject, note: (String) -> Unit): JsonObject? {
        val url = (server["url"] ?: server["httpUrl"] ?: server["serverUrl"]).str()
        var command = server["command"].str()
        var args = (server["args"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
        if (command != null && args.isEmpty() && ' ' in command.trim()) {
            val words = command.trim().split(Regex("\\s+")); command = words.first(); args = words.drop(1)
        }
        when (command) {
            "bunx" -> { command = "npx"; args = listOf("-y") + args }
            "bun" -> if (args.firstOrNull() == "x") { command = "npx"; args = listOf("-y") + args.drop(1) }
            "pnpm", "yarn" -> if (args.firstOrNull() == "dlx") { command = "npx"; args = listOf("-y") + args.drop(1) }
            "docker", "podman" -> { note("needs Docker, which Android cannot run. The server was added switched off."); }
        }
        if (url == null && command.isNullOrBlank()) { note("has no command or URL and was skipped."); return null }
        if (server["cwd"] != null) note("its working folder setting is ignored.")
        return buildJsonObject {
            if (url != null) {
                put("type", if (server["type"].str() == "sse" || server["transport"].str() == "sse") "sse" else "http")
                put("url", text(url))
                (server["headers"] as? JsonObject)?.let { headers -> put("headers", JsonObject(headers.mapValues { JsonPrimitive(text(it.value.str().orEmpty())) })) }
            } else {
                put("command", text(command!!))
                if (args.isNotEmpty()) put("args", JsonArray(args.map { JsonPrimitive(text(it)) }))
            }
            (server["env"] as? JsonObject)?.let { env -> put("env", JsonObject(env.mapValues { JsonPrimitive(text(it.value.str().orEmpty())) })) }
            if (command in setOf("docker", "podman") || server["disabled"]?.jsonPrimitive?.booleanOrNull == true) put("enabled", false)
        }
    }
}
