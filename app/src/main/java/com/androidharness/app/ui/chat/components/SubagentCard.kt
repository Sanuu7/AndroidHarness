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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses the task tool's arguments; null when they aren't valid JSON. */
internal fun taskArgsObject(argumentsJson: String): JsonObject? =
    runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

/** Label for tabs/summaries: the delegating agent's title, else a stable fallback. */
internal fun subagentLabel(call: ToolCallData, index: Int): String =
    taskArgsObject(call.argumentsJson)?.get("title")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: "agent ${index + 1}"

/**
 * The shared face of a subagent: neutral icon tile, monospace "subagent"
 * label, live status line, and a status slot on the right. A non-null
 * [chevronRotation] adds the expand chevron ([SubagentCard]); pager pages
 * ([SubagentPagerCard]) pass null since the pager replaces expansion.
 */
@Composable
internal fun SubagentHeaderRow(
    statusLine: String,
    running: Boolean,
    ok: Boolean?,
    modifier: Modifier = Modifier,
    chevronRotation: Float? = null,
    /** When set, a trailing affordance opens this agent's full-screen page. */
    onOpenFull: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .background(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "subagent",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine,
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
            label = "subagent status",
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
        if (onOpenFull != null) {
            IconButton(onClick = onOpenFull, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = "Open subagent page",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        if (chevronRotation != null) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
        }
    }
}

/**
 * TASK + RESULT sections of a subagent run. The delegation reads like a chat
 * message from the model (title + prompt in normal prose); only failure
 * output stays mono. Used by [SubagentCard]'s expanded area and pager pages.
 */
@Composable
internal fun SubagentDetailBody(
    call: ToolCallData,
    result: ChatMessage?,
) {
    val taskInfo = remember(call.argumentsJson) { taskArgsObject(call.argumentsJson) }
    val prompt = taskInfo?.get("prompt")?.jsonPrimitive?.contentOrNull
    val title = taskInfo?.get("title")?.jsonPrimitive?.contentOrNull
    val invocation = remember(prompt, title) {
        buildString {
            if (!title.isNullOrBlank() && prompt?.contains(title) != true) {
                append("**").append(title).append("**\n\n")
            }
            append(prompt ?: "")
        }.ifBlank { null }
    }
    if (invocation != null) {
        Text(
            "TASK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        MarkdownText(invocation)
    } else {
        MonoBlock(prettyArgs(call.argumentsJson))
    }
    result?.let {
        Spacer(Modifier.height(8.dp))
        if (it.isError) {
            // Failure notes / budget messages keep the terminal look.
            MonoBlock(it.text.take(4000))
        } else {
            Text(
                "RESULT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            MarkdownText(it.text.take(6000))
        }
    }
}

/**
 * The task tool's card: unlike ordinary tools, a subagent runs for a while and
 * takes many actions, so this card shows its live step feed (latest lines under
 * the header while running) instead of a bare spinner. When finished it
 * collapses to a quiet done row; expanding reveals the full trail and answer.
 */
@Composable
internal fun SubagentCard(
    call: ToolCallData,
    steps: List<String>,
    result: ChatMessage?,
    running: Boolean,
    onOpenFile: (String, Int?) -> Unit,
    onOpenFull: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "subagent chevron",
    )
    val description = remember(call) { describeToolCall(call).removeSuffix("…") }
    val ok = result?.let { !it.isError }
    // While running the tail of the feed is always visible so progress reads
    // at a glance; once done only the chevron reveals history.
    val visibleTrail = if (running && !expanded) steps.takeLast(VISIBLE_TRAIL) else steps

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            SubagentHeaderRow(
                statusLine = if (expanded) description else steps.lastOrNull() ?: description,
                running = running,
                ok = ok,
                chevronRotation = chevronRotation,
                onOpenFull = onOpenFull,
            )
            if (running) {
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            // Live step tail, no tap needed while it works.
            if (visibleTrail.isNotEmpty() && (running || expanded)) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp),
                ) {
                    visibleTrail.forEachIndexed { index, line ->
                        val isLatest = running && !expanded && index == visibleTrail.lastIndex
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (isLatest) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isLatest) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(animationSpec = fastSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = fastSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    SubagentDetailBody(call, result)
                }
            }
        }
    }
}

/** How many trailing step lines stay visible on the face of a running card. */
internal const val VISIBLE_TRAIL = 4
