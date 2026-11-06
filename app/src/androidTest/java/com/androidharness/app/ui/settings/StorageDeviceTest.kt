package com.androidharness.app.ui.settings

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun inventoryAndCleanupPreserveOtherWorkspacesAndRequireIdleExecution() = runBlocking {
        val previous = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "storage-fixture-${System.nanoTime()}").apply { mkdirs() }
        val aRoot = File(root, "a").apply { mkdirs() }
        val bRoot = File(root, "b").apply { mkdirs() }
        val a = c.workspace.addShellProject(aRoot.path)
        val b = c.workspace.addShellProject(bRoot.path)
        val download = File(c.appContext.cacheDir, "deb-download/storage-test-${System.nanoTime()}.deb").apply {
            parentFile!!.mkdirs(); writeText("interrupted download"); setLastModified(1_000)
        }
        val packages = c.linuxEnv.installedPackages().toSet()
        try {
            File(aRoot, "build.gradle.kts").writeText("plugins {}")
            val output = File(aRoot, "build/outputs/apk/test.apk").apply { parentFile!!.mkdirs(); writeText("old build"); setLastModified(1_000) }
            output.parentFile!!.setLastModified(1_000); output.parentFile!!.parentFile!!.setLastModified(1_000)
            val source = File(aRoot, "src/main.kt").apply { parentFile!!.mkdirs(); writeText("source kept") }
            val other = File(bRoot, "keep.txt").apply { writeText("second project kept") }
            val inventory = c.storage.scan()
            assertTrue(inventory.bytes > 0)
            val workspaces = inventory.report.items.filter { it.role == com.androidharness.app.data.storage.StorageRole.WORKSPACE }
            assertNotNull(workspaces.first { it.key == a.id }.bytes)
            assertNotNull(workspaces.first { it.key == b.id }.bytes)
            val chosen = inventory.candidates.filter { it.id == "download:${download.name}" || it.id == "build:${output.parentFile!!.parentFile!!.canonicalPath}" }
            assertEquals(2, chosen.size)
            c.runManager.runningSessionIds.value = setOf("storage-test-busy")
            try { assertTrue(runCatching { c.storage.clean(chosen) }.isFailure) }
            finally { c.runManager.runningSessionIds.value = emptySet() }
            c.terminal.ensureStarted()
            try { assertTrue(runCatching { c.storage.clean(chosen) }.isFailure) }
            finally { c.terminal.stopTerminal() }
            assertTrue(download.exists()); assertTrue(output.exists())
            val result = c.storage.clean(chosen)
            assertEquals(29L, result.removedBytes)
            assertFalse(download.exists()); assertFalse(output.exists())
            assertEquals("source kept", source.readText()); assertEquals("second project kept", other.readText())
            assertEquals(packages, c.linuxEnv.installedPackages().toSet())
            val app = c.linuxEnv.processEnv(); val shell = c.linuxEnv.tmpProcessEnv()
            assertEquals(File(c.linuxEnv.sharedCacheRoot, "npm").path, app["npm_config_cache"])
            assertEquals("${c.linuxEnv.tmpBase}/package-cache/npm", shell["npm_config_cache"])
            assertEquals("${c.linuxEnv.tmpPrefix}/lib", shell["LD_LIBRARY_PATH"])
        } finally {
            download.delete(); c.workspace.deleteProject(a); c.workspace.deleteProject(b)
            c.workspace.setActiveProject(previous.id); root.deleteRecursively()
        }
    }

    @Test fun managedToolFileOwnershipAndAtomicRemovalWorkOnAndroid() = runBlocking {
        val root = File(c.appContext.cacheDir, "storage-tools-${System.nanoTime()}").apply { mkdirs() }
        val prefix = File(root, "linux").apply { mkdirs() }
        val binary = File(prefix, "bin/tool").apply { parentFile!!.mkdirs(); writeText("shared tool") }
        try {
            val store = com.androidharness.app.data.storage.SharedToolStore(File(root, "metadata"), prefix)
            val stamp = com.androidharness.app.data.storage.StorageFiles.snapshot(prefix, binary).single().copy(path = "bin/tool")
            store.put(com.androidharness.app.data.storage.ManagedTool("tool", "1", emptyList(), listOf(stamp), true))
            store.require(listOf("tool"), "Project A"); store.require(listOf("tool"), "Project B")
            store.release("tool", "Project A")
            assertNull(store.preview("tool", setOf("tool"), emptySet()))
            assertEquals("shared tool", binary.readText())
            store.release("tool", "Project B")
            val plan = requireNotNull(store.preview("tool", setOf("tool"), emptySet()))
            var committed = false
            assertEquals(11L, store.remove(plan, setOf("tool"), emptySet()) { committed = true })
            assertTrue(committed); assertFalse(binary.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun extractedDebFilesProduceAnActualOwnershipManifest() = runBlocking {
        val root = File(c.appContext.cacheDir, "storage-deb-${System.nanoTime()}").apply { mkdirs() }
        val fixtureContext = object : android.content.ContextWrapper(c.appContext) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDataDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        try {
            val env = com.androidharness.app.data.env.LinuxEnvironmentManager(fixtureContext)
            val payload = java.io.ByteArrayOutputStream()
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(java.util.zip.GZIPOutputStream(payload)).use { tar ->
                mapOf("bin/storage-tool" to "tool bytes", "lib/storage-test.dat" to "library bytes").forEach { (path, content) ->
                    val bytes = content.toByteArray()
                    val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("data/data/com.termux/files/usr/$path").apply {
                        size = bytes.size.toLong(); mode = 0x1A4
                    }
                    tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
                }
            }
            val deb = File(root, "fixture.deb")
            org.apache.commons.compress.archivers.ar.ArArchiveOutputStream(deb.outputStream()).use { ar ->
                val bytes = payload.toByteArray()
                ar.putArchiveEntry(org.apache.commons.compress.archivers.ar.ArArchiveEntry("data.tar.gz", bytes.size.toLong()))
                ar.write(bytes); ar.closeArchiveEntry()
            }
            val extract = env.javaClass.getDeclaredMethod("extractDeb", File::class.java).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val paths = extract.invoke(env, deb) as List<String>
            assertEquals(setOf("bin/storage-tool", "lib/storage-test.dat"), paths.toSet())
            env.recordPackageFiles(com.androidharness.app.data.env.PkgMeta("storage-tool", "1", emptyList(), "fixture.deb", deb.length(), ""), paths)
            val record = requireNotNull(env.managedTools()["storage-tool"])
            assertTrue(record.complete); assertEquals(23L, record.files.sumOf { it.size })
            assertEquals(paths.toSet(), record.ownedPaths.toSet())
            assertEquals("tool bytes", File(env.prefix, "bin/storage-tool").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun oldUnverifiedDetachedProcessStillBlocksMaintenanceAndCannotBeForgottenOnFailedStop() = runBlocking {
        val root = File(c.appContext.cacheDir, "storage-process-${System.nanoTime()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(c.appContext) { override fun getFilesDir() = root }
        try {
            val entry = com.androidharness.app.data.BgProcessEntry(77, "fixture only", root.path, "fixture.log",
                777777, "SHIZUKU", System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000)
            File(root, "bg-processes.json").writeText(kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(com.androidharness.app.data.BgProcessEntry.serializer()), listOf(entry)))
            val store = com.androidharness.app.data.BgProcessStore(context, c.linuxEnv, c.shizuku, root, privilegedLiveness = { null })
            assertNotNull(store.get(77))
            var ran = false
            assertTrue(runCatching { store.withIdleStorage { ran = true } }.isFailure)
            assertFalse(ran); assertFalse(store.kill(77)); assertNotNull(store.get(77))
        } finally { root.deleteRecursively() }
    }

    @Test fun compactStorageTabsCleanupPreviewAndRecreation() = runBlocking {
        val saved = c.settings.settings.first()
        val download = File(c.appContext.cacheDir, "deb-download/storage-ui-${System.nanoTime()}.deb").apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(8192)); setLastModified(1_000)
        }
        try {
            c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(false)
            instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            tap("Open navigation"); tap("Settings")
            c.pendingSettingsScroll.value = "storage"
            awaitNode("Storage on this device"); awaitNode("Measured on this device")
            assertVisibleBounds("Categories"); assertVisibleBounds("Largest"); assertVisibleBounds("Rescan")
            screenshot("storage-compact-overview.png")
            tap("Caches"); scrollTo("Linux package downloads"); screenshot("storage-compact-caches.png")
            scrollToTop(); tap("Largest"); screenshot("storage-compact-largest.png")
            tap("Categories"); tap("Review cleanup"); awaitNode(download.name)
            screenshot("storage-compact-cleanup.png")
            // Preview and cancel only: existing user cleanup candidates are never deleted by this UI test.
            var review = findPrefix("Review ", exclude = "Review cleanup")
            repeat(5) {
                if (review == null) { scrollForward(); SystemClock.sleep(120); instrumentation.uiAutomation.clearCache(); review = findPrefix("Review ", exclude = "Review cleanup") }
            }
            checkNotNull(review) { "Missing cleanup confirmation button" }
            click(requireNotNull(review)); awaitNode("Cancel"); screenshot("storage-compact-confirm.png"); tap("Cancel")
            assertTrue(download.exists())
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).toList()
                    .filterIsInstance<MainActivity>().forEach { it.recreate() }
            }
            awaitNode("Review cleanup")
            assertTrue(download.exists())
        } finally {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).toList()
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
            download.delete(); c.settings.setOnboardingDone(saved.onboardingDone); c.settings.setResumeLastChat(saved.resumeLastChat)
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.lines()?.contains(text) == true || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun findPrefix(prefix: String, exclude: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.startsWith(prefix) == true && node.text?.toString() != exclude) return node
        for (i in 0 until node.childCount) findPrefix(prefix, exclude, node.getChild(i))?.let { return it }
        return null
    }
    private fun scrollForward(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        node ?: return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scrollForward(node.getChild(i))) return true
        return false
    }
    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            find(text)?.let { return it }; SystemClock.sleep(80)
        }
        screenshot("storage-failure.png"); error("Missing UI node: $text")
    }
    /** Scrolls down until [text] is on screen; rows below the fold are not in the tree until then. */
    private fun scrollTo(text: String): AccessibilityNodeInfo {
        repeat(12) {
            instrumentation.uiAutomation.clearCache()
            find(text)?.let { return it }
            if (!scrollForward()) return@repeat
            SystemClock.sleep(150)
        }
        return awaitNode(text)
    }
    private fun scrollToTop() {
        repeat(20) { if (!scrollBackward()) return; SystemClock.sleep(120); instrumentation.uiAutomation.clearCache() }
    }
    private fun assertVisibleBounds(text: String) {
        val bounds = Rect(); awaitNode(text).getBoundsInScreen(bounds)
        assertTrue("$text has no visible area", bounds.width() > 0 && bounds.height() > 0)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(120)
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        SystemClock.sleep(180)
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = null
        repeat(10) {
            instrumentation.uiAutomation.clearCache()
            if (node == null) node = find(text)
            if (node == null) {
                if (text == "Overview" || text == "Shared") scrollBackward() else scrollForward()
                SystemClock.sleep(100)
            }
        }
        click(node ?: awaitNode(text))
    }
    private fun scrollBackward(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        node ?: return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        for (i in 0 until node.childCount) if (scrollBackward(node.getChild(i))) return true
        return false
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(350)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            c.appContext.cacheDir.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
