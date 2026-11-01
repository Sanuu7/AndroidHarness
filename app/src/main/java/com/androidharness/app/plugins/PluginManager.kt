package com.androidharness.app.plugins

import android.content.Context
import android.net.Uri
import com.androidharness.app.config.ConfigDocumentFs
import com.androidharness.app.config.HarnessConfig
import com.androidharness.app.data.env.PackageInstallStatus
import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.SafFs
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class PluginProgress(val stage: String = "", val bytes: Long = 0, val total: Long? = null, val detail: String = "",
    val packages: PackageInstallStatus? = null)

/** A marketplace or repository holds several plugins; the user picks which one to install. */
class MarketplaceChoices(val listings: List<PluginListing>) : IllegalStateException("Choose which plugin to install.")

/** What a pasted request turned into before anything is downloaded. */
sealed interface PluginRequest {
    data class Install(val origin: PluginOrigin) : PluginRequest
    /** Running [command] is the only way to get the plugin's files, so the user approves it first. */
    data class RunInstaller(val command: String, val packageName: String, val reason: String) : PluginRequest
    data class MarketplaceAdded(val source: MarketplaceSource, val listings: List<PluginListing>) : PluginRequest
}

/** One catalog section of Discover. A failed source reports [error] and leaves the others usable. */
data class CatalogSection(val title: String, val listings: List<PluginListing>, val error: String? = null, val repo: String = "")

class PluginManager(
    private val context: Context,
    val revision: MutableStateFlow<Long>,
    private val downloads: PluginDownloads = PluginDownloads(),
    private val runtime: () -> PluginRuntime? = { null },
) {
    val store = PluginStore(File(context.filesDir, "plugins")) { revision.value += 1 }
    val marketplaces = MarketplaceStore(File(context.filesDir, "plugins/marketplaces.json"))
    private val previews = File(context.cacheDir, "plugin-previews").apply { mkdirs() }
    private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PluginListing>>>()
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

    /** Reads a pasted command, package, link or JSON. Only package metadata and marketplace files are fetched. */
    suspend fun request(input: String): PluginRequest = when (val parsed = InstallCommands.parse(input)) {
        is InstallRequest.Source -> PluginRequest.Install(parsed.origin)
        is InstallRequest.Marketplace -> {
            val listings = marketplaceListings(parsed.source, refresh = true)
            runCatching { marketplaces.add(parsed.source) }
            PluginRequest.MarketplaceAdded(parsed.source, listings)
        }
        is InstallRequest.MarketplacePlugin -> PluginRequest.Install(findInMarketplaces(parsed.plugin, parsed.marketplace).origin)
        is InstallRequest.Run -> runRequest(parsed)
    }
    private suspend fun runRequest(run: InstallRequest.Run): PluginRequest {
        val server = if (run.runner == "uvx") Regex("(^|[-_.])mcp([-_.]|$)").containsMatchIn(run.pkg) else {
            val pkg = downloads.npmVersion(run.pkg, run.version)
            val keywords = pkg["keywords"]?.let { it as? JsonArray }.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
            val deps = pkg["dependencies"]?.let { it as? JsonObject }?.keys.orEmpty()
            "@modelcontextprotocol/sdk" in deps || keywords.any { "mcp" in it || "modelcontextprotocol" in it } ||
                Regex("(^|[-/@])mcp([-/]|$)").containsMatchIn(run.pkg)
        }
        if (server) {
            val name = PluginAdapter.sanitizeName(run.pkg.substringAfterLast('/'), "server").take(32)
            val words = run.command.let(InstallCommands::tokenize)
            return PluginRequest.Install(PluginOrigin("mcp", buildJsonObject { put(name, buildJsonObject {
                put("command", words.first()); put("args", JsonArray(words.drop(1).map(::JsonPrimitive)))
            }) }.toString(), run.version, name = name))
        }
        return PluginRequest.RunInstaller(run.command, run.pkg, "${run.pkg} installs its files by running a command.")
    }
    private suspend fun findInMarketplaces(plugin: String, marketplace: String): PluginListing {
        val sources = marketplaces.list().filter { marketplace.isBlank() || it.repo.equals(marketplace, true) || it.name.equals(marketplace, true) ||
            it.repo.substringAfter('/').equals(marketplace, true) }
        for (source in sources) {
            val listing = runCatching { marketplaceListings(source) }.getOrDefault(emptyList()).firstOrNull { it.name.equals(plugin, true) }
            if (listing != null) return listing
        }
        // Marketplace names are not repository names; search every added marketplace before giving up.
        if (marketplace.isNotBlank()) marketplaces.list().forEach { source ->
            runCatching { marketplaceListings(source) }.getOrDefault(emptyList()).firstOrNull { it.name.equals(plugin, true) && it.source.equals(marketplace, true) }?.let { return it }
        }
        error(if (marketplace.isBlank()) "No added marketplace has a plugin named $plugin. Add its marketplace first."
            else "Marketplace $marketplace is not added, or it has no plugin named $plugin. Add it with /plugin marketplace add owner/repo.")
    }

    suspend fun marketplaceListings(source: MarketplaceSource, refresh: Boolean = false): List<PluginListing> {
        val key = "market:${source.repo.lowercase()}@${source.ref}"
        catalogCache[key]?.takeIf { !refresh && System.currentTimeMillis() - it.first < 30 * 60 * 1000 }?.let { return it.second }
        val document = downloads.marketplace(source)
        val label = source.name.ifBlank { (document["name"] as? JsonPrimitive)?.contentOrNull ?: source.repo }
        return PluginMarketplaces.entries(document, PluginOrigin("github", source.repo, source.ref), label).also {
            catalogCache[key] = System.currentTimeMillis() to it
        }
    }
    /** Searches every catalog at once. Each section fails on its own, so one slow or blocked source never empties Discover. */
    suspend fun search(query: String): List<CatalogSection> = coroutineScope {
        val text = query.trim()
        val markets = marketplaces.list().map { source ->
            async {
                runCatching { marketplaceListings(source) }.fold(
                    { all -> CatalogSection(source.name.ifBlank { source.repo }, if (text.isBlank()) all else all.filter { it.matches(text) }, repo = source.repo) },
                    { CatalogSection(source.name.ifBlank { source.repo }, emptyList(), it.message ?: "Could not load this marketplace.", source.repo) })
            }
        }
        val remote = listOf("MCP registry" to suspend { downloads.searchMcpRegistry(text) }, "GitHub" to suspend { downloads.searchGitHub(text) },
            "npm" to suspend { downloads.searchNpm(text) }).map { (title, load) ->
            async {
                val key = "$title:$text"
                catalogCache[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60 * 1000 }?.let { return@async CatalogSection(title, it.second) }
                runCatching { withTimeout(20_000) { load() } }.fold(
                    { catalogCache[key] = System.currentTimeMillis() to it; CatalogSection(title, it) },
                    { CatalogSection(title, emptyList(), it.message ?: "Search failed.") })
            }
        }
        val featured = PluginMarketplaces.featured.filter { text.isBlank() || it.matches(text) }
        listOf(CatalogSection("Featured", featured)) + markets.awaitAll() + remote.awaitAll()
    }
    private fun PluginListing.matches(text: String) = text.split(Regex("\\s+")).all { word ->
        name.contains(word, true) || description.contains(word, true) || category.contains(word, true) || publisher.contains(word, true)
    }
    fun addMarketplace(input: String): MarketplaceSource {
        val source = when (val parsed = InstallCommands.parse(input)) {
            is InstallRequest.Marketplace -> parsed.source
            is InstallRequest.Source -> if (parsed.origin.kind == "github") MarketplaceSource(parsed.origin.target, parsed.origin.ref) else null
            else -> null
        } ?: error("Enter the marketplace's GitHub repository, such as owner/repo.")
        marketplaces.add(source)
        return source
    }
    fun removeMarketplace(repo: String) { marketplaces.remove(repo); catalogCache.keys.removeAll { it.startsWith("market:${repo.lowercase()}@") } }

    suspend fun prepare(origin: PluginOrigin, update: InstalledPlugin? = null, progress: (PluginProgress) -> Unit = {}): PreparedPlugin {
        val temporary = withContext(Dispatchers.IO) { File(previews, UUID.randomUUID().toString()).apply { mkdirs() } }
        try {
            val resolved = if (origin.kind in setOf("npm", "github", "url")) {
                progress(PluginProgress("Resolving source")); downloads.resolve(origin)
            } else null
            val revision = resolved?.revision ?: when (origin.kind) { "mcp" -> origin.ref.ifBlank { "config" }; "installer" -> "installer"; else -> "local" }
            val files = File(temporary, "files")
            val skipped = when (origin.kind) {
                "npm", "github", "url" -> {
                    val archive = File(temporary, "download")
                    progress(PluginProgress("Downloading"))
                    downloads.download(requireNotNull(resolved), archive) { n, total -> progress(PluginProgress("Downloading", n, total)) }
                    withContext(Dispatchers.IO) {
                        progress(PluginProgress("Checking bundle"))
                        PluginArchives.extract(archive, files).also { archive.delete() }
                    }
                }
                "archive" -> withContext(Dispatchers.IO) {
                    progress(PluginProgress("Reading archive"))
                    val archive = File(temporary, "download")
                    context.contentResolver.openInputStream(Uri.parse(origin.target))?.use { input -> archive.outputStream().use { out ->
                        PluginArchives.copyLimited(input, out, PluginArchives.MAX_DOWNLOAD)
                    } } ?: error("Cannot open plugin archive. Select it again.")
                    PluginArchives.extract(archive, files).also { archive.delete() }
                }
                "folder" -> withContext(Dispatchers.IO) {
                    progress(PluginProgress("Reading folder"))
                    PluginArchives.copyFolder(ConfigDocumentFs(context, Uri.parse(origin.target)).resolve("."), files)
                }
                "builtin" -> withContext(Dispatchers.IO) { files.mkdirs(); PluginStarters.materialize(origin.target, files); emptyList() }
                "mcp" -> withContext(Dispatchers.IO) { files.mkdirs(); materializeMcp(origin, files); emptyList() }
                "installer" -> {
                    val env = runtime() ?: error("Installer commands need the Linux environment, which is not available.")
                    val needed = env.available(PluginInstallers.runtimesFor(origin.target) + "bash")
                    progress(PluginProgress("Installing runtime", detail = needed.joinToString(", ")))
                    env.install(needed, "Plugin installer") { progress(PluginProgress("Installing runtime", detail = needed.joinToString(", "), packages = it)) }
                    progress(PluginProgress("Running installer", detail = origin.target))
                    val run = File(temporary, "run").apply { mkdirs() }
                    PluginInstallers.run(env, origin.target, run) { progress(PluginProgress("Running installer", detail = it)) }
                    withContext(Dispatchers.IO) { PluginArchives.copyLocal(run, files).also { run.deleteRecursively() } }
                }
                "git" -> error("Only GitHub repositories can be downloaded. Ask the publisher for a GitHub, npm or archive source.")
                else -> error("Unsupported plugin source.")
            }
            return withContext(Dispatchers.IO) {
                ensureActive()
                progress(PluginProgress("Checking bundle"))
                val adapted = PluginAdapter.adapt(files, origin, fallbackName(origin))
                if (adapted.choices.isNotEmpty()) throw MarketplaceChoices(adapted.choices)
                val bundle = adapted.root
                if (skipped.isNotEmpty()) appendNotes(bundle, listOf("Left out ${skipped.size} file(s) that were unsafe or too large: " + skipped.take(5).joinToString("; ")))
                val contents = PluginManifest.inspect(bundle)
                if (update != null) require(contents.name == update.name) { "The source returned a different plugin; update was refused." }
                PreparedPlugin(temporary.name, bundle, resolved?.origin?.copy(entry = origin.entry, marketplace = origin.marketplace, name = origin.name,
                    subdirectory = origin.subdirectory) ?: origin, revision, contents, PluginManifest.digest(bundle), update?.id, update?.digest)
            }
        } catch (e: Exception) { withContext(NonCancellable + Dispatchers.IO) { temporary.deleteRecursively() }; throw e }
    }
    private fun fallbackName(origin: PluginOrigin): String = origin.name.ifBlank {
        when (origin.kind) {
            "npm" -> origin.target.substringAfterLast('/')
            "github" -> origin.subdirectory.substringAfterLast('/').ifBlank { origin.target.substringAfterLast('/') }
            "installer" -> InstallCommands.tokenize(origin.target).drop(1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/')?.substringBefore('@') ?: "installed"
            "url" -> origin.target.substringAfterLast('/').substringBefore('.')
            else -> Uri.parse(origin.target).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.substringBefore('.') ?: "plugin"
        }
    }
    private fun appendNotes(bundle: File, notes: List<String>) {
        val manifest = bundle.resolve("plugin.json")
        val current = Json.parseToJsonElement(manifest.readText()).jsonObject
        val merged = (current["notes"] as? JsonArray).orEmpty() + notes.map(::JsonPrimitive)
        manifest.writeText(JsonObject(current + ("notes" to JsonArray(merged.take(50)))).toString())
    }
    private fun materializeMcp(origin: PluginOrigin, folder: File) {
        val servers = Json.parseToJsonElement(origin.target).jsonObject
        val notes = mutableListOf<String>()
        val converted = servers.mapNotNull { (key, value) ->
            val server = value as? JsonObject ?: return@mapNotNull null
            PluginMcp.convert(server) { notes += "$key: $it" }?.let { key.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(32).ifBlank { "server" } to it }
        }.toMap()
        require(converted.isNotEmpty()) { "The MCP configuration has no usable server." }
        val name = PluginAdapter.sanitizeName(origin.name.ifBlank { converted.keys.first() }, "mcp-server")
        val needsKeys = converted.values.flatMap { server -> listOf("env", "headers").flatMap { (server[it] as? JsonObject)?.values.orEmpty() } }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.flatMap { Regex("\\{env:([^}]+)\\}").findAll(it).map { m -> m.groupValues[1] } }.distinct()
        if (needsKeys.isNotEmpty()) notes += "Set ${needsKeys.joinToString(", ")} in Configuration settings, under variables, before the server can sign in."
        folder.resolve("plugin.json").writeText(buildJsonObject {
            put("name", name); put("version", origin.ref.ifBlank { "0.0.0" }); put("format", "MCP server")
            put("description", "MCP server${if (converted.size > 1) "s" else ""}: ${converted.keys.joinToString(", ")}")
            put("mcp", JsonObject(converted))
            if (notes.isNotEmpty()) put("notes", JsonArray(notes.map(::JsonPrimitive)))
        }.toString())
    }

    /** Installs the runtimes the bundle needs, then the bundle. A runtime failure keeps the plugin and reports why. */
    suspend fun install(prepared: PreparedPlugin, scope: String, label: String, progress: (PluginProgress) -> Unit = {}): Pair<InstalledPlugin, String?> {
        try {
            var runtimeProblem: String? = null
            val env = runtime()
            if (prepared.contents.runtimes.isNotEmpty()) {
                if (env == null) runtimeProblem = "Install ${prepared.contents.runtimes.joinToString(", ")} in Settings, Terminal and environment."
                else try {
                    val needed = env.available(prepared.contents.runtimes + "bash")
                    val missing = prepared.contents.runtimes.filter { it !in needed }
                    progress(PluginProgress("Installing runtime", detail = needed.joinToString(", ")))
                    env.install(needed, "Plugin ${prepared.contents.name}") { progress(PluginProgress("Installing runtime", detail = needed.joinToString(", "), packages = it)) }
                    if (missing.isNotEmpty()) runtimeProblem = "${missing.joinToString(", ")} is not offered by the Linux package repository."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { runtimeProblem = e.message ?: "Runtime installation failed." }
            }
            val record = withContext(Dispatchers.IO) { store.install(prepared, scope, label) }
            return record to runtimeProblem
        } finally { withContext(NonCancellable + Dispatchers.IO) { discard(prepared) } }
    }
    fun discard(prepared: PreparedPlugin) {
        require(Regex("[a-f0-9-]{36}").matches(prepared.token))
        File(previews, prepared.token).deleteRecursively()
    }
}

/** Real, bundled starter packs; community search results are labelled separately. */
object PluginStarters {
    val listings = listOf(
        PluginListing("review-workflow", "A code-review skill and instructions for reporting verified findings.", "1.0.0", PluginOrigin("builtin", "review-workflow"), "AndroidHarness", true, kind = "skill", source = "Built in"),
        PluginListing("test-workflow", "A regression-testing skill and instructions for checking changes.", "1.0.0", PluginOrigin("builtin", "test-workflow"), "AndroidHarness", true, kind = "skill", source = "Built in"),
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
