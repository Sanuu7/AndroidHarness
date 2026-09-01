package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.common.VisualDiffViewer
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One tool call = one compact, quiet row.
 *
 * Flat hairline card, neutral icon tile, monospace tool name, one-line summary.
 * Status is a small slot on the right (dots while running, green check on
 * success, red cross on failure) and a thin 3dp line under the header while
 * running, no tinted circles, no wavy bars. The whole row toggles details.
 */
@Composable
internal fun ToolCallCard(
    call: ToolCallData,
    result: ChatMessage?,
    running: Boolean,
    onOpenFile: (String, Int?) -> Unit,
) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "tool card chevron",
    )
    // JSON work is memoized: these cards recompose on every state emission,
    // and parsing args per frame was a measurable cost during runs.
    val description = remember(call) { describeToolCall(call).removeSuffix("…") }
    val pretty = remember(call.argumentsJson) { prettyArgs(call.argumentsJson) }
    val patchDiff = remember(call) {
        if (call.name == "apply_patch") {
            runCatching {
                val obj = Json.parseToJsonElement(call.argumentsJson).jsonObject
                obj["patch"]?.jsonPrimitive?.content
            }.getOrNull()
        } else null
    }
    val ok = result?.let { !it.isError }
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
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
                        toolIcon(call.name),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        call.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val statusKey = when {
                    running -> 0
                    ok == true -> 1
                    ok == false -> 2
                    else -> 3
                }
                Crossfade(
                    targetState = statusKey,
                    animationSpec = fastEffectsSpec(),
                    label = "tool status",
                ) { key ->
                    when (key) {
                        0 -> DotLoading(Modifier.size(width = 20.dp, height = 12.dp))
                        1 -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Done",
                            tint = success,
                            modifier = Modifier.size(18.dp),
                        )
                        2 -> Icon(
                            Icons.Filled.Close,
                            contentDescription = "Failed",
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        else -> Spacer(Modifier.size(18.dp))
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(20.dp)
                        .rotate(chevronRotation),
                )
            }
            if (running) {
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(animationSpec = fastSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = fastSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    if (patchDiff != null) {
                        VisualDiffViewer(
                            diffText = patchDiff,
                            maxHeight = 280.dp,
                            showFileHeader = false,
                            onOpenFile = { path -> onOpenFile(path, null) },
                        )
                    } else {
                        MonoBlock(pretty)
                    }
                    result?.let {
                        Spacer(Modifier.height(8.dp))
                        if (call.name in listOf("grep", "search_files", "web_search")) {
                            ClickableOutput(it.text.take(4000), onOpenFile)
                        } else {
                            MonoBlock(it.text.take(4000))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collapses a run of consecutive tool calls behind one quiet header row.
 * A batch made entirely of task calls is a subagent spawn: robot icon,
 * "Spawned N subagents" wording, and each child renders as its own
 * [SubagentCard] section with live steps instead of a bare tool row.
 */
@Composable
internal fun ToolGroupCard(
    calls: List<ToolCallData>,
    results: Map<String, ChatMessage?>,
    runningIds: Set<String>,
    onOpenFile: (String, Int?) -> Unit,
    subagentSteps: Map<String, List<String>> = emptyMap(),
) {
    var expanded by rememberSaveable(calls.joinToString(",") { it.id }) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "tool group chevron",
    )
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val anyRunning = runningIds.isNotEmpty()
    val failedCount = calls.count { results[it.id]?.isError == true }
    val doneCount = calls.count { results[it.id]?.isError == false }
    val allSubagents = calls.isNotEmpty() && calls.all { it.name == "task" }

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
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
                        if (allSubagents) Icons.Filled.SmartToy else Icons.Outlined.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            allSubagents && anyRunning ->
                                "Running ${runningIds.size} of ${calls.size} subagents…"
                            allSubagents -> "Spawned ${calls.size} subagents"
                            anyRunning -> "Running tools…"
                            else -> "Ran ${calls.size} tools"
                        },
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
                            append("${runningIds.size} running")
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
                    label = "tool group status",
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
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(20.dp)
                        .rotate(rotation),
                )
            }
            if (anyRunning) {
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(animationSpec = fastSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = fastSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    calls.forEach { call ->
                        if (call.name == "task") {
                            SubagentCard(
                                call = call,
                                steps = subagentSteps[call.id].orEmpty(),
                                result = results[call.id],
                                running = call.id in runningIds,
                                onOpenFile = onOpenFile,
                            )
                        } else {
                            ToolCallCard(
                                call = call,
                                result = results[call.id],
                                running = call.id in runningIds,
                                onOpenFile = onOpenFile,
                            )
                        }
                    }
                }
            }
        }
    }
}
