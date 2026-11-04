package com.androidharness.app.plugins

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PluginFormatsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file(root: File, path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private fun skill(name: String) = "---\nname: $name\ndescription: Use this skill when designing interfaces.\n---\nBody for $name.\n"
    private fun source(input: String) = (InstallCommands.parse(input) as InstallRequest.Source).origin
    private fun adapt(root: File, origin: PluginOrigin = PluginOrigin("github", "owner/repo")): PluginContents {
        val result = PluginAdapter.adapt(root, origin, "fallback")
        assertTrue(result.choices.isEmpty())
        return PluginManifest.inspect(result.root)
    }

    @Test fun `runner commands become installer requests with package and version`() {
        assertEquals(InstallRequest.Run("npx", "@modelcontextprotocol/server-memory", "latest", emptyList()),
            InstallCommands.parse("npx -y @modelcontextprotocol/server-memory"))
        assertEquals(InstallRequest.Run("npx", "create-thing", "2.0.1", listOf("init", "--yes")), InstallCommands.parse("$ pnpm dlx create-thing@2.0.1 init --yes"))
        assertEquals(InstallRequest.Run("uvx", "mcp-server-fetch", "latest", emptyList()), InstallCommands.parse("uvx mcp-server-fetch"))
        assertEquals(InstallRequest.Run("npx", "tool", "latest", listOf("setup")), InstallCommands.parse("cd ~/project && npx tool setup"))
    }

    @Test fun `package manager commands and links resolve to sources without running anything`() {
        assertEquals(PluginOrigin("npm", "@scope/package", "1.2.0"), source("npm i -D @scope/package@1.2.0"))
        assertEquals(PluginOrigin("npm", "plugin", "latest"), source("plugin"))
        assertEquals(PluginOrigin("github", "nextlevelbuilder/ui-ux-pro-max-skill"), source("https://github.com/nextlevelbuilder/ui-ux-pro-max-skill"))
        assertEquals(PluginOrigin("github", "anthropics/skills", "main", "skills/pdf"), source("https://github.com/anthropics/skills/tree/main/skills/pdf"))
        assertEquals(PluginOrigin("github", "owner/repo", "dev"), source("git clone -b dev https://github.com/owner/repo.git"))
        assertEquals(PluginOrigin("url", "https://example.com/plugin.zip"), source("https://example.com/plugin.zip"))
        assertEquals("pdf", source("npx skills add anthropics/skills --skill pdf").name)
    }

    @Test fun `claude plugin and mcp commands are understood`() {
        assertEquals(InstallRequest.MarketplacePlugin("superpowers", "superpowers-marketplace"), InstallCommands.parse("/plugin install superpowers@superpowers-marketplace"))
        assertEquals(InstallRequest.Marketplace(MarketplaceSource("anthropics/claude-plugins-official", "")),
            InstallCommands.parse("claude plugin marketplace add anthropics/claude-plugins-official"))
        val http = source("claude mcp add --transport http context7 https://mcp.context7.com/mcp")
        assertEquals("mcp", http.kind)
        assertTrue(http.target.contains("\"url\":\"https://mcp.context7.com/mcp\""))
        val stdio = source("claude mcp add memory -e KEY=1 -- npx -y @modelcontextprotocol/server-memory")
        assertTrue(stdio.target.contains("\"command\":\"npx\""))
        assertTrue(stdio.target.contains("\"KEY\":\"1\""))
        assertEquals("mcp", source("""{"mcpServers":{"fs":{"command":"npx","args":["-y","server-fs"]}}}""").kind)
        assertThrows(IllegalArgumentException::class.java) { InstallCommands.parse("rm -rf something else") }
    }

    @Test fun `claude plugin layout is converted with skills commands agents hooks and servers`() {
        val root = tmp.newFolder()
        file(root, ".claude-plugin/plugin.json", """{"name":"ui-kit","version":"1.2.0","description":"UI","author":{"name":"Team"},"skills":"./.claude/skills/"}""")
        file(root, ".claude/skills/ui-ux-pro-max/SKILL.md", skill("ui-ux-pro-max"))
        file(root, "cli/assets/skills/ui-ux-pro-max/SKILL.md", skill("ui-ux-pro-max"))
        file(root, "commands/polish.md", "---\ndescription: Polish the current screen\n---\nPolish \$ARGUMENTS.\n")
        file(root, "agents/reviewer.md", "---\nname: reviewer\ndescription: Reviews UI\ntools: Read, Grep\n---\nReview it.\n")
        file(root, "hooks/hooks.json", """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"${'$'}{CLAUDE_PLUGIN_ROOT}/check.sh"}]}]}}""")
        file(root, ".mcp.json", """{"mcpServers":{"docs":{"command":"npx","args":["-y","docs-mcp"],"env":{"TOKEN":"${'$'}{DOCS_TOKEN}"}}}}""")
        val contents = adapt(root)
        assertEquals("ui-kit", contents.name)
        assertEquals("1.2.0", contents.version)
        assertTrue(contents.skills.toString(), contents.skills.any { "ui-ux-pro-max" in it })
        assertTrue(contents.skills.toString(), contents.skills.any { "polish" in it })
        assertTrue(contents.agents.toString(), contents.agents.any { "reviewer" in it })
        assertEquals(1, contents.commandHooks.size)
        assertTrue(contents.servers.toString(), contents.servers.any { "docs" in it })
        assertTrue(contents.runtimes.containsAll(listOf("nodejs", "npm")))
        val manifest = root.resolve("plugin.json").readText()
        assertTrue(manifest.contains("${'$'}{CLAUDE_PLUGIN_ROOT}/check.sh"))
        assertTrue(manifest.contains("{env:DOCS_TOKEN}"))
    }

    @Test fun `loose skill folders and rules files become a bundle`() {
        val skills = tmp.newFolder()
        file(skills, "deep/nested/frontend-design/SKILL.md", skill("frontend-design"))
        file(skills, "deep/nested/frontend-design/reference.md", "extra")
        assertTrue(adapt(skills).skills.any { "frontend-design" in it })

        val rules = tmp.newFolder()
        file(rules, "CLAUDE.md", "Always write tests first.")
        assertEquals(1, adapt(rules).instructions.size)
    }

    @Test fun `claude bundle reached through a filesystem alias keeps component paths inside the bundle`() {
        val root = tmp.newFolder()
        file(root, ".claude-plugin/plugin.json", """{"name":"ui-kit","skills":"./.claude/skills/"}""")
        file(root, ".claude/skills/ui-ux-pro-max/SKILL.md", skill("ui-ux-pro-max"))
        file(root, "commands/polish.md", "Polish the current screen.")
        file(root, "agents/reviewer.md", "Review the current screen.")
        val alias = File(tmp.root, "android-data-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())

        val contents = adapt(alias)

        assertEquals(setOf("ui-ux-pro-max", "polish"), contents.skills.toSet())
        assertTrue(contents.agents.toString(), contents.agents.any { "reviewer" in it })
        assertTrue(root.resolve(".claude/skills/ui-ux-pro-max/SKILL.md").isFile)
    }

    @Test fun `filesystem aliases still reject components outside the bundle`() {
        val root = tmp.newFolder()
        val outside = tmp.newFolder()
        val alias = File(tmp.root, "bundle-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        Files.createSymbolicLink(root.resolve("skills").toPath(), outside.toPath())

        assertThrows(IllegalArgumentException::class.java) { PluginManifest.path(alias, "skills") }
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.path(alias, "../outside") }
    }

    @Test fun `marketplace with several plugins offers choices and installers are detected`() {
        val root = tmp.newFolder()
        file(root, ".claude-plugin/marketplace.json", """{"name":"market","plugins":[
            {"name":"one","source":"./plugins/one","description":"First"},{"name":"two","source":"./plugins/two"}]}""")
        val result = PluginAdapter.adapt(root, PluginOrigin("github", "owner/market"), "market")
        assertEquals(listOf("one", "two"), result.choices.map { it.name })
        assertEquals("plugins/one", result.choices.first().origin.subdirectory)

        val cli = tmp.newFolder()
        file(cli, "package.json", """{"name":"skill-installer","bin":{"skill-installer":"cli.js"}}""")
        file(cli, "cli.js", "console.log('copy skills')")
        val needed = assertThrows(InstallerNeeded::class.java) { PluginAdapter.adapt(cli, PluginOrigin("npm", "skill-installer"), "x") }
        assertEquals("npx -y skill-installer", needed.command)
    }
}
