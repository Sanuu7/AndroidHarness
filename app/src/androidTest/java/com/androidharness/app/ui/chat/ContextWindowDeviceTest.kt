package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.R
import com.androidharness.app.agent.ContextEstimate
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.LocalDesktopInput
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.components.ContextWindowButton
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ContextWindowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val provider = ProviderConfig("context-test", "Test", ProviderType.OPENAI_COMPAT,
        "https://example.test/v1", "test-model")
    private fun estimate(tokens: Int) = ContextEstimate(tokens, 0, 0, 0)
    private fun fixture() = ChatUiState(sessionId = "context-test", modelSelectionReady = true,
        providers = listOf(provider), activeProviderId = provider.id,
        messages = List(6) { ChatMessage(if (it % 2 == 0) Role.USER else Role.ASSISTANT, "Message $it") },
        estimate = estimate(250_000), usage = UsageStats(totalInput = 1_000_000, totalCached = 600_000,
            requests = 4, lastInput = 900_000), attachments = listOf("draft.png"))

    @Test fun popoverTracksCompactionAndKeepsDraftWhileBusyAndChatAreGuarded() {
        val state = mutableStateOf(fixture())
        val draft = mutableStateOf(TextFieldValue("Keep this draft"))
        val compacts = AtomicInteger()
        val limits = AtomicInteger()
        val mode = mutableIntStateOf(0)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                    LocalDesktopLayout provides (mode.intValue != 0), LocalDesktopInput provides (mode.intValue == 2)) {
                    HarnessTheme(if (mode.intValue == 1) ThemeMode.LIGHT else ThemeMode.DARK, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.widthIn(max = 320.dp).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                                MessageComposer(state.value.busy, draft.value, null, { draft.value = it }, {},
                                    onSend = { error("Compact must not send the draft") }, onStop = {},
                                    onAttachImage = {}, onAttachFile = {}, chatOnly = state.value.conversation.isChat,
                                    contextControl = {
                                        ContextWindowButton(state.value, { limits.incrementAndGet() }, {
                                            compacts.incrementAndGet()
                                            state.value = state.value.copy(estimate = estimate(50_000),
                                                taskControl = state.value.taskControl.copy(summaryOverride = "Earlier work"))
                                        })
                                    })
                            }
                        }
                    }
                }
            } }
            val label = context.getString(R.string.context_window_button)
            val compact = context.getString(R.string.context_window_compact)
            fun remaining(tokens: String) = context.getString(R.string.context_window_remaining, tokens)
            fun used(tokens: String) = context.getString(R.string.context_window_used, tokens, "1M")
            for (layout in 0..2) {
                scenario.onActivity { mode.intValue = layout; state.value = fixture() }
                await(label)
                val meterBounds = bounds(label)
                assertFalse(Rect.intersects(meterBounds, bounds(context.getString(R.string.ui_send_message))))
                click(label)
                await(used("250K"))
                await(remaining("750K"))
                await("600K")
                await("60.0%")
                assertOnScreen(context.getString(R.string.ui_overall_cache_hit_rate))
                assertOnScreen(remaining("750K"))
                assertOnScreen(compact)
                screenshot("context-$layout")
                click(compact)
                waitFor { compacts.get() == layout + 1 }
                assertEquals("Keep this draft", draft.value.text)
                assertEquals(listOf("draft.png"), state.value.attachments)
                click(label)
                await(remaining("950K"))
                await(used("50K"))
                await(context.getString(R.string.context_window_summarized))
                scenario.onActivity { state.value = state.value.copy(busy = true,
                    usage = state.value.usage.copy(totalInput = 2_000_000, totalCached = 1_500_000, requests = 5)) }
                await("75.0%")
                await(context.getString(R.string.context_window_busy))
                assertTrue("Compact must be disabled while busy",
                    generateSequence(await(compact)) { it.parent }.any { !it.isEnabled })
                click(context.getString(R.string.ui_context_limits_extra71))
                waitFor { limits.get() == layout + 1 }
            }
            scenario.onActivity { state.value = fixture().copy(estimate = null, usage = UsageStats()) }
            click(label)
            await(context.getString(R.string.context_window_pending))
            await("0.0%")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { find(context.getString(R.string.context_window_pending)) == null }
            scenario.onActivity { state.value = state.value.copy(conversation = ConversationConfig(kind = ConversationKind.CHAT)) }
            waitFor { find(label) == null }
        }
    }

    @Test fun localWindowUsesTheRunLimitAndOverfullContextNeverShowsNegativeRemaining() {
        val local = provider.copy(id = "local-model:test-model")
        val state = fixture().copy(providers = listOf(local), activeProviderId = local.id,
            taskControl = fixture().taskControl.copy(provider = local, maxContext = 32_000),
            estimate = estimate(40_000))
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Box(Modifier.padding(24.dp)) { ContextWindowButton(state, {}, {}) }
                }
            } } }
            click(context.getString(R.string.context_window_button))
            await(context.getString(R.string.context_window_remaining, "0"))
            await(context.getString(R.string.context_window_used, "40K", "32K"))
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(visit(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty().filter { it.isVisibleToUser }
    }
    private fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(50) }
        assertTrue("Visible controls: ${nodes().mapNotNull { it.text ?: it.contentDescription }}", condition())
    }
    private fun await(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor { result = find(label); result != null }
        return requireNotNull(result)
    }
    private fun bounds(label: String) = Rect().also(await(label)::getBoundsInScreen)
    private fun assertOnScreen(label: String) {
        val rect = bounds(label); val screen = context.resources.displayMetrics
        assertTrue("$label outside screen: $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= screen.widthPixels && rect.bottom <= screen.heightPixels)
    }
    private fun click(label: String) {
        var node = await(label)
        while (!node.isClickable) node = node.parent ?: error("No click target: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(250)
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "context-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
