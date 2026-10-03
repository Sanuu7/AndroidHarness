package com.androidharness.app.ui.common

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.StorageAccess
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.WorkspaceManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run each case on a freshly cleared emulator without pre-granted permissions. */
@RunWith(AndroidJUnit4::class)
class StorageAccessDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as HarnessApp).container

    @Before
    fun requireCleanLegacyEmulator() {
        assumeTrue("Pass storageEmulator=true on an isolated Android 9/10 emulator",
            InstrumentationRegistry.getArguments().getString("storageEmulator") == "true")
        assumeTrue(Build.VERSION.SDK_INT in 28..29)
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        assertFalse("Clear the app between cases; do not install with -g", StorageAccess.isGranted(context))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        runBlocking { container.settings.setOnboardingDone(false) }
    }

    @Test
    fun deniedStorageStillAllowsSetupAndAppWorkspace() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("Welcome to AndroidHarness")
            assertTrue(clickable(awaitText("Start harness")).isEnabled)
            clickText("Grant")
            clickId("com.android.packageinstaller:id/permission_deny_button")
            awaitText("Ready with the app workspace")
            assertFalse(StorageAccess.isGranted(context))
            screenshot("storage-denied-setup")
            clickText("Start harness")
            awaitCondition("Setup did not finish") { runBlocking { container.settings.settings.first().onboardingDone } }
            awaitCondition("Setup remained visible") { findText("Welcome to AndroidHarness") == null }

            val workspace = container.workspace.currentOnce()
            val root = requireNotNull(workspace.shellRoot)
            val file = workspace.resolve("storage-test/file.txt")
            try {
                file.writeText("app workspace works")
                assertEquals("app workspace works", file.readText())
                val result = container.shellRouter.run(
                    "printf shell-ok > storage-test/shell.txt; cat storage-test/shell.txt", root, 10_000, 2_000,
                )
                assertEquals(result.rawStderr, 0, result.exitCode)
                assertEquals("shell-ok", result.rawOutput.trim())
                assertEquals(ExecutionTier.TOYBOX, result.tier)
                assertNull("Private workspace must not report missing broad access", result.note)
                assertFalse(StorageAccess.isGranted(context))
                screenshot("storage-denied-app-open")
                println("STORAGE_SETUP_DENIED_OK: SDK=${Build.VERSION.SDK_INT}; setup finished; app file and shell read/write work without storage grants")
            } finally {
                File(root, "storage-test").deleteRecursively()
            }
        }
    }

    @Test
    fun normalPermissionGrantUnlocksDeviceFolders() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("Welcome to AndroidHarness")
            clickText("Grant")
            clickId("com.android.packageinstaller:id/permission_allow_button")
            awaitCondition("Storage permission was not granted") { StorageAccess.isGranted(context) }
            awaitText("Device storage access granted")
            for (permission in StorageAccess.runtimePermissions()) {
                assertEquals(permission, PackageManager.PERMISSION_GRANTED,
                    ContextCompat.checkSelfPermission(context, permission))
            }
            screenshot("storage-granted-setup")
            clickText("Start harness")
            awaitCondition("Setup did not finish") { runBlocking { container.settings.settings.first().onboardingDone } }
            val root = File(Environment.getExternalStorageDirectory(), "Download/HarnessStorageProbe")
            try {
                assertTrue(root.mkdirs() || root.isDirectory)
                val fs = FileFs(root)
                fs.resolve("project.txt").writeText("shared storage works")
                assertEquals("shared storage works", fs.resolve("project.txt").readText())
                val result = container.shellRouter.run("cat project.txt", root, 10_000, 2_000)
                assertEquals(result.rawStderr, 0, result.exitCode)
                assertEquals("shared storage works", result.rawOutput.trim())
                assertNull(result.note)
                val picked = AtomicReference<String>()
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    FolderPickerDialog(container, onPick = picked::set, onDismiss = {})
                } } }
                clickText("Download")
                clickText("HarnessStorageProbe")
                awaitText(root.absolutePath)
                screenshot("storage-device-folder-browser")
                clickText("Use this folder")
                awaitCondition("Folder browser did not select the project") { picked.get() == root.absolutePath }
                println("STORAGE_GRANT_BROWSER_OK: SDK=${Build.VERSION.SDK_INT}; native dialog grants read and write; device file and shell access; folder selection works")
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun permanentDenialOpensAppSettingsAndSetupRemainsAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("Welcome to AndroidHarness")
            clickText("Grant")
            clickId("com.android.packageinstaller:id/permission_deny_button")
            awaitText("Ready with the app workspace")
            clickText("Grant")
            clickId("com.android.packageinstaller:id/do_not_ask_checkbox")
            clickId("com.android.packageinstaller:id/permission_deny_button")
            awaitText("Ready with the app workspace")
            assertFalse(StorageAccess.isGranted(context))
            clickText("Grant")
            awaitCondition("Permanent denial did not open app settings") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
            }
            screenshot("storage-permanent-denial-settings")
            shell("input keyevent 4")
            awaitText("Welcome to AndroidHarness")
            assertTrue(clickable(awaitText("Start harness")).isEnabled)
            clickText("Start harness")
            awaitCondition("Permanent denial blocked setup") { runBlocking { container.settings.settings.first().onboardingDone } }
            println("STORAGE_PERMANENT_DENIAL_OK: SDK=${Build.VERSION.SDK_INT}; app settings opens and setup can still finish")
        }
    }

    @Test
    fun settingsCanGrantLegacyStorageAfterSkippingSetup() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("Welcome to AndroidHarness")
            clickText("Start harness")
            awaitCondition("Setup did not finish") { runBlocking { container.settings.settings.first().onboardingDone } }
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                SettingsScreen(container, onBack = {})
            } } }
            scrollToText("Terminal & device")
            clickText("Terminal & device")
            scrollToText("Grant")
            clickText("Grant")
            clickId("com.android.packageinstaller:id/permission_allow_button")
            awaitCondition("Settings grant did not update") { StorageAccess.isGranted(context) }
            awaitText("Granted: the shell and file tools can use shared storage.")
            screenshot("storage-settings-granted")
            println("STORAGE_SETTINGS_GRANT_OK: SDK=${Build.VERSION.SDK_INT}; Grant is available on legacy Android and updates immediately")
        }
    }

    @Test
    fun systemPickerWithoutStoragePreservesFilesAndProjectHistory() = runBlocking {
        val fixture = "/storage/emulated/0/Download/HarnessSafProbe"
        shell("mkdir -p $fixture")
        shell("touch $fixture/seed.txt")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Welcome to AndroidHarness")
                clickText("Start harness")
                awaitCondition("Setup did not finish") { runBlocking { container.settings.settings.first().onboardingDone } }
                // A folder added earlier through the device browser must keep its
                // identity and chat history when reopened with a picker grant.
                val previous = container.workspace.addShellProject(fixture)
                val session = container.sessions.createSession("Storage picker regression", previous.id)
                container.sessions.addMessage(session, ChatMessage(Role.USER, "history survives"))
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    SettingsScreen(container, onBack = {})
                } } }
                scrollToText("Workspaces")
                clickText("Workspaces")
                scrollToText("Add workspace")
                clickText("Add workspace")
                clickText("On this device")
                clickText("Open system picker")
                awaitText("Show roots")
                clickText("Show roots")
                clickText("Downloads")
                // DocumentsUI's folder rows on API 28 do not expose ACTION_CLICK.
                val folderBounds = Rect().also { awaitText("HarnessSafProbe").getBoundsInScreen(it) }
                shell("input tap ${folderBounds.centerX()} ${folderBounds.centerY()}")
                awaitText("seed.txt")
                screenshot("storage-system-picker")
                clickId("android:id/button1")
                awaitCondition("Picker result was not retained") {
                    runBlocking { container.workspace.currentProjectOnce().kind == WorkspaceManager.KIND_SAF }
                }
                val project = container.workspace.currentProjectOnce()
                val uri = Uri.parse(requireNotNull(project.uri))
                assertEquals(previous.id, project.id)
                assertFalse(StorageAccess.isGranted(context))
                assertTrue("Picker must persist read and write access", context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                })
                val workspace = container.workspace.fsFor(project)
                assertTrue("Denied broad access must use the picker grant", workspace.isSaf)
                assertNull(workspace.shellRoot)
                assertEquals("", workspace.resolve("seed.txt").readText())
                workspace.resolve("written.txt").writeText("picker write works")
                assertEquals("picker write works", workspace.resolve("written.txt").readText())
                val reopened = container.workspace.addPickedFolder(uri)
                assertEquals(project.id, reopened.id)
                assertEquals(project.id, container.sessions.session(session)?.projectId)
                assertEquals("history survives", container.sessions.messages(session).single().text)
                scenario.recreate()
                assertEquals("picker write works", container.workspace.fsFor(reopened).resolve("written.txt").readText())
                assertFalse(StorageAccess.isGranted(context))
                screenshot("storage-system-picker-workspace")
                println("STORAGE_SYSTEM_PICKER_OK: SDK=${Build.VERSION.SDK_INT}; persisted picker read/write without broad permission; project identity and chat history survive reopening")
            }
        } finally {
            shell("rm -rf $fixture")
        }
    }

    private fun findText(text: String): AccessibilityNodeInfo? = root()?.let { find(it) { node ->
        node.text?.toString() == text || node.contentDescription?.toString() == text
    } }

    private fun root(): AccessibilityNodeInfo? = instrumentation.uiAutomation.let { automation ->
        automation.rootInActiveWindow ?: automation.windows
            .firstOrNull { it.isActive || it.isFocused }?.root
    }

    private fun find(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, match)?.let { return it } }
        }
        return null
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        awaitCondition("Missing text: $text") {
            node = findText(text)
            node != null
        }
        return requireNotNull(node)
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    private fun clickText(text: String) {
        val node = clickable(awaitText(text))
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        runCatching { instrumentation.uiAutomation.waitForIdle(300, 3_000) }
    }

    private fun clickId(id: String) {
        var node: AccessibilityNodeInfo? = null
        awaitCondition("Missing Android control: $id") {
            node = root()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            node != null
        }
        assertTrue("Cannot click $id", requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        runCatching { instrumentation.uiAutomation.waitForIdle(300, 3_000) }
    }

    private fun scrollToText(text: String) {
        repeat(12) {
            if (findText(text)?.isVisibleToUser == true) return
            root()?.let { find(it) { node -> node.isScrollable } }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        awaitText(text)
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        root()?.let { node ->
            fun dump(current: AccessibilityNodeInfo) {
                if (current.text != null || current.contentDescription != null) {
                    println("UI_AT_FAILURE: ${current.packageName} ${current.text} ${current.contentDescription} ${current.viewIdResourceName}")
                }
                for (i in 0 until current.childCount) current.getChild(i)?.let(::dump)
            }
            dump(node)
        }
        runCatching { screenshot("storage-test-failure") }
        throw AssertionError(message)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }
}
