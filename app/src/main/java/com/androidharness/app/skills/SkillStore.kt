package com.androidharness.app.skills

import java.io.File

/**
 * Three-layer skill tree. Project wins, then user, then bundled.
 * Disabled names stay on disk but drop out of the catalog and slash picker.
 */
class SkillStore(
    private val bundled: Map<String, BundledSkill>,
    private val userDir: File,
    private val projectDir: () -> File?,
    private val disabled: () -> Set<String>,
    private val optionalBundled: () -> Map<String, BundledSkill> = { emptyMap() },
    private val configuredSkills: Map<String, BundledSkill> = emptyMap(),
    private val configuration: com.androidharness.app.config.HarnessConfig? = null,
    /** The selected workspace folder, used for ${CLAUDE_PROJECT_DIR} in third-party skills. */
    private val workspaceRoot: File? = null,
) {
    private var frozen: List<Located>? = null
    fun freeze(): SkillStore {
        if (frozen == null) frozen = list().flatMap { findAll(it.name) }
        return this
    }
    /** A fixed project directory keeps one chat's catalog independent of the selected workspace. */
    fun forProject(directory: File?): SkillStore = SkillStore(bundled, userDir, { directory }, disabled, optionalBundled)
    fun configured(config: com.androidharness.app.config.HarnessConfig): SkillStore =
        if (!config.present) this else SkillStore(bundled, userDir, { null }, disabled, optionalBundled, config.skills, config, workspaceRoot)

    fun forWorkspace(workspace: com.androidharness.app.workspace.WorkspaceFs): SkillStore {
        if (configuration != null) return this
        val primary = (workspace as? com.androidharness.app.workspace.ChatWorkspaceFs)?.primary?.fs ?: workspace
        val root = primary.shellRoot
        return SkillStore(bundled, userDir, { root?.resolve(".harness/skills") }, disabled, optionalBundled, workspaceRoot = root)
    }

    /**
     * [directory] is the skill's real folder when it has one; its files are then listed and read on demand.
     * [pluginRoot] is the installed plugin the skill came from, for ${CLAUDE_PLUGIN_ROOT}.
     */
    data class BundledSkill(
        val relativeDir: String,
        val content: String,
        val files: Map<String, String> = emptyMap(),
        val directory: String? = null,
        val pluginRoot: String? = null,
        /** The name the skill is listed and loaded by when it differs from its own, such as plugin:skill. */
        val name: String? = null,
    )

    fun list(): List<SkillMeta> {
        frozen?.let { entries -> return entries.groupBy { it.meta.name }.values.mapNotNull(::pickWinner)
            .map { it.meta.copy(enabled = it.meta.name !in disabled() && configuration?.skillEnabled(it.meta.name) != false) }
            .sortedWith(compareBy({ it.category }, { it.name })) }
        val byName = linkedMapOf<String, SkillMeta>()
        scanBundled().forEach { byName[it.name] = it }
        scanDir(userDir, SkillSource.USER).forEach { byName[it.name] = it }
        projectDir()?.let { dir ->
            scanDir(dir, SkillSource.PROJECT).forEach { byName[it.name] = it }
        }
        configuredSkills.values.mapNotNull { parseLocated(it.content, SkillSource.PROJECT, it.relativeDir, it.files, it.directory, pluginRoot = it.pluginRoot, listedName = it.name)?.meta }
            .forEach { byName[it.name] = it }
        val off = disabled()
        return byName.values
            .map { it.copy(enabled = it.name !in off && configuration?.skillEnabled(it.name) != false) }
            .sortedWith(compareBy({ it.category }, { it.name }))
    }

    fun enabled(): List<SkillMeta> = list().filter { it.enabled }

    fun catalog(): String {
        val enabled = enabled().filter { it.modelInvocable }
        if (enabled.isEmpty()) return ""
        val grouped = enabled.groupBy { it.category }
        val index = buildString {
            for (category in grouped.keys.sorted()) {
                append("  ").append(category).append(":\n")
                for (skill in grouped.getValue(category).sortedBy { it.name }) {
                    append("    - ").append(skill.name).append(": ").append(skill.description).append('\n')
                }
            }
        }
        return buildSkillsPromptBlock(index)
    }

    /** Every listed name, plus the plain name of a plugin skill when no other skill uses it. */
    fun slashNames(): Set<String> {
        val names = enabled().filter { it.userInvocable }.map { it.name }
        val plain = names.filter { ':' in it }.groupBy { it.substringAfter(':') }.filter { (short, all) -> all.size == 1 && short !in names }.keys
        return (names + plain).toSet()
    }

    private var folderMemo: List<File>? = null

    /**
     * Real folders of the enabled skills that were picked for each name, plus the installed plugin each came from.
     * The shell sandbox lets commands read and run what is inside them, so a skill's bundled scripts work from
     * where they are installed. Remembered once the catalog is frozen, because every shell call asks.
     */
    fun folders(): List<File> {
        folderMemo?.let { return it }
        val found = linkedSetOf<File>()
        enabled().forEach { meta ->
            val located = pickWinner(findAll(meta.name)) ?: return@forEach
            located.skillFolder?.takeIf { it.isDirectory }?.let(found::add)
            located.pluginRoot?.let(::File)?.takeIf { it.isDirectory }?.let(found::add)
        }
        return found.toList().also { if (frozen != null) folderMemo = it }
    }

    /**
     * The listed name for [name]. Plugin skills are listed as plugin:skill; their plain name still works when only one
     * enabled skill has it, as in Claude Code.
     */
    fun resolve(name: String): String {
        val key = name.trim().removePrefix("/")
        if (key.isEmpty() || ':' in key || findAll(key).isNotEmpty()) return key
        return enabled().map { it.name }.filter { it.substringAfter(':') == key && ':' in it }.singleOrNull() ?: key
    }

    fun view(name: String, filePath: String? = null): Result<SkillView> {
        val key = resolve(name)
        if (key.isEmpty()) return Result.failure(SkillParseException("Missing skill name."))
        if (".." in key) return Result.failure(SkillParseException("Path traversal is not allowed."))

        if (key in disabled() || configuration?.skillEnabled(key) == false) {
            return Result.failure(
                SkillParseException("Skill '$key' is disabled. Enable it in Settings → Skills."),
            )
        }

        val matches = findAll(key)
        if (matches.isEmpty()) {
            val available = enabled().joinToString(", ") { it.name }
            return Result.failure(
                SkillParseException("Skill '$key' not found." + if (available.isNotEmpty()) " Available: $available" else ""),
            )
        }
        val winner = pickWinner(matches)
            ?: return Result.failure(
                SkillParseException(
                    "Ambiguous skill name '$key': ${matches.size} matches. Load one by a unique name.",
                ),
            )

        if (!filePath.isNullOrBlank()) {
            val extra = readSupportFile(winner, filePath)
                ?: return Result.failure(
                    SkillParseException(
                        "File '$filePath' not found in skill '$key'. " +
                            "Use a path from the skill's supporting files list.",
                    ),
                )
            return Result.success(
                SkillView(
                    name = winner.meta.name,
                    description = winner.meta.description,
                    category = winner.meta.category,
                    content = expand(extra, winner),
                    source = winner.meta.source,
                    linkedFiles = winner.linkedFiles,
                ),
            )
        }

        val header = winner.directory?.let { folder ->
            "Skill folder: ${folder.path}\nScripts, data and other files named in this skill are in that folder. " +
                "Run its scripts from there, even where the text below shows another path such as .claude/skills/${winner.meta.name}/.\n\n"
        }.orEmpty()
        return Result.success(
            SkillView(
                name = winner.meta.name,
                description = winner.meta.description,
                category = winner.meta.category,
                content = header + commandNote(winner.content) + SkillHostNotes.forBody(winner.content) + expand(winner.content, winner),
                source = winner.meta.source,
                linkedFiles = winner.linkedFiles,
            ),
        )
    }

    /** When the model loads a command itself, nobody filled in its placeholders, so it is told what they mean. */
    private fun commandNote(content: String): String {
        val parsed = runCatching { SkillParser.parse(content) }.getOrNull() ?: return ""
        if (!CommandTemplate.applies(parsed.fields, parsed.body)) return ""
        return "This skill is a slash command. \$ARGUMENTS and {{args}} stand for the input of the task at hand, and \$1, \$2 for its words. " +
            "A !`command` or !{command} is a shell command whose output belongs in its place: run it first. @path or @{path} names a file to read.\n\n"
    }

    /** A skill run as a slash command: its frontmatter and its body with plugin and project paths filled in. */
    data class Command(val name: String, val fields: Map<String, String>, val body: String, val folder: File?)

    /** The skill behind /[name] when it is a command or takes arguments; null for an ordinary skill. */
    fun command(name: String): Command? {
        val key = resolve(name)
        if (key in disabled() || configuration?.skillEnabled(key) == false) return null
        val winner = pickWinner(findAll(key)) ?: return null
        val parsed = runCatching { SkillParser.parse(winner.content) }.getOrNull() ?: return null
        if (!CommandTemplate.applies(parsed.fields, parsed.body)) return null
        return Command(winner.meta.name, parsed.fields, expand(parsed.body, winner), winner.directory ?: winner.pluginRoot?.let(::File))
    }

    /** The SKILL.md text that wins for [name], enabled or not, for showing and editing; null when missing or ambiguous. */
    fun document(name: String): SkillDocument? {
        val key = resolve(name)
        val winner = pickWinner(findAll(key)) ?: return null
        return SkillDocument(winner.meta.copy(enabled = key !in disabled() && configuration?.skillEnabled(key) != false),
            winner.content, winner.linkedFiles, winner.directory?.path)
    }

    fun saveUser(content: String): ParsedSkill {
        val parsed = SkillParser.validateUserContent(content)
        val dir = File(userDir, parsed.name)
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(parsed.raw)
        return parsed
    }

    fun deleteUser(name: String): Boolean {
        val dir = File(userDir, name)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun patchUserOrCopy(name: String, oldString: String, newString: String): Result<String> {
        if (oldString == newString) {
            return Result.failure(SkillParseException("old_string and new_string are identical."))
        }
        val located = findAll(name).let { pickWinner(it) }
            ?: return Result.failure(SkillParseException("Skill '$name' not found."))
        val current = located.content
        val count = countOccurrences(current, oldString)
        if (count == 0) return Result.failure(SkillParseException("old_string was not found in $name."))
        if (count > 1) {
            return Result.failure(
                SkillParseException("old_string appears $count times in $name; make it more specific."),
            )
        }
        val next = current.replaceFirst(oldString, newString)
        val parsed = runCatching { SkillParser.validateUserContent(next) }
            .getOrElse { return Result.failure(it) }
        val dir = File(userDir, parsed.name)
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(parsed.raw)
        return Result.success("Patched ${parsed.name} (saved to your skills).")
    }

    /** [skillFolder] is where the skill's files really live, even when they are held in memory; [pluginRoot] is its plugin. */
    private data class Located(
        val meta: SkillMeta,
        val content: String,
        val linkedFiles: List<String>,
        val support: Map<String, String>,
        val directory: File? = null,
        val skillFolder: File? = directory,
        val pluginRoot: String? = null,
    )

    private fun expand(text: String, located: Located): String =
        SkillVariables.expand(text, located.skillFolder?.path, located.pluginRoot, workspaceRoot?.path)

    private fun findAll(name: String): List<Located> {
        frozen?.let { return it.filter { located -> located.meta.name == name } }
        val out = mutableListOf<Located>()
        (bundled + optionalBundled())[name]?.let { b ->
            parseLocated(b.content, SkillSource.BUNDLED, b.relativeDir, b.files)?.let { out += it }
        }
        scanDirFiles(userDir).filter { it.first == name }.forEach { (_, content, files, rel) ->
            parseLocated(content, SkillSource.USER, rel, files, home = File(userDir, rel))?.let { out += it }
        }
        projectDir()?.let { dir ->
            scanDirFiles(dir).filter { it.first == name }.forEach { (_, content, files, rel) ->
                parseLocated(content, SkillSource.PROJECT, rel, files, home = File(dir, rel))?.let { out += it }
            }
        }
        configuredSkills[name]?.let { b ->
            parseLocated(b.content, SkillSource.PROJECT, b.relativeDir, b.files, b.directory, pluginRoot = b.pluginRoot, listedName = b.name)?.let { out += it }
        }
        return out
    }

    private fun pickWinner(matches: List<Located>): Located? {
        if (matches.isEmpty()) return null
        val project = matches.filter { it.meta.source == SkillSource.PROJECT }
        if (project.size == 1) return project[0]
        if (project.size > 1) return null
        val user = matches.filter { it.meta.source == SkillSource.USER }
        if (user.size == 1) return user[0]
        if (user.size > 1) return null
        val bundledHits = matches.filter { it.meta.source == SkillSource.BUNDLED }
        return bundledHits.singleOrNull()
    }

    private fun parseLocated(
        content: String,
        source: SkillSource,
        relativeDir: String,
        files: Map<String, String>,
        directory: String? = null,
        home: File? = null,
        pluginRoot: String? = null,
        listedName: String? = null,
    ): Located? {
        val parsed = runCatching { SkillParser.parse(content) }.getOrNull() ?: return null
        val folder = directory?.let(::File)?.takeIf { it.isDirectory }
        val meta = SkillMeta(
            name = listedName ?: parsed.name,
            // Plugin skills are chosen by their description, as in Claude Code, so it is listed in full.
            description = if (pluginRoot != null || listedName != null) parsed.description.replace(Regex("\\s+"), " ").take(MAX_PLUGIN_DESCRIPTION)
                else parsed.catalogDescription,
            category = parsed.category,
            source = source,
            relativeDir = relativeDir,
            enabled = true,
            modelInvocable = parsed.fields["disable-model-invocation"]?.trim()?.lowercase() != "true",
            userInvocable = parsed.fields["user-invocable"]?.trim()?.lowercase() != "false",
        )
        if (folder != null) {
            val listed = folder.walkTopDown().onEnter { it == folder || !it.name.startsWith(".") && it.name !in setOf("node_modules", "__pycache__") }
                .filter { it.isFile && it.name != "SKILL.md" }.take(MAX_LISTED + 1)
                .map { it.relativeTo(folder).invariantSeparatorsPath }.sorted().toList()
            return Located(meta, parsed.raw, listed.take(MAX_LISTED), files, folder, folder, pluginRoot)
        }
        return Located(meta, parsed.raw, files.keys.sorted(), files, null, home, pluginRoot)
    }

    private fun scanBundled(): List<SkillMeta> =
        (bundled + optionalBundled()).values.mapNotNull { b ->
            val parsed = runCatching { SkillParser.parse(b.content) }.getOrNull() ?: return@mapNotNull null
            SkillMeta(
                name = parsed.name,
                description = parsed.catalogDescription,
                category = parsed.category,
                source = SkillSource.BUNDLED,
                relativeDir = b.relativeDir,
                enabled = true,
            )
        }

    private fun scanDir(dir: File, source: SkillSource): List<SkillMeta> =
        scanDirFiles(dir).mapNotNull { (name, content, _, rel) ->
            val parsed = runCatching { SkillParser.parse(content) }.getOrNull() ?: return@mapNotNull null
            SkillMeta(
                name = parsed.name.ifBlank { name },
                description = parsed.catalogDescription,
                category = parsed.category,
                source = source,
                relativeDir = rel,
                enabled = true,
            )
        }

    private fun scanDirFiles(dir: File): List<Quad> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { child ->
                val skillMd = File(child, "SKILL.md")
                if (!skillMd.isFile) return@mapNotNull null
                val content = runCatching { skillMd.readText() }.getOrNull() ?: return@mapNotNull null
                val files = mutableMapOf<String, String>()
                for (folder in SUPPORT_DIRS) {
                    val sub = File(child, folder)
                    if (!sub.isDirectory) continue
                    sub.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = "${folder}/${f.relativeTo(sub).invariantSeparatorsPath}"
                        files[rel] = runCatching { f.readText() }.getOrDefault("")
                    }
                }
                Quad(child.name, content, files, child.name)
            }
    }

    private fun readSupportFile(located: Located, filePath: String): String? {
        val cleaned = filePath.trim().replace('\\', '/').trimStart('/')
        if (cleaned.contains("..")) return null
        located.directory?.let { folder ->
            val file = folder.resolve(cleaned).canonicalFile
            if (!file.toPath().startsWith(folder.canonicalFile.toPath()) || !file.isFile) return null
            if (file.length() > MAX_SUPPORT_READ) return "File is ${file.length() / 1024} KiB, too large to show here. Path: ${file.path}"
            return file.readText()
        }
        val root = cleaned.substringBefore('/')
        if (root !in SUPPORT_DIRS) return null
        return located.support[cleaned]
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) return count
            count++
            idx = found + needle.length
        }
    }

    private data class Quad(
        val first: String,
        val second: String,
        val third: Map<String, String>,
        val fourth: String,
    )

    companion object {
        val SUPPORT_DIRS = setOf("references", "templates", "scripts", "assets")
        const val MAX_PLUGIN_DESCRIPTION = 1024
        private const val MAX_LISTED = 300
        private const val MAX_SUPPORT_READ = 256 * 1024

        fun loadBundledFromAssets(read: (String) -> String?, list: (String) -> List<String>): Map<String, BundledSkill> {
            val root = "skills"
            val names = list(root)
            val out = linkedMapOf<String, BundledSkill>()
            for (entry in names) {
                val skillMdPath = "$root/$entry/SKILL.md"
                val content = read(skillMdPath) ?: continue
                val parsed = runCatching { SkillParser.parse(content) }.getOrNull() ?: continue
                val files = mutableMapOf<String, String>()
                for (folder in SUPPORT_DIRS) {
                    val prefix = "$root/$entry/$folder"
                    collectAssetFiles(prefix, folder, read, list, files)
                }
                out[parsed.name] = BundledSkill(entry, content, files)
            }
            return out
        }

        private fun collectAssetFiles(
            assetDir: String,
            relPrefix: String,
            read: (String) -> String?,
            list: (String) -> List<String>,
            into: MutableMap<String, String>,
        ) {
            val children = list(assetDir)
            if (children.isEmpty()) {
                read(assetDir)?.let { into[relPrefix] = it }
                return
            }
            for (child in children) {
                collectAssetFiles("$assetDir/$child", "$relPrefix/$child", read, list, into)
            }
        }
    }
}
