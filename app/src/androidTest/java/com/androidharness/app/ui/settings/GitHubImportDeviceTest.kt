package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.github.GitHubImportPicker
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.theme.LocalHarnessGlassScene
import com.androidharness.app.ui.theme.recordHarnessGlassScene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GitHubImportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun signedInRepositoriesLoadImmediatelyAndOnlyImportAfterConfirmation() {
        var loads = 0
        var imports = 0
        var opened: String? = null
        showPicker("fixture-user", { page -> assertEquals(1, page); loads++; listOf("owner/one", "owner/two") },
            { repository, progress -> imports++; assertEquals("owner/two", repository); progress("Downloading fixture"); "project-two" },
            { opened = it }) {
            await("owner/one"); assertEquals(1, loads)
            tap("owner/one"); await("Import repository?")
            assertEquals(0, imports)
            tap("Cancel"); await("owner/two"); assertEquals(0, imports)
            tap("owner/two"); tap("Import repository")
            await("Imported owner/two")
            assertEquals(1, imports); assertEquals("project-two", opened)
            screenshot("github-import-confirmed")
        }
    }

    @Test fun failedImportKeepsThePickerOpenAndRetryUsesTheSelectedRepository() {
        var loads = 0
        var imports = 0
        var opened: String? = null
        showPicker("fixture-user", { loads++; listOf("owner/repository") }, { _, _ ->
            imports++
            if (imports == 1) error("Fixture connection failed")
            "retry-project"
        }, { opened = it }) {
            tap("owner/repository"); tap("Import repository")
            await("Fixture connection failed"); await("Choose a repository")
            assertNull(opened)
            tap("owner/repository"); tap("Import repository")
            await("Imported owner/repository")
            assertEquals("retry-project", opened)
            assertEquals(2, imports); assertEquals(1, loads)
        }
    }

    @Test fun publicUrlImportStillWorksWithoutSigningInOrBrowsing() {
        var imports = 0
        showPicker(null, { error("Signed-out import must not request private repositories") }, { repository, _ ->
            assertEquals("octocat/Hello-World", repository); imports++; "public-project"
        }, {}) {
            val edit = awaitNode { it.isEditable }
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "https://github.com/octocat/Hello-World")
            })
            tap("Import repository"); await("Import repository?")
            assertEquals(0, imports)
            tap("Import repository"); await("Imported octocat/Hello-World")
            assertEquals(1, imports)
        }
    }

    @Test fun urlImportControlsStayVisibleWhenRepositoryLoadingFinishes() {
        val repositories = CompletableDeferred<List<String>>()
        var imports = 0
        showPicker("fixture-user", { repositories.await() }, { _, _ -> imports++; "url-project" }, {}) {
            tap("Import by URL")
            awaitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "owner/target")
            })
            repositories.complete((1..30).map { "owner/repo-$it" })
            await("owner/repo-1")
            val button = await("Import repository")
            val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
            assertTrue("Loading repositories must not move URL import offscreen", button.isVisibleToUser &&
                bounds.top >= 0 && bounds.bottom < context.resources.displayMetrics.heightPixels)
            tap("Import repository"); assertEquals(0, imports)
            tap("Import repository"); await("Imported owner/target")
            assertEquals(1, imports)
        }
    }

    @Test fun realCloneOpensItsFilesWithoutChangingThePreviousWorkspace() = runBlocking {
        val container = (context.applicationContext as HarnessApp).container
        val settings = container.settings.settings.first()
        val original = container.workspace.currentProjectOnce()
        val previousSession = settings.lastActiveSessionId?.let { container.sessions.session(it) }
        val existingIds = container.workspace.projects.first().map { it.id }.toSet()
        var imported: com.androidharness.app.data.db.ProjectEntity? = null
        var cloneRoot: File? = null
        fun isFixture(project: com.androidharness.app.data.db.ProjectEntity): Boolean =
            project.id !in existingIds && project.name == "Hello-World"
        try {
            container.settings.setOnboardingDone(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    host.setContent {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true) {
                        com.androidharness.app.ui.AppNav(container)
                        var open by remember { mutableStateOf(true) }
                        if (open) com.androidharness.app.ui.github.GitHubImportDialog(container,
                            onDismiss = { open = false }, activateWorkspace = false)
                    }
                } }
                if (container.github.state.value.login != null) tap("Import by URL")
                val edit = awaitNode { it.isEditable }
                assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "https://github.com/octocat/Hello-World")
                }))
                awaitNode { it.isEditable && it.text?.toString()?.contains("octocat/Hello-World") == true }
                tap("Import repository")
                await("Import repository?")
                screenshot("github-import-real-confirmation")
                tap("Import repository")
                awaitNode(180_000) { it.text?.toString()?.lines()?.contains("README") == true }
                await("Hello-World")
                imported = withTimeout(10_000) { container.workspace.projects.first { rows -> rows.any(::isFixture) } }
                    .first(::isFixture)
                val fs = container.workspace.fsFor(requireNotNull(imported))
                cloneRoot = fs.shellRoot
                assertTrue(fs.resolve("README").isFile)
                assertEquals("https://github.com/octocat/Hello-World.git", container.githubRepositories.inspect(fs).remoteUrl)
                assertEquals(original.id, container.workspace.currentProjectOnce().id)
                previousSession?.let { old -> assertEquals(old.projectId, container.sessions.session(old.id)?.projectId) }
                screenshot("github-import-opened-real-files")
            }
        } finally {
            container.pendingImportedWorkspace.value = null
            val created = imported ?: container.workspace.projects.first().firstOrNull(::isFixture)
            val folder = cloneRoot ?: created?.let { container.workspace.fsFor(it).shellRoot }
            created?.let { container.workspace.deleteProject(it) }
            folder?.deleteRecursively()
            container.settings.setOnboardingDone(settings.onboardingDone)
        }
    }

    private fun showPicker(login: String?, load: suspend (Int) -> List<String>,
        importer: suspend (String, (String) -> Unit) -> String, opened: (String) -> Unit, block: () -> Unit) {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.setContent {
                HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true) {
                    Box(Modifier.fillMaxSize().recordHarnessGlassScene(LocalHarnessGlassScene.current)) { Text("Code workspace") }
                    GitHubImportPicker(login, load, importer, { it.message.orEmpty() }, opened, {})
                }
            } }
            block()
        }
    }

    private fun awaitNode(timeoutMs: Long = 15_000, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (match(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("github-import-missing-control")
        error("Missing import control")
    }
    private fun await(label: String) = awaitNode { it.text?.toString()?.lines()?.contains(label) == true || it.contentDescription?.toString() == label }
    private fun tap(label: String) {
        fun actionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var action = node
            while (!action.isClickable && action.parent != null) action = action.parent
            return action
        }
        val node = awaitNode {
            (it.text?.toString()?.lines()?.contains(label) == true || it.contentDescription?.toString() == label) &&
                actionNode(it).let { action -> action.isClickable && action.isEnabled }
        }
        assertTrue("Cannot tap $label", actionNode(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(200)
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
