package com.androidharness.app.plugins

import com.androidharness.app.config.ConfigCommandHook
import com.androidharness.app.config.ConfigPlugin
import com.androidharness.app.config.HarnessConfigLoader
import com.androidharness.app.config.HookEvents
import com.androidharness.app.config.PluginHooks
import com.androidharness.app.skills.CommandTemplate
import com.androidharness.app.skills.SkillParser
import com.androidharness.app.skills.SkillStore
import com.androidharness.app.workspace.FileFs
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Claude Code plugins keep what they declare, load without caps, and one broken plugin never stops the others. */
class PluginFidelityTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file(root: File, path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private fun skill(name: String) = "---\nname: $name\ndescription: Use $name when testing.\n---\nBody for $name.\n"

    private fun claudePlugin(): File {
        val root = tmp.newFolder()
        file(root, ".claude-plugin/plugin.json", """{"name":"kit","version":"1.2.0","description":"Kit",
            "userConfig":{"api_token":{"description":"Token for the API","sensitive":true,"required":true}},
            "lspServers":{"pyright":{"command":"pyright-langserver","args":["--stdio"],"extensionToLanguage":{".py":"python"}}},
            "mcpServers":{"api":{"command":"node","args":["${'$'}{CLAUDE_PLUGIN_ROOT}/server.js"],"env":{"TOKEN":"${'$'}{user_config.api_token}","CACHE":"${'$'}{CLAUDE_PLUGIN_DATA}"}}}}""")
        file(root, "commands/review.md", "---\ndescription: Review a pull request\nargument-hint: [pr]\nallowed-tools: Bash(git diff:*)\n---\nReview PR \$1.\n\nChanges: !`git diff`\n")
        file(root, "commands/frontend/test.md", "Run the frontend tests for \$ARGUMENTS.\n")
        file(root, "agents/checker.md", "---\nname: checker\ndescription: Checks code\nmodel: haiku\ntools:\n  - Read\n  - Grep\n---\nYou check code.\n")
        file(root, "hooks/hooks.json", """{"hooks":{
            "PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo ${'$'}{user_config.api_token}","timeout":900}]}],
            "Stop":[{"hooks":[{"type":"prompt","prompt":"Is the task done?"}]}],
            "PreCompact":[{"hooks":[{"type":"command","command":"echo compacting"}]}]}}""")
        file(root, "output-styles/terse.md", "---\nname: Terse\ndescription: Short answers\n---\nAnswer in one line.\n")
        file(root, "bin/kit-tool", "#!/usr/bin/env bash\necho tool\n")
        file(root, "skills/BadName/SKILL.md", "# A skill with no frontmatter\n\nDo the thing carefully.\n")
        return root
    }

    @Test fun `claude plugin parts survive conversion unchanged and uncapped`() {
        val root = claudePlugin()
        val originalSkill = root.resolve("skills/BadName/SKILL.md").readText()
        val contents = PluginManifest.inspect(PluginAdapter.adapt(root, PluginOrigin("github", "owner/kit"), "kit").root)
        val manifest = Json.parseToJsonElement(root.resolve("plugin.json").readText()).jsonObject

        // SKILL.md files are never rewritten; the name is fixed in memory when the plugin loads.
        assertEquals(originalSkill, root.resolve("skills/BadName/SKILL.md").readText())
        assertTrue("badname" in contents.skills)

        // Commands keep their exact body and frontmatter, marked as commands.
        val review = SkillParser.parse(root.resolve(".harness/commands/review/SKILL.md").readText())
        assertEquals("true", review.fields["command"])
        assertEquals("Bash(git diff:*)", review.fields["allowed-tools"])
        assertEquals("Review PR \$1.\n\nChanges: !`git diff`", review.body)
        assertTrue(root.resolve(".harness/commands/frontend-test/SKILL.md").isFile)

        // Agents keep their model and tool list, including YAML block lists.
        val agent = manifest["agents"]!!.jsonObject["checker"]!!.jsonObject
        assertEquals("haiku", agent["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("Read", "Grep"), agent["allowedTools"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Every hook event and type is kept, with user options and long timeouts.
        val hooks = manifest["commandHooks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf(HookEvents.TOOL_BEFORE, HookEvents.STOP, HookEvents.COMPACT), hooks.map { it["event"]!!.jsonPrimitive.content }.toSet())
        val pre = hooks.first { it["source"]!!.jsonPrimitive.content == "PreToolUse" }
        assertEquals("echo \${CLAUDE_PLUGIN_OPTION_API_TOKEN}", pre["command"]!!.jsonPrimitive.content)
        assertEquals(900, pre["timeout"]!!.jsonPrimitive.int)
        assertEquals("prompt", hooks.first { it["source"]!!.jsonPrimitive.content == "Stop" }["type"]!!.jsonPrimitive.content)

        // Language servers, settings, bin folders and output styles are all kept.
        assertEquals(listOf("pyright"), contents.languageServers)
        assertEquals(listOf("api_token"), contents.settings)
        assertEquals(listOf("bin"), manifest["bin"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(contents.skills.any { it.startsWith("style-terse") })
        val env = manifest["mcp"]!!.jsonObject["api"]!!.jsonObject["env"]!!.jsonObject
        assertEquals("{env:api_token}", env["TOKEN"]!!.jsonPrimitive.content)
        assertEquals("{plugin:data}", env["CACHE"]!!.jsonPrimitive.content)
    }

    @Test fun `installed claude plugin loads its agents settings language servers and bins`() {
        val root = claudePlugin()
        PluginAdapter.adapt(root, PluginOrigin("github", "owner/kit"), "kit")
        val store = PluginStore(tmp.newFolder())
        store.install(PluginManagerTestSupport.prepared(root), "global", "Global")
        val config = HarnessConfigLoader(managedPlugins = store.configurationSources("project:x"), environment = { if (it == "api_token") "secret-value" else null },
            pluginData = { "/data/$it" }).load(FileFs(tmp.newFolder()))
        val agent = config.agents.getValue("kit:checker")
        assertEquals("haiku", agent.model)
        assertEquals(listOf("Read", "Grep"), agent.allowed)
        assertEquals("secret-value", config.plugins.single().options["api_token"])
        assertEquals("kit:pyright", config.languageServers.single().name)
        assertEquals(1, config.pluginBins.size)
        assertTrue(config.prompt().contains("kit:checker: Checks code"))
        assertEquals("secret-value", config.servers.single().env["TOKEN"])
        assertTrue(config.servers.single().env["CACHE"]!!.startsWith("/data/"))
        assertEquals("[redacted]", config.redact("secret-value"))
    }

    @Test fun `more than thirty two plugins load and a broken one is skipped and reported`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"plugins":{"paths":[".harness/plugins"]}}""")
        (1..45).forEach { n ->
            file(root, ".harness/plugins/p$n/plugin.json", """{"name":"p$n","version":"1","skills":["skills"],"commandHooks":[{"event":"tool.before","command":"true"}]}""")
            file(root, ".harness/plugins/p$n/skills/s$n/SKILL.md", skill("s$n"))
        }
        file(root, ".harness/plugins/broken/plugin.json", """{"name":"broken","version":"1","instructions":["missing.md"]}""")
        val config = HarnessConfigLoader().load(FileFs(root))
        assertEquals(45, config.plugins.size)
        assertEquals(45, config.skills.size)
        assertTrue(config.pluginProblems.containsKey("broken"))
    }

    @Test fun `plugin skills are namespaced and a unique short name still resolves`() {
        val root = tmp.newFolder()
        file(root, "harness.json", """{"plugins":{"paths":[".harness/plugins"]}}""")
        listOf("alpha", "beta").forEach { plugin ->
            file(root, ".harness/plugins/$plugin/plugin.json", """{"name":"$plugin","version":"1","skills":["skills"]}""")
            file(root, ".harness/plugins/$plugin/skills/shared/SKILL.md", skill("shared"))
        }
        file(root, ".harness/plugins/alpha/skills/only-alpha/SKILL.md", skill("only-alpha"))
        val config = HarnessConfigLoader().load(FileFs(root))
        assertTrue(setOf("alpha:shared", "beta:shared", "alpha:only-alpha").all { it in config.skills })
        val store = SkillStore(emptyMap(), tmp.newFolder(), { null }, { emptySet() }).configured(config)
        assertEquals("alpha:only-alpha", store.resolve("only-alpha"))
        assertEquals("shared", store.resolve("shared"))
        assertTrue(store.view("only-alpha").isSuccess)
    }

    @Test fun `hook answers follow claude code rules`() {
        val plugin = ConfigPlugin("guard", "", emptySet())
        val handler = ConfigCommandHook(HookEvents.TOOL_BEFORE, "check", source = "PreToolUse")
        assertEquals("guard: no rm", PluginHooks.interpret(plugin, handler, HookEvents.TOOL_BEFORE, 2, "", "no rm").denied)
        val ask = PluginHooks.interpret(plugin, handler, HookEvents.TOOL_BEFORE, 0,
            """{"hookSpecificOutput":{"permissionDecision":"ask","permissionDecisionReason":"check","updatedInput":{"command":"ls"}}}""", "")
        assertEquals("guard: check", ask.ask)
        assertEquals("ls", ask.updatedInput!!["command"]!!.jsonPrimitive.content)
        assertEquals("halt", PluginHooks.interpret(plugin, handler, HookEvents.TOOL_AFTER, 0, """{"continue":false,"stopReason":"halt"}""", "").stop)
        val stop = ConfigCommandHook(HookEvents.STOP, "check", source = "Stop")
        assertEquals("Plugin guard: tests fail", PluginHooks.interpret(plugin, stop, HookEvents.STOP, 0, """{"decision":"block","reason":"tests fail"}""", "").keepGoing)
        val start = ConfigCommandHook(HookEvents.SESSION_START, "ctx", source = "SessionStart")
        assertTrue(PluginHooks.interpret(plugin, start, HookEvents.SESSION_START, 0, "Project uses pnpm.", "").instructions.contains("pnpm"))
        assertTrue(PluginHooks.interpret(plugin, handler, HookEvents.TOOL_BEFORE, 1, "", "oops").messages.single().contains("code 1"))
    }

    @Test fun `command templates fill arguments the way claude code and gemini do`() {
        assertEquals("Fix 42 in api", CommandTemplate.arguments("Fix \$1 in \$2", "42 api"))
        assertEquals("Do it: a b", CommandTemplate.arguments("Do it: \$ARGUMENTS", "a b"))
        assertEquals("Gemini x", CommandTemplate.arguments("Gemini {{args}}", "x"))
        assertEquals("Plain\n\nARGUMENTS: extra", CommandTemplate.arguments("Plain", "extra"))
        assertEquals(listOf("say \"hi there\"", "x"), CommandTemplate.words("'say \"hi there\"' x").let { listOf(it[0], it[1]) })
        assertEquals(listOf("git diff", "ls"), CommandTemplate.shellCommands("A !`git diff` B !{ls}"))
        assertEquals("A out B", CommandTemplate.withShellOutput("A !`git diff` B") { "out" })
        assertEquals(listOf("docs/a.md", "src/b.kt"), CommandTemplate.fileReferences("See @{docs/a.md} and @src/b.kt, not me@example.com or @../x"))
        assertTrue(CommandTemplate.shellAllowed("Bash(git diff:*), Read", "git diff --stat"))
        assertFalse(CommandTemplate.shellAllowed("Bash(git diff:*)", "rm -rf ."))
        assertTrue(CommandTemplate.shellAllowed("Bash", "anything"))
    }

    @Test fun `non github git hosts and links are understood`() {
        fun origin(input: String) = (InstallCommands.parse(input) as InstallRequest.Source).origin
        assertEquals(PluginOrigin("git", "https://gitlab.com/group/sub/repo", "main", "plugins/kit"), origin("https://gitlab.com/group/sub/repo/-/tree/main/plugins/kit"))
        assertEquals(PluginOrigin("git", "https://bitbucket.org/team/repo", "dev"), origin("git clone -b dev https://bitbucket.org/team/repo.git"))
        assertEquals(PluginOrigin("git", "https://codeberg.org/me/repo", "main", "kit"), origin("https://codeberg.org/me/repo/src/branch/main/kit"))
        assertEquals(PluginOrigin("github", "owner/repo"), origin("https://github.com/owner/repo"))
    }

    @Test fun `a plugin agent tool list limits its tools`() {
        val listed = setOf("Read", "Grep", "mcp__github__create_issue")
        assertTrue(com.androidharness.app.config.ConfigToolPolicy.listed(listed, "read_file"))
        assertTrue(com.androidharness.app.config.ConfigToolPolicy.listed(listed, "grep"))
        assertTrue(com.androidharness.app.config.ConfigToolPolicy.listed(listed, "mcp__kit_ab12_github__create_issue"))
        assertFalse(com.androidharness.app.config.ConfigToolPolicy.listed(listed, "shell"))
        assertFalse(com.androidharness.app.config.ConfigToolPolicy.listed(listed, "write_file"))
    }
}

/** Builds a preview record for a bundle folder the way the manager does, without downloading. */
internal object PluginManagerTestSupport {
    fun prepared(root: File): PreparedPlugin = PreparedPlugin(java.util.UUID.randomUUID().toString(), root, PluginOrigin("github", "owner/kit"), "1",
        PluginManifest.inspect(root), PluginManifest.digest(root))
}
