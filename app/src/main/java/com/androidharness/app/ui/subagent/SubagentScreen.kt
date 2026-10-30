package com.androidharness.app.ui.subagent

import com.androidharness.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.core.Role
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.agent.SubagentStatus
import com.androidharness.app.ui.chat.components.subagentStatusText
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.browser.BrowserToolActivity
import com.androidharness.app.ui.chat.components.WebPreviewSheet
import com.androidharness.app.ui.chat.components.MonoBlock
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.chat.components.UserBubble
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.taskArgsObject
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.androidharness.app.ui.common.TextButton

/**
 * One subagent run as a real conversation: the delegation prompt as a user
 * bubble, then the subagent's own assistant turns and tool calls rendered with
 * the same components as the main chat. Inner turns are persisted during the
 * run (assistant rows carry the parent task's call id), so the page is
 * complete even after the run ends; while it runs, the live step feed trails
 * at the bottom.
 */
@Composable
fun SubagentScreen(
    container: AppContainer,
    sessionId: String,
    toolCallId: String,
    onBack: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val messages by container.sessions.messagesFlow(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val live by container.runManager.live(sessionId)
        .collectAsStateWithLifecycle()
    val run by remember(sessionId, toolCallId) {
        container.runManager.subagents.forSession(sessionId).map { list -> list.firstOrNull { it.id == toolCallId } }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    val parentCall = run?.call ?: remember(messages) {
        messages.firstNotNullOfOrNull { m ->
            if (m.role == Role.ASSISTANT && m.toolCallId == null) {
                m.toolCalls.firstOrNull { it.id == toolCallId }
            } else null
        }
    }
    val result = if (run != null) run?.takeUnless { it.status.active }?.let {
        ChatMessage(role = Role.TOOL, text = it.output, toolCallId = toolCallId,
            isError = it.status != SubagentStatus.COMPLETED)
    } else remember(messages) {
        messages.firstOrNull { it.role == Role.TOOL && it.toolCallId == toolCallId }
    }
    val innerTurns = remember(messages) {
        messages.filter { it.role == Role.ASSISTANT && it.toolCallId == toolCallId }
    }
    val innerResults = remember(messages, innerTurns) {
        val innerCallIds = innerTurns.flatMap { t -> t.toolCalls.map { it.id } }.toSet()
        messages.filter { it.role == Role.TOOL && it.toolCallId in innerCallIds }
            .associateBy { it.toolCallId }
    }
    val running = run?.status?.active ?: live.runningCalls.any { it.id == toolCallId }
    val steps = run?.steps ?: live.subagentSteps[toolCallId].orEmpty()

    val taskInfo = remember(parentCall) {
        parentCall?.let { taskArgsObject(it.argumentsJson) }
    }
    val title = run?.name ?: (taskInfo?.get("name") ?: taskInfo?.get("title"))?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: uiContext.getString(R.string.ui_subagent)
    val prompt = taskInfo?.get("prompt")?.jsonPrimitive?.contentOrNull.orEmpty()
    val statusText = run?.let { "${subagentStatusText(it.status)} · ${it.model}" } ?: when {
        running -> steps.lastOrNull() ?: uiContext.getString(R.string.ui_running)
        result?.isError == true -> uiContext.getString(R.string.ui_failed_64)
        result != null -> uiContext.getString(R.string.settings_action_done)
        else -> uiContext.getString(R.string.ui_interrupted)
    }

    val listState = rememberLazyListState()
    val browserTargets = remember(messages) { BrowserToolActivity.targets(messages) }
    var watchCall by remember(sessionId) { mutableStateOf<ToolCallData?>(null) }
    var watchTarget by remember(sessionId) { mutableStateOf<String?>(null) }
    watchCall?.let { call ->
        val fs by produceState<com.androidharness.app.workspace.WorkspaceFs?>(null, sessionId) {
            value = container.workspace.forSession(sessionId)
        }
        WebPreviewSheet(initialTarget = watchTarget, workspace = fs, messages = messages, browserController = container.browser,
            agentActivity = BrowserToolActivity.from(call, innerResults[call.id], running && call.id !in innerResults, browserTargets[call.id]),
            agentRunning = running, onStopAgent = { container.runManager.stop(sessionId) }, onDismiss = { watchCall = null })
    }
    LaunchedEffect(innerTurns.size, steps.size) {
        if (running) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.scrollToItem(count - 1)
        }
    }

    Scaffold(
        containerColor = scheme.surface,
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(stringResource(R.string.subagents_results_return), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.subagents_main_chat)) }
                    if (run?.status?.active == true) TextButton(onClick = { container.runManager.subagents.stop(sessionId, toolCallId) }) {
                        Text(stringResource(R.string.subagents_stop))
                    }
                }
            }
        },
        topBar = {
            AppHeader(
                title = title,
                subtitle = statusText,
                onBack = onBack,
                actions = {
                    when {
                        running -> DotLoading(Modifier.size(width = 20.dp, height = 12.dp))
                        result?.isError == true -> Icon(
                            Icons.Filled.Close,
                            contentDescription = uiContext.getString(R.string.ui_failed_64),
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        result != null -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = uiContext.getString(R.string.settings_action_done),
                            tint = success,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (parentCall == null) {
            Text(
                uiContext.getString(R.string.ui_this_subagent_run_isn_t_part_of),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(20.dp),
            )
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The delegation itself: a user-sent message, like any chat input.
            if (prompt.isNotBlank()) {
                item(key = "prompt") { UserBubble(prompt) }
            }

            innerTurns.forEach { turn ->
                if (turn.thinking.isNotBlank() || (turn.generation.reasoningTokens ?: 0) > 0) {
                    item(key = "${turn.id}-thinking") {
                        ThinkingBlock(turn.thinking, thinkingMs = turn.thinkingMs, reasoningTokens = turn.generation.reasoningTokens)
                    }
                }
                if (turn.text.isNotBlank()) {
                    item(key = "${turn.id}-text") { AssistantText(turn.text) }
                }
                turn.toolCalls.forEach { call ->
                    item(key = call.id) {
                        ToolCallCard(
                            call = call,
                            result = innerResults[call.id],
                            running = running && call.id !in innerResults,
                            onOpenFile = { _, _ -> },
                            browserTarget = browserTargets[call.id],
                            onWatchBrowser = { target -> watchTarget = target ?: container.browser.getActiveUrl(); watchCall = call },
                        )
                    }
                }
            }

            if (running) {
                run?.preview?.takeIf { it.isNotBlank() && it != innerTurns.lastOrNull()?.text?.takeLast(360) }?.let { preview ->
                    item(key = "live-answer-preview") { AssistantText(preview) }
                }
                item(key = "live-progress") {
                    Column(Modifier.fillMaxWidth()) {
                        ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                        if (steps.isNotEmpty()) {
                            Column(Modifier.padding(top = 6.dp)) {
                                steps.takeLast(6).forEachIndexed { index, line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = HarnessMono,
                                        color = if (index == steps.takeLast(6).lastIndex)
                                            scheme.onSurface else scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (result?.isError == true) {
                item(key = "error-result") { MonoBlock(result!!.text.take(4000)) }
            }

            if (!running && result == null && innerTurns.isEmpty()) {
                item(key = "interrupted") {
                    Text(
                        uiContext.getString(R.string.ui_this_run_was_interrupted_before_it_produced),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
