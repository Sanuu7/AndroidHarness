package com.androidharness.app.plugins

import com.androidharness.app.config.*
import com.androidharness.app.workspace.FileFs
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file(root: File, path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private fun skill(name: String) = "---\nname: $name\ndescription: Use this skill to review code changes.\ncategory: development\n---\nReview carefully.\n"
    private fun bundle(name: String = "test-plugin", version: String = "1.0.0", mcp: Boolean = false): File {
        val root = tmp.newFolder()
        file(root, "instructions.md", "PLUGIN_INSTRUCTIONS_$version")
        file(root, "skills/review/SKILL.md", skill("review"))
        file(root, "index.js", "module.exports={'prompt.before':()=>({instructions:'HOOK'})};")
        file(root, "server.js", "// standalone server fixture")
        file(root, "plugin.json", buildJsonObject {
            put("name", name); put("version", version); put("description", "Test plugin")
            put("skills", buildJsonArray { add("skills") }); put("instructions", buildJsonArray { add("instructions.md") })
            put("hooks", buildJsonArray { add("prompt.before") }); put("entry", "index.js")
            if (mcp) put("mcp", buildJsonObject { put("test", buildJsonObject {
                put("command", buildJsonArray { add("node"); add("{plugin:root}/server.js") })
            }) })
        }.toString())
        return root
    }
    private fun prepared(root: File, old: InstalledPlugin? = null) = PreparedPlugin(UUID.randomUUID().toString(), root,
        PluginOrigin("npm", "test-plugin", "latest"), PluginManifest.inspect(root).version, PluginManifest.inspect(root), PluginManifest.digest(root), old?.id, old?.digest)
    private fun fails(block: () -> Unit): String {
        try { block(); fail("Expected plugin operation to be refused") } catch (e: Exception) { return e.message.orEmpty() }
        return ""
    }
    private fun zip(vararg files: Pair<String, ByteArray>): File = tmp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }
    private fun tar(link: Byte? = null): File = tmp.newFile().apply {
        TarArchiveOutputStream(GZIPOutputStream(outputStream())).use { out ->
            val entry = if (link == null) TarArchiveEntry("package/plugin.json").apply { size = 2 }
                else TarArchiveEntry("package/link", link).apply { linkName = "../../outside" }
            out.putArchiveEntry(entry)
            if (link == null) out.write("{}".toByteArray())
            out.closeArchiveEntry(); out.finish()
        }
    }

    @Test fun `zip and npm tar archives preserve bundle files`() {
        val out = tmp.newFolder()
        PluginArchives.extract(zip("wrapper/plugin.json" to "{}".toByteArray()), out)
        assertEquals("{}", out.resolve("wrapper/plugin.json").readText())
        val npm = tmp.newFolder(); PluginArchives.extract(tar(), npm)
        assertEquals("{}", npm.resolve("package/plugin.json").readText())
    }
    @Test fun `archive traversal absolute paths windows paths and duplicate names are rejected`() {
        listOf("../escaped", "/absolute", "C:/windows", "dir/../../escaped", "dir\\file").forEach { path ->
            val out = tmp.newFolder()
            fails { PluginArchives.extract(zip(path to byteArrayOf(1)), out) }
            assertFalse(out.exists())
        }
        fails { PluginArchives.extract(zip("readme" to byteArrayOf(1), "README" to byteArrayOf(2)), tmp.newFolder()) }
    }
    @Test fun `zip symlinks tar links and special files are rejected`() {
        for (kind in listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_CHR)) {
            fails { PluginArchives.extract(tar(kind), tmp.newFolder()) }
        }
        val archive = tmp.newFile()
        ZipArchiveOutputStream(archive).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("link").apply { unixMode = 0xA1FF })
            out.write("../../outside".toByteArray()); out.closeArchiveEntry(); out.finish()
        }
        fails { PluginArchives.extract(archive, tmp.newFolder()) }
    }
    @Test fun `expanded file size is bounded even when zip size metadata is absent`() {
        val archive = zip("bomb" to ByteArray(PluginArchives.MAX_FILE.toInt() + 1))
        assertTrue(archive.length() < 100_000)
        fails { PluginArchives.extract(archive, tmp.newFolder()) }
    }
    @Test fun `npm integrity prefers SHA512 and rejects altered bytes`() {
        val archive = tmp.newFile().apply { writeText("package bytes") }
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(archive.readBytes()))
        PluginDownloads.verifyIntegrity(archive, "sha512-$hash")
        archive.appendText("tampered")
        assertTrue(fails { PluginDownloads.verifyIntegrity(archive, "sha512-$hash") }.contains("integrity"))
    }
    @Test fun `npm package names exact versions scoped packages and tags are parsed without executing commands`() {
        assertEquals("plugin" to "latest", PluginDownloads.npmTarget("plugin"))
        assertEquals("@team/plugin" to "1.2.3", PluginDownloads.npmTarget("@team/plugin@1.2.3"))
        assertEquals("@team/plugin" to "beta", PluginDownloads.npmTarget("@team/plugin@beta"))
        listOf("https://evil/package", "plugin;echo hi", "plugin@^1.2", "../package", "@scope/plugin@", "$(command)").forEach { value -> fails { PluginDownloads.npmTarget(value) } }
    }
    @Test fun `github source parsing accepts repository URLs and refuses commands and ambiguous tree paths`() {
        assertEquals("owner/repo", PluginDownloads.githubTarget("https://github.com/owner/repo.git"))
        assertEquals("owner/repo", PluginDownloads.githubTarget("owner/repo"))
        listOf("https://other.test/owner/repo", "owner/repo/tree/main/plugin", "owner/..", "owner/repo;command").forEach { fails { PluginDownloads.githubTarget(it) } }
    }
    @Test fun `manifest review reports every contribution before install`() {
        val contents = PluginManifest.inspect(bundle(mcp = true))
        assertEquals(listOf("instructions.md"), contents.instructions)
        assertEquals(listOf("review"), contents.skills)
        assertEquals(listOf("test"), contents.servers)
        assertEquals(listOf("prompt.before"), contents.hooks)
        assertTrue(contents.needsRuntime)
    }
    @Test fun `incompatible missing and escaping components fail inspection`() {
        val root = tmp.newFolder(); fails { PluginManifest.inspect(root) }
        file(root, "plugin.json", """{"name":"test","version":"1","instructions":["../secret"]}""")
        fails { PluginManifest.inspect(root) }
        file(root, "plugin.json", """{"name":"test","version":"1","hooks":["unknown"],"entry":"index.js"}""")
        fails { PluginManifest.inspect(root) }
        file(root, "plugin.json", """{"name":"test","version":"1","mcp":{"test":{"type":"local"}}}}""")
        fails { PluginManifest.inspect(root) }
        listOf("!rules.md", "@shared/rules.md", "~/rules.md", "rules*.md").forEach { path ->
            file(root, path, "Rule")
            file(root, "plugin.json", buildJsonObject {
                put("name", "test"); put("version", "1"); put("instructions", buildJsonArray { add(path) })
            }.toString())
            assertTrue(fails { PluginManifest.inspect(root) }.contains("literal bundle-relative"))
        }
    }
    @Test fun `install enable disable and uninstall change only owned contributions`() {
        val storage = tmp.newFolder(); val workspace = tmp.newFolder()
        file(workspace, "harness.jsonc", "// untouched user file\n{}")
        val store = PluginStore(storage)
        val record = store.install(prepared(bundle()), "project:fixture", "Fixture")
        assertEquals(1, store.list().size)
        val sources = store.configurationSources("project:fixture")
        val config = HarnessConfigLoader(managedPlugins = sources).load(FileFs(workspace))
        assertTrue(config.prompt().contains("PLUGIN_INSTRUCTIONS_1.0.0"))
        assertTrue("review" in config.skills)
        assertEquals(1, config.plugins.size)
        store.setEnabled(record.id, false)
        assertTrue(store.configurationSources("project:fixture").isEmpty())
        store.setEnabled(record.id, true)
        assertEquals(1, store.configurationSources("project:fixture").size)
        store.uninstall(record.id)
        assertTrue(store.list().isEmpty())
        assertEquals("// untouched user file\n{}", workspace.resolve("harness.jsonc").readText())
        // Active run references still point to the old immutable files until process startup.
        assertTrue(config.prompt().contains("PLUGIN_INSTRUCTIONS_1.0.0"))
        assertTrue(store.directory(record).isDirectory)
    }
    @Test fun `project plugin overrides same named global bundle without leaking to other projects`() {
        val store = PluginStore(tmp.newFolder())
        store.install(prepared(bundle(version = "1.0.0")), "global", "Global")
        store.install(prepared(bundle(version = "2.0.0")), "project:a", "A")
        val a = HarnessConfigLoader(managedPlugins = store.configurationSources("project:a")).load(FileFs(tmp.newFolder()))
        val b = HarnessConfigLoader(managedPlugins = store.configurationSources("project:b")).load(FileFs(tmp.newFolder()))
        assertTrue(a.prompt().contains("PLUGIN_INSTRUCTIONS_2.0.0")); assertFalse(a.prompt().contains("PLUGIN_INSTRUCTIONS_1.0.0"))
        assertTrue(b.prompt().contains("PLUGIN_INSTRUCTIONS_1.0.0")); assertFalse(b.prompt().contains("PLUGIN_INSTRUCTIONS_2.0.0"))
    }
    @Test fun `updates are transactional stale previews fail and the old run snapshot stays usable`() {
        val store = PluginStore(tmp.newFolder())
        val old = store.install(prepared(bundle()), "global", "Global")
        val update = prepared(bundle(version = "2.0.0"), old)
        val next = store.install(update, "global", "Global")
        assertEquals(old.id, next.id); assertEquals("2.0.0", store.list().single().version)
        assertTrue(store.directory(old).isDirectory)
        fails { store.install(prepared(bundle(version = "3.0.0"), old), "global", "Global") }
        assertEquals("2.0.0", store.list().single().version)
    }
    @Test fun `tampered preview and duplicate installations cannot replace a reviewed package`() {
        val store = PluginStore(tmp.newFolder())
        val candidate = prepared(bundle())
        candidate.root.resolve("instructions.md").appendText("Changed")
        fails { store.install(candidate, "global", "Global") }
        assertTrue(store.list().isEmpty())
        store.install(prepared(bundle()), "global", "Global")
        fails { store.install(prepared(bundle()), "global", "Global") }
        assertEquals(1, store.list().size)
    }
    @Test fun `bundle MCP servers are namespaced device local and bound to plugin root`() {
        val store = PluginStore(tmp.newFolder())
        val record = store.install(prepared(bundle(mcp = true)), "global", "Global")
        val config = HarnessConfigLoader(managedPlugins = store.configurationSources("project:a")).load(FileFs(tmp.newFolder()))
        val server = config.servers.single()
        assertTrue(server.name.startsWith("test_plugi_"))
        assertTrue(server.deviceLocal)
        assertEquals(store.directory(record).resolve("server.js").canonicalPath, server.args.single())
    }
    @Test fun `similarly named MCP servers remain distinct after namespacing`() {
        val root = bundle()
        val manifest = Json.parseToJsonElement(root.resolve("plugin.json").readText()).jsonObject
        file(root, "plugin.json", JsonObject(manifest + ("mcp" to buildJsonObject {
            listOf("same-prefix-long-one", "same-prefix-long-two", "Case-One", "Case-Two").forEach { key -> put(key, buildJsonObject {
                put("type", "remote"); put("url", "https://example.invalid/mcp")
            }) }
        })).toString())
        val store = PluginStore(tmp.newFolder())
        store.install(prepared(root), "global", "Global")
        val config = HarnessConfigLoader(managedPlugins = store.configurationSources("project:a")).load(FileFs(tmp.newFolder()))
        assertEquals(4, config.servers.map { it.name }.toSet().size)
        assertTrue(config.servers.all { it.name.length <= 32 })
    }
    @Test fun `two bundles keep distinct file-variable origins when merged`() {
        val store = PluginStore(tmp.newFolder())
        for ((name, token) in listOf("first" to "one", "second" to "two")) {
            val root = bundle(name, mcp = true)
            file(root, "token", token)
            val manifest = Json.parseToJsonElement(root.resolve("plugin.json").readText()).jsonObject
            val server = manifest["mcp"]!!.jsonObject["test"]!!.jsonObject
            val updated = JsonObject(manifest + ("mcp" to buildJsonObject { put("test", JsonObject(server + ("env" to buildJsonObject { put("TOKEN", "{file:token}") }))) }))
            root.resolve("plugin.json").writeText(updated.toString())
            store.install(prepared(root), "global", "Global")
        }
        val config = HarnessConfigLoader(managedPlugins = store.configurationSources("project:a")).load(FileFs(tmp.newFolder()))
        assertEquals(setOf("one", "two"), config.servers.map { it.env.getValue("TOKEN") }.toSet())
    }
    @Test fun `modified installed files are refused and registry corruption never silently enables packages`() {
        val storage = tmp.newFolder(); val store = PluginStore(storage)
        val record = store.install(prepared(bundle()), "global", "Global")
        store.directory(record).resolve("instructions.md").writeText("Tampered")
        fails { store.configurationSources("project:a") }
        storage.resolve("installed.json").writeText("invalid")
        val restarted = PluginStore(storage)
        assertTrue(restarted.registryBroken())
        fails { restarted.list() }
        restarted.resetBrokenRegistry()
        assertTrue(restarted.list().isEmpty())
    }
    @Test fun `starter packs are real inspectable compatible bundles`() {
        PluginStarters.listings.forEach { listing ->
            val directory = tmp.newFolder(); PluginStarters.materialize(listing.origin.target, directory)
            val contents = PluginManifest.inspect(directory)
            assertEquals(listing.name, contents.name)
            assertTrue(contents.skills.isNotEmpty()); assertTrue(contents.instructions.isNotEmpty())
        }
    }
}
