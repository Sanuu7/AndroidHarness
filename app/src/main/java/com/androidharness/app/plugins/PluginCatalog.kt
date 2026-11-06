package com.androidharness.app.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class CatalogSort { RELEVANCE, STARS, UPDATED, DOWNLOADS, NEWEST, OLDEST, NAME }
enum class CatalogStatus { ALL, INSTALLED, NOT_INSTALLED, UPDATES }

/** Where a listing came from, independent of the section title a user may have renamed. */
enum class CatalogSource { FEATURED, MARKETPLACE, GITHUB, NPM, MCP_REGISTRY, BUILT_IN }

/** What a listing needs before it can run. Plugins are only known after their bundle is checked, so they have neither. */
enum class CatalogSetup { ANY, NONE_NEEDED, RUNTIME }

data class CatalogFilter(
    val kind: String = "all",
    val source: CatalogSource? = null,
    val category: String = "",
    val status: CatalogStatus = CatalogStatus.ALL,
    val setup: CatalogSetup = CatalogSetup.ANY,
    val sort: CatalogSort = CatalogSort.RELEVANCE,
) {
    val active: Int get() = listOf(kind != "all", source != null, category.isNotBlank(), status != CatalogStatus.ALL, setup != CatalogSetup.ANY).count { it }
}

data class CatalogEntry(
    val listing: PluginListing,
    val section: String,
    val source: CatalogSource,
    val installed: List<InstalledPlugin>,
    val update: InstalledPlugin?,
) {
    val isInstalled get() = installed.isNotEmpty()
    val key get() = "$section-${listing.name}-${listing.origin.target}-${listing.origin.subdirectory}"
}

/** Matching, filtering and ranking for Discover. Pure, so it is tested without a device. */
object PluginCatalog {
    private val githubLink = Regex("github\\.com[/:]([A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}?)(?:\\.git)?(?:[/#?].*)?$")
    private val sha = Regex("[0-9a-f]{40}")

    /** The GitHub repository a listing installs from or links to, as owner/repo. */
    fun githubRepo(listing: PluginListing): String? = when {
        listing.origin.kind == "github" -> listing.origin.target.removeSuffix(".git")
        else -> githubLink.find(listing.homepage.trim())?.groupValues?.get(1)?.removeSuffix(".git")
    }?.takeIf { '/' in it }

    fun source(section: CatalogSection, listing: PluginListing): CatalogSource = when {
        listing.builtIn -> CatalogSource.BUILT_IN
        section.repo.isNotBlank() -> CatalogSource.MARKETPLACE
        section.title == "Featured" -> CatalogSource.FEATURED
        section.title == "GitHub" -> CatalogSource.GITHUB
        section.title == "npm" -> CatalogSource.NPM
        section.title == "MCP registry" -> CatalogSource.MCP_REGISTRY
        else -> CatalogSource.MARKETPLACE
    }

    /** Two origins install the same thing when they name the same package, repository folder or server. */
    fun identity(origin: PluginOrigin): String = when (origin.kind) {
        "mcp" -> "mcp:" + origin.name.ifBlank { runCatching { Json.parseToJsonElement(origin.target) as JsonObject }.getOrNull()?.keys?.firstOrNull().orEmpty() }.lowercase()
        "npm" -> "npm:" + origin.target.let { t -> t.lastIndexOf('@').takeIf { it > 0 }?.let { t.substring(0, it) } ?: t }.lowercase()
        "github" -> "github:" + origin.target.lowercase().removeSuffix(".git") + "/" + origin.subdirectory.trim('/').lowercase() +
            (if (origin.entry.isNotBlank() && origin.name.isNotBlank()) "#" + origin.name.lowercase() else "")
        else -> origin.kind + ":" + origin.target
    }

    fun installedFor(listing: PluginListing, installed: List<InstalledPlugin>): List<InstalledPlugin> {
        val id = identity(listing.origin)
        return installed.filter { identity(it.origin) == id }
    }

    /**
     * True only when the catalog proves something newer exists: a higher version, a different pinned commit,
     * or a repository pushed after the plugin was installed.
     */
    fun hasUpdate(listing: PluginListing, record: InstalledPlugin): Boolean {
        if (record.origin.kind in setOf("archive", "folder", "builtin", "installer")) return false
        val listed = listing.version.ifBlank { if (listing.origin.kind == "mcp") listing.origin.ref else "" }
        if (listed.isNotBlank() && record.version.isNotBlank()) {
            val newer = compareVersions(listed, record.version)
            if (newer != null) return newer > 0
        }
        if (listing.origin.kind == "github") {
            val want = listing.origin.ref.lowercase()
            val have = record.origin.ref.lowercase()
            if (sha.matches(want) && sha.matches(have)) return want != have
            if (sha.matches(want)) return false
            val pushed = listing.updatedAt ?: return false
            return pushed > record.installedAt + 60_000
        }
        return false
    }

    /** Numeric comparison of dotted versions such as v1.10.2; null when either side has no number. */
    fun compareVersions(a: String, b: String): Int? {
        fun parts(v: String) = Regex("\\d+").findAll(v.substringBefore('-').substringBefore('+')).map { it.value.toBigInteger() }.toList()
        val x = parts(a); val y = parts(b)
        if (x.isEmpty() || y.isEmpty()) return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(y.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (c != 0) return c
        }
        return 0
    }

    fun setup(listing: PluginListing): CatalogSetup? = when {
        listing.kind == "skill" -> CatalogSetup.NONE_NEEDED
        listing.origin.kind == "mcp" -> {
            val servers = runCatching { Json.parseToJsonElement(listing.origin.target) as JsonObject }.getOrNull()?.values.orEmpty()
            val remote = servers.isNotEmpty() && servers.all { server -> (server as? JsonObject)?.let { it["url"] != null && it["command"] == null } == true }
            val local = servers.any { (it as? JsonObject)?.get("command") != null }
            when { remote -> CatalogSetup.NONE_NEEDED; local -> CatalogSetup.RUNTIME; else -> null }
        }
        listing.kind == "mcp" && listing.origin.kind == "npm" -> CatalogSetup.RUNTIME
        else -> null
    }

    fun entries(sections: List<CatalogSection>, installed: List<InstalledPlugin>): List<CatalogEntry> = sections.flatMap { section ->
        section.listings.map { listing ->
            val matches = installedFor(listing, installed)
            CatalogEntry(listing, section.title, source(section, listing), matches, matches.firstOrNull { hasUpdate(listing, it) })
        }
    }

    fun categories(entries: List<CatalogEntry>): List<String> = entries.map { it.listing.category.trim() }.filter(String::isNotBlank)
        .groupBy { it.lowercase() }.values.sortedByDescending { it.size }.map { it.first().replaceFirstChar(Char::titlecase) }

    fun apply(entries: List<CatalogEntry>, filter: CatalogFilter): List<CatalogEntry> {
        val shown = entries.filter { entry ->
            (filter.kind == "all" || entry.listing.kind == filter.kind) &&
                (filter.source == null || entry.source == filter.source) &&
                (filter.category.isBlank() || entry.listing.category.trim().equals(filter.category, true)) &&
                (filter.setup == CatalogSetup.ANY || setup(entry.listing) == filter.setup) &&
                when (filter.status) {
                    CatalogStatus.ALL -> true
                    CatalogStatus.INSTALLED -> entry.isInstalled
                    CatalogStatus.NOT_INSTALLED -> !entry.isInstalled
                    CatalogStatus.UPDATES -> entry.update != null
                }
        }
        if (filter.sort == CatalogSort.RELEVANCE) return shown
        val unique = shown.distinctBy { identity(it.listing.origin) }
        return when (filter.sort) {
            CatalogSort.STARS -> unique.sortedWith(compareByDescending(nullsFirst()) { it.listing.stars })
            CatalogSort.UPDATED -> unique.sortedWith(compareByDescending(nullsFirst()) { it.listing.updatedAt })
            CatalogSort.DOWNLOADS -> unique.sortedWith(compareByDescending(nullsFirst()) { it.listing.downloads })
            CatalogSort.NEWEST -> unique.sortedWith(compareByDescending(nullsFirst()) { it.listing.createdAt ?: it.listing.updatedAt })
            CatalogSort.OLDEST -> unique.sortedWith(compareBy(nullsLast()) { it.listing.createdAt ?: it.listing.updatedAt })
            CatalogSort.NAME -> unique.sortedBy { it.listing.name.lowercase() }
            CatalogSort.RELEVANCE -> unique
        }
    }
}
