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
import com.androidharness.app.ui.chat.SlashCommands
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
 * Live milestone progress. Completion comes only from explicit agent updates.
 * The header badge spins while work remains, a wavy bar tracks progress, and
 * each step carries its own small shape: cookie in progress, circle when done.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
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
    val allDone = done == todos.size
    val listHeight = (LocalConfiguration.current.screenHeightDp * .28f).coerceIn(120f, 240f).dp
    val progress by androidx.compose.animation.core.animateFloatAsState(
        done.toFloat() / todos.size, bouncySpec(), label = "todo progress",
    )
    Box(Modifier.padding(horizontal = 12.dp)) {
        CodeCard(onClick = { expanded = !expanded }, expanded = expanded,
            modifier = Modifier.semantics {
                stateDescription = "$done completed, ${todos.count { it.status == TodoItem.Status.IN_PROGRESS }} in progress, ${todos.count { it.status == TodoItem.Status.PENDING }} pending"
            }) {
            CodeCardHeader(
                badge = {
                    MorphingStatusBadge(if (allDone) WorkState.Done else WorkState.Running, Icons.Outlined.Checklist,
                        size = 34.dp, showOutcome = false)
                },
                title = uiContext.getString(R.string.ui_task_list),
                subtitle = if (expanded) uiContext.getString(R.string.ui_of_completed, (done).toString(), (todos.size).toString())
                    else (active ?: next)?.content ?: uiContext.getString(R.string.ui_all_tasks_completed),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("$done / ${todos.size}", if (allDone) WorkState.Done else WorkState.Running)
                        ExpandChevron(expanded,
                            if (expanded) uiContext.getString(R.string.ui_collapse_task_list) else uiContext.getString(R.string.ui_expand_task_list))
                    }
                },
            )
            androidx.compose.material3.LinearWavyProgressIndicator(
                progress = { progress },
                color = if (allDone) success else scheme.primary,
                trackColor = scheme.surfaceContainerHighest,
                amplitude = { if (allDone) 0f else 1f },
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
            )
            AnimatedVisibility(expanded,
                enter = expandVertically(animationSpec = bouncySpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = fastSpatialSpec()) + fadeOut()) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp).heightIn(max = listHeight)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    todos.forEachIndexed { index, todo ->
                        val status = when (todo.status) {
                            TodoItem.Status.COMPLETED -> uiContext.getString(R.string.ui_completed)
                            TodoItem.Status.IN_PROGRESS -> uiContext.getString(R.string.ui_in_progress)
                            TodoItem.Status.PENDING -> uiContext.getString(R.string.ui_pending)
                        }
                        val first = index == 0
                        val last = index == todos.lastIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(
                                    topStart = if (first) 20.dp else 6.dp, topEnd = if (first) 20.dp else 6.dp,
                                    bottomStart = if (last) 20.dp else 6.dp, bottomEnd = if (last) 20.dp else 6.dp))
                                .background(if (todo.status == TodoItem.Status.IN_PROGRESS) scheme.secondaryContainer else scheme.surfaceContainerLowest)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .semantics(mergeDescendants = true) { stateDescription = status },
                        ) {
                            when (todo.status) {
                                TodoItem.Status.COMPLETED -> Box(Modifier.size(22.dp).background(success, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = scheme.surface)
                                }
                                TodoItem.Status.IN_PROGRESS -> MorphingStatusBadge(WorkState.Running, Icons.Outlined.PlayArrow, size = 22.dp, showOutcome = false)
                                TodoItem.Status.PENDING -> Box(Modifier.size(22.dp).border(2.dp, scheme.outlineVariant, CircleShape))
                            }
                            Text(todo.content, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (todo.status == TodoItem.Status.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal,
                                textDecoration = if (todo.status == TodoItem.Status.COMPLETED) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                color = when (todo.status) {
                                    TodoItem.Status.COMPLETED -> scheme.onSurfaceVariant
                                    TodoItem.Status.IN_PROGRESS -> scheme.onSecondaryContainer
                                    TodoItem.Status.PENDING -> scheme.onSurface
                                },
                                modifier = Modifier.weight(1f))
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
            add(SlashEntry("/autocompact", uiContext.getString(R.string.auto_compact_title), uiContext.getString(R.string.auto_compact_slash_hint), SlashCommands.Kind.AUTO_COMPACT))
            add(SlashEntry("/cost", "Cost", "Show token and cost totals", SlashCommands.Kind.COST))
            add(SlashEntry("/context", uiContext.getString(R.string.context_command_title), uiContext.getString(R.string.context_command_hint), SlashCommands.Kind.CONTEXT))
            add(SlashEntry(SlashCommands.SIDE_COMMAND, "By the way", "Ask aside on the live context, kept out of the chat", SlashCommands.Kind.BTW))
            add(SlashEntry("/doctor", "Harness doctor", "Run self-test across every harness tool", SlashCommands.Kind.DOCTOR))
            add(SlashEntry("/init", "Init project", "Write or refresh AGENTS.md", SlashCommands.Kind.INIT))
            if (state.skills.none { it.enabled && it.name == "review" } && state.snippets.none { it.name.equals("review", ignoreCase = true) }) {
                add(SlashEntry("/review", "Review changes", "Read-only review of the pending changes, add a focus after it", SlashCommands.Kind.REVIEW))
            }
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

