package com.androidharness.app.ui.projects

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun projectCreationAndRenamePreserveDefaultAndSavedChats() = runBlocking {
        val context = instrumentation.targetContext
        val name = "projects-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val root = File(context.cacheDir, name).apply { mkdirs() }
        try {
            var manager = WorkspaceManager(context, db.dao())
            val default = manager.currentProjectOnce()
            val project = manager.addShellProject(File(root, "alpha").apply { mkdirs() }.path, activate = false)
            assertEquals(default.id, manager.currentProjectOnce().id)
            assertEquals(project.id, manager.addShellProject(project.uri!!, activate = false).id)
            assertEquals(default.id, manager.currentProjectOnce().id)
            val sessions = SessionRepository(db)
            val sid = sessions.createProjectSession(project.id)
            manager.renameProject(project.id, "Renamed project")
            assertEquals(default.id, manager.currentProjectOnce().id)
            assertEquals(project.uri, db.dao().project(project.id)?.uri)
            assertEquals(project.id, sessions.session(sid)?.projectId)
            assertTrue(runCatching { sessions.createProjectSession("missing-project") }.isFailure)
            assertTrue(runCatching { manager.renameProject(project.id, " ") }.isFailure)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            manager = WorkspaceManager(context, db.dao())
            assertEquals("Renamed project", manager.projectForSession(sid).name)
            assertEquals(project.uri, manager.projectForSession(sid).uri)
            assertEquals(default.id, manager.currentProjectOnce().id)
        } finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }

    @Test fun projectChatNameSeriesNumbersCustomNamesAndPrunesEmptyOnes() = runBlocking {
        val roots = File(c.appContext.cacheDir, "series-${System.nanoTime()}").apply { mkdirs() }
        val workspace = c.workspace.addShellProject(File(roots, "series").apply { mkdirs() }.path, activate = false)
        val project = c.workspace.createWorkProject("Series project",
            ChatWorkspaceSelection(workspace.id, listOf(ChatWorkspaceGrant(workspace.id, true))), "  ALPHA ")
        try {
            assertEquals("ALPHA", project.chatNamePattern)
            assertEquals("ALPHA 1", c.runManager.nextProjectChatTitle(project.id))
            val first = c.runManager.createProjectChat(project.id, "New chat")
            val second = c.runManager.createProjectChat(project.id, "New chat")
            val named = c.runManager.createProjectChat(project.id, "New chat", "  Login bug ")
            assertEquals(listOf("ALPHA 1", "ALPHA 2", "Login bug"), listOf(first, second, named).map { c.sessions.session(it)?.title })
            // Deleting the first never brings its number back.
            c.sessions.deleteSession(requireNotNull(c.sessions.session(first)))
            assertEquals("ALPHA 3", c.runManager.nextProjectChatTitle(project.id))
            // Empty numbered chats are cleared like empty "New chat" rows; a name the user typed stays.
            val others = c.sessions.sessions.first().filter { it.workProjectId != project.id }.mapTo(mutableSetOf()) { it.id }
            c.sessions.pruneUntouchedChats(others, setOf("New chat"))
            assertEquals(listOf("Login bug"), c.workspace.workProjectChats(project.id).map { it.title })
            c.workspace.setWorkProjectChatNames(project.id, "Run #!")
            assertEquals("Run 1!", c.sessions.session(c.runManager.createProjectChat(project.id, "New chat"))?.title)
            c.workspace.setWorkProjectChatNames(project.id, "   ")
            assertNull(c.workspace.workProject(project.id)?.chatNamePattern)
            assertEquals("New chat", c.sessions.session(c.runManager.createProjectChat(project.id, "New chat"))?.title)
        } finally {
            c.workspace.workProjectChats(project.id).forEach { c.sessions.deleteSession(it) }
            c.runManager.deleteWorkProject(project.id)
            c.workspace.deleteProject(workspace); roots.deleteRecursively()
        }
    }

    @Test fun drawerBrowsingKeepsDraftAndNewChatUsesOpenedProject() = runBlocking {
        fixture { _, a, b, sa, _ ->
            awaitText("Alpha project chat")
            setText("Keep this unsent draft")
            tap("Open navigation"); tap("Projects"); tap("Manage projects"); awaitText("Add project")
            screenshot("projects-list")
            assertEquals(b.id, c.workspace.currentProjectOnce().id)
            tap(a.name); awaitText("Project memory")
            assertNotNull(find { it.text?.toString() == "Alpha project chat" })
            assertNull(find { it.text?.toString() == "Beta project chat" })
            assertEquals(a.id, c.sessions.session(sa)?.projectId)
            screenshot("project-chats")
            tap("Automations"); awaitText("Alpha automation")
            assertNull(find { it.text?.toString() == "Beta automation" })
            screenshot("project-automations")
            tap("Alpha automation"); awaitText("Edit automation"); awaitText(a.name)
            setText("Draft automation title", focus = false)
            val editing = c.automation.repository.tasks.value.single { it.projectId == a.id }
            c.automation.repository.updateTask(editing.id) { it.copy(lastMessage = "Status refreshed") }
            SystemClock.sleep(500)
            awaitText("Draft automation title")
            tap("Close"); tap("Back"); awaitText("Add automation")
            tap("Add automation"); awaitText("Schedule message")
            scrollTo("Workspace"); awaitText(a.name)
            screenshot("project-schedule-context")
            assertEquals(b.id, c.workspace.currentProjectOnce().id)
            tap("Close schedule"); tap("Back"); awaitText("Project memory")
            tap("Chats"); tap("New chat")
            awaitText("Message actions")
            withTimeout(15000) { while (c.sessions.sessions.first().none { it.title == "New chat" && it.projectId == a.id }) delay(50) }
            assertEquals(b.id, c.workspace.currentProjectOnce().id)
            // The previous chat remains on the navigation stack with its composer draft.
            shell("input keyevent 4"); awaitText("Project memory")
            tap("Back"); awaitText("Add project"); tap("Back")
            awaitText("Keep this unsent draft")
            assertEquals(a.id, c.sessions.session(sa)?.projectId)
        }
    }

    @Test fun projectOverviewToolsKeepTheSelectedProjectWithoutCreatingChats() = runBlocking {
        fixture { _, a, b, _, _ ->
            File(a.uri!!, "alpha-only.txt").writeText("Alpha workspace content")
            File(b.uri!!, "beta-only.txt").writeText("Beta workspace content")
            val before = c.sessions.sessions.first().map { it.id }.toSet()
            awaitText("Alpha project chat"); tap("Open navigation"); tap("Projects"); tap("Manage projects"); tap(a.name)
            awaitText("Build & Test"); awaitText("No checks run yet")
            screenshot("project-overview")
            tap("Files"); awaitText("alpha-only.txt")
            assertNull(find { it.text?.toString() == "beta-only.txt" })
            tap("alpha-only.txt"); awaitText("Alpha workspace content")
            shell("input keyevent 4"); awaitText("alpha-only.txt"); tap("Back")
            awaitText("Build & Test"); tap("Terminal"); awaitText("Terminal")
            withTimeout(10000) { while (c.terminal.state.value.cwd != a.uri) delay(50) }
            tap("Back"); awaitText("Build & Test"); tap("Build & Test"); awaitText(a.name)
            screenshot("project-build-checks")
            tap("Back"); awaitText("Project memory")
            assertEquals(b.id, c.workspace.currentProjectOnce().id)
            assertEquals(before, c.sessions.sessions.first().map { it.id }.toSet())
        }
    }

    @Test fun memoryConflictClearAndRecreationKeepNotesRecoverable() = runBlocking {
        fixture { activity, a, b, _, _ ->
            val fs = c.workspace.fsFor(a)
            ProjectMemory.save(fs, "", "Alpha memory")
            ProjectMemory.save(c.workspace.fsFor(b), "", "Beta memory")
            awaitText("Open navigation"); tap("Open navigation"); tap("Projects"); tap("Manage projects"); tap(a.name); tap("Project memory")
            awaitText("Alpha memory")
            setText("My pending notes")
            shell("input keyevent 4")
            ProjectMemory.save(fs, "Alpha memory", "Newer task notes")
            tap("Save")
            await { it.text?.toString()?.startsWith("Memory changed.") == true }
            assertEquals("Newer task notes", ProjectMemory.read(fs))
            awaitText("My pending notes")
            screenshot("project-memory-conflict")
            scrollTo("Reload latest notes"); tap("Reload latest notes"); awaitText("Discard your changes?"); tap("Keep editing")
            awaitText("My pending notes")
            tap("Reload latest notes"); tap("Discard"); awaitText("Newer task notes")
            tap("Clear")
            assertEquals("Newer task notes", ProjectMemory.read(fs))
            tap("Save"); awaitText("Saved")
            assertEquals("", ProjectMemory.read(fs))
            assertEquals("Beta memory", ProjectMemory.read(c.workspace.fsFor(b)))
            assertEquals(b.id, c.workspace.currentProjectOnce().id)
            // Use the real activity content throughout so saved-state keys match on recreation.
            setText("Rotation-safe notes")
            shell("input keyevent 4")
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity
            instrumentation.removeMonitor(monitor)
            assertNotNull(recreated)
            try {
                awaitText("Rotation-safe notes")
                instrumentation.runOnMainSync { recreated!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                awaitText("Rotation-safe notes")
                screenshot("project-memory-landscape")
                shell("input keyevent 4"); awaitText("Discard your changes?"); tap("Keep editing")
                awaitText("Rotation-safe notes")
            } finally { recreated?.let { instrumentation.runOnMainSync { it.finish() } } }
        }
    }

    private suspend fun fixture(block: suspend (MainActivity, com.androidharness.app.data.db.ProjectEntity,
        com.androidharness.app.data.db.ProjectEntity, String, String) -> Unit) {
        val previous = c.workspace.currentProjectOnce()
        val saved = c.settings.settings.first()
        val roots = File(c.appContext.cacheDir, "projects-ui-${System.nanoTime()}").apply { mkdirs() }
        val a = c.workspace.addShellProject(File(roots, "Alpha project").apply { mkdirs() }.path, activate = false)
        val b = c.workspace.addShellProject(File(roots, "Beta project").apply { mkdirs() }.path)
        val sa = c.sessions.createProjectSession(a.id)
        val sb = c.sessions.createProjectSession(b.id)
        c.sessions.renameSession(sa, "Alpha project chat")
        c.sessions.renameSession(sb, "Beta project chat")
        c.automation.repository.save(AutomationTask(title = "Alpha automation", prompt = "Fixture", projectId = a.id, projectName = a.name, enabled = false))
        c.automation.repository.save(AutomationTask(title = "Beta automation", prompt = "Fixture", projectId = b.id, projectName = b.name, enabled = false))
        c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(true); c.settings.setLastActiveSessionId(sa)
        shell("pm grant ${c.appContext.packageName} android.permission.POST_NOTIFICATIONS")
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { block(activity, a, b, sa, sb) }
        finally {
            shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 0")
            instrumentation.runOnMainSync { activity.finish() }
            c.automation.repository.tasks.value.filter { it.projectId == a.id || it.projectId == b.id }.forEach { c.automation.delete(it.id) }
            c.sessions.sessions.first().filter { it.projectId == a.id || it.projectId == b.id }.forEach { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.setSidebarProjects(saved.sidebarProjects)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId); c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setOnboardingDone(saved.onboardingDone); roots.deleteRecursively()
        }
    }
    private fun find(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        // A closed navigation drawer retains its search field off screen.
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            // An existing Shizuku setup may legitimately show its disconnected warning on entry.
            if (find { it.text?.toString() == "Shizuku is disabled" } != null) {
                var action = find { it.text?.toString() == "OK" }
                while (action != null && !action.isClickable) action = action.parent
                action?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(100)
            }
            find(predicate = predicate)?.let { return it }; SystemClock.sleep(100)
        }
        error("Missing UI node")
    }
    private fun awaitText(text: String): AccessibilityNodeInfo = try {
        await { it.text?.toString() == text || it.contentDescription?.toString() == text }
    } catch (e: IllegalStateException) { screenshot("projects-failure"); error("Missing UI text: $text") }
    private fun tap(text: String) {
        awaitText(text); SystemClock.sleep(400)
        var target = awaitText(text)
        while (!target.isClickable && target.parent != null) target = target.parent
        if (!target.isClickable || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = Rect(); awaitText(text).getBoundsInScreen(bounds)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        SystemClock.sleep(350)
    }
    private fun setText(text: String, focus: Boolean = true) {
        val editable = await { it.isEditable }
        if (focus) {
            editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(400)
        }
        assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        SystemClock.sleep(300)
        awaitText(text)
    }
    private fun scrollTo(text: String) {
        repeat(8) {
            if (find { it.text?.toString() == text } != null) return
            val display = instrumentation.targetContext.resources.displayMetrics
            shell("input swipe ${display.widthPixels / 2} ${display.heightPixels * 3 / 4} ${display.widthPixels / 2} ${display.heightPixels / 3} 300")
            SystemClock.sleep(250)
        }
        awaitText(text)
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(300)
        val folder = File(c.appContext.getExternalFilesDir(null), "projects-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
