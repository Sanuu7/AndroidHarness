package com.androidharness.app.config

import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.skills.SkillStore
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HarnessConfigTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file(root: File, path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private fun load(root: File, global: File? = null) = HarnessConfigLoader(global?.let(::FileFs)).load(FileFs(root))
    private fun fails(block: () -> Unit): String {
        try { block(); fail("Expected a configuration error") } catch (e: Exception) { return e.message.orEmpty() }
        return ""
    }
    private fun skill(name: String) = "---\nname: $name\ndescription: Use this skill for Android application development.\ncategory: android\n---\nInstructions for $name.\n"

    @Test fun `jsonc respects quoted comment markers unicode and escapes`() {
        val value = ConfigDocument.parse("""{
          // a comment
          "url": "https://example.test/a//b", /* another */
          "quoted": "a\"//b", "unicode": "\u263a", "array": [true, null, -1.2e3,],
        }""", "test.jsonc")
        assertEquals("https://example.test/a//b", value["url"]?.string())
        assertEquals("a\"//b", value["quoted"]?.string())
        assertEquals("☺", value["unicode"]?.string())
        assertEquals(3, value["array"]?.items?.size)
    }
    @Test fun `strict syntax duplicate keys and diagnostics are preserved`() {
        assertTrue(fails { ConfigDocument.parse("{\n \"a\": 1,\n}", "test.json") }.startsWith("test.json:3:1:"))
        assertTrue(fails { ConfigDocument.parse("{\"a\":1,\"a\":2}", "test.json") }.contains("Duplicate"))
        listOf("{\"a\":01}", "{\"a\":NaN}", "{\"a\":\"\\x\"}", "{\"a\":true}x", "[1]", "{/* unclosed").forEach {
            fails { ConfigDocument.parse(it, "test.jsonc") }
        }
    }
    @Test fun `merge concatenates arrays preserves origins and null clears`() {
        val a = ConfigDocument.parse("""{"instructions":["one.md"],"skills":{"paths":["one"],"disable":["a"]}}""", "global.json")
        val b = ConfigDocument.parse("""{"instructions":["two.md"],"skills":{"paths":null,"disable":["b"]}}""", "project.json")
        val merged = ConfigDocument.merge(a, b)
        assertEquals(listOf("one.md", "two.md"), merged["instructions"]?.strings())
        assertEquals(listOf("global.json", "project.json"), merged["instructions"]?.items?.map { it.source })
        assertNull(merged["skills"]?.get("paths"))
        assertEquals(listOf("a", "b"), merged["skills"]?.get("disable")?.strings())
    }
    @Test fun `no config returns the unchanged legacy path`() {
        val root = tmp.newFolder()
        file(root, "AGENTS.md", "Legacy")
        val config = load(root)
        assertFalse(config.present)
        assertTrue(config.instructions.isEmpty())
    }
    @Test fun `global relative files keep their own base after a project merge`() {
        val home = tmp.newFolder(); val root = tmp.newFolder()
        file(home, "config/harness.json", """{"instructions":["rules.md"]}""")
        file(home, "config/rules.md", "Global rules")
        file(root, "harness.json", """{"instructions":["rules.md"]}""")
        file(root, "rules.md", "Project rules")
        assertEquals(listOf("Global rules", "Project rules"), load(root, home).instructions.map { it.text })
    }
    @Test fun `json takes precedence with a visible notice and no jsonc merge`() {
        val root = tmp.newFolder()
        file(root, "harness.json", "{}")
        file(root, "harness.jsonc", "invalid ignored file")
        val config = load(root)
        assertEquals(1, config.sources.size)
        assertTrue(config.warnings.single().contains("takes precedence"))
    }
    @Test fun `both conventional docs load exactly once and exclusions opt out`() {
        val root = tmp.newFolder()
        file(root, "AGENTS.md", "Agents")
        file(root, "HARNESS.md", "Harness")
        file(root, "harness.json", """{"instructions":["AGENTS.md", "!HARNESS.md"]}""")
        assertEquals(listOf("Agents"), load(root).instructions.map { it.text })
    }
    @Test fun `globs sort and exclude files while references remain lazy`() = runBlocking {
        val root = tmp.newFolder()
        file(root, "harness.jsonc", """{"instructions":["rules/**/*.md","!rules/archive/**"],"references":[{"title":"Design","path":"docs/*.md","glob":true}]}""")
        file(root, "rules/b.md", "B"); file(root, "rules/a.md", "A"); file(root, "rules/archive/old.md", "Old")
        file(root, "docs/architecture.md", "Reference content that stays off the prompt")
        val config = load(root)
        assertEquals(listOf("rules/a.md", "rules/b.md"), config.instructions.map { it.path })
        assertFalse(config.prompt().contains("Reference content"))
        val result = ConfigReferenceReadTool(config).execute(buildJsonObject { put("name", "1") }, ToolContext(FileFs(root)))
        assertTrue(result.output.contains("Reference content"))
    }
    @Test fun `invalid lower layer cannot be hidden by an override`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"skills":{"paths":42}}""")
        assertTrue(fails { HarnessConfigLoader().load(FileFs(root), inline = """{"skills":null}""") }.contains("array"))
    }
    @Test fun `all layers obey ordering and inline null reset`() {
        val root = tmp.newFolder(); val home = tmp.newFolder()
        file(home, "config/harness.json", """{"permissions":{"edit":"ask"}}""")
        file(root, "harness.json", """{"permissions":{"network":"deny"}}""")
        file(root, "extra.jsonc", """{"permissions":{"edit":"deny"}}""")
        val config = HarnessConfigLoader(FileFs(home)).load(FileFs(root), "extra.jsonc", """{"permissions":{"network":null}}""")
        assertEquals(mapOf("edit" to "deny"), config.permissions)
        assertEquals(4, config.sources.size)
    }
    @Test fun `missing files path traversal and symlink escapes fail closed`() {
        val root = tmp.newFolder(); val outside = tmp.newFolder()
        file(root, "harness.json", """{"instructions":["missing.md"]}""")
        assertTrue(fails { load(root) }.contains("missing"))
        file(root, "harness.json", """{"instructions":["../outside.md"]}""")
        fails { load(root) }
        file(outside, "secret.md", "SECRET")
        java.nio.file.Files.createSymbolicLink(root.resolve("linked.md").toPath(), outside.resolve("secret.md").toPath())
        file(root, "harness.json", """{"instructions":["linked.md"]}""")
        assertTrue(fails { load(root) }.contains("granted folder"))
    }
    @Test fun `folder aliases do not grant tool access outside the workspace`() {
        val root = tmp.newFolder(); val shared = tmp.newFolder()
        file(shared, "rules.md", "Shared rules")
        file(root, "harness.json", """{"instructions":["@shared/rules.md"]}""")
        val config = HarnessConfigLoader(folders = mapOf("shared" to FileFs(shared))).load(FileFs(root))
        assertEquals("Shared rules", config.instructions.single().text)
        fails { FileFs(root).resolve("../outside") }
    }
    @Test fun `skill paths filters autoload and supporting files use the same configured store`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"skills":{"paths":["extra"],"enable":["android-*"],"disable":["*-experimental"],"auto_load":["android-safe"]}}""")
        file(root, "extra/android-safe/SKILL.md", skill("android-safe"))
        file(root, "extra/android-safe/references/details.md", "Support")
        file(root, "extra/android-experimental/SKILL.md", skill("android-experimental"))
        val config = load(root)
        val store = SkillStore(emptyMap(), tmp.newFolder(), { null }, { emptySet() }).configured(config)
        assertEquals(listOf("android-safe"), store.enabled().map { it.name })
        assertTrue(store.view("android-experimental").isFailure)
        assertEquals("Support", store.view("android-safe", "references/details.md").getOrThrow().content)
        file(root, "extra/android-safe/SKILL.md", skill("changed"))
        assertEquals("android-safe", store.view("android-safe").getOrThrow().name)
    }
    @Test fun `mcp commands substitutions and disabled secrets are handled without a shell`() {
        val root = tmp.newFolder()
        file(root, "token", "file-token\n")
        file(root, "harness.jsonc", """{"mcp":{
          "local":{"type":"local","command":["node","server.js"],"args":["--port","1234"],"env":{"A":"{env:TOKEN}","B":"{file:token}","C":"{secret:SAVED}"}},
          "off":{"type":"remote","url":"https://example.test/mcp","headers":{"Authorization":"{secret:MISSING}"},"enabled":false}
        }}""")
        val config = HarnessConfigLoader(environment = { if (it == "TOKEN") "env-token" else null }, secret = { if (it == "SAVED") "saved-token" else null }).load(FileFs(root))
        val server = config.servers.first()
        assertEquals("node", server.command)
        assertEquals(listOf("server.js", "--port", "1234"), server.args)
        assertEquals(mapOf("A" to "env-token", "B" to "file-token", "C" to "saved-token"), server.env)
        val context = ConfigRunContext(config, SkillStore(emptyMap(), tmp.newFolder(), { null }, { emptySet() }))
        assertFalse(context.redact("file-token env-token saved-token").contains("token"))
    }
    @Test fun `schema rejects misspellings wrong types transport conflicts and unknown default agents`() {
        val samples = listOf("""{"instruction":[]}""", """{"skills":{"disable":true}}""", """{"default_agent":"missing"}""",
            """{"mcp":{"x":{"command":[]}}}""", """{"mcp":{"x":{"command":"node","type":"remote"}}}""",
            """{"permissions":{"network":"yes"}}""", """{"memory":{"file":"../notes"}}""", """{"memory":{"max_core_lines":0}}""")
        samples.forEach { sample -> fails { ConfigValidation.validate(ConfigDocument.parse(sample, "test.json"), mapOf("test.json" to sample)) } }
    }
    @Test fun `agent restrictions and project permissions only narrow app behavior`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"agents":{"reviewer":{"tools":{"write":false}}},"default_agent":"reviewer","permissions":{"edit":"ask","network":"deny"}}""")
        val config = load(root)
        val context = ConfigRunContext(config, SkillStore(emptyMap(), tmp.newFolder(), { null }, { emptySet() }))
        assertTrue(context.denies(WriteFileTool()))
        assertTrue(context.child("default").denies(WriteFileTool()))
        assertFalse(context.denies(ReadFileTool()))
        assertEquals("ask", ConfigToolPolicy.permission(config, WriteFileTool(), PermissionMode.FULL_AUTO))
        assertEquals("allow", ConfigToolPolicy.permission(config, WriteFileTool(), PermissionMode.FULL_ACCESS))
        val opaque = object : Tool {
            override val name = "mcp__test__run"; override val description = "Test"; override val isReadOnly = false
            override val parametersSchema = buildJsonObject {}
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
        }
        assertEquals("deny", ConfigToolPolicy.permission(config, opaque, PermissionMode.FULL_AUTO))
    }
    @Test fun `plugin bundles load skills and scripts and fingerprint changes with entry contents`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"plugins":{"paths":[".harness/plugins"]}}""")
        file(root, ".harness/plugins/test/plugin.json", """{"name":"test","version":"1","entry":"index.js","hooks":["tool.before"],"skills":["skills"]}""")
        val script = file(root, ".harness/plugins/test/index.js", "module.exports={'tool.before': () => ({deny:'No'})};")
        file(root, ".harness/plugins/test/skills/android-safe/SKILL.md", skill("android-safe"))
        val before = load(root)
        assertEquals("test", before.plugins.single().name)
        assertTrue("android-safe" in before.skills)
        script.writeText("module.exports={'tool.before': () => ({})};")
        assertNotEquals(before.fingerprint, load(root).fingerprint)
    }
    @Test fun `partial MCP overrides can disable a global transport and null clears servers`() {
        val root = tmp.newFolder(); val home = tmp.newFolder()
        file(home, "config/harness.json", """{"mcp":{"fs":{"command":"node","args":["server.js"]}}}""")
        file(root, "harness.json", """{"mcp":{"fs":{"enabled":false}}}""")
        val config = load(root, home)
        assertEquals("node", config.servers.single().command)
        assertFalse(config.servers.single().enabled)
        file(root, "harness.json", """{"mcp":{"fs":null}}""")
        assertTrue(load(root, home).servers.isEmpty())
    }
    @Test fun `remote URLs can use variables and expanded destinations are validated`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"mcp":{"remote":{"type":"remote","url":"{env:ENDPOINT}/mcp"}}}""")
        val config = HarnessConfigLoader(environment = { "http://127.0.0.1:1234" }).load(FileFs(root))
        assertEquals("http://127.0.0.1:1234/mcp", config.servers.single().url)
        assertTrue(fails { HarnessConfigLoader(environment = { "file:///private" }).load(FileFs(root)) }.contains("after variable substitution"))
    }
    @Test fun `glob rules distinguish files and directory recursion`() {
        assertTrue(ConfigGlob.matches("rules/**/*.md", "rules/a.md"))
        assertTrue(ConfigGlob.matches("rules/**/*.md", "rules/nested/a.md"))
        assertFalse(ConfigGlob.matches("rules/*.md", "rules/nested/a.md"))
        assertTrue(ConfigGlob.matches("android-*", "android-crash"))
        assertFalse(ConfigGlob.matches("a?.md", "abc.md"))
    }
}
