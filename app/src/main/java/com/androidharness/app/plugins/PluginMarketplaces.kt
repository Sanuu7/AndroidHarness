package com.androidharness.app.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class MarketplaceSource(val repo: String, val ref: String = "", val name: String = "")

/** Claude Code marketplace.json files: each entry becomes an installable listing with a concrete source. */
object PluginMarketplaces {
    val defaults = listOf(
        MarketplaceSource("anthropics/claude-plugins-official", name = "Claude plugins"),
        MarketplaceSource("anthropics/skills", name = "Anthropic skills"),
        MarketplaceSource("obra/superpowers-marketplace", name = "Superpowers"),
    )
    /** Hand-picked bundles shown before any search. Each one is installed and checked like any other source. */
    val featured = listOf(
        PluginListing("ui-ux-pro-max", "UI and UX design intelligence: styles, palettes, font pairings, charts and stack guidelines, with searchable local data.",
            "", PluginOrigin("github", "nextlevelbuilder/ui-ux-pro-max-skill"), "nextlevelbuilder", kind = "skill", category = "design", source = "Featured"),
        PluginListing("superpowers", "Core skills library: test-driven development, debugging, planning and collaboration workflows.",
            "", PluginOrigin("github", "obra/superpowers"), "obra", kind = "plugin", category = "development", source = "Featured"),
        PluginListing("document-skills", "Excel, Word, PowerPoint and PDF skills from Anthropic.", "",
            PluginOrigin("github", "anthropics/skills", entry = """{"name":"document-skills","skills":["./skills/xlsx","./skills/docx","./skills/pptx","./skills/pdf"]}""",
                marketplace = "anthropics/skills", name = "document-skills"), "Anthropic", kind = "skill", category = "documents", source = "Featured"),
        PluginListing("frontend-design", "Distinctive, production-grade frontend design without generic AI looks.", "",
            PluginOrigin("github", "anthropics/skills", subdirectory = "skills/frontend-design", name = "frontend-design"), "Anthropic", kind = "skill", category = "design", source = "Featured"),
        PluginListing("skill-creator", "Create, improve and test new skills.", "",
            PluginOrigin("github", "anthropics/skills", subdirectory = "skills/skill-creator", name = "skill-creator"), "Anthropic", kind = "skill", category = "development", source = "Featured"),
        PluginListing("context7", "Up-to-date library documentation for any package, served over MCP.", "",
            PluginOrigin("mcp", """{"context7":{"type":"http","url":"https://mcp.context7.com/mcp"}}""", name = "context7"), "Upstash", kind = "mcp", category = "documentation", source = "Featured"),
    )

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun join(vararg parts: String) = parts.flatMap { it.replace('\\', '/').split('/') }.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    private fun githubRepo(url: String): String? =
        Regex("^(?:https://|git@|ssh://git@)github\\.com[/:]([A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}?)(?:\\.git)?/?$").find(url.trim())?.groupValues?.get(1)

    fun entries(marketplace: JsonObject, base: PluginOrigin, label: String): List<PluginListing> {
        val pluginRoot = (marketplace["metadata"] as? JsonObject)?.get("pluginRoot").str().orEmpty()
        val owner = (marketplace["owner"] as? JsonObject)?.get("name").str().orEmpty()
        val repo = if (base.kind == "github") base.target else base.marketplace
        return (marketplace["plugins"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry["name"].str() ?: return@mapNotNull null
            val entryJson = entry.toString().takeIf { it.length <= 64_000 }.orEmpty()
            val origin = when (val source = entry["source"]) {
                is JsonPrimitive -> base.copy(subdirectory = join(base.subdirectory, pluginRoot, source.content), entry = entryJson, marketplace = repo, name = name)
                is JsonObject -> when (source["source"].str()) {
                    "github" -> PluginOrigin("github", source["repo"].str() ?: return@mapNotNull null, source["sha"].str() ?: source["ref"].str().orEmpty(),
                        join(source["path"].str().orEmpty()), entryJson, repo, name)
                    "url", "git" -> githubRepo(source["url"].str().orEmpty())?.let {
                        PluginOrigin("github", it, source["sha"].str() ?: source["ref"].str().orEmpty(), "", entryJson, repo, name)
                    } ?: PluginOrigin("git", source["url"].str() ?: return@mapNotNull null, source["ref"].str().orEmpty(), "", entryJson, repo, name)
                    "git-subdir" -> githubRepo(source["url"].str().orEmpty())?.let {
                        PluginOrigin("github", it, source["sha"].str() ?: source["ref"].str().orEmpty(), join(source["path"].str().orEmpty()), entryJson, repo, name)
                    } ?: return@mapNotNull null
                    "npm" -> PluginOrigin("npm", source["package"].str() ?: return@mapNotNull null, source["version"].str() ?: "latest", "", entryJson, repo, name)
                    else -> return@mapNotNull null
                }
                else -> return@mapNotNull null
            }
            val kind = when {
                entry["mcpServers"] != null && entry["skills"] == null && entry["commands"] == null -> "mcp"
                entry["skills"] != null && entry["commands"] == null && entry["agents"] == null -> "skill"
                else -> "plugin"
            }
            PluginListing(name, entry["description"].str().orEmpty().take(2000), entry["version"].str().orEmpty(), origin,
                (entry["author"] as? JsonObject)?.get("name").str() ?: entry["author"].str() ?: owner, kind = kind,
                category = entry["category"].str().orEmpty(), source = label, homepage = entry["homepage"].str().orEmpty())
        }.take(1000)
    }
}

/** User-chosen marketplaces. The defaults appear until the user changes the list. */
class MarketplaceStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    fun list(): List<MarketplaceSource> = synchronized(lock) {
        if (!file.isFile) return PluginMarketplaces.defaults
        runCatching { json.decodeFromString(ListSerializer(MarketplaceSource.serializer()), file.readText()) }.getOrDefault(PluginMarketplaces.defaults)
    }
    fun add(source: MarketplaceSource) = synchronized(lock) {
        val current = list()
        require(current.none { it.repo.equals(source.repo, true) }) { "This marketplace is already added." }
        require(current.size < 50) { "At most 50 marketplaces can be added." }
        save(current + source)
    }
    fun remove(repo: String) = synchronized(lock) { save(list().filterNot { it.repo.equals(repo, true) }) }
    private fun save(sources: List<MarketplaceSource>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(json.encodeToString(ListSerializer(MarketplaceSource.serializer()), sources))
        require(temporary.renameTo(file)) { "Cannot save the marketplace list." }
    }
}
