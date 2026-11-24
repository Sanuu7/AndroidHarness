package com.androidharness.app.tools.mcp

import com.androidharness.app.config.ConfigCommandHook
import com.androidharness.app.config.ConfigLanguageServer
import com.androidharness.app.config.ConfigPlugin
import com.androidharness.app.config.HarnessConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTrustTest {

    private val files = McpServerConfig(name = "files", command = "npx", args = listOf("-y", "srv"))
    private val lint = ConfigPlugin("lint", "hook()", setOf("tool.before"),
        listOf(ConfigCommandHook("tool.before", "./check.sh")), root = "/ws/.harness/plugins/lint")
    private val lintLsp = ConfigLanguageServer("lint:ts", "lint", "tsls", listOf("--stdio"), mapOf(".ts" to "typescript"), emptyMap(), "/ws")
    private val config = HarnessConfig(servers = listOf(files), plugins = listOf(lint), languageServers = listOf(lintLsp),
        pluginBins = listOf("/ws/.harness/plugins/lint/bin"))

    private fun items(c: HarnessConfig = config) = WorkspaceTrust.items(c.servers, c)

    @Test
    fun `each server and plugin is its own item and plugin tools fold into the plugin`() {
        assertEquals(listOf("server:files", "plugin:lint"), items().map { it.key })
    }

    @Test
    fun `instruction text never changes an item hash`() {
        val edited = config.copy(trustPayload = "AGENTS.md was rewritten")
        assertEquals(items().map { it.hash }, items(edited).map { it.hash })
    }

    @Test
    fun `new items ask, approved ones do not, and a changed one asks again`() {
        var record = WorkspaceTrustRecord()
        assertEquals(2, WorkspaceTrust.pending(items(), record).size)
        record = WorkspaceTrust.approve(record, items(), 1)
        assertTrue(WorkspaceTrust.pending(items(), record).isEmpty())

        val changed = config.copy(servers = listOf(files.copy(args = listOf("-y", "evil"))))
        val pending = WorkspaceTrust.pending(items(changed), record)
        assertEquals(listOf("server:files"), pending.map { it.key })
        assertEquals(TrustItemState.CHANGED, WorkspaceTrust.state(pending.single(), record))
    }

    @Test
    fun `a plugin's language server change asks again for that plugin`() {
        val record = WorkspaceTrust.approve(WorkspaceTrustRecord(), items(), 1)
        val changed = config.copy(languageServers = listOf(lintLsp.copy(command = "other")))
        assertEquals(listOf("plugin:lint"), WorkspaceTrust.pending(items(changed), record).map { it.key })
    }

    @Test
    fun `ask new only keeps running approved items after they change`() {
        val record = WorkspaceTrust.approve(WorkspaceTrustRecord(mode = WorkspaceTrustMode.ASK_NEW), items(), 1)
        val changed = config.copy(servers = listOf(files.copy(args = listOf("other"))),
            plugins = listOf(lint, lint.copy(name = "fresh", root = "/ws/fresh")))
        assertEquals(listOf("plugin:fresh"), WorkspaceTrust.pending(items(changed), record).map { it.key })
    }

    @Test
    fun `declined items stop asking until they change`() {
        var record = WorkspaceTrust.decline(WorkspaceTrustRecord(), items().filter { it.key == "server:files" }, 1)
        assertEquals(listOf("plugin:lint"), WorkspaceTrust.pending(items(), record).map { it.key })
        assertEquals(TrustItemState.DECLINED, WorkspaceTrust.state(items().first(), record))

        val changed = config.copy(servers = listOf(files.copy(command = "other")))
        assertTrue(WorkspaceTrust.pending(items(changed), record).any { it.key == "server:files" })

        record = WorkspaceTrust.approve(record, items(), 2)
        assertTrue(record.declined.isEmpty())
    }

    @Test
    fun `trust all and block all never ask`() {
        val trusted = WorkspaceTrustRecord(mode = WorkspaceTrustMode.TRUST_ALL)
        val blocked = WorkspaceTrustRecord(mode = WorkspaceTrustMode.BLOCK_ALL, items = items().associate { it.key to it.hash })
        assertTrue(WorkspaceTrust.pending(items(), trusted).isEmpty())
        assertTrue(WorkspaceTrust.pending(items(), blocked).isEmpty())
        assertTrue(items().all { WorkspaceTrust.approved(it, trusted) })
        assertTrue(items().none { WorkspaceTrust.approved(it, blocked) })
    }

    @Test
    fun `restrict keeps only approved servers, plugins and their tools`() {
        val onlyServer = WorkspaceTrust.restrict(config, setOf("server:files"))
        assertEquals(listOf("files"), onlyServer.servers.map { it.name })
        assertTrue(onlyServer.plugins.isEmpty())
        assertTrue(onlyServer.languageServers.isEmpty())
        assertTrue(onlyServer.pluginBins.isEmpty())

        val onlyPlugin = WorkspaceTrust.restrict(config, setOf("plugin:lint"))
        assertTrue(onlyPlugin.servers.isEmpty())
        assertEquals(listOf("lint"), onlyPlugin.plugins.map { it.name })
        assertEquals(listOf(lintLsp), onlyPlugin.languageServers)
        assertEquals(config.pluginBins, onlyPlugin.pluginBins)
    }

    @Test
    fun `fingerprint changes when anything runnable changes`() {
        val changed = config.copy(plugins = listOf(lint.copy(script = "other()")))
        assertNotEquals(WorkspaceTrust.fingerprint(items()), WorkspaceTrust.fingerprint(items(changed)))
        assertEquals(WorkspaceTrust.fingerprint(items()), WorkspaceTrust.fingerprint(items(config.copy(trustPayload = "docs"))))
    }
}
