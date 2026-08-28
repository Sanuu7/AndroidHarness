package com.androidharness.app.ui.chat.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.launch

/**
 * Several parallel subagents in ONE bounded-height card instead of a vertical
 * stack: an aggregate header (reused ToolGroupCard idiom), a scrollable row of
 * status tabs, one per agent, and a swipeable pager where each page holds a
 * single agent's header, live step tail, and scrollable TASK/RESULT detail.
 * The footprint never grows with agent count, which is what keeps parallel
 * runs usable on a phone.
 */
@Composable
internal fun SubagentPagerCard(
    calls: List<ToolCallData>,
    results: Map<String, ChatMessage?>,
    runningIds: Set<String>,
    subagentSteps: Map<String, List<String>>,
    onOpen: (toolCallId: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val scope = rememberCoroutineScope()
    val runningCount = calls.count { it.id in runningIds }
    val anyRunning = runningCount > 0
    val failedCount = calls.count { results[it.id]?.isError == true }
    val doneCount = calls.count { results[it.id]?.isError == false }

    // Open on the first agent that's still working, that's where the action is.
    val pagerState = rememberPagerState(
        initialPage = calls.indexOfFirst { it.id in runningIds }.coerceAtLeast(0),
    ) { calls.size }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Aggregate header, no chevron; the pager replaces expansion.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .background(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
                ) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (anyRunning) "Running $runningCount of ${calls.size} subagents…"
                        else "Spawned ${calls.size} subagents",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val summary = buildString {
                        if (doneCount > 0) append("$doneCount done")
                        if (failedCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("$failedCount failed")
                        }
                        if (anyRunning) {
                            if (isNotEmpty()) append(" · ")
                            append("$runningCount running")
                        }
                    }
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failedCount > 0) scheme.error else scheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Crossfade(
                    targetState = anyRunning,
                    animationSpec = fastEffectsSpec(),
                    label = "subagent pager status",
                ) { running ->
                    when {
                        running -> DotLoading(Modifier.size(width = 20.dp, height = 12.dp))
                        failedCount > 0 -> Icon(
                            Icons.Filled.Close,
                            contentDescription = "Failed",
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        else -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Done",
                            tint = success,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (anyRunning) {
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

            // Tabs: status dot + task title; tap scrolls the pager.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                calls.forEachIndexed { index, call ->
                    AgentTab(
                        label = remember(call) { subagentLabel(call, index) },
                        running = call.id in runningIds,
                        ok = results[call.id]?.let { !it.isError },
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                }
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))

            // Fixed height: each page scrolls internally, so swiping between
            // agents (and agents finishing) never shifts the chat around.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
                key = { calls[it].id },
            ) { page ->
                val call = calls[page]
                SubagentPagerPage(
                    call = call,
                    steps = subagentSteps[call.id].orEmpty(),
                    result = results[call.id],
                    running = call.id in runningIds,
                    onOpenFull = { onOpen(call.id) },
                )
            }
        }
    }
}

@Composable
private fun AgentTab(
    label: String,
    running: Boolean,
    ok: Boolean?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) scheme.surfaceContainerHigh else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary.copy(alpha = 0.55f)
            else scheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AgentStatusDot(running, ok)
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
    }
}

@Composable
private fun AgentStatusDot(running: Boolean, ok: Boolean?) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    when {
        running -> {
            val pulse = rememberInfiniteTransition(label = "agent dot").animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "agent dot alpha",
            ).value
            Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary.copy(alpha = pulse)))
        }
        ok == true -> Box(Modifier.size(7.dp).clip(CircleShape).background(success))
        ok == false -> Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.error))
        else -> Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(scheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/**
 * One pager page = one agent: its header row (no chevron), the last few live
 * step lines while running, then a bounded scroll region with TASK + RESULT.
 * Finished agents report no steps (RunManager clears them), so done pages go
 * straight to the detail.
 */
@Composable
private fun SubagentPagerPage(
    call: ToolCallData,
    steps: List<String>,
    result: ChatMessage?,
    running: Boolean,
    onOpenFull: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ok = result?.let { !it.isError }
    val description = remember(call) { describeToolCall(call).removeSuffix("…") }
    Column(Modifier.fillMaxSize()) {
        SubagentHeaderRow(
            statusLine = steps.lastOrNull() ?: description,
            running = running,
            ok = ok,
            onOpenFull = onOpenFull,
        )
        if (running) {
            ThinLinearProgress(modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            if (running && steps.isNotEmpty()) {
                val trail = steps.takeLast(VISIBLE_TRAIL)
                trail.forEachIndexed { index, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (index == trail.lastIndex) scheme.onSurface
                                else scheme.onSurfaceVariant,
                        maxLines = if (index == trail.lastIndex) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            HorizontalDivider(
                color = scheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            SubagentDetailBody(call, result)
        }
    }
}
