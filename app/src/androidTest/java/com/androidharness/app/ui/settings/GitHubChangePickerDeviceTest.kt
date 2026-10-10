package com.androidharness.app.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.github.GitChange
import com.androidharness.app.ui.github.GitHubChangePicker
import com.androidharness.app.ui.github.GitHubCommitFooter
import com.androidharness.app.ui.github.GitHubDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GitHubChangePickerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun folderLongPressAndNavigationPreserveSelectionsAndPublishExactFiles() {
        val changes = listOf(GitChange("app/src/Main.kt", " M"), GitChange("app/src/deleted.kt", " D"),
            GitChange("app/renamed.txt", "R ", "old.txt"), GitChange("other/note.txt", "??"), GitChange("README.md", " M"))
        var published: Set<String>? = null
        withPicker(changes, { published = it }) {
            await("0 of 5 files selected")
            assertFalse(actionNode(await("Commit selected files & push")).isEnabled)
            tap("app")
            hold("src")
            await("2 of 5 files selected")
            tap("src")
            assertTrue(await("Select Main.kt").isChecked)
            assertTrue(await("Select deleted.kt").isChecked)
            tap("Select deleted.kt")
            await("1 of 5 files selected")
            tap("Up one folder")
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                assertEquals("Partially checked", await("Select src").stateDescription?.toString())
            }
            tap("Workspace")
            hold("other")
            await("2 of 5 files selected")
            tap("app"); tap("src")
            assertTrue(await("Select Main.kt").isChecked)
            assertFalse(await("Select deleted.kt").isChecked)
            tap("Workspace")
            tap("Select app")
            await("4 of 5 files selected")
            hold("app")
            await("1 of 5 files selected")
            hold("README.md")
            await("2 of 5 files selected")
            val edit = awaitNode { it.className?.toString() == "android.widget.EditText" }
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Selected folders")
            }))
            awaitNode { it.text?.toString() == "Commit selected files & push" && actionNode(it).isEnabled }
            tap("Commit selected files & push")
            assertEquals(setOf("other/note.txt", "README.md"), published)
            screenshot("github-folder-selection")
            tap("Select all files"); await("5 of 5 files selected")
            tap("Deselect all files"); await("0 of 5 files selected")
        }
    }

    @Test fun manyFilesScrollWithCountAndCommitControlsStillVisible() {
        val changes = (0..79).map { GitChange("file-${it.toString().padStart(3, '0')}.txt", "??") }
        withPicker(changes) {
            await("0 of 80 files selected")
            // Choose the list rather than its outer dialog scroller.
            repeat(30) {
                if (find { matches(it, "file-079.txt") }?.let(::visible) == true) return@repeat
                val list = awaitNode { it.isScrollable && !hasScrollableChild(it) }
                list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
            assertTrue(visible(await("file-079.txt")))
            hold("file-079.txt")
            assertTrue(visible(await("1 of 80 files selected")))
            assertTrue(visible(await("Commit selected files & push")))
            assertTrue(visible(await("Close")))
            screenshot("github-folder-picker-many-files")
        }
    }

    private fun withPicker(changes: List<GitChange>, onPublish: (Set<String>) -> Unit = {}, block: () -> Unit) = runBlocking {
        val container = (context.applicationContext as HarnessApp).container
        val saved = container.settings.settings.first()
        container.settings.setOnboardingDone(true)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                var directory by remember { mutableStateOf("") }
                var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
                var message by remember { mutableStateOf("") }
                GitHubDialog("Commit & push", false, {}, footer = {
                    GitHubCommitFooter(selected.size, changes.size, message, { message = it }, true) { onPublish(selected) }
                }) {
                    Text("Folder selection fixture")
                    GitHubChangePicker(changes, selected, directory, { directory = it }, { selected = it }, true)
                }
            } } }
            block()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            container.settings.setOnboardingDone(saved.onboardingDone)
        }
    }

    private fun matches(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString()?.lines()?.contains(text) == true || node.contentDescription?.toString() == text

    private fun hasScrollableChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) node.getChild(i)?.let {
            if (it.isScrollable || hasScrollableChild(it)) return true
        }
        return false
    }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun awaitNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            find(match)?.let { return it }; SystemClock.sleep(100)
        }
        error("Missing UI node")
    }
    private fun await(text: String) = awaitNode { matches(it, text) }
    private fun actionNode(node: AccessibilityNodeInfo, long: Boolean = false): AccessibilityNodeInfo {
        var current = node
        while ((if (long) !current.isLongClickable else !current.isClickable) && current.parent != null) current = current.parent
        return current
    }
    private fun tap(text: String) {
        assertTrue("Cannot tap $text", actionNode(await(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150)
    }
    private fun hold(text: String) {
        assertTrue("Cannot hold $text", actionNode(await(text), true).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        SystemClock.sleep(150)
    }
    private fun visible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 && bounds.top >= 0 &&
            bounds.bottom <= context.resources.displayMetrics.heightPixels
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
