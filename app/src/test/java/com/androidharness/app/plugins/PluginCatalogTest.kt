package com.androidharness.app.plugins

import org.junit.Assert.*
import org.junit.Test

class PluginCatalogTest {
    private fun record(origin: PluginOrigin, version: String = "1.0.0", installedAt: Long = 1_000_000L, scope: String = "global") =
        InstalledPlugin("id-${origin.target}-${origin.subdirectory}", origin.name.ifBlank { origin.target }, version, "", origin, "rev", scope,
            if (scope == "global") "Global" else "My app", "/dir", "digest", installedAt = installedAt)

    @Test fun githubListingsMatchTheirInstalledFolderOnly() {
        val skill = PluginListing("frontend-design", "", "", PluginOrigin("github", "Anthropics/Skills", subdirectory = "skills/frontend-design"))
        val other = PluginListing("skill-creator", "", "", PluginOrigin("github", "anthropics/skills", subdirectory = "skills/skill-creator"))
        val installed = listOf(record(PluginOrigin("github", "anthropics/skills", "abc", "skills/frontend-design/")))
        assertEquals(1, PluginCatalog.installedFor(skill, installed).size)
        assertTrue(PluginCatalog.installedFor(other, installed).isEmpty())
    }

    @Test fun marketplaceEntriesSharingAFolderAreToldApartByName() {
        val docs = PluginOrigin("github", "anthropics/skills", entry = "{}", name = "document-skills")
        val examples = PluginOrigin("github", "anthropics/skills", entry = "{}", name = "example-skills")
        val installed = listOf(record(docs))
        assertEquals(1, PluginCatalog.installedFor(PluginListing("document-skills", "", "", docs), installed).size)
        assertTrue(PluginCatalog.installedFor(PluginListing("example-skills", "", "", examples), installed).isEmpty())
    }

    @Test fun npmAndMcpMatchIgnoringVersions() {
        val npm = PluginListing("pkg", "", "2.0.0", PluginOrigin("npm", "@team/pkg", "latest"))
        assertEquals(1, PluginCatalog.installedFor(npm, listOf(record(PluginOrigin("npm", "@team/pkg@1.0.0", "1.0.0")))).size)
        val mcp = PluginListing("ctx", "", "", PluginOrigin("mcp", """{"context7":{"url":"https://x"}}""", name = "context7"))
        assertEquals(1, PluginCatalog.installedFor(mcp, listOf(record(PluginOrigin("mcp", """{"context7":{"url":"https://y"}}""")))).size)
    }

    @Test fun updatesNeedProofOfSomethingNewer() {
        val npm = PluginOrigin("npm", "pkg")
        assertTrue(PluginCatalog.hasUpdate(PluginListing("pkg", "", "1.10.0", npm), record(npm, "1.9.3")))
        assertFalse(PluginCatalog.hasUpdate(PluginListing("pkg", "", "1.9.3", npm), record(npm, "1.9.3")))
        assertFalse(PluginCatalog.hasUpdate(PluginListing("pkg", "", "1.0.0", npm), record(npm, "2.0.0")))
        val repo = PluginOrigin("github", "o/r")
        assertTrue(PluginCatalog.hasUpdate(PluginListing("r", "", "", repo, updatedAt = 5_000_000L), record(repo, "", installedAt = 1_000_000L)))
        assertFalse(PluginCatalog.hasUpdate(PluginListing("r", "", "", repo, updatedAt = 1_010_000L), record(repo, "", installedAt = 1_000_000L)))
        val pinned = { sha: String -> PluginOrigin("github", "o/r", sha) }
        assertTrue(PluginCatalog.hasUpdate(PluginListing("r", "", "", pinned("a".repeat(40))), record(pinned("b".repeat(40)), "")))
        assertFalse(PluginCatalog.hasUpdate(PluginListing("r", "", "", pinned("a".repeat(40)), updatedAt = Long.MAX_VALUE), record(repo, "")))
        val local = PluginOrigin("archive", "content://x")
        assertFalse(PluginCatalog.hasUpdate(PluginListing("x", "", "9.0.0", local), record(local, "1.0.0")))
    }

    @Test fun filtersAndSortsWithMissingValuesLast() {
        val a = PluginListing("alpha", "", "", PluginOrigin("github", "o/a"), stars = 10, updatedAt = 3, kind = "skill", category = "design")
        val b = PluginListing("beta", "", "", PluginOrigin("github", "o/b"), stars = 500, updatedAt = 1, kind = "plugin")
        val c = PluginListing("gamma", "", "", PluginOrigin("npm", "gamma"), downloads = 99, kind = "mcp")
        val sections = listOf(CatalogSection("GitHub", listOf(a, b)), CatalogSection("npm", listOf(c)), CatalogSection("Featured", listOf(a)))
        val entries = PluginCatalog.entries(sections, listOf(record(PluginOrigin("github", "o/a"))))
        fun names(filter: CatalogFilter) = PluginCatalog.apply(entries, filter).map { it.listing.name }
        assertEquals(listOf("beta", "alpha", "gamma"), names(CatalogFilter(sort = CatalogSort.STARS)))
        assertEquals(listOf("alpha", "beta", "gamma"), names(CatalogFilter(sort = CatalogSort.UPDATED)))
        assertEquals(listOf("gamma", "alpha", "beta"), names(CatalogFilter(sort = CatalogSort.DOWNLOADS)))
        assertEquals(listOf("alpha", "alpha"), names(CatalogFilter(status = CatalogStatus.INSTALLED)))
        assertEquals(listOf("beta", "gamma"), names(CatalogFilter(status = CatalogStatus.NOT_INSTALLED)))
        assertEquals(listOf("gamma"), names(CatalogFilter(source = CatalogSource.NPM)))
        assertEquals(listOf("alpha", "alpha"), names(CatalogFilter(category = "Design")))
        assertEquals(listOf("Design"), PluginCatalog.categories(entries))
    }

    @Test fun setupTellsRemoteServersFromLocalOnes() {
        val remote = PluginListing("r", "", "", PluginOrigin("mcp", """{"r":{"type":"http","url":"https://x"}}"""), kind = "mcp")
        val local = PluginListing("l", "", "", PluginOrigin("mcp", """{"l":{"command":"npx","args":["-y","x"]}}"""), kind = "mcp")
        assertEquals(CatalogSetup.NONE_NEEDED, PluginCatalog.setup(remote))
        assertEquals(CatalogSetup.RUNTIME, PluginCatalog.setup(local))
        assertNull(PluginCatalog.setup(PluginListing("p", "", "", PluginOrigin("github", "o/p"))))
    }

    @Test fun githubRepoComesFromOriginOrLink() {
        assertEquals("o/r", PluginCatalog.githubRepo(PluginListing("x", "", "", PluginOrigin("github", "o/r.git"))))
        assertEquals("o/r", PluginCatalog.githubRepo(PluginListing("x", "", "", PluginOrigin("npm", "x"), homepage = "git+https://github.com/o/r.git")))
        assertEquals("o/r", PluginCatalog.githubRepo(PluginListing("x", "", "", PluginOrigin("npm", "x"), homepage = "https://github.com/o/r#readme")))
        assertNull(PluginCatalog.githubRepo(PluginListing("x", "", "", PluginOrigin("npm", "x"), homepage = "https://example.com")))
    }

    @Test fun registryTimesParse() {
        assertEquals(0L, PluginDownloads.epochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_000L, PluginDownloads.epochMillis("1970-01-01T00:00:01.000+00:00"))
        assertNull(PluginDownloads.epochMillis("soon"))
    }
}
