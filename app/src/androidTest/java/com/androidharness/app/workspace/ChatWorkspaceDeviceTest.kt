package com.androidharness.app.workspace

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.llm.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatWorkspaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun assignmentsPersistAndLegacyChatsBindOnce() = runBlocking {
        val context = instrumentation.targetContext
        val name = "workspace-test-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val roots = File(context.cacheDir, name).apply { mkdirs() }
        try {
            var manager = WorkspaceManager(context, db.dao())
            val a = manager.addShellProject(File(roots, "alpha").apply { mkdirs() }.path)
            val b = manager.addShellProject(File(roots, "beta").apply { mkdirs() }.path)
            db.dao().insertSession(SessionEntity("a", "Alpha chat", 1, 1, projectId = a.id))
            db.dao().insertSession(SessionEntity("b", "Beta chat", 1, 1, projectId = b.id))
            db.dao().insertSession(SessionEntity("legacy", "Legacy chat", 1, 1))
            assertEquals(b.id, manager.projectForSession("legacy").id)
            manager.setActiveProject(a.id)
            assertEquals(b.id, manager.projectForSession("legacy").id)
            assertEquals(a.id, manager.projectForSession("a").id)
            assertEquals(b.id, manager.projectForSession("b").id)
            assertTrue(runCatching { manager.deleteProject(b) }.isFailure)

            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            manager = WorkspaceManager(context, db.dao())
            assertEquals(a.id, manager.projectForSession("a").id)
            assertEquals(b.id, manager.projectForSession("b").id)
            assertEquals(b.id, manager.projectForSession("legacy").id)
            // Simulate unavailable project metadata: never silently open another folder.
            db.dao().deleteProject(b)
            assertNull(manager.forChat("b").first())
            assertTrue(runCatching { manager.forSession("b") }.isFailure)
            assertEquals(a.id, manager.projectForSession("a").id)
        } finally {
            db.close(); context.deleteDatabase(name); roots.deleteRecursively()
        }
    }

    @Test fun concurrentRunsAndUndoStayInTheirAssignedFolders() = runBlocking {
        val previous = c.workspace.currentProjectOnce()
        val permission = c.settings.settings.first().permissionMode
        val roots = File(c.appContext.cacheDir, "chat-runs-${System.nanoTime()}").apply { mkdirs() }
        val aRoot = File(roots, "alpha").apply { mkdirs() }
        val bRoot = File(roots, "beta").apply { mkdirs() }
        File(aRoot, "project.txt").writeText("alpha original")
        File(bRoot, "project.txt").writeText("beta original")
        val a = c.workspace.addShellProject(aRoot.path)
        val b = c.workspace.addShellProject(bRoot.path)
        val sa = c.sessions.createSession("Alpha", a.id)
        val sb = c.sessions.createSession("Beta", b.id)
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val release = CompletableDeferred<Unit>()
        val prompts = ConcurrentHashMap<String, String>()
        val calls = ConcurrentHashMap<String, Int>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                prompts[config.model] = systemPrompt
                val call = calls.merge(config.model, 1, Int::plus)!!
                if (call == 1) {
                    entered[if (config.model == "alpha") 0 else 1].complete(Unit)
                    release.await()
                    emit(StreamEvent.ToolCallReady(ToolCallData("write-${config.model}", "write_file",
                        """{"path":"project.txt","content":"${config.model} changed"}""")))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    emit(StreamEvent.TextDelta("Done in ${config.model}"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace,
            c.linuxEnv, c.settings, TodoStore(), c.providers)
        suspend fun start(sid: String, model: String, replacement: String? = null) = runs.startRun(sid, "Edit project", emptyList(),
            ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", model),
            "fixture", PermissionMode.FULL_AUTO, AgentMode.ACT, 1024, 32000, ThinkingLevel.OFF, 5,
            notifyOnFinish = false, replacementMessageId = replacement)
        try {
            c.settings.setPermissionMode(PermissionMode.FULL_AUTO)
            start(sa, "alpha"); start(sb, "beta")
            withTimeout(30000) { entered.forEach { it.await() } }
            assertTrue(runs.isRunning(sa)); assertTrue(runs.isRunning(sb))
            assertTrue(runCatching { runs.assignWorkspace(sa, b.id) }.isFailure)
            c.workspace.setActiveProject(a.id)
            c.workspace.setActiveProject(b.id)
            release.complete(Unit)
            withTimeout(30000) { while (runs.isRunning(sa) || runs.isRunning(sb)) delay(50) }
            assertNull(runs.live(sa).value.error); assertNull(runs.live(sb).value.error)
            assertEquals("alpha changed\n", File(aRoot, "project.txt").readText())
            assertEquals("beta changed\n", File(bRoot, "project.txt").readText())
            assertTrue(prompts.getValue("alpha").contains(aRoot.path))
            assertTrue(prompts.getValue("beta").contains(bRoot.path))
            assertEquals(aRoot.path, runs.controls.flow(sa).value.workspacePath)
            assertEquals(bRoot.path, runs.controls.flow(sb).value.workspacePath)
            assertTrue(runCatching { runs.assignWorkspace(sa, b.id) }.isFailure)

            // Resending Alpha while Beta is selected must rewind only Alpha.
            val firstUser = c.sessions.messages(sa).first { it.role == com.androidharness.app.core.Role.USER }
            start(sa, "alpha", firstUser.id)
            withTimeout(30000) { while (runs.isRunning(sa)) delay(50) }
            assertEquals("alpha original", File(aRoot, "project.txt").readText())
            assertEquals("beta changed\n", File(bRoot, "project.txt").readText())
            val turn = c.checkpoints.turnsWithCheckpoints(sb).single()
            c.workspace.setActiveProject(a.id)
            assertEquals(0, runs.rewindFromTurn(sb, turn).filesFailed)
            assertEquals("beta original", File(bRoot, "project.txt").readText())
            assertEquals("alpha original", File(aRoot, "project.txt").readText())
        } finally {
            release.complete(Unit); runs.stopAllAndJoin()
            for (sid in listOf(sa, sb)) c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b)
            c.workspace.setActiveProject(previous.id); c.settings.setPermissionMode(permission)
            roots.deleteRecursively()
        }
    }

    @Test fun filesNavigationAndPickerFollowTheOpenChat() = runBlocking {
        val previous = c.workspace.currentProjectOnce()
        val saved = c.settings.settings.first()
        val roots = File(c.appContext.cacheDir, "chat-ui-${System.nanoTime()}").apply { mkdirs() }
        val aRoot = File(roots, "alpha-ui").apply { mkdirs() }
        val bRoot = File(roots, "beta-ui").apply { mkdirs() }
        File(aRoot, "only-alpha.txt").writeText("Alpha file")
        File(bRoot, "only-beta.txt").writeText("Beta file")
        val a = c.workspace.addShellProject(aRoot.path)
        val b = c.workspace.addShellProject(bRoot.path)
        val sa = c.sessions.createSession("Alpha UI chat", a.id)
        val sb = c.sessions.createSession("Beta UI chat", b.id)
        try {
            c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(true)
            c.settings.setLastActiveSessionId(sa)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${c.appContext.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
            }
            val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                awaitNode("Alpha UI chat")
                tap("Workspace files")
                awaitNode("only-alpha.txt")
                assertNull(find("only-beta.txt"))
                tap("Switch workspace")
                awaitNode("Assigned to this chat")
                tap(b.name)
                awaitNode("only-beta.txt")
                assertEquals(b.id, c.sessions.session(sa)?.projectId)
                assertEquals(b.id, c.sessions.session(sb)?.projectId)
                tap("Switch workspace"); awaitNode("Assigned to this chat"); tap(a.name)
                awaitNode("only-alpha.txt")
                assertEquals(a.id, c.sessions.session(sa)?.projectId)
                assertEquals(b.id, c.sessions.session(sb)?.projectId)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally {
            for (sid in listOf(sa, sb)) c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setResumeLastChat(saved.resumeLastChat); c.settings.setOnboardingDone(saved.onboardingDone)
            roots.deleteRecursively()
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI node: $text")
    }
    private fun tap(text: String) {
        val node = awaitNode(text)
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").close()
        SystemClock.sleep(250)
    }
}
