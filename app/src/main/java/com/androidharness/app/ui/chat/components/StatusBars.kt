package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.animation.togetherWith
import com.androidharness.app.ui.common.HarnessActivityLogo
import com.androidharness.app.ui.common.LogoShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.TodoItem
import com.androidharness.app.ui.chat.ChatUiState
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastSpatialSpec
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Slim live-activity strip above the composer. The AH monogram morphs into whatever
 * the agent is doing (an eye while reading, a magnifier while searching, a pencil
 * while editing, a prompt while running commands, sparkles while thinking, lines
 * while writing) and the line of text beside it rolls up to each new step.
 */
@Composable
internal fun AgentStatusBar(action: String?, busy: Boolean, shape: LogoShape = LogoShape.Monogram) {
    AnimatedVisibility(
        visible = busy && action != null,
        enter = fadeIn(tween(150)) + expandVertically(animationSpec = fastSpatialSpec()),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = fastSpatialSpec()),
    ) {
        val scheme = MaterialTheme.colorScheme
        val context = androidx.compose.ui.platform.LocalContext.current
        val motion = remember(context) { android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            HarnessActivityLogo(shape, scheme.primary, Modifier.size(18.dp), animate = motion)
            Spacer(Modifier.width(10.dp))
            androidx.compose.animation.AnimatedContent(action.orEmpty(), transitionSpec = {
                (androidx.compose.animation.slideInVertically(tween(240)) { it } + fadeIn(tween(200))) togetherWith
                    (androidx.compose.animation.slideOutVertically(tween(180)) { -it } + fadeOut(tween(120)))
            }, label = "Agent status line", modifier = Modifier.weight(1f)) { text ->
                ChatShimmerText(text, running = motion, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Which outline the status monogram takes for the agent's current step. */
internal fun codeLogoShape(
    runningCall: com.androidharness.app.core.ToolCallData?,
    waiting: Boolean,
    drafting: Boolean,
    thinking: Boolean,
    writing: Boolean,
    lastTool: LogoShape?,
): LogoShape = when {
    waiting -> LogoShape.Monogram
    runningCall != null -> toolLogoShape(runningCall)
    drafting -> LogoShape.Pencil
    thinking -> LogoShape.Sparkles
    writing -> LogoShape.Lines
    lastTool != null -> lastTool
    else -> LogoShape.Sparkles
}

internal fun toolLogoShape(call: com.androidharness.app.core.ToolCallData): LogoShape {
    if (call.name in com.androidharness.app.agent.LiveCodePreview.tools) return LogoShape.Pencil
    if (call.name == "task") return LogoShape.Sparkles
    return when (com.androidharness.app.agent.toolPresentation(call).kind) {
        com.androidharness.app.agent.ToolActivityKind.READ, com.androidharness.app.agent.ToolActivityKind.MEMORY -> LogoShape.Eye
        com.androidharness.app.agent.ToolActivityKind.SEARCH -> LogoShape.Magnifier
        com.androidharness.app.agent.ToolActivityKind.WEB -> if (call.name == "web_search") LogoShape.Magnifier else LogoShape.Eye
        com.androidharness.app.agent.ToolActivityKind.EDIT -> if (call.name == "pkg_install" || call.name == "android_sdk_install") LogoShape.Terminal else LogoShape.Pencil
        com.androidharness.app.agent.ToolActivityKind.COMMAND, com.androidharness.app.agent.ToolActivityKind.VERIFY -> LogoShape.Terminal
        com.androidharness.app.agent.ToolActivityKind.SUBAGENT -> LogoShape.Sparkles
        com.androidharness.app.agent.ToolActivityKind.QUESTION -> LogoShape.Monogram
        com.androidharness.app.agent.ToolActivityKind.OTHER -> if (call.name in setOf("delete_file", "move_file", "create_dir", "file_restore"))
            LogoShape.Pencil else if (call.name.startsWith("git_")) LogoShape.Terminal else LogoShape.Code
    }
}

/**
 * Queued mid-run message. Default is wait for the next iteration.
 * Steer stops the agent and sends this text as a new turn.
 */
@Composable
internal fun QueuedMessageChip(
    text: String,
    onCancel: () -> Unit,
    onSteer: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            DotLoading(dotSize = 3.dp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text.take(80),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    uiContext.getString(R.string.ui_sends_after_this_turn),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onSteer) {
                Text(uiContext.getString(R.string.ui_steer))
            }
            // 40dp not 28: this sits next to a destructive action, so it needs to
            // be reliably hittable without growing the chip to full toolbar height.
            IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = uiContext.getString(R.string.ui_cancel_queue), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * File suggestions for a trailing @path token in the composer, styled like
 * the slash menu. Empty query lists the workspace from the top; picking
 * replaces the token with @<path>.
 */
@Composable
internal fun MentionSuggestions(
    files: List<String>,
    query: String,
    loading: Boolean,
    onPick: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val filtered = remember(files, query) {
        if (query.isBlank()) files
        else files.filter { it.contains(query, ignoreCase = true) }
    }.take(8)
    if (filtered.isEmpty()) return
    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
                .padding(vertical = 4.dp)
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            filtered.forEach { path ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(path) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        "@" + path.substringBeforeLast('/').ifEmpty { "." },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Transient "compacting" banner shown in the transcript while compaction runs. */
@Composable
internal fun CompactionBanner(note: String, modifier: Modifier = Modifier) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        contentColor = scheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            DotLoading()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(uiContext.getString(R.string.ui_compacting_conversation), style = MaterialTheme.typography.bodyMedium)
                if (note.isNotBlank() && note != "Compacting conversation…") {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/** The persistent one-liner the transcript keeps after a compaction finished. */
@Composable
internal fun CompactionNoticeLine(modifier: Modifier = Modifier) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    Text(
        uiContext.getString(R.string.ui_context_compacted_older_messages_summarized),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** A saved failure remains readable after the transient snackbar disappears. */
@Composable
internal fun CompactionResultCard(message: String, showRetry: Boolean, busy: Boolean, onRetry: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val stopped = message.startsWith("Compaction stopped.")
    val saved = message.startsWith("Chat compacted, but")
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(if (stopped) uiContext.getString(R.string.ui_compaction_stopped) else if (saved) uiContext.getString(R.string.ui_summary_saved) else uiContext.getString(R.string.ui_compaction_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (stopped || saved) scheme.onSurface else scheme.error)
            Text(if (saved) uiContext.getString(R.string.ui_your_summary_is_saved_some_task_information)
                else uiContext.getString(R.string.ui_your_messages_are_still_here_try_again),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            androidx.compose.foundation.layout.FlowRow {
                if (showRetry && !saved) TextButton(onClick = onRetry, enabled = !busy) { Text(uiContext.getString(R.string.ui_retry)) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) uiContext.getString(R.string.settings_shizuku_hide_details) else uiContext.getString(R.string.ui_details)) }
            }
        }
    }
}

