package com.androidharness.app.ui.chat.rich

import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.artifacts.ChatArtifact
import com.androidharness.app.core.*
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.ChatAppearance
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RichResponseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun savedChatJsonInsideCalloutDirectiveRendersAfterReopeningWithoutRewritingHistory() = runBlocking {
        val sid = c.sessions.createSession("Callout recovery", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        val reply = """Before the callout.

::callout
{"tone":"info","title":"Why the still is unavailable","text":"The original footage was cut. This explanation should remain visible."}
::

After the callout."""
        c.sessions.addMessage(sid, ChatMessage(Role.USER, "Explain why the still is unavailable."))
        c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, reply))
        fun show(host: ChatListTestActivity) {
            val vm = ChatViewModel(c, sid); store.put("chat", vm)
            host.setContent { HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
            } }
        }
        fun verify() {
            await("Why the still is unavailable")
            await("The original footage was cut. This explanation should remain visible.")
            await("After the callout.")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "This content couldn’t be displayed"))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "\"tone\"", true))
        }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity(::show); verify()
                scenario.recreate(); scenario.onActivity(::show); verify()
                screenshot("saved-json-callout-recovered")
            }
            assertEquals(reply, c.sessions.messages(sid).last { it.role == Role.ASSISTANT }.text)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun providerRichReplyPersistsInRealChatAndActionsOnlyPrepareComposerWhileArtifactsRemain() = runBlocking {
        val root = File(context.cacheDir, "rich-response-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Rich conversation", c.workspace.currentProjectOnce().id)
        val store = ViewModelStore()
        var requests = 0
        val answer = "Here are two approaches.\n\n" + ui(buildJsonObject {
            put("type", "choices"); put("title", "Choose your approach")
            putJsonArray("options") {
                add(buildJsonObject { put("label", "Local tools"); put("description", "Work offline with files on your phone."); put("prompt", "Help me use local tools.") })
                add(buildJsonObject { put("label", "Remote tools"); put("description", "Use a connected development machine.") })
            }
        }) + "\n\nBoth approaches stay in this conversation."
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                requests++
                assertTrue(systemPrompt.contains("RICH CONVERSATION RESPONSES"))
                assertTrue(tools.any { it.name == "create_artifact" })
                answer.chunked(37).forEach { emit(StreamEvent.TextDelta(it)) }
                emit(StreamEvent.Done("stop"))
            }
        }
        try {
            val oldArtifact = ChatArtifact.create("Existing artifact", "<p>Your saved artifact is still here.</p>")
            c.sessions.addMessage(sid, ChatMessage(Role.TOOL, "Saved concept", toolName = "create_artifact", artifact = oldArtifact), "old")
            val user = ChatMessage(Role.USER, "Compare local and remote approaches right in the reply.")
            c.sessions.addMessage(sid, user, "rich-turn")
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            engine.run(sid, "rich-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"), "", listOf(user),
                permissionMode = { PermissionMode.CONFIRM_RISKY }, sessionAllowedTools = mutableSetOf(), workspace = FileFs(root),
                options = RequestOptions(), maxContextTokens = 100_000, mode = AgentMode.ACT, repoMapEnabled = false).collect { event ->
                if (event is AgentEvent.AssistantCommitted) c.sessions.addMessage(sid, event.message, "rich-turn")
            }
            assertEquals(answer, c.sessions.messages(sid).last { it.role == Role.ASSISTANT }.text)
            assertEquals(listOf(oldArtifact), c.sessions.artifacts(sid))
            assertTrue(root.listFiles().orEmpty().isEmpty())
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                tap("Remote tools"); tap("Discuss selection")
                await("Response · Choose your approach"); await("Tell me more about Remote tools.", contains = true)
                assertEquals("Actions do not send another provider request", 1, requests)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "harness-ui", true))
                screenshot("rich-real-chat")
            }
        } finally { instrumentation.runOnMainSync { store.clear() }; c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun nativeContentAndCustomPreviewWorkInlineWithSelectionsThemeAndExpandedState() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        var followUp = RichResponse.FollowUp("", "", "")
        val text = showcase()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) { ChatAppearance {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AssistantText(text, onFollowUp = { followUp = it })
                }
            } } } }
            await("A little clarity, right here")
            screenshot("rich-native-light")
            tap("Show data"); await("Research")
            tap("Why this plan?"); await("Short sessions make the next action easier to start.")
            tap("Add 5 minutes"); await("10 minutes")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "PARENT EXPOSED", true))
            tap("Ask about this")
            assertTrue(followUp.context.contains("Break length: 10 minutes"))
            scenario.onActivity { host ->
                fun preview(view: android.view.View): com.androidharness.app.artifacts.ArtifactWebView? {
                    if (view is com.androidharness.app.artifacts.ArtifactWebView) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
                    return null
                }
                val view = requireNotNull(preview(host.window.decorView))
                assertTrue("Preview should size to its content", view.height > 0 && view.height / context.resources.displayMetrics.density < 600)
            }
            tap("Expand response preview"); await("10 minutes")
            screenshot("rich-preview-expanded")
            tap("Close response preview"); await("10 minutes")
            scenario.onActivity { theme.value = ThemeMode.DARK }
            await("5 minutes")
            screenshot("rich-preview-dark")
        }
    }

    @Test fun streamingKeepsIncompleteMarkupHiddenAndInvalidContentHasARecoveryAction() {
        val text = mutableStateOf("Before the visual.\n\n```harness-ui\n{\"type\":\"preview\",\"html\":\"<script>")
        val streaming = mutableStateOf(true)
        var followUp = RichResponse.FollowUp("", "", "")
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme { ChatAppearance {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) { AssistantText(text.value, streaming = streaming.value, onFollowUp = { followUp = it }) }
            } } } }
            await("Preparing visual…")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Interactive response preview"))
            scenario.onActivity { streaming.value = false }
            await("This content couldn’t be displayed")
            tap("Ask for a text version"); assertTrue(followUp.prompt.contains("text explanation"))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "<script>", true))
        }
    }

    @Test fun ordinaryStepListsHaveNoCheckboxesAndExplicitTaskChecklistsRemainInteractive() {
        val plain = """{"type":"steps","title":"Key layout upgrades","items":[{"title":"Split screen","text":"Use the available space","done":true},{"title":"Context bridge","done":false}]}"""
        val source = mutableStateOf("```harness-ui\n$plain\n```")
        var followUp: RichResponse.FollowUp? = null
        fun checkableCount(node: AccessibilityNodeInfo?): Int = if (node == null) 0 else
            (if (node.isCheckable) 1 else 0) + (0 until node.childCount).sumOf { checkableCount(node.getChild(it)) }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme { ChatAppearance {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    AssistantText(source.value, onFollowUp = { followUp = it })
                }
            } } } }
            await("Split screen"); await("Context bridge")
            assertEquals(0, checkableCount(instrumentation.uiAutomation.rootInActiveWindow))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Continue from here"))
            assertNull(followUp)
            screenshot("plain-steps-without-checkboxes")
            scenario.onActivity { source.value = "```harness-ui\n" + plain.replace("\"type\":\"steps\"", "\"type\":\"steps\",\"checkable\":true") + "\n```" }
            await("Continue from here")
            assertEquals(2, checkableCount(instrumentation.uiAutomation.rootInActiveWindow))
            tap("Context bridge"); tap("Continue from here")
            assertTrue(requireNotNull(followUp).context.contains("Done: Context bridge"))
            screenshot("explicit-task-checklist")
        }
    }

    @Test fun nativeActionsCheckboxStateAndLinkCardsWorkWithoutExecutingAgentActions() {
        var followUp = RichResponse.FollowUp("", "", "")
        var openedUrl = ""
        val text = listOf(
            buildJsonObject { put("type", "card"); put("title", "A practical starting point"); put("text", "Start with **one small task**."); putJsonArray("actions") {
                add(buildJsonObject { put("label", "Explore locally"); put("prompt", "Help me start locally.") })
            } },
            buildJsonObject { put("type", "steps"); put("title", "Your next steps"); put("checkable", true); putJsonArray("items") {
                add(buildJsonObject { put("title", "Pick a task"); put("text", "Keep the scope small.") })
                add(buildJsonObject { put("title", "Review the result"); put("text", "Check it before continuing.") })
            } },
            buildJsonObject { put("type", "link"); put("title", "Android reference"); put("text", "Read the platform documentation."); put("url", "https://developer.android.com/") },
        ).joinToString("\n\n", transform = ::ui)
        fun show(host: ChatListTestActivity) { host.setContent { HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { ChatAppearance {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AssistantText(text, onFollowUp = { followUp = it }, onOpenUrl = { openedUrl = it })
            }
        } } } }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity(::show)
            tap("Explore locally"); assertTrue(followUp.prompt.startsWith("Help me start locally."))
            tap("Pick a task"); tap("Continue from here")
            assertTrue(followUp.context.contains("Done: Pick a task")); assertTrue(followUp.context.contains("Remaining: Review the result"))
            scenario.recreate(); scenario.onActivity(::show)
            tap("Continue from here"); assertTrue("Checkbox state survives recreation", followUp.context.contains("Done: Pick a task"))
            assertEquals("", openedUrl)
            tap("Android reference"); assertEquals("https://developer.android.com/", openedUrl)
            screenshot("rich-actions-and-checklist")
        }
    }

    @Test fun phoneResultsReadNaturallyAndSelectionOnlyAppearsWhenRequested() {
        val theme = mutableStateOf(ThemeMode.DARK)
        val fontScale = mutableStateOf(1f)
        var changed = ""
        var followUp = RichResponse.FollowUp("", "", "")
        val text = "The two blockers pass. Numbering is stable and the reduced-copy fallback works.\n\n" + ui(buildJsonObject {
            put("type", "table"); put("title", "Re-test")
            putJsonArray("columns") { add("Check"); add("Result") }
            putJsonArray("rows") {
                listOf(
                    "Number stability" to "Pass. At turn start #1 = screenshot, #2 = logo. Adding the social card as #3 left both unchanged; view_chat_image(1) still returned the screenshot.",
                    "Full-frame by number" to "Pass. Returned the 1080×1920 screenshot at 882×1568, scale 0.8167, with the full source region.",
                    "Reduced-copy fallback" to "Pass. The logo returned a usable 256×256 image from its saved copy, with source and attachment sizes populated.",
                    "Crop geometry" to "Pass. The requested 540×576 crop shows the header at the expected coordinates.",
                    "Delivery contract" to "Failed. The original status contradicted the saved attachment metadata. This still needs a fix."
                ).forEach { (check, result) -> add(buildJsonArray { add(check); add(result) }) }
            }
        })
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) { ChatAppearance {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale.value),
                    LocalResponseContext provides { _, value -> changed = value }) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AssistantText(text, onFollowUp = { followUp = it })
                    }
                }
            } } } }
            await("Number stability"); await("Full-frame by number"); await("Reduced-copy fallback")
            fun checkboxes(node: AccessibilityNodeInfo?): Int = if (node == null) 0 else
                (if (node.isVisibleToUser && node.isCheckable) 1 else 0) + (0 until node.childCount).sumOf { checkboxes(node.getChild(it)) }
            assertEquals(0, checkboxes(instrumentation.uiAutomation.rootInActiveWindow))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Filter rows"))
            assertEquals("", changed)
            screenshot("rich-table-phone-dark")
            scenario.onActivity { theme.value = ThemeMode.LIGHT; fontScale.value = 1.5f }
            await("Number stability"); screenshot("rich-table-phone-large-text")
            scenario.onActivity { fontScale.value = 1f }
            tap("Table options"); tap("Select rows")
            tap("Number stability"); await("1 selected")
            assertTrue(changed.contains("Selected rows:\nNumber stability"))
            tap("Discuss selected rows"); assertTrue(followUp.context.contains("Number stability"))
            tap("Table options"); tap("Filter rows"); await("Filter rows")
            screenshot("rich-table-controls-on-demand")
        }
    }

    private fun showcase(): String = "A little clarity, right here\n\n" + listOf(
        buildJsonObject { put("type", "callout"); put("title", "Make room for focused work"); put("text", "One clear next step is easier to act on than a full dashboard.") },
        buildJsonObject { put("type", "metrics"); putJsonArray("items") {
            add(buildJsonObject { put("label", "Focus sessions"); put("value", "4"); put("detail", "Illustrative plan") })
            add(buildJsonObject { put("label", "Break length"); put("value", "5 min"); put("detail", "Adjust it below") })
        } },
        buildJsonObject { put("type", "chart"); put("title", "A balanced afternoon"); put("unit", "min"); put("caption", "Illustrative schedule"); putJsonArray("points") {
            add(buildJsonObject { put("label", "Research"); put("value", 20) }); add(buildJsonObject { put("label", "Create"); put("value", 40) }); add(buildJsonObject { put("label", "Review"); put("value", 15) })
        } },
        buildJsonObject { put("type", "details"); put("title", "Why this plan?"); put("text", "Short sessions make the next action easier to start.") },
        buildJsonObject { put("type", "preview"); put("title", "Try your break length"); put("html", """<h3 style="margin-top:0;font-weight:500">A moment to reset.</h3><p>Adjust the break to fit your day.</p><p id="value" role="status" style="font-size:28px">5 minutes</p><button onclick="let v=document.getElementById('value');let n=parseInt(v.textContent)+5;v.textContent=n+' minutes';parent.postMessage({type:'harness:state',summary:'Break length: '+n+' minutes'},'*')">Add 5 minutes</button><script>try{parent.document.body;document.body.append('PARENT EXPOSED')}catch(e){}</script>""") }
    ).joinToString("\n\n", transform = ::ui) + "\n\nTell me what you’d like to adjust."
    private fun ui(body: JsonObject) = "```harness-ui\n$body\n```"
    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        node ?: return null; node.refresh()
        if (node.isVisibleToUser && listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { if (contains) it.contains(text) else it == text }) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 12_000
        var attempts = 0
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, text, contains)?.let { return it }
            if (++attempts > 4) scrollable(root)?.performAction(if ((attempts / 7) % 2 == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(160)
        } while (SystemClock.uptimeMillis() < end)
        screenshot("rich-failure"); throw AssertionError("Missing: $text")
    }
    private fun tap(text: String) {
        await(text).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(180)
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Tap $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(180)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        val dir = File(context.getExternalFilesDir(null), "rich-response-qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
