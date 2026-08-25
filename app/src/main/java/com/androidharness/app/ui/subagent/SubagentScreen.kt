package com.androidharness.app.ui.subagent

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.core.Role
import com.androidharness.app.ui.chat.components.MonoBlock
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.chat.components.UserBubble
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.taskArgsObject
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val messages by container.sessions.messagesFlow(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val live by container.runManager.live(sessionId)
        .collectAsStateWithLifecycle()

    val parentCall = remember(messages) {
        messages.firstNotNullOfOrNull { m ->
            if (m.role == Role.ASSISTANT && m.toolCallId == null) {
                m.toolCalls.firstOrNull { it.id == toolCallId }
            } else null
        }
    }
    val result = remember(messages) {
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
    val running = live.runningCalls.any { it.id == toolCallId }
    val steps = live.subagentSteps[toolCallId].orEmpty()

    val taskInfo = remember(parentCall) {
        parentCall?.let { taskArgsObject(it.argumentsJson) }
    }
    val title = taskInfo?.get("title")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: "Subagent"
    val prompt = taskInfo?.get("prompt")?.jsonPrimitive?.contentOrNull.orEmpty()
    val statusText = when {
        running -> steps.lastOrNull() ?: "Running…"
        result?.isError == true -> "Failed"
        result != null -> "Done"
        else -> "Interrupted"
    }

    val listState = rememberLazyListState()
    LaunchedEffect(innerTurns.size, steps.size) {
        if (running) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.scrollToItem(count - 1)
        }
    }

    Scaffold(
        containerColor = scheme.surface,
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
                            contentDescription = "Failed",
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        result != null -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Done",
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
                "This subagent run isn't part of this chat anymore.",
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
                if (turn.thinking.isNotBlank()) {
                    item(key = "${turn.id}-thinking") { ThinkingBlock(turn.thinking) }
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
                        )
                    }
                }
            }

            if (running) {
                item(key = "live-progress") {
                    Column(Modifier.fillMaxWidth()) {
                        ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                        if (steps.isNotEmpty()) {
                            Column(Modifier.padding(top = 6.dp)) {
                                steps.takeLast(6).forEachIndexed { index, line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
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
                        "This run was interrupted before it produced anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
