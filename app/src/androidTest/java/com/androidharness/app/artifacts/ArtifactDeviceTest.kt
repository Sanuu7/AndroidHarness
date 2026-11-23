package com.androidharness.app.artifacts

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.chat.components.ArtifactCard
import com.androidharness.app.ui.chat.components.ChatAppearance
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtifactDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun agentToolPersistsAConceptThatRendersInTheRealChatAndCanBeRevised() = runBlocking {
        val sid = c.sessions.createSession("A calmer workspace", c.workspace.currentProjectOnce().id)
        val root = File(context.cacheDir, "artifact-engine-${System.nanoTime()}").apply { mkdirs() }
        val args = buildJsonObject { put("title", "Focus workspace"); put("html", conceptHtml) }.toString()
        var round = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                assertTrue(systemPrompt.contains("create_artifact"))
                assertTrue(tools.any { it.name == "create_artifact" })
                if (round++ == 0) {
                    emit(StreamEvent.ToolCallReady(ToolCallData("concept-tool", "create_artifact", args)))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    assertNotNull(messages.last { it.role == Role.TOOL }.artifact)
                    emit(StreamEvent.TextDelta("Here is an interactive workspace concept. Try the focus control."))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        val store = ViewModelStore()
        try {
            val user = ChatMessage(Role.USER, "Show me an interactive focus workspace concept in this chat.")
            c.sessions.addMessage(sid, user, "artifact-turn")
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            engine.run(sid, "artifact-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"), "", listOf(user),
                permissionMode = { PermissionMode.CONFIRM_RISKY }, sessionAllowedTools = mutableSetOf(), workspace = FileFs(root),
                options = RequestOptions(), maxContextTokens = 100_000, mode = AgentMode.ACT, repoMapEnabled = false).collect { event ->
                when (event) {
                    is AgentEvent.AssistantCommitted -> c.sessions.addMessage(sid, event.message, "artifact-turn")
                    is AgentEvent.ToolMessageCommitted -> c.sessions.addMessage(sid, event.message, "artifact-turn")
                    is AgentEvent.ApprovalNeeded -> throw AssertionError("Concept should not modify the workspace or require write approval")
                    else -> Unit
                }
            }
            val saved = c.sessions.artifacts(sid).single()
            assertEquals(conceptHtml, saved.html)
            assertTrue(root.listFiles().orEmpty().none { it.extension == "html" })
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                // Opening a finished transcript follows its end; the concept header can be above it.
                awaitCondition("Concept header can be reached in the transcript") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (find(root, "Interactive concept") != null) true
                    else { scrollTranscript(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); false }
                }
                tap("Expand concept")
                await("Focus workspace")
                tap("Start focus")
                await("Focus is on")
                screenshot("artifact-chat-expanded")
                tap("Close concept")
                awaitCondition("Concept revision control can be reached in the transcript") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (find(root, "Revise") != null) true
                    else { scrollTranscript(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                tap("Revise")
                await("Concept · Focus workspace"); await("Revise this concept:", contains = true)
                assertEquals("Revise only prefills the composer", 2, round)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            root.deleteRecursively()
        }
    }

    @Test fun inlineExpansionSourceVersionsAndSaveKeepTheSelectedConcept() {
        val first = ChatArtifact.create("Focus workspace", conceptHtml)
        val second = ChatArtifact.create("Focus workspace", conceptHtml.replace("A little room to think.", "A quieter place to create."), first.id)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val exported = File(context.cacheDir, "concept-export-${System.nanoTime()}.html")
        val result = Intent().setData(Uri.fromFile(exported))
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("text/html") }, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) { ChatAppearance {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp)) { ArtifactCard(second, listOf(first, second)) }
                } } } }
                await("A quieter place to create.")
                val largeText = context.resources.configuration.fontScale > 1f
                if (largeText) tap("Expand concept")
                tap("Start focus"); await("Focus is on")
                screenshot("artifact-inline-light")
                if (!largeText) tap("Expand concept")
                await("Focus is on")
                tap("Code"); await("<!doctype html>", contains = true)
                tap("Preview"); await("Focus is on")
                screenshot("artifact-expanded-light")
                tap("Concept versions"); tap("Version 1")
                await("A little room to think.")
                tap("Save HTML")
                awaitCondition("HTML was not exported") { exported.exists() && exported.readText().contains("A little room to think.") }
                assertFalse(exported.readText().contains("A quieter place to create."))
                await("HTML saved")
                tap("Start focus"); await("Focus is on")
                scenario.onActivity { theme.value = ThemeMode.DARK }
                await("Start focus")
                screenshot("artifact-expanded-dark")
                tap("Close concept"); await("Start focus")
                screenshot("artifact-inline-dark")
            }
        } finally { instrumentation.removeMonitor(monitor); exported.delete() }
    }

    @Test fun sandboxRunsInteractionsWithoutParentStorageFilesNetworkOrNativeAccess() {
        val requests = AtomicInteger()
        val server = ServerSocket(0).apply { soTimeout = 300 }
        val watching = java.util.concurrent.atomic.AtomicBoolean(true)
        val thread = Thread {
            while (watching.get()) try { server.accept().use { requests.incrementAndGet() } } catch (_: java.net.SocketTimeoutException) {} catch (_: Exception) { break }
        }.apply { start() }
        val source = """<h2>Sandbox probe</h2><button onclick="this.textContent='Interaction works'">Try interaction</button><div id="report"></div><script>
            let checks=[];
            try{parent.document.body;checks.push('PARENT EXPOSED')}catch(e){checks.push('Parent isolated')}
            try{localStorage.setItem('probe','1');checks.push('STORAGE EXPOSED')}catch(e){checks.push('Storage isolated')}
            checks.push(typeof Android==='undefined'&&typeof Harness==='undefined'?'No native bridge':'BRIDGE EXPOSED');
            document.getElementById('report').textContent=checks.join(' · ');
            fetch('http://127.0.0.1:${server.localPort}/probe').then(()=>document.body.append('NETWORK EXPOSED')).catch(()=>document.body.append(' Network blocked'));
            let img=new Image();img.onload=()=>document.body.append('FILE EXPOSED');img.onerror=()=>document.body.append(' File blocked');img.src='file:///data/data/${context.packageName}/shared_prefs/settings.xml';
            </script>"""
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme { ChatAppearance {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) { ArtifactCard(ChatArtifact.create("Isolation checks", source)) }
                } } } }
                await("Parent isolated", contains = true); await("Storage isolated", contains = true); await("No native bridge", contains = true)
                await("Network blocked", contains = true); await("File blocked", contains = true)
                tap("Try interaction"); await("Interaction works")
                SystemClock.sleep(500)
                assertEquals(0, requests.get())
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "EXPOSED", true))
                screenshot("artifact-isolation")
            }
        } finally { watching.set(false); server.close(); thread.join(1000) }
    }

    @Test fun migrationReopeningBackupAndForkPreserveConceptSource() = runBlocking {
        val name = "artifact-storage-${System.nanoTime()}.db"
        val importedName = "artifact-import-${System.nanoTime()}.db"
        val export = File(context.cacheDir, "$name.json")
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var imported: AppDatabase? = null
        try {
            var repo = SessionRepository(db)
            val sid = repo.createSession("Saved concepts")
            repo.addMessage(sid, ChatMessage(Role.USER, "Keep this old message"), "old")
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use {
                it.execSQL("ALTER TABLE messages DROP COLUMN artifactJson"); it.execSQL("ALTER TABLE messages DROP COLUMN generationJson"); it.version = 16
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19).build()
            repo = SessionRepository(db)
            assertEquals("Keep this old message", repo.messages(sid).single().text)
            val concept = ChatArtifact.create("Saved counter", "<button>Saved offline</button><!--" + "x".repeat(251_000) + "-->")
            val call = ToolCallData("large-concept", "create_artifact", buildJsonObject {
                put("title", concept.title); put("html", concept.html)
            }.toString())
            repo.addMessage(sid, ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(call)), "old")
            val storedCall = repo.messages(sid).last().toolCalls.single()
            assertEquals(call.id, storedCall.id)
            assertEquals("create_artifact", storedCall.name)
            assertTrue(storedCall.argumentsJson.contains("read_artifact"))
            repo.addMessage(sid, ChatMessage(Role.TOOL, "Concept created", toolName = "create_artifact", artifact = concept), "old")
            val updated = ChatArtifact.create("Saved counter", "<button>Updated offline</button>", concept.id)
            repo.addMessage(sid, ChatMessage(Role.TOOL, "Concept updated", toolName = "create_artifact", artifact = updated), "old")
            val target = repo.addMessage(sid, ChatMessage(Role.ASSISTANT, "Here is the concept."), "old")
            val fork = repo.forkSession(sid, target)
            assertEquals(listOf(concept, updated), repo.artifacts(fork))
            assertEquals(1, repo.messages(fork).count { it.artifact != null && it.role != Role.SYSTEM })
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(listOf(concept, updated), SessionRepository(db).artifacts(sid))
            ChatBackupManager(db, context).exportTo(Uri.fromFile(export))
            imported = Room.databaseBuilder(context, AppDatabase::class.java, importedName).build()
            run {
                val manager = ChatBackupManager(imported, context)
                val target = com.androidharness.app.workspace.WorkspaceManager(context, imported.dao()).currentProjectOnce()
                val preview = manager.prepareImport(Uri.fromFile(export))
                val all = preview.chats.map { it.info.id }.toSet()
                manager.importPrepared(preview, manager.missingWorkspaces(preview, all, com.androidharness.app.data.DuplicatePolicy.SKIP, true).associate { it.id to target.id })
            }
            assertEquals(listOf(concept, updated), SessionRepository(imported).artifacts(sid))
        } finally { db.close(); imported?.close(); context.deleteDatabase(name); context.deleteDatabase(importedName); export.delete() }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        node ?: return null
        node.refresh() // Chromium can retain the previous label in the accessibility client cache.
        if (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { if (contains) it.contains(text) else it == text }) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        do {
            find(instrumentation.uiAutomation.rootInActiveWindow, text, contains)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        screenshot("artifact-failure")
        throw AssertionError("Missing: $text")
    }
    private fun tap(text: String) {
        await(text).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(100)
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Tap $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(120)
    }
    private fun scrollTranscript(node: AccessibilityNodeInfo?, action: Int): Boolean {
        node ?: return false
        if (node.isScrollable && node.actionList.any { it.id == action }) {
            return node.performAction(action)
        }
        for (index in 0 until node.childCount) if (scrollTranscript(node.getChild(index), action)) return true
        return false
    }
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        throw AssertionError(message)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400) // Let the WebView compositor present the frame after reparent/theme changes.
        val dir = File(context.getExternalFilesDir(null), "artifact-qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    private val conceptHtml = """<!doctype html><html><head><style>
        body{padding:20px;background:var(--background);color:var(--foreground)}.eyebrow{color:var(--muted);font-size:11px;letter-spacing:.12em}h1{font-size:26px;line-height:1.15;font-weight:500;letter-spacing:-.04em;margin:16px 0 12px}p{color:var(--muted);margin:0 0 20px}.panel{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:16px;margin-bottom:16px}.row{display:flex;justify-content:space-between;align-items:center;gap:12px}small{color:var(--muted)}button{background:var(--accent);color:var(--background);border:0;width:100%;font-weight:500} .bars{display:flex;align-items:end;gap:5px;height:52px;margin:18px 0}.bars span{flex:1;background:var(--accent);border-radius:4px 4px 0 0;opacity:.5}.bars span:last-child{opacity:1}
        </style></head><body><div class="eyebrow">YOUR WORKSPACE</div><h1>A little room to think.</h1><p>One task. A clear head. Make something good.</p><div class="panel"><div class="row"><span>Today’s rhythm</span><small>4 sessions</small></div><div class="bars"><span style="height:35%"></span><span style="height:60%"></span><span style="height:45%"></span><span style="height:85%"></span><span style="height:70%"></span><span style="height:100%"></span></div><small>Space for your best work.</small></div><button onclick="this.textContent=this.textContent==='Start focus'?'Focus is on':'Start focus'">Start focus</button></body></html>"""
}
