package com.androidharness.app.plugins

import android.content.Context
import android.net.Uri
import com.androidharness.app.config.ConfigDocumentFs
import com.androidharness.app.config.HarnessConfig
import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.SafFs
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class PluginProgress(val stage: String = "", val bytes: Long = 0, val total: Long? = null)

class PluginManager(
    private val context: Context,
    val revision: MutableStateFlow<Long>,
    private val downloads: PluginDownloads = PluginDownloads(),
) {
    val store = PluginStore(File(context.filesDir, "plugins")) { revision.value += 1 }
    private val previews = File(context.cacheDir, "plugin-previews").apply { mkdirs() }
    init { previews.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000 }.forEach { it.deleteRecursively() } }

    fun workspaceScope(workspace: WorkspaceFs): String {
        val fs = (workspace as? ChatWorkspaceFs)?.primary?.fs ?: workspace
        val identity = when (fs) {
            is SafFs -> fs.treeUri.toString()
            is com.androidharness.app.workspace.SshFs -> "ssh:${fs.location.connectionId}:${fs.root}"
            else -> fs.shellRoot?.canonicalPath ?: fs.displayPath
        }
        return "project:${HarnessConfig.digest(identity)}"
    }
    fun configurationSources(workspace: WorkspaceFs) = store.configurationSources(workspaceScope(workspace))

    suspend fun prepare(origin: PluginOrigin, update: InstalledPlugin? = null, progress: (PluginProgress) -> Unit = {}): PreparedPlugin {
        val temporary = withContext(Dispatchers.IO) { File(previews, UUID.randomUUID().toString()).apply { mkdirs() } }
        try {
            val resolved = if (origin.kind in setOf("npm", "github")) {
                progress(PluginProgress("Resolving source")); downloads.resolve(origin)
            } else null
            val revision = resolved?.revision ?: "local"
            val bundle = when (origin.kind) {
                "npm", "github" -> {
                    val archive = File(temporary, "download")
                    progress(PluginProgress("Downloading"))
                    downloads.download(requireNotNull(resolved), archive) { n, total -> progress(PluginProgress("Downloading", n, total)) }
                    withContext(Dispatchers.IO) {
                        progress(PluginProgress("Checking bundle"))
                        val directory = File(temporary, "files")
                        PluginArchives.extract(archive, directory)
                        root(directory, origin.subdirectory)
                    }
                }
                "archive" -> withContext(Dispatchers.IO) {
                    progress(PluginProgress("Reading archive"))
                    val archive = File(temporary, "download")
                    context.contentResolver.openInputStream(Uri.parse(origin.target))?.use { input -> archive.outputStream().use { out ->
                        PluginArchives.copyLimited(input, out, PluginArchives.MAX_DOWNLOAD)
                    } } ?: error("Cannot open plugin archive. Select it again.")
                    val directory = File(temporary, "files"); PluginArchives.extract(archive, directory); root(directory, "")
                }
                "folder" -> withContext(Dispatchers.IO) {
                    progress(PluginProgress("Reading folder"))
                    val directory = File(temporary, "files")
                    PluginArchives.copyFolder(ConfigDocumentFs(context, Uri.parse(origin.target)).resolve("."), directory)
                    root(directory, "")
                }
                "builtin" -> withContext(Dispatchers.IO) {
                    val directory = File(temporary, "files").apply { mkdirs() }
                    PluginStarters.materialize(origin.target, directory)
                    directory
                }
                else -> error("Unsupported plugin source.")
            }
            return withContext(Dispatchers.IO) {
                ensureActive()
                val contents = PluginManifest.inspect(bundle)
                if (update != null) require(contents.name == update.name) { "The source returned a different plugin; update was refused." }
                PreparedPlugin(temporary.name, bundle, resolved?.origin ?: origin, revision, contents, PluginManifest.digest(bundle), update?.id, update?.digest)
            }
        } catch (e: Exception) { withContext(NonCancellable + Dispatchers.IO) { temporary.deleteRecursively() }; throw e }
    }
    private fun root(directory: File, subdirectory: String): File {
        var current = directory
        if (subdirectory.isNotBlank()) {
            val envelope = directory.listFiles().orEmpty().filterNot { it.name in setOf("__MACOSX", ".DS_Store") }
            val base = if (envelope.size == 1 && envelope.single().isDirectory) envelope.single() else directory
            current = PluginManifest.path(base, subdirectory)
        }
        repeat(if (subdirectory.isBlank()) 4 else 0) {
            if (current.resolve("plugin.json").isFile || current.resolve("SKILL.md").isFile) return@repeat
            val children = current.listFiles().orEmpty().filterNot { it.name == "__MACOSX" || it.name == ".DS_Store" }
            if (children.size == 1 && children.single().isDirectory) current = children.single()
        }
        require(current.isDirectory) { "Plugin subfolder was not found." }
        if (!current.resolve("plugin.json").isFile && current.resolve("SKILL.md").isFile) {
            val md = current.resolve("SKILL.md")
            require(md.length() <= 64 * 1024) { "Skill is too large." }
            val skill = com.androidharness.app.skills.SkillParser.parse(md.readText())
            val children = current.listFiles().orEmpty()
            require(children.none { it.name == "__imported_skills__" }) { "Reserved skill import directory already exists." }
            val folder = current.resolve("__imported_skills__/${skill.name}").apply { mkdirs() }
            children.forEach { file -> require(file.renameTo(folder.resolve(file.name))) { "Cannot normalize imported skill." } }
            current.resolve("plugin.json").writeText(buildJsonObject {
                put("name", skill.name); put("version", "1.0.0-local"); put("description", skill.description)
                put("skills", buildJsonArray { add("__imported_skills__") })
            }.toString())
        }
        require(current.resolve("plugin.json").isFile) {
            "No AndroidHarness plugin.json or SKILL.md found. Choose the plugin's own folder, or specify its repository subfolder. Other harness plugin formats need adaptation."
        }
        return current
    }
    suspend fun install(prepared: PreparedPlugin, scope: String, label: String): InstalledPlugin = withContext(Dispatchers.IO) {
        try { store.install(prepared, scope, label) } finally { discard(prepared) }
    }
    fun discard(prepared: PreparedPlugin) {
        require(Regex("[a-f0-9-]{36}").matches(prepared.token))
        File(previews, prepared.token).deleteRecursively()
    }
    suspend fun search(query: String) = downloads.searchNpm(query)
}

/** Real, bundled starter packs; community search results are labelled separately. */
object PluginStarters {
    val listings = listOf(
        PluginListing("review-workflow", "A code-review skill and instructions for reporting verified findings.", "1.0.0", PluginOrigin("builtin", "review-workflow"), "AndroidHarness", true),
        PluginListing("test-workflow", "A regression-testing skill and instructions for checking changes.", "1.0.0", PluginOrigin("builtin", "test-workflow"), "AndroidHarness", true),
    )
    fun materialize(id: String, folder: File) {
        val entry = listings.firstOrNull { it.origin.target == id } ?: error("Unknown starter pack.")
        val body = when (id) {
            "review-workflow" -> "Review the current diff and relevant callers. Prioritize actionable defects and cite exact files. Distinguish verified behavior from assumptions. Do not edit files unless the user asks."
            "test-workflow" -> "Choose checks that exercise the changed behavior and likely regressions. Use the available project toolchain. Report commands and actual outcomes. Never claim a check passed without its successful result."
            else -> error("Unknown starter pack.")
        }
        folder.resolve("instructions.md").writeText("# ${entry.name}\n\n$body\n")
        val skillDir = folder.resolve("skills/${entry.name}").apply { mkdirs() }
        skillDir.resolve("SKILL.md").writeText("---\nname: ${entry.name}\ndescription: ${entry.description}\ncategory: development\n---\n\n$body\n")
        folder.resolve("plugin.json").writeText(buildJsonObject {
            put("name", entry.name); put("version", entry.version); put("description", entry.description)
            put("instructions", buildJsonArray { add("instructions.md") }); put("skills", buildJsonArray { add("skills") })
        }.toString())
    }
}
