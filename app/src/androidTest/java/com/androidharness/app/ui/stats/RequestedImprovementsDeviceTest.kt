package com.androidharness.app.ui.stats

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.files.CodeEditorScreen
import com.androidharness.app.ui.settings.ChatRestoreDialog
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.*
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RequestedImprovementsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun editorConflictRetainsDraftAcrossRotationAndRechecksReviewedOverwrite(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "conflict-ui-${System.nanoTime()}").apply { mkdirs() }
        val file = File(root, "test.kt").apply { writeText("original\n") }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createProjectSession(project.id)
        val activityBefore = c.sessions.codeActivityBetween(0, Long.MAX_VALUE).first()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                fun mount() {
                    scenario.onActivity { it.setContent { HarnessTheme { CodeEditorScreen(c, file.name, sessionId = sid, onBack = {}) } } }
                    eventually { var ready = false; scenario.onActivity { ready = editor(it.window.decorView) != null }; ready }
                }
                mount(); tap("Edit")
                scenario.onActivity { requireNotNull(editor(it.window.decorView)).setText("my draft\n") }
                file.writeText("agent version\n")
                c.sessions.addMessage(sid, ChatMessage(role = Role.SYSTEM, text = "Agent updated file"))
                scenario.recreate(); mount(); tap("Save"); await("File changed on disk")
                assertEquals("agent version\n", file.readText())
                tap("Compare"); await("Current saved file"); await("Your edits"); screenshot("editor-conflict")
                file.writeText("newer agent version\n")
                tap("Keep my edits"); await("File changed on disk")
                assertEquals("newer agent version\n", file.readText())
                tap("Reload file")
                eventually { var text = ""; scenario.onActivity { text = editor(it.window.decorView)?.text.toString() }; text == "newer agent version\n" }
                scenario.onActivity { requireNotNull(editor(it.window.decorView)).setText("final draft\n") }
                file.writeText("third agent version\n")
                tap("Save"); await("File changed on disk"); tap("Keep my edits")
                eventually { file.readText() == "final draft\n" }
                assertEquals(activityBefore, c.sessions.codeActivityBetween(0, Long.MAX_VALUE).first())
            }
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; c.workspace.deleteProject(project); root.deleteRecursively() }
    }

    @Test fun restoreDialogReconnectsBothRootsAndRetainsReadOnlyAccess(): Unit = runBlocking {
        val context = c.appContext
        val suffix = System.nanoTime().toString().takeLast(8)
        val previous = c.workspace.currentProjectOnce()
        val root = File(context.cacheDir, "restore-ui-$suffix").apply { mkdirs() }
        val sourceName = "restore-source-$suffix.db"
        val source = Room.databaseBuilder(context, AppDatabase::class.java, sourceName).build()
        val targetA = c.workspace.addShellProject(File(root, "Reconnect Alpha $suffix").apply { mkdirs() }.path)
        val targetB = c.workspace.addShellProject(File(root, "Reconnect Beta $suffix").apply { mkdirs() }.path)
        var sid: String? = null
        try {
            val manager = WorkspaceManager(context, source.dao())
            val a = manager.addShellProject(File(root, "Original Alpha").apply { mkdirs() }.path)
            val b = manager.addShellProject(File(root, "Original Beta").apply { mkdirs() }.path)
            val repo = SessionRepository(source)
            sid = repo.createProjectSession(a.id)
            source.dao().setChatWorkspaces(sid, a.id, ChatWorkspaceSelection.encode(ChatWorkspaceSelection(a.id,
                listOf(ChatWorkspaceGrant(a.id, true), ChatWorkspaceGrant(b.id, false)))))
            repo.addMessage(sid, ChatMessage(role = Role.USER, text = "Restored history stays intact"))
            val backup = File(root, "chats.harnessbackup")
            ChatBackupManager(source, context).exportTo(Uri.fromFile(backup))
            val preview = c.chatBackup.prepareImport(Uri.fromFile(backup))
            val everything = preview.chats.map { it.info.id }.toSet()
            val missing = c.chatBackup.missingWorkspaces(preview, everything, com.androidharness.app.data.DuplicatePolicy.SKIP, true)
            assertEquals(2, missing.size)
            assertTrue(runCatching { c.chatBackup.importPrepared(preview, emptyMap()) }.isFailure)
            assertNull(c.sessions.session(sid))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                var done = false
                var chosen = emptyMap<String, String>()
                scenario.onActivity { it.setContent { HarnessTheme { ChatRestoreDialog(c, missing, preview.chats.size, {}, { chosen = it; done = true }) } } }
                tap("Choose workspace"); scrollTo(targetA.name); tap(targetA.name)
                tap("Choose workspace"); scrollTo(targetB.name); tap(targetB.name)
                screenshot("restore-mapping"); tap("Restore chats"); eventually { done }
                c.chatBackup.importPrepared(preview, chosen)
            }
            val restored = requireNotNull(c.sessions.session(sid))
            assertEquals(targetA.id, restored.projectId)
            val selection = ChatWorkspaceSelection.decode(requireNotNull(restored.workspaceSelectionJson))
            assertFalse(selection.grants.single { it.projectId == targetB.id }.canEdit)
            assertEquals("Restored history stays intact", c.sessions.messages(sid).single().text)
            assertTrue(c.workspace.forSession(sid) is ChatWorkspaceFs)
            assertEquals(0, c.chatBackup.prepareImport(Uri.fromFile(backup)).chats.count { !it.exists })
        } finally {
            sid?.let { c.sessions.session(it)?.let { chat -> c.sessions.deleteSession(chat) } }
            c.workspace.deleteProject(targetA); c.workspace.deleteProject(targetB); c.workspace.setActiveProject(previous.id)
            source.close(); context.deleteDatabase(sourceName); root.deleteRecursively()
        }
    }

    @Test fun codeDatesAreAccurateAndTotalsSurviveUndoDeletionAndReopen(): Unit = runBlocking {
        val name = "code-dates-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(c.appContext, AppDatabase::class.java, name).build()
        try {
            val now = System.currentTimeMillis()
            val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val repo = SessionRepository(db)
            val sid = repo.createSession("Fixture")
            listOf(0L, 3L, 20L, 60L).forEachIndexed { i, day ->
                db.dao().insertCodeActivity(CodeActivityEntity("seed-$i", "hash-$i", (i + 1) * 100L, (i + 1) * 10L,
                    local.minusDays(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1))
            }
            listOf(StatsRange.DAY to 100L, StatsRange.WEEK to 300L, StatsRange.MONTH to 600L, StatsRange.LIFETIME to 1000L).forEach { (range, expected) ->
                val window = range.window(now)
                assertEquals(expected, repo.codeActivityBetween(window.since, window.until).first().added)
            }
            repo.recordFileEdit(sid, "turn", "src/app.kt", 5, 2, "one-operation")
            repo.recordFileEdit(sid, "turn", "src/app.kt", 5, 2, "one-operation")
            repo.recordFileEdit(sid, "turn", "README.md", 100, 0)
            repo.recordFileEdit(sid, "turn", "build/generated.kt", 100, 0)
            assertEquals(1005L, repo.codeActivityBetween(0, Long.MAX_VALUE).first().added)
            db.dao().deleteFileEditsForTurns(sid, listOf("turn"))
            repo.deleteSession(requireNotNull(repo.session(sid)))
            assertEquals(1005L, repo.codeActivityBetween(0, Long.MAX_VALUE).first().added)
            db.close(); db = Room.databaseBuilder(c.appContext, AppDatabase::class.java, name).build()
            assertEquals(1005L, db.dao().codeActivityBetween(0, Long.MAX_VALUE).first().added)
        } finally { db.close(); c.appContext.deleteDatabase(name) }
    }

    @Test fun migrationBackfillsOnlyRetainedCodeHistory(): Unit = runBlocking {
        val name = "code-migration-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(c.appContext, AppDatabase::class.java, name).build()
        try {
            db.dao().insertFileEdit(FileEditEntity(sessionId = "old", turnId = "turn", relPath = "App.java", added = 12, removed = 3, createdAt = 100))
            db.dao().insertFileEdit(FileEditEntity(sessionId = "old", turnId = "turn", relPath = "README.md", added = 90, removed = 0, createdAt = 100))
            db.openHelper.writableDatabase.execSQL("DROP TABLE code_activity")
            db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 18")
            db.close()
            db = Room.databaseBuilder(c.appContext, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19).build()
            val totals = db.dao().codeActivityBetween(0, Long.MAX_VALUE).first()
            assertEquals(12L, totals.added); assertEquals(3L, totals.removed); assertEquals(1L, totals.edits)
        } finally { db.close(); c.appContext.deleteDatabase(name) }
    }

    @Test fun usageLayoutAndCodeFiltersWorkAtNormalAndLargeText(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(c.appContext, AppDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val day = StatsRange.DAY.window(now).since
            db.dao().insertSession(SessionEntity("ui", "Usage fixture", day, day, totalInputTokens = 124000, totalOutputTokens = 19000, requestCount = 34))
            db.dao().insertUsageEvent(UsageEventEntity(sessionId = "ui", providerName = "Fixture provider", model = "gpt-4o", inputTokens = 124000, outputTokens = 19000, cachedTokens = 50000, createdAt = now))
            db.dao().insertCodeActivity(CodeActivityEntity("today", "one", 2450, 830, now))
            db.dao().insertCodeActivity(CodeActivityEntity("five-days", "two", 100, 10, day - 5 * 86400000L))
            db.dao().insertCodeActivity(CodeActivityEntity("twenty-days", "three", 50, 5, day - 20 * 86400000L))
            db.dao().insertCodeActivity(CodeActivityEntity("old", "four", 90, 9, day - 40 * 86400000L))
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (font in listOf(1f, 1.5f)) {
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                            HarnessTheme(theme, dynamicColor = false) { StatsScreen(SessionRepository(db), {}) }
                        }
                    } }
                    await("Usage"); screenshot("usage-$theme-$font-top")
                    scrollTo("+2,550"); assertVisible("+2,550"); screenshot("usage-$theme-$font-code")
                    scrollTop(); tap("Today"); scrollTo("+2,450")
                    assertVisible("+2,450"); assertVisible("−830")
                    scrollTop(); tap("1 month"); scrollTo("+2,600"); assertVisible("+2,600")
                    scrollTop(); tap("Lifetime"); scrollTo("+2,690"); assertVisible("+2,690")
                }
            }
        } finally { db.close() }
    }

    @Test fun engineCapturesForegroundShellChangesWithFixtureProvider(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "engine-code-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createProjectSession(project.id)
        try {
            val calls = AtomicInteger()
            val provider = object : LlmProvider {
                override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
                    if (calls.getAndIncrement() == 0) { emit(StreamEvent.ToolCallReady(ToolCallData("code-shell", "shell", "{\"command\":\"fixture-write\"}"))); emit(StreamEvent.Done("tool_calls")) }
                    else { emit(StreamEvent.TextDelta("Done")); emit(StreamEvent.Done("stop")) }
                }
            }
            val shell = object : Tool {
                override val name = "shell"
                override val description = "Fixture shell writer"
                override val parametersSchema = Schema.obj(emptyMap())
                override val isReadOnly = false
                override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                    ctx.workspace.resolve("generated_by_agent.kt").writeText("fun main() = println(1)\n")
                    ctx.workspace.resolve("README.md").writeText("Docs\n")
                    return ToolResult(true, "Wrote fixture files")
                }
            }
            val engine = AgentEngine({ provider }, ToolRegistry(listOf(shell)), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            val events = engine.run(sid, "fixture-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://127.0.0.1", "fixture"), "fixture", emptyList(),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 32000, AgentMode.ACT, repoMapEnabled = false).toList()
            val activity = events.filterIsInstance<AgentEvent.CodeActivity>().single()
            assertEquals("generated_by_agent.kt", activity.path); assertEquals(1L, activity.added)
            assertTrue(events.none { it is AgentEvent.Error })
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; c.workspace.deleteProject(project); root.deleteRecursively() }
    }

    private fun editor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { if (node.isVisibleToUser) add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun eventually(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(100) }
        error("Requested improvement UI condition timed out")
    }
    private fun await(text: String) { eventually { find(text) != null } }
    private fun tap(text: String) {
        await(text)
        val rect = Rect().also(requireNotNull(find(text))::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        listOf(0, 1).forEach { action ->
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
    }
    private fun scrollTo(text: String) {
        repeat(15) {
            if (find(text) != null) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(180)
        }
        await(text)
    }
    private fun scrollTop() {
        repeat(10) {
            if (find("Today") != null) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(150)
        }
    }
    private fun assertVisible(text: String) {
        val rect = Rect().also(requireNotNull(find(text))::getBoundsInScreen)
        val metrics = c.appContext.resources.displayMetrics
        assertTrue("$text outside screen: $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= metrics.widthPixels && rect.bottom <= metrics.heightPixels)
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(c.appContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
