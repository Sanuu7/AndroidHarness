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
) {
    data class BundledSkill(
        val relativeDir: String,
        val content: String,
        val files: Map<String, String> = emptyMap(),
    )

    fun list(): List<SkillMeta> {
        val byName = linkedMapOf<String, SkillMeta>()
        scanBundled().forEach { byName[it.name] = it }
        scanDir(userDir, SkillSource.USER).forEach { byName[it.name] = it }
        projectDir()?.let { dir ->
            scanDir(dir, SkillSource.PROJECT).forEach { byName[it.name] = it }
        }
        val off = disabled()
        return byName.values
            .map { it.copy(enabled = it.name !in off) }
            .sortedWith(compareBy({ it.category }, { it.name }))
    }

    fun enabled(): List<SkillMeta> = list().filter { it.enabled }

    fun catalog(): String {
        val enabled = enabled()
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

    fun slashNames(): Set<String> = enabled().map { it.name }.toSet()

    fun view(name: String, filePath: String? = null): Result<SkillView> {
        val key = name.trim()
        if (key.isEmpty()) return Result.failure(SkillParseException("Missing skill name."))
        if (".." in key) return Result.failure(SkillParseException("Path traversal is not allowed."))

        if (key in disabled()) {
            return Result.failure(
                SkillParseException("Skill '$key' is disabled. Enable it in Settings → Skills."),
            )
        }

        val matches = findAll(key)
        if (matches.isEmpty()) {
            val available = enabled().take(12).joinToString(", ") { it.name }
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
                            "Use a path under references/, templates/, scripts/, or assets/.",
                    ),
                )
            return Result.success(
                SkillView(
                    name = winner.meta.name,
                    description = winner.meta.description,
                    category = winner.meta.category,
                    content = extra,
                    source = winner.meta.source,
                    linkedFiles = winner.linkedFiles,
                ),
            )
        }

        return Result.success(
            SkillView(
                name = winner.meta.name,
                description = winner.meta.description,
                category = winner.meta.category,
                content = winner.content,
                source = winner.meta.source,
                linkedFiles = winner.linkedFiles,
            ),
        )
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

    private data class Located(
        val meta: SkillMeta,
        val content: String,
        val linkedFiles: List<String>,
        val support: Map<String, String>,
    )

    private fun findAll(name: String): List<Located> {
        val out = mutableListOf<Located>()
        bundled[name]?.let { b ->
            parseLocated(b.content, SkillSource.BUNDLED, b.relativeDir, b.files)?.let { out += it }
        }
        scanDirFiles(userDir).filter { it.first == name }.forEach { (_, content, files, rel) ->
            parseLocated(content, SkillSource.USER, rel, files)?.let { out += it }
        }
        projectDir()?.let { dir ->
            scanDirFiles(dir).filter { it.first == name }.forEach { (_, content, files, rel) ->
                parseLocated(content, SkillSource.PROJECT, rel, files)?.let { out += it }
            }
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
    ): Located? {
        val parsed = runCatching { SkillParser.parse(content) }.getOrNull() ?: return null
        val meta = SkillMeta(
            name = parsed.name,
            description = parsed.catalogDescription,
            category = parsed.category,
            source = source,
            relativeDir = relativeDir,
            enabled = true,
        )
        return Located(meta, parsed.raw, files.keys.sorted(), files)
    }

    private fun scanBundled(): List<SkillMeta> =
        bundled.values.mapNotNull { b ->
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
