package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
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
import com.androidharness.app.ui.chat.SlashCommands
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastSpatialSpec
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Slim live-activity strip above the composer: one small gently-pulsing accent
 * dot and a single muted line of text. No scale wobble, no colored container.
 */
@Composable
internal fun AgentStatusBar(action: String?, busy: Boolean) {
    AnimatedVisibility(
        visible = busy && action != null,
        enter = fadeIn(tween(150)) + expandVertically(animationSpec = fastSpatialSpec()),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = fastSpatialSpec()),
    ) {
        val scheme = MaterialTheme.colorScheme
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(horizontal = 18.dp, vertical = 7.dp),
        ) {
            StatusDot()
            Spacer(Modifier.width(10.dp))
            Text(
                action ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusDot() {
    val transition = rememberInfiniteTransition(label = "status dot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "status dot alpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** Live milestone progress. Completion comes only from explicit agent updates. */
@Composable
internal fun TodoCard(todos: List<TodoItem>) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val done = todos.count { it.status == TodoItem.Status.COMPLETED }
    val active = todos.firstOrNull { it.status == TodoItem.Status.IN_PROGRESS }
    val next = todos.firstOrNull { it.status == TodoItem.Status.PENDING }
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val listHeight = (LocalConfiguration.current.screenHeightDp * .28f).coerceIn(120f, 240f).dp
    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(
                onClickLabel = if (expanded) uiContext.getString(R.string.ui_collapse_task_list) else uiContext.getString(R.string.ui_expand_task_list),
                onClick = { expanded = !expanded },
            ).semantics {
                stateDescription = "$done completed, ${todos.count { it.status == TodoItem.Status.IN_PROGRESS }} in progress, ${todos.count { it.status == TodoItem.Status.PENDING }} pending"
            }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (done == todos.size) success else scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(uiContext.getString(R.string.ui_task_list), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(uiContext.getString(R.string.ui_of_completed, (done).toString(), (todos.size).toString()), style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) uiContext.getString(R.string.ui_collapse_task_list) else uiContext.getString(R.string.ui_expand_task_list),
                    modifier = Modifier.size(20.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(progress = { done.toFloat() / todos.size },
                color = if (done == todos.size) success else scheme.primary,
                trackColor = scheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(4.dp))
            if (!expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(when { active != null -> uiContext.getString(R.string.ui_in_progress); next != null -> uiContext.getString(R.string.ui_next); else -> uiContext.getString(R.string.ui_all_tasks_completed) },
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                        color = if (done == todos.size) success else scheme.primary)
                    (active ?: next)?.let { Text(it.content, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(max = listHeight)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    todos.forEach { todo ->
                        val status = when (todo.status) {
                            TodoItem.Status.COMPLETED -> uiContext.getString(R.string.ui_completed)
                            TodoItem.Status.IN_PROGRESS -> uiContext.getString(R.string.ui_in_progress)
                            TodoItem.Status.PENDING -> uiContext.getString(R.string.ui_pending)
                        }
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = status },
                        ) {
                            Icon(
                                when (todo.status) {
                                    TodoItem.Status.COMPLETED -> Icons.Filled.CheckCircle
                                    TodoItem.Status.IN_PROGRESS -> Icons.Outlined.PlayArrow
                                    TodoItem.Status.PENDING -> Icons.Outlined.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                modifier = Modifier.padding(top = 2.dp).size(18.dp),
                                tint = when (todo.status) {
                                    TodoItem.Status.COMPLETED -> success
                                    TodoItem.Status.IN_PROGRESS -> scheme.primary
                                    TodoItem.Status.PENDING -> scheme.outline
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(todo.content, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (todo.status == TodoItem.Status.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal,
                                    color = if (todo.status == TodoItem.Status.COMPLETED) scheme.onSurfaceVariant else scheme.onSurface)
                                Text(status, style = MaterialTheme.typography.labelSmall,
                                    color = if (todo.status == TodoItem.Status.IN_PROGRESS) scheme.primary else scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
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

/** Removable image-attachment chips above the composer. */
@Composable
internal fun AttachmentChips(
    attachments: List<String>,
    onRemove: (Int) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { idx, name ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                onClick = { onRemove(idx) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(name.take(20), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Close, contentDescription = uiContext.getString(R.string.settings_action_remove), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

private data class SlashEntry(
    val command: String,
    val title: String,
    val subtitle: String,
    val kind: SlashCommands.Kind,
)

/** "/" menu: a short filtered list over the messages, never covering the composer. */
@Composable
internal fun SlashSuggestions(
    state: ChatUiState,
    query: String,
    expanded: Boolean,
    onPick: (command: String, kind: SlashCommands.Kind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val typed = remember(query) { query.removePrefix("/").trim().lowercase() }
    val entries = remember(state.skills, state.snippets) {
        buildList {
            add(SlashEntry("/clear", "New chat", "Start a fresh conversation", SlashCommands.Kind.CLEAR))
            add(SlashEntry("/compact", "Compact", "Summarize older context", SlashCommands.Kind.COMPACT))
            add(SlashEntry("/cost", "Cost", "Show token and cost totals", SlashCommands.Kind.COST))
            add(SlashEntry("/doctor", "Harness doctor", "Run self-test across every harness tool", SlashCommands.Kind.DOCTOR))
            add(SlashEntry("/init", "Init project", "Write or refresh AGENTS.md", SlashCommands.Kind.INIT))
            add(SlashEntry("/plan", "Plan mode", "Load the plan skill and switch to Plan mode", SlashCommands.Kind.PLAN))
            add(SlashEntry("/skills", "Browse skills", "Open the full skill picker", SlashCommands.Kind.SKILLS))
            add(SlashEntry("/env", "Linux environment", "Check, update, install or repair the Linux env", SlashCommands.Kind.ENV))
            // "plan" is excluded: the built-in /plan entry above already covers
            // it (activates Plan mode + loads the plan skill), showing the
            // skill too used to list /plan twice.
            state.skills.filter { it.enabled && it.name != "plan" }.forEach { skill ->
                add(SlashEntry("/${skill.name}", skill.name, skill.description, SlashCommands.Kind.SKILL))
            }
            state.snippets.forEach { snippet ->
                add(SlashEntry("/${snippet.name}", snippet.name, "Saved prompt snippet", SlashCommands.Kind.SNIPPET))
            }
        }
    }
    val filtered = remember(entries, typed) {
        if (typed.isEmpty()) entries
        else entries.filter { entry ->
            entry.command.removePrefix("/").contains(typed) ||
                entry.title.lowercase().contains(typed) ||
                entry.subtitle.lowercase().contains(typed)
        }
    }.take(8)

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(150)) + expandVertically(animationSpec = tween(200)),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = tween(150)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            color = scheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Column(
                Modifier
                    .padding(vertical = 4.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (filtered.isEmpty()) {
                    Text(
                        uiContext.getString(R.string.ui_no_matches_for, (typed).toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    filtered.forEach { entry ->
                        Surface(
                            onClick = { onPick(entry.command, entry.kind) },
                            color = scheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.command,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (entry.kind) {
                                        SlashCommands.Kind.SKILL -> "Skill"
                                        SlashCommands.Kind.SNIPPET -> "Snippet"
                                        else -> "Command"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * File suggestions for a trailing @path token in the composer, styled like
 * [SlashSuggestions]. Empty query lists the workspace from the top; picking
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
    var expanded by remember { mutableStateOf(false) }
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

/** Pending file attachments in the composer: name, size, tap to remove. */
@Composable
internal fun FileAttachmentChips(
    files: List<com.androidharness.app.ui.chat.FileAttachment>,
    onRemove: (Int) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEachIndexed { idx, file ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                onClick = { onRemove(idx) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(file.name.take(20), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        file.sizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Close, contentDescription = uiContext.getString(R.string.settings_action_remove), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}
