package com.androidharness.app.config

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.settings.ConfigurationSection
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConfigurationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private fun fixture() = File(c.appContext.cacheDir, "configuration-fixture-${System.nanoTime()}").apply { mkdirs() }

    @Test fun nativeRunUsesSnapshotReferencesSkillsAndMandatoryProjectConfirmation() = runBlocking {
        val root = fixture()
        root.resolve("rules.md").writeText("SNAPSHOT_INSTRUCTIONS")
        root.resolve("background.md").writeText("LAZY_REFERENCE_CONTENT")
        root.resolve("extra/android-safe").mkdirs()
        root.resolve("extra/android-safe/SKILL.md").writeText("---\nname: android-safe\ndescription: Use for Android work.\ncategory: android\n---\nAUTO_LOADED_FIXTURE_SKILL\n")
        root.resolve("harness.jsonc").writeText("""{
          "instructions":["rules.md"],"references":[{"title":"Background","path":"background.md"}],
          "skills":{"paths":["extra"],"auto_load":["android-safe"]},"permissions":{"edit":"ask"}
        }""")
        val sid = c.sessions.createSession("Configuration runtime fixture")
        var requests = 0
        var confirmations = 0
        val fixture = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                assertTrue(systemPrompt.contains("SNAPSHOT_INSTRUCTIONS"))
                assertFalse(systemPrompt.contains("CHANGED_DURING_RUN"))
                assertTrue(systemPrompt.contains("AUTO_LOADED_FIXTURE_SKILL"))
                assertFalse(systemPrompt.contains("LAZY_REFERENCE_CONTENT"))
                assertTrue(tools.any { it.name == "reference_read" })
                if (requests++ == 0) {
                    emit(StreamEvent.ToolCallReady(ToolCallData("config-write", "write_file", """{"path":"result.txt","content":"saved\n"}""")))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    emit(StreamEvent.TextDelta("Fixture finished")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        try {
            val engine = AgentEngine({ fixture }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            withTimeout(30_000) { engine.run(sid, "config-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"),
                "", listOf(ChatMessage(Role.USER, text = "Write the fixture")), permissionMode = { PermissionMode.FULL_AUTO },
                sessionAllowedTools = mutableSetOf("write_file"), workspace = FileFs(root), options = RequestOptions(),
                maxContextTokens = 100_000, mode = AgentMode.ACT, repoMapEnabled = false).collect { event ->
                if (event is AgentEvent.ApprovalNeeded) {
                    assertFalse(root.resolve("result.txt").exists())
                    confirmations++
                    root.resolve("rules.md").writeText("CHANGED_DURING_RUN")
                    root.resolve("harness.jsonc").writeText("{" )
                    event.request.response.complete(true)
                }
            } }
            assertEquals(1, confirmations)
            assertEquals(2, requests)
            assertEquals("saved\n", root.resolve("result.txt").readText())
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun projectMcpApprovalExpiresWhenResolvedConfigurationChanges() {
        val root = fixture()
        try {
            root.resolve("harness.json").writeText("""{"mcp":{"fixture":{"type":"remote","url":"https://example.invalid/mcp"}}}""")
            val fs = FileFs(root)
            val a = c.configuration.load(fs)
            assertFalse(c.mcp.isWorkspaceApproved(fs, a))
            c.mcp.approveWorkspace(fs, a)
            assertTrue(c.mcp.isWorkspaceApproved(fs, a))
            root.resolve("harness.json").writeText("""{"mcp":{"fixture":{"type":"remote","url":"https://example.invalid/changed"}}}""")
            val b = c.configuration.load(fs)
            assertFalse(c.mcp.isWorkspaceApproved(fs, b))
            assertNotEquals(c.mcp.approvalFingerprint(fs, a), c.mcp.approvalFingerprint(fs, b))
        } finally { root.deleteRecursively() }
    }

    @Test fun pluginHooksAreIsolatedAndAnInfiniteLoopIsTerminated() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val runner = c.pluginHooks
        val plugin = ConfigPlugin("fixture", """module.exports = {
            "prompt.before": async event => ({instructions: "fixture=" + event.value + ";require=" + typeof require + ";fetch=" + typeof fetch}),
            "tool.before": () => ({deny:"Blocked by fixture"})
        };""", setOf("prompt.before", "tool.before"))
        val result = withTimeout(10_000) { runner.invoke(listOf(plugin), "prompt.before", buildJsonObject { put("value", "OK") }) }
        assertTrue(result.instructions.contains("fixture=OK;require=undefined;fetch=undefined"))
        assertTrue(runner.invoke(listOf(plugin), "tool.before", buildJsonObject {}).denied!!.contains("Blocked by fixture"))
        val looping = ConfigPlugin("loop", "module.exports={'tool.before':()=>{while(true){}}};", setOf("tool.before"))
        try { withTimeout(6_000) { runner.invoke(listOf(looping), "tool.before", buildJsonObject {}) }; fail("Infinite loop must time out") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("timed out")) }
        assertTrue(runner.invoke(listOf(plugin), "tool.before", buildJsonObject {}).denied!!.contains("Blocked by fixture"))
    }

    @Test fun configurationEditorFitsNarrowPhoneAndSavesWithLargeText() {
        val root = fixture()
        try {
            root.resolve("harness.jsonc").writeText("{\n  // Phone editor fixture\n  \"instructions\": []\n}\n")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                        Column(Modifier.width(320.dp).fillMaxHeight().safeDrawingPadding().padding(12.dp)) {
                            ConfigurationSection(c, FileFs(root))
                        }
                    }
                } } }
                awaitText("Edit configuration")
                screenshot("configuration-project-narrow")
                click("Edit configuration")
                awaitText("Validate configuration")
                screenshot("configuration-editor-large-text")
                val field = await { it.isEditable }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "{\n  \"instructions\": []\n}\n") }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                click("Validate configuration")
                awaitText("Configuration is valid.", contains = true)
                click("Save")
                awaitText("Project configuration")
                assertEquals("{\n  \"instructions\": []\n}\n", root.resolve("harness.jsonc").readText())
                click("Global")
                awaitText("Global defaults")
                screenshot("configuration-global-narrow")
                click("Project")
                awaitText("Project configuration")
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun nativeSubstitutionsAndCustomMemoryUseTheConfiguredFiles() = runBlocking {
        val root = fixture()
        try {
            root.resolve("token").writeText("file-secret\n")
            root.resolve("harness.json").writeText("""{
                "mcp":{"fixture":{"command":["node","server.js"],"env":{"A":"{env:TOKEN}","B":"{file:token}","C":"{secret:SAVED}"}}},
                "memory":{"file":"notes/core.md","topics_dir":"notes/topics","max_core_lines":2}
            }""")
            val fs = FileFs(root)
            val config = HarnessConfigLoader(environment = { "env-secret" }, secret = { "saved-secret" }).load(fs)
            assertEquals(mapOf("A" to "env-secret", "B" to "file-secret", "C" to "saved-secret"), config.servers.single().env)
            val ctx = com.androidharness.app.tools.ToolContext(fs, configuration = config)
            val write = com.androidharness.app.tools.MemoryWriteTool()
            write.execute(buildJsonObject { put("content", "one\ntwo\nthree"); put("mode", "replace") }, ctx)
            assertEquals("two\nthree\n", root.resolve("notes/core.md").readText())
            write.execute(buildJsonObject { put("topic", "architecture"); put("content", "Topic fixture") }, ctx)
            assertTrue(root.resolve("notes/topics/architecture.md").exists())
            assertFalse(root.resolve(".harness/memory.md").exists())
            val read = com.androidharness.app.tools.MemoryReadTool().execute(buildJsonObject { put("topic", "architecture") }, ctx)
            assertTrue(read.output.contains("Topic fixture"))
            val search = com.androidharness.app.tools.MemorySearchTool().execute(buildJsonObject { put("query", "Topic") }, ctx)
            assertTrue(search.output.contains("notes/topics/architecture.md"))
        } finally { root.deleteRecursively() }
    }

    @Test fun projectMcpConnectionsStayInTheirWorkspaceAndCloseAfterTheirLastRun() = runBlocking {
        val root = fixture()
        val context = object : android.content.ContextWrapper(c.appContext) {
            override fun getFilesDir() = root.resolve("manager").apply { mkdirs() }
        }
        val manager = com.androidharness.app.tools.mcp.McpManager(context, c.linuxEnv, c.keys)
        val first = FixtureMcpServer("First workspace")
        val second = FixtureMcpServer("Second workspace")
        try {
            val a = root.resolve("a").apply { mkdirs() }; val b = root.resolve("b").apply { mkdirs() }
            fun config(folder: File, port: Int): Pair<FileFs, HarnessConfig> {
                folder.resolve("harness.json").writeText("""{"mcp":{"fixture":{"type":"remote","url":"http://127.0.0.1:$port/mcp"}}}""")
                val fs = FileFs(folder)
                val snapshot = HarnessConfigLoader().load(fs)
                manager.approveWorkspace(fs, snapshot)
                return fs to snapshot
            }
            val (fsA, configA) = config(a, first.port)
            val (fsB, configB) = config(b, second.port)
            val toolsA = manager.activeTools(fsA, configA, leaseId = "a-one")
            val toolsAgain = manager.activeTools(fsA, configA, leaseId = "a-two")
            val toolsB = manager.activeTools(fsB, configB, leaseId = "b-one")
            assertEquals("mcp__fixture__echo", toolsA.single().name)
            assertEquals(toolsA.single().name, toolsB.single().name)
            suspend fun echo(tools: List<com.androidharness.app.tools.Tool>, fs: FileFs) =
                tools.single().execute(buildJsonObject {}, com.androidharness.app.tools.ToolContext(fs)).output
            assertTrue(echo(toolsA, fsA).contains("First workspace"))
            assertTrue(echo(toolsB, fsB).contains("Second workspace"))
            assertEquals(1, first.initializations.get())
            manager.releaseWorkspaceTools("a-one")
            assertTrue(echo(toolsAgain, fsA).contains("First workspace"))
            manager.releaseWorkspaceTools("a-two")
            assertTrue(echo(toolsB, fsB).contains("Second workspace"))
            manager.releaseWorkspaceTools("b-one")
            assertTrue(manager.statuses.value.isEmpty())
        } finally { manager.disconnectAll(); first.close(); second.close(); root.deleteRecursively() }
    }

    @Test fun editorCanRepairOneLayerWhileAnotherLayerIsStillInvalid() = runBlocking {
        val root = fixture()
        val home = root.resolve("app").apply { mkdirs() }
        val project = root.resolve("project").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(c.appContext) { override fun getFilesDir() = home }
        val repository = HarnessConfigRepository(context, c.keys)
        val fs = FileFs(project)
        try {
            home.resolve("config").mkdirs()
            home.resolve("config/harness.json").writeText("{")
            project.resolve("harness.json").writeText("{")
            repository.save(fs, true, "config/harness.json", "{}", "{")
            assertEquals("{}\n", home.resolve("config/harness.json").readText())
            try { repository.load(fs); fail("The invalid project must still block a run") }
            catch (_: HarnessConfigException) { }
            repository.save(fs, false, "harness.json", "{}", "{")
            assertTrue(repository.load(fs).present)
            try { repository.save(fs, false, "harness.json", "{", "{}\n"); fail("Malformed JSON must not be saved") }
            catch (_: HarnessConfigException) { }
            assertEquals("{}\n", project.resolve("harness.json").readText())
        } finally { root.deleteRecursively() }
    }

    private class FixtureMcpServer(private val label: String) : java.io.Closeable {
        private val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val initializations = java.util.concurrent.atomic.AtomicInteger()
        private val worker = kotlin.concurrent.thread(isDaemon = true, name = "configuration-mcp-fixture") {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val out = java.io.ByteArrayOutputStream()
                        while (true) { val ch = input.read(); if (ch < 0 || ch == 10) break; if (ch != 13) out.write(ch) }
                        return out.toString("UTF-8")
                    }
                    line()
                    var length = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    require(length in 0..100_000)
                    val bytes = ByteArray(length); var read = 0
                    while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) break; read += n }
                    val request = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                    val method = request["method"]?.jsonPrimitive?.content.orEmpty()
                    val id = request["id"]
                    val result = when (method) {
                        "initialize" -> { initializations.incrementAndGet(); buildJsonObject {
                            put("protocolVersion", request["params"]!!.jsonObject["protocolVersion"]!!)
                            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                            put("serverInfo", buildJsonObject { put("name", "fixture"); put("version", "1") })
                        } }
                        "tools/list" -> buildJsonObject { put("tools", buildJsonArray { add(buildJsonObject {
                            put("name", "echo"); put("description", "Fixture echo"); put("inputSchema", buildJsonObject { put("type", "object") })
                        }) }) }
                        else -> buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", label) }) }) }
                    }
                    val body = if (id == null) byteArrayOf() else buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString().toByteArray()
                    val header = "HTTP/1.1 ${if (id == null) "202 Accepted" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }
        override fun close() { server.close(); worker.join(1_000) }
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        var captured: Bitmap? = null
        if (android.os.Build.VERSION.SDK_INT >= 29) instrumentation.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull()?.let { view ->
                if (view.width > 0 && view.height > 0) captured = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }
        }
        (captured ?: instrumentation.uiAutomation.takeScreenshot())?.let { bitmap ->
            File(c.appContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun click(text: String) {
        var node = awaitText(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun awaitText(text: String, contains: Boolean = false) = await {
        val actual = it.text?.toString() ?: it.contentDescription?.toString().orEmpty()
        if (contains) actual.contains(text) else actual == text
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        while (SystemClock.uptimeMillis() < deadline) {
            val automation = instrumentation.uiAutomation
            val root = automation.rootInActiveWindow ?: automation.windows.mapNotNull { it.root }
                .firstOrNull { it.packageName == instrumentation.targetContext.packageName }
            find(root)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Timed out waiting for configuration UI")
    }
}
