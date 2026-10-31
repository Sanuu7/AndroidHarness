package com.androidharness.app.plugins

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
import com.androidharness.app.config.HarnessConfigRepository
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.settings.PluginManagerSection
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PluginManagerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private class Fixture(context: android.content.Context) : java.io.Closeable {
        val root = File(context.cacheDir, "plugin-fixture-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(context) {
            override fun getFilesDir() = root.resolve("app").apply { mkdirs() }
            override fun getCacheDir() = root.resolve("cache").apply { mkdirs() }
        }
        val manager = PluginManager(this.context, MutableStateFlow(0L))
        val workspace = FileFs(root.resolve("workspace").apply { mkdirs() })
        override fun close() { root.deleteRecursively() }
    }
    private fun archive(root: File, version: String, instruction: String, name: String = "device-plugin"): PluginOrigin {
        val files = mapOf(
            "plugin.json" to """{"name":"$name","version":"$version","instructions":["instructions.md"],"skills":["skills"],"hooks":["prompt.before"],"entry":"index.js","mcp":{"fixture":{"type":"remote","url":"https://example.invalid/mcp","enabled":false}}}""",
            "instructions.md" to instruction,
            "skills/device-skill/SKILL.md" to "---\nname: device-skill\ndescription: Review device fixture changes.\n---\nDEVICE_SKILL_CONTENT\n",
            "index.js" to "module.exports={'prompt.before':()=>({instructions:'INSTALLED_HOOK_$version'})};"
        )
        val zip = root.resolve("plugin-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out -> files.forEach { (path, value) ->
            out.putNextEntry(ZipEntry("repo-envelope/$path")); out.write(value.toByteArray()); out.closeEntry()
        } }
        return PluginOrigin("archive", Uri.fromFile(zip).toString())
    }

    @Test fun archiveInstallUpdateDisableAndUninstallReachRunConfigurationWithoutEditingProject() = runBlocking {
        Fixture(c.appContext).use { f ->
            val fs = f.workspace
            fs.resolve("harness.jsonc").writeText("{\n // keep my settings\n \"instructions\":[\"own.md\"]\n}\n")
            fs.resolve("own.md").writeText("USER_INSTRUCTIONS")
            val before = fs.resolve("harness.jsonc").readText()
            val repo = HarnessConfigRepository(f.context, c.keys, f.manager, f.manager.revision)
            val first = f.manager.prepare(archive(f.root, "1.0.0", "PLUGIN_VERSION_ONE"))
            assertTrue(f.manager.store.list().isEmpty())
            assertEquals(listOf("device-skill"), first.contents.skills)
            val installed = f.manager.install(first, f.manager.workspaceScope(fs), "Fixture project")
            assertFalse(first.root.exists())
            val captured = repo.load(fs)
            assertTrue(captured.prompt().contains("PLUGIN_VERSION_ONE"))
            assertTrue(captured.prompt().contains("USER_INSTRUCTIONS"))
            assertEquals(1, captured.skills.size)
            assertEquals(1, captured.servers.size)
            assertTrue(captured.servers.single().deviceLocal)
            assertFalse(c.mcp.isWorkspaceApproved(fs, captured))
            if (JavaScriptSandbox.isSupported()) {
                assertTrue(c.pluginHooks.invoke(captured.plugins, "prompt.before", buildJsonObject {}).instructions.contains("INSTALLED_HOOK_1.0.0"))
            }
            val next = f.manager.prepare(archive(f.root, "2.0.0", "PLUGIN_VERSION_TWO"), installed)
            val updated = f.manager.install(next, installed.scope, installed.scopeLabel)
            assertEquals(installed.id, updated.id)
            assertTrue(repo.load(fs).prompt().contains("PLUGIN_VERSION_TWO"))
            assertTrue(captured.prompt().contains("PLUGIN_VERSION_ONE"))
            assertTrue(f.manager.store.directory(installed).isDirectory)
            f.manager.store.setEnabled(updated.id, false)
            assertFalse(repo.load(fs).prompt().contains("PLUGIN_VERSION_TWO"))
            f.manager.store.setEnabled(updated.id, true)
            assertTrue(repo.load(fs).prompt().contains("PLUGIN_VERSION_TWO"))
            val other = FileFs(f.root.resolve("other-project").apply { mkdirs() })
            assertFalse(repo.load(other).prompt().contains("PLUGIN_VERSION_TWO"))
            f.manager.store.uninstall(updated.id)
            assertTrue(f.manager.store.list().isEmpty())
            assertTrue(repo.load(fs).plugins.isEmpty())
            assertEquals(before, fs.resolve("harness.jsonc").readText())
            val restarted = PluginManager(f.context, MutableStateFlow(0L))
            assertTrue(restarted.store.list().isEmpty())
            assertFalse(f.manager.store.directory(installed).exists())
            assertFalse(f.manager.store.directory(updated).exists())
        }
    }

    @Test fun localDocumentFoldersAndStandaloneSkillsImportWithSupportingFiles() = runBlocking {
        Fixture(c.appContext).use { f ->
            fun tree(id: String): Uri {
                val tree = DocumentsContract.buildTreeDocumentUri("com.androidharness.pluginimport.fixture", id)
                // Starts the standalone fixture provider, which grants read-only access to this test's target app.
                f.context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id), null, null, null, null)?.close()
                return tree
            }
            val folder = f.manager.prepare(PluginOrigin("folder", tree("fixture").toString()))
            assertEquals("folder-fixture", folder.contents.name)
            val record = f.manager.install(folder, "global", "Global")
            val standalone = f.manager.prepare(PluginOrigin("folder", tree("skill").toString()))
            assertEquals(listOf("imported-skill"), standalone.contents.skills)
            val imported = f.manager.install(standalone, f.manager.workspaceScope(f.workspace), "Project")
            val repo = HarnessConfigRepository(f.context, c.keys, f.manager)
            val config = repo.load(f.workspace)
            assertTrue(config.prompt().contains("DOCUMENT_FOLDER_INSTRUCTIONS"))
            assertEquals(2, config.skills.size)
            assertTrue(f.manager.store.directory(imported).resolve("__imported_skills__/imported-skill/notes.md").readText().contains("SKILL_SUPPORTING_FILE"))
            assertEquals(record.id, PluginManager(f.context, MutableStateFlow(0L)).store.list().first { it.scope == "global" }.id)
        }
    }

    @Test fun incompatibleOrTamperedUpdateDoesNotReplaceInstalledVersion() = runBlocking {
        Fixture(c.appContext).use { f ->
            val initial = f.manager.prepare(archive(f.root, "1.0.0", "ORIGINAL"))
            val installed = f.manager.install(initial, "global", "Global")
            try {
                f.manager.prepare(archive(f.root, "2.0.0", "WRONG", "other-plugin"), installed)
                fail("An update must preserve identity")
            } catch (_: IllegalArgumentException) { }
            val prepared = f.manager.prepare(archive(f.root, "2.0.0", "CHANGED"), installed)
            prepared.root.resolve("instructions.md").writeText("TAMPERED_AFTER_REVIEW")
            try { f.manager.install(prepared, "global", "Global"); fail("Tampered preview must not install") }
            catch (_: IllegalArgumentException) { }
            assertEquals("1.0.0", f.manager.store.list().single().version)
            assertEquals("ORIGINAL", f.manager.store.directory(installed).resolve("instructions.md").readText())
            assertTrue(f.context.cacheDir.resolve("plugin-previews").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun installedLocalMcpServerRequiresApprovalThenStartsAndCallsItsTool() = runBlocking {
        assumeTrue("Local MCP requires the existing Linux toolchain", c.linuxEnv.bashExecutable() != null)
        Fixture(c.appContext).use { f ->
            val zip = f.root.resolve("mcp.zip")
            val script = """
                while IFS= read -r request; do
                  id=${'$'}(printf '%s' "${'$'}request" | /system/bin/sed -n 's/.*"id":\([^,}]*\).*/\1/p')
                  [ -n "${'$'}id" ] || continue
                  case "${'$'}request" in
                    *'"method":"initialize"'*) result='{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"installed-fixture","version":"1"}}' ;;
                    *'"method":"tools/list"'*) result='{"tools":[{"name":"echo","description":"Installed fixture","inputSchema":{"type":"object"}}]}' ;;
                    *) result='{"content":[{"type":"text","text":"INSTALLED_MCP_TOOL_RESULT"}]}' ;;
                  esac
                  printf '{"jsonrpc":"2.0","id":%s,"result":%s}\n' "${'$'}id" "${'$'}result"
                done
            """.trimIndent()
            ZipOutputStream(zip.outputStream()).use { out ->
                mapOf("plugin.json" to """{"name":"device-mcp","version":"1.0.0","mcp":{"fixture":{"command":["/system/bin/sh","{plugin:root}/server.sh"]}}}""",
                    "server.sh" to script).forEach { (path, value) ->
                    out.putNextEntry(ZipEntry(path)); out.write(value.toByteArray()); out.closeEntry()
                }
            }
            val candidate = f.manager.prepare(PluginOrigin("archive", Uri.fromFile(zip).toString()))
            f.manager.install(candidate, "global", "Global")
            val config = HarnessConfigRepository(f.context, c.keys, f.manager).load(f.workspace)
            val mcp = com.androidharness.app.tools.mcp.McpManager(f.context, c.linuxEnv, c.keys)
            try {
                assertTrue(mcp.activeTools(f.workspace, config, leaseId = "plugin-fixture").isEmpty())
                mcp.approveWorkspace(f.workspace, config)
                val tools = withTimeout(20_000) { mcp.activeTools(f.workspace, config, leaseId = "plugin-fixture") }
                assertEquals(mcp.statuses.value.toString(), 1, tools.size)
                assertTrue(tools.single().name.startsWith("mcp__device_mcp_"))
                val result = tools.single().execute(buildJsonObject {}, com.androidharness.app.tools.ToolContext(f.workspace))
                assertTrue(result.output.contains("INSTALLED_MCP_TOOL_RESULT"))
                mcp.releaseWorkspaceTools("plugin-fixture")
                assertTrue(mcp.statuses.value.isEmpty())
            } finally { mcp.disconnectAll() }
        }
    }

    @Test fun phoneManagerInstallsShowsDetailsTogglesUpdatesAndUninstalls() {
        Fixture(c.appContext).use { f ->
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                show(scenario, f)
                click("Discover")
                click("Review bundle", scroll = true)
                awaitText("Review installation")
                screenshot("plugins-install-review")
                click("Global · all Code workspaces", scroll = true)
                click("Review manifest", scroll = true)
                awaitText("Review manifest")
                awaitText("review-workflow", contains = true)
                click("Close")
                click("Install and enable")
                waitUntil { f.manager.store.list().size == 1 }
                assertEquals("global", f.manager.store.list().single().scope)
                awaitText("review-workflow installed.", contains = true)
                screenshot("plugins-installed")
                click("Details", scroll = true)
                awaitText("instructions.md")
                click("Close")
                click("Enable review-workflow", scroll = true)
                waitUntil { !f.manager.store.list().single().enabled }
                val previousDirectory = f.manager.store.list().single().directory
                click("Update", scroll = true)
                awaitText("Review installation")
                click("Apply update")
                waitUntil { f.manager.store.list().single().directory != previousDirectory && !f.manager.store.list().single().enabled }
                awaitText("review-workflow installed.", contains = true)
                click("Uninstall review-workflow", scroll = true)
                click("Uninstall")
                waitUntil { f.manager.store.list().isEmpty() }
                awaitText("No plugins installed here")
            }
        }
    }

    @Test fun narrowPhoneWithLargeTextCanReachEverySourceAndCancelPreview() {
        Fixture(c.appContext).use { f ->
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                show(scenario, f, narrow = true)
                awaitText("No plugins installed here", scroll = true)
                screenshot("plugins-narrow-installed")
                click("Add")
                click("GitHub", scroll = true)
                awaitText("Plugin subfolder (optional)", scroll = true)
                screenshot("plugins-narrow-github")
                click("Local", scroll = true)
                awaitText("Choose archive", scroll = true)
                awaitText("Choose folder", scroll = true)
                screenshot("plugins-narrow-local")
                click("Discover")
                click("Review bundle", scroll = true)
                awaitText("Review installation")
                screenshot("plugins-narrow-review")
                click("Cancel")
                assertTrue(f.manager.store.list().isEmpty())
            }
        }
    }

    private fun show(scenario: ActivityScenario<ChatListTestActivity>, f: Fixture, narrow: Boolean = false) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        scenario.onActivity { activity -> activity.setContent { HarnessTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow) 1.4f else 1f)) {
                Surface { Column(Modifier.width(if (narrow) 320.dp else 380.dp).fillMaxHeight().safeDrawingPadding().padding(12.dp)) {
                    PluginManagerSection(c, f.manager, f.workspace)
                } }
            }
        } } }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(300)
        var captured: Bitmap? = null
        if (android.os.Build.VERSION.SDK_INT >= 29) instrumentation.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull()?.let { view ->
                if (view.width > 0 && view.height > 0) captured = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }
        }
        (captured ?: instrumentation.uiAutomation.takeScreenshot())?.let { bitmap ->
            File(c.appContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(100) }
        error("Timed out waiting for plugin operation")
    }
    private fun click(text: String, scroll: Boolean = false) {
        var node = awaitText(text, scroll = scroll)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(200)
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun awaitText(text: String, contains: Boolean = false, scroll: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var forward = true
        while (SystemClock.uptimeMillis() < deadline) {
            val root = uiRoot()
            find(root) { node ->
                val labels = listOfNotNull(node.text, node.contentDescription, node.hintText).map { it.toString() }
                node.isVisibleToUser && labels.any { actual -> if (contains) actual.contains(text) else actual == text }
            }?.let { return it }
            if (scroll) find(root) { it.isScrollable && it.actionList.any { action ->
                action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                    action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            } }?.let { node ->
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (!node.performAction(action)) forward = !forward
            }
            SystemClock.sleep(200)
        }
        screenshot("plugins-missing-" + text.take(30).replace(Regex("[^A-Za-z0-9]"), "-"))
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24) return
            dump.append("  ".repeat(depth)).append("${node.className}: text=${node.text} desc=${node.contentDescription} hint=${node.hintText} visible=${node.isVisibleToUser} clickable=${node.isClickable}\n")
            for (i in 0 until node.childCount) describe(node.getChild(i), depth + 1)
        }
        describe(uiRoot(), 0)
        File(c.appContext.getExternalFilesDir(null), "plugins-missing-ui.txt").writeText(dump.toString())
        error("Timed out waiting for plugin UI: $text")
    }
    private fun uiRoot(): AccessibilityNodeInfo? = instrumentation.uiAutomation.run {
        rootInActiveWindow ?: windows.mapNotNull { it.root }.firstOrNull { it.packageName == instrumentation.targetContext.packageName }
    }
}
