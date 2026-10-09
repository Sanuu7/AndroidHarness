package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.github.*
import com.androidharness.app.ui.github.GitHubPushPresetDialog
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GitHubDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun publicImportUsesItsOwnPrivateWorkspace() = runBlocking {
        assumeTrue("Pass githubImport=true for a read-only public GitHub clone", InstrumentationRegistry.getArguments().getString("githubImport") == "true")
        assumeTrue("Use an unsigned-in emulator fixture", c.keys.githubToken() == null)
        withTimeout(300_000) { c.linuxEnv.install(c.linuxEnv.corePackages) }
        val original = c.workspace.currentProjectOnce()
        var imported: com.androidharness.app.data.db.ProjectEntity? = null
        try {
            withTimeout(180_000) { c.githubRepositories.importRepository("octocat/Hello-World") }
            imported = c.workspace.currentProjectOnce()
            assertNotEquals(original.id, imported.id)
            val fs = c.workspace.fsFor(imported)
            assertTrue(requireNotNull(fs.shellRoot).canonicalPath.startsWith(File(context.filesDir, "github-projects").canonicalPath + "/"))
            assertTrue(fs.resolve(".git").exists)
            assertEquals("https://github.com/octocat/Hello-World.git", c.githubRepositories.inspect(fs).remoteUrl)
            println("GITHUB_PUBLIC_IMPORT_OK: real GitHub HTTPS clone; private workspace; no PAT or OAuth needed")
        } finally {
            c.workspace.setActiveProject(original.id)
            imported?.let { project ->
                val root = c.workspace.fsFor(project).shellRoot
                c.workspace.deleteProject(project)
                root?.deleteRecursively()
            }
        }
    }

    @Test fun nativePresetNeedsNoAiModelAndRecordsMissingGitHubConnection() = runBlocking {
        assumeTrue("Use an unsigned-in emulator fixture", c.keys.githubToken() == null)
        val project = c.workspace.currentProjectOnce()
        val task = com.androidharness.app.automation.AutomationTask(
            title = "GitHub connection test", prompt = "Publish", projectId = project.id, projectName = project.name,
            githubPush = GitHubPushPreset("https://github.com/example/repository.git", "main", "Test"))
        try {
            c.automation.save(task)
            withTimeout(30_000) { c.automation.execute(task.id) }
            val saved = requireNotNull(c.automation.repository.task(task.id))
            assertEquals(com.androidharness.app.automation.AutomationStatus.BLOCKED, saved.lastStatus)
            assertTrue(saved.lastMessage.orEmpty().contains("Connect GitHub"))
            val history = c.automation.repository.history.value.first { it.taskId == task.id }
            assertEquals("github", history.providerId)
            assertNotNull(history.finishedAt)
            assertNotNull(history.sessionId)
            assertTrue(c.runManager.runningSessionIds.value.isEmpty())
            println("GITHUB_NATIVE_PRESET_OK: no AI model required; missing connection recorded with recovery instruction")
        } finally { c.automation.delete(task.id) }
    }

    @Test fun publishingGuardBlocksOnlyTheWorkspaceWithAnActiveTask() = runBlocking {
        val id = "github-guard-fixture"
        val first = FileFs(File(context.filesDir, "github-first-workspace").apply { mkdirs() })
        val second = FileFs(File(context.filesDir, "github-second-workspace").apply { mkdirs() })
        val previous = c.runManager.runningSessionIds.value
        try {
            c.runManager.controls.update(id) { it.copy(workspacePath = first.displayPath) }
            c.runManager.runningSessionIds.value = previous + id
            assertTrue(runCatching { c.runManager.withIdleWorkspace(first) { error("Must not run") } }.exceptionOrNull()?.message.orEmpty().contains("task is working"))
            assertEquals("other workspace", c.runManager.withIdleWorkspace(second) { "other workspace" })
            println("GITHUB_WORKSPACE_GUARD_OK: active task blocks its own workspace; another workspace remains usable")
        } finally {
            c.runManager.runningSessionIds.value = previous
            first.shellRoot?.deleteRecursively(); second.shellRoot?.deleteRecursively()
        }
    }

    @Test fun githubSettingsAndDialogsRemainReachableWithLargeText() = runBlocking {
        c.settings.setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(c, onBack = {}) } } }
            val github = waitText("GitHub")
            val accounts = waitText("Connected accounts")
            val first = Rect(); val second = Rect()
            github.getBoundsInScreen(first); accounts.getBoundsInScreen(second)
            assertTrue("GitHub must appear before other settings", first.top < second.top)
            click("GitHub")
            scrollTo("Use a personal access token")
            screenshot("github-settings")
            click("Use a personal access token")
            scrollTo("Verify & connect")
            assertTrue(clickable(waitText("Verify & connect")).isVisibleToUser)
            scrollTo("Cancel"); click("Cancel")
            scrollTo("Import from GitHub"); click("Import from GitHub")
            scrollTo("Import repository")
            assertTrue(clickable(waitText("Import repository")).isVisibleToUser)
            screenshot("github-import")
            click("Close")
            scrollTo("Commit & push"); click("Commit & push")
            waitEnabled("Close")
            scrollTo("Refresh repository")
            assertTrue(clickable(waitText("Refresh repository")).isVisibleToUser)
            screenshot("github-publish")
            click("Close")
            val project = c.workspace.currentProjectOnce()
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                GitHubPushPresetDialog(c, project.id, project.name, onDismiss = {},
                    initial = GitHubPushPreset("https://github.com/example/repository.git", "main", "Update", listOf("README.md")))
            } } }
            scrollTo("Daily"); click("Daily")
            scrollTo("Save push preset")
            assertTrue(clickable(waitText("Save push preset")).isVisibleToUser)
            screenshot("github-preset")
            println("GITHUB_LAYOUT_OK: settings first; PAT, import, publish and daily preset actions reachable")
        }
    }

    @Test fun realAndroidGitPublishesToFixtureAndPreservesOtherStaging() = runBlocking {
        assumeTrue("Pass githubGit=true to install Git and exercise the device toolchain", InstrumentationRegistry.getArguments().getString("githubGit") == "true")
        withTimeout(300_000) { c.linuxEnv.install(c.linuxEnv.corePackages) }
        val root = File(context.filesDir, "github-device-test").apply { mkdirs() }
        val work = File(root, "work").apply { mkdirs() }
        val remote = File(root, "remote.git").apply { mkdirs() }
        suspend fun git(fs: FileFs, vararg args: String): GitCommandResult {
            val result = c.shellRouter.runWorkspace(gitCommand(args.toList()), fs, 30_000, 256_000)
            return GitCommandResult(result.exitCode, result.rawOutput, result.rawStderr)
        }
        val fs = FileFs(work)
        try {
            assertEquals(0, git(FileFs(remote), "init", "--bare").code)
            assertEquals(0, git(fs, "init", "-b", "main").code)
            git(fs, "config", "user.name", "Device Test"); git(fs, "config", "user.email", "test@example.com")
            git(fs, "remote", "add", "origin", "https://github.com/test/repo.git")
            git(fs, "config", "url.${remote.absolutePath}.insteadOf", "https://github.com/test/repo.git")
            File(work, "selected.txt").writeText("selected")
            File(work, "other.txt").writeText("other")
            git(fs, "add", "other.txt")
            val repository = GitHubRepository { args -> git(fs, *args.toTypedArray()) }
            val state = repository.inspect()
            repository.publish(state, setOf("selected.txt"), "Device commit", true)
            assertEquals("other.txt", git(fs, "diff", "--cached", "--name-only").out.trim())
            assertEquals(git(fs, "rev-parse", "HEAD").out.trim(), git(FileFs(remote), "rev-parse", "main").out.trim())
            val original = c.workspace.currentProjectOnce()
            val project = c.workspace.addShellProject(work.absolutePath)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                        com.androidharness.app.ui.github.GitHubPublishDialog(c, project.id, project.name, onDismiss = {})
                    } } }
                    waitEnabled("Close")
                    scrollTo("other.txt")
                    assertTrue(visible(waitText("other.txt")))
                    screenshot("github-publish-files")
                    scrollTo("Commit selected files & push")
                    assertTrue(visible(clickable(waitText("Commit selected files & push"))))
                    scrollTo("Push existing commits")
                    assertTrue(visible(clickable(waitText("Push existing commits"))))
                    scrollTo("Save reusable push preset")
                    assertTrue(visible(clickable(waitText("Save reusable push preset"))))
                    screenshot("github-publish-actions")
                }
            } finally { c.workspace.setActiveProject(original.id); c.workspace.deleteProject(project) }
            println("GITHUB_ANDROID_GIT_OK: real bundled Git committed and pushed selected file; unrelated staging kept; remote hash verified")
        } finally { root.deleteRecursively() }
    }

    private fun find(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { find(it, match)?.let { result -> return result } }
        return null
    }
    private fun text(value: String) = instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
        find(root) { it.text?.toString() == value || it.contentDescription?.toString() == value }
    }
    private fun waitText(value: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            text(value)?.let { return it }; SystemClock.sleep(100)
        }
        error("Missing UI text: $value")
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun click(value: String) {
        assertTrue("Cannot click $value", clickable(waitText(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(300)
    }
    private fun visible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 &&
            bounds.top >= 0 && bounds.bottom <= context.resources.displayMetrics.heightPixels
    }
    private fun waitEnabled(value: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (text(value)?.let { clickable(it).isEnabled } == true) return
            SystemClock.sleep(100)
        }
        error("Control stayed disabled: $value")
    }
    private fun scrollTo(value: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(16) {
                if (text(value)?.let { visible(clickable(it)) } == true) return
                instrumentation.uiAutomation.rootInActiveWindow?.let { root -> find(root) { it.isScrollable } }
                    ?.performAction(direction)
                SystemClock.sleep(200)
            }
        }
        assertTrue("Control is outside the screen: $value", visible(clickable(waitText(value))))
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val folder = File(context.getExternalFilesDir(null), "github-test-screenshots").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
