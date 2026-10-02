package com.androidharness.app.ui.settings

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.local.LocalModelCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelContextDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun removedLocalModelCannotRemainSelected() = runBlocking {
        val settings = container.settings
        val original = settings.settings.first()
        val missing = "local-model:removed-device-test-model"
        try {
            settings.setActiveModel(missing)
            settings.setActiveProvider(missing)
            settings.setPlanningModel(missing, missing)
            settings.setExecutionModel(missing, missing)
            val repaired = withTimeout(5000) { settings.settings.first {
                it.activeProviderId == HarnessProvider.ID && it.activeModel == null &&
                    it.planningProviderId == null && it.planningModel == null &&
                    it.executionProviderId == null && it.executionModel == null
            } }
            val providers = container.providers.providers.first()
            assertFalse(providers.any { it.id == missing })
            assertTrue(providers.any { it.id == repaired.activeProviderId })
            println("REMOVED_LOCAL_SELECTION_OK: missing provider hidden and all saved model selections cleared")
        } finally {
            settings.setActiveModel(original.activeModel)
            settings.setActiveProvider(original.activeProviderId)
            settings.setPlanningModel(original.planningProviderId, original.planningModel)
            settings.setExecutionModel(original.executionProviderId, original.executionModel)
        }
    }

    @Test fun localContextSwitchPersists() {
        val settings = container.settings
        val original = runBlocking { settings.settings.first().localModelAgentContext }
        try {
            runBlocking { settings.setLocalModelAgentContext(false) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { LocalModelsSection(container) }
                } } }
                clickSwitch(false)
                runBlocking { withTimeout(5000) { settings.settings.first { it.localModelAgentContext } } }
                clickSwitch(true)
                runBlocking { withTimeout(5000) { settings.settings.first { !it.localModelAgentContext } } }
                println("LOCAL_CONTEXT_SWITCH_OK: enabled and disabled through settings")
            }
        } finally { runBlocking { settings.setLocalModelAgentContext(original) } }
    }

    /** Optional test uses an already installed model and never changes its files or saved limits. */
    @Test(timeout = 120_000) fun installedModelRepliesWithoutAgentContext() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("localModelId")
        assumeTrue("Pass localModelId to test an installed model", id != null)
        val config = container.localModels.configs().first { it.id == LocalModelCatalog.PROVIDER_PREFIX + id }
        val original = container.settings.settings.first().localModelAgentContext
        try {
            container.settings.setLocalModelAgentContext(false)
            val workspace = container.workspace.current.first()
            val events = mutableListOf<AgentEvent>()
            val started = SystemClock.elapsedRealtime()
            withTimeout(90_000) {
                container.engine.run(sessionId = "local-context-device-check", turnId = "local-context-check",
                    config = config, apiKey = "", history = listOf(ChatMessage(Role.USER, "Hi")),
                    permissionMode = { PermissionMode.CONFIRM_ALL }, sessionAllowedTools = mutableSetOf(),
                    workspace = workspace, options = RequestOptions(thinking = ThinkingLevel.OFF, maxOutputTokens = 32),
                    maxContextTokens = container.localModels.limits(id!!).context, mode = AgentMode.ACT,
                    pinnedInstructions = "These project instructions should never reach plain chat. ".repeat(1000)
                ).collect { events += it }
            }
            assertFalse(events.filterIsInstance<AgentEvent.Error>().toString(), events.any { it is AgentEvent.Error })
            val estimate = events.filterIsInstance<AgentEvent.EstimatedContext>().first().estimate
            assertEquals(0, estimate.systemTokens)
            assertEquals(0, estimate.toolsTokens)
            assertFalse(events.any { it is AgentEvent.ToolStarted })
            val reply = events.filterIsInstance<AgentEvent.AssistantCommitted>().single().message
            assertTrue("No visible reply", reply.text.isNotBlank())
            val usage = events.filterIsInstance<AgentEvent.Usage>().single()
            assertTrue("Unexpectedly large input: ${usage.inputTokens}", usage.inputTokens in 1..128)
            println("LOCAL_PLAIN_CHAT_OK: input=${usage.inputTokens}, output=${usage.outputTokens}, firstTokenMs=${reply.firstTokenMs}, totalMs=${SystemClock.elapsedRealtime() - started}, reply=${reply.text}")
        } finally { container.settings.setLocalModelAgentContext(original) }
    }

    private fun clickSwitch(checked: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it) }
            if (node != null) {
                var target: AccessibilityNodeInfo = node
                while (!target.isClickable && target.parent != null) target = target.parent
                if (target.isChecked == checked) {
                    assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                    return
                }
            }
            SystemClock.sleep(50)
        }
        error("Local model context switch did not appear")
    }

    private fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains("System instructions and tools") == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
        return null
    }
}
