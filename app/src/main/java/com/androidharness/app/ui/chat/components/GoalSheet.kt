package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.GoalCheck
import com.androidharness.app.agent.GoalJudge
import com.androidharness.app.agent.GoalState
import com.androidharness.app.agent.TaskLimits
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.ExpressiveSheetHeader
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.Switch
import com.androidharness.app.ui.common.TextButton

private val TURN_PRESETS = listOf(5, 10, 20, 50)
private const val EXTRA_TURNS = 10

/** Colour and icon of a goal's state, shared by the pill, the sheet and the check rows. */
@Composable
private fun goalTone(status: GoalState.Status): Pair<Color, ImageVector> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        GoalState.Status.ACTIVE -> scheme.primary to Icons.Outlined.Flag
        GoalState.Status.BLOCKED -> scheme.tertiary to Icons.Outlined.ErrorOutline
        GoalState.Status.MET -> scheme.primary to Icons.Outlined.TaskAlt
        GoalState.Status.LIMIT -> scheme.error to Icons.Outlined.ErrorOutline
        GoalState.Status.PAUSED -> scheme.onSurfaceVariant to Icons.Outlined.Pause
    }
}

@Composable
private fun statusLabel(goal: GoalState): String {
    val ui = LocalContext.current
    if (goal.status == GoalState.Status.ACTIVE && goal.improving) return ui.getString(R.string.goal_state_improving)
    return ui.getString(when (goal.status) {
        GoalState.Status.ACTIVE -> R.string.goal_state_active
        GoalState.Status.BLOCKED -> R.string.goal_state_blocked
        GoalState.Status.MET -> R.string.goal_state_met
        GoalState.Status.LIMIT -> R.string.goal_state_limit
        GoalState.Status.PAUSED -> R.string.goal_state_paused
    })
}

/**
 * Goal turns as a row of segments: one per turn up to 20, beyond that each segment
 * covers several turns so the bar keeps its width. The segment being worked on pulses.
 */
@Composable
private fun GoalSegments(goal: GoalState, running: Boolean, modifier: Modifier, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    val (tone, _) = goalTone(goal.status)
    val segments = goal.turnLimit.coerceIn(1, 20)
    // Finished turns: a reached goal counts the turn that reached it, a stopped one all of them.
    val finished = when (goal.status) {
        GoalState.Status.MET -> goal.turnsUsed
        GoalState.Status.LIMIT -> goal.turnLimit
        else -> goal.turns
    }
    val done = finished.coerceAtMost(goal.turnLimit).toFloat() / goal.turnLimit.coerceAtLeast(1) * segments
    val pulse = if (running && goal.status == GoalState.Status.ACTIVE) {
        rememberInfiniteTransition(label = "goal-pulse")
            .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "goal-pulse-alpha").value
    } else 0f
    val track = scheme.surfaceContainerHighest
    Canvas(modifier.height(height)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val r = CornerRadius(size.height / 2)
        for (i in 0 until segments) {
            val x = i * (w + gap)
            val color = when {
                i + 1 <= done -> tone
                i < done -> tone.copy(alpha = 0.6f)
                i == done.toInt() && pulse > 0f -> tone.copy(alpha = pulse * 0.6f)
                else -> track
            }
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), r)
        }
    }
}

/** One line above the composer: the goal's state, its turns as segments, and parts done. */
@Composable
fun GoalProgressPill(goal: GoalState, running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalContext.current
    val (tone, icon) = goalTone(goal.status)
    val partsDone = goal.parts.count { it.done }
    val description = ui.getString(R.string.goal_pill_description, statusLabel(goal), goal.turnsUsed, goal.turnLimit)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = tone)
            Text(statusLabel(goal), style = MaterialTheme.typography.labelMedium, color = tone, maxLines = 1)
            GoalSegments(goal, running, Modifier.weight(1f), 6.dp)
            if (goal.parts.isNotEmpty()) Text(ui.getString(R.string.goal_parts_short, partsDone, goal.parts.size),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("${goal.turnsUsed}/${goal.turnLimit}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * A goal-loop message (a check's continuation or a resume) drawn as one slim row
 * instead of a user bubble. Tapping it shows the check's reason and next step.
 */
@Composable
fun GoalCheckpointRow(text: String, modifier: Modifier = Modifier) {
    val ui = LocalContext.current
    val checkpoint = remember(text) { GoalJudge.checkpoint(text) }
    var open by rememberSaveable(text) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = checkpoint != null) { open = !open }.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (checkpoint == null) Icons.Outlined.PlayArrow else Icons.Outlined.Replay, null, Modifier.size(14.dp), tint = scheme.primary)
            Text(
                if (checkpoint == null) ui.getString(R.string.goal_row_resumed)
                else ui.getString(if (checkpoint.improving) R.string.goal_row_improve else R.string.goal_row_check,
                    checkpoint.turn, checkpoint.limit, checkpoint.reason.lineSequence().first()),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(open && checkpoint != null) {
            Column(Modifier.padding(start = 26.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                checkpoint?.let {
                    Text(it.reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (it.next.isNotBlank()) Text(ui.getString(R.string.goal_next, it.next),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                }
            }
        }
    }
}

/**
 * The /goal sheet. With no goal (or after "New goal") it sets one up: what done looks
 * like, how to check it, max turns, a budget and notifications. With a goal it shows
 * progress: turns, the parts ticked off one by one, every check, and the controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalSheet(
    goal: GoalState?,
    draft: String,
    limits: TaskLimits,
    running: Boolean,
    onStart: (ChatViewModel.GoalDraft) -> Unit,
    onPause: () -> Unit,
    onResume: (extraTurns: Int) -> Unit,
    onExtend: (extraTurns: Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var setup by rememberSaveable { mutableStateOf(goal == null || draft.isNotBlank()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (setup || goal == null) {
                GoalSetup(
                    initialText = draft.ifBlank { goal?.text.orEmpty() },
                    initialTurns = goal?.turnLimit ?: GoalJudge.DEFAULT_TURN_LIMIT,
                    initialCheck = goal?.checkCommand.orEmpty(),
                    initialNotify = goal?.notifyEachCheck ?: false,
                    initialKeepImproving = goal?.keepImproving ?: false,
                    limits = limits,
                    replacing = goal != null,
                    onStart = onStart,
                    onDismiss = onDismiss,
                )
            } else {
                GoalProgress(goal, running, onPause, onResume, onExtend, onClear, onNew = { setup = true }, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun GoalSetup(
    initialText: String,
    initialTurns: Int,
    initialCheck: String,
    initialNotify: Boolean,
    initialKeepImproving: Boolean,
    limits: TaskLimits,
    replacing: Boolean,
    onStart: (ChatViewModel.GoalDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    var turns by rememberSaveable { mutableIntStateOf(initialTurns) }
    var check by rememberSaveable { mutableStateOf(initialCheck) }
    var notify by rememberSaveable { mutableStateOf(initialNotify) }
    var keepImproving by rememberSaveable { mutableStateOf(initialKeepImproving) }
    var tokens by rememberSaveable { mutableStateOf(limits.tokens.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by rememberSaveable { mutableStateOf(limits.cost.takeIf { it > 0 }?.toString().orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(limits.minutes.takeIf { it > 0 }?.toString().orEmpty()) }

    ExpressiveSheetHeader(ui.getString(R.string.goal_setup_title), ui.getString(R.string.goal_setup_subtitle),
        ui.getString(R.string.goal_close), onDismiss)
    OutlinedTextField(text, { text = it.take(GoalJudge.MAX_GOAL_CHARS) }, Modifier.fillMaxWidth(),
        label = { Text(ui.getString(R.string.goal_field_label)) },
        placeholder = { Text(ui.getString(R.string.goal_field_placeholder)) }, minLines = 2, maxLines = 6)

    Label(ui.getString(R.string.goal_turns_label))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TURN_PRESETS.forEach { preset ->
            FilterChip(selected = turns == preset, onClick = { turns = preset }, label = { Text("$preset") })
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { turns = (turns - 1).coerceAtLeast(1) }, enabled = turns > 1) {
            Icon(Icons.Outlined.Remove, ui.getString(R.string.goal_turns_fewer))
        }
        Text("$turns", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { turns = (turns + 1).coerceAtMost(GoalJudge.MAX_TURN_LIMIT) }, enabled = turns < GoalJudge.MAX_TURN_LIMIT) {
            Icon(Icons.Outlined.Add, ui.getString(R.string.goal_turns_more))
        }
    }

    OutlinedTextField(check, { check = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(ui.getString(R.string.goal_check_label)) },
        placeholder = { Text("./gradlew test", fontFamily = FontFamily.Monospace) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        supportingText = { Text(ui.getString(R.string.goal_check_hint)) })

    Label(ui.getString(R.string.goal_budget_label))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(tokens, { tokens = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true,
            label = { Text(ui.getString(R.string.goal_budget_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(cost, { cost = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), singleLine = true,
            label = { Text(ui.getString(R.string.goal_budget_cost)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true,
            label = { Text(ui.getString(R.string.goal_budget_minutes)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Text(ui.getString(R.string.goal_budget_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(Modifier.fillMaxWidth().clickable { keepImproving = !keepImproving }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ui.getString(R.string.goal_keep_improving), style = MaterialTheme.typography.bodyLarge)
            Text(ui.getString(R.string.goal_keep_improving_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(keepImproving, { keepImproving = it })
    }

    Row(Modifier.fillMaxWidth().clickable { notify = !notify }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ui.getString(R.string.goal_notify_each), style = MaterialTheme.typography.bodyLarge)
            Text(ui.getString(R.string.goal_notify_each_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(notify, { notify = it })
    }

    if (replacing) Text(ui.getString(R.string.goal_replace_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(
        onClick = {
            onStart(ChatViewModel.GoalDraft(text, turns, check,
                TaskLimits(tokens.toLongOrNull() ?: 0, cost.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0,
                    (minutes.toLongOrNull() ?: 0).coerceIn(0, 525_600)), notify, keepImproving))
        },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(ui.getString(R.string.goal_start)) }
}

@Composable
private fun GoalProgress(
    goal: GoalState,
    running: Boolean,
    onPause: () -> Unit,
    onResume: (Int) -> Unit,
    onExtend: (Int) -> Unit,
    onClear: () -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (tone, icon) = goalTone(goal.status)
    ExpressiveSheetHeader(goal.text, null, ui.getString(R.string.goal_close), onDismiss)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = tone)
        Text(statusLabel(goal), style = MaterialTheme.typography.titleSmall, color = tone)
        Text(ui.getString(R.string.goal_turns_used, goal.turnsUsed, goal.turnLimit), style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant)
    }
    GoalSegments(goal, running, Modifier.fillMaxWidth(), 8.dp)
    goal.reachedAtTurn?.let { reachedAt ->
        val passes = goal.turnsUsed - reachedAt
        Text(when {
            goal.improving -> ui.getString(R.string.goal_reached_on_improving, reachedAt)
            passes > 0 -> ui.getString(R.string.goal_reached_then_improved, reachedAt, passes)
            else -> ui.getString(R.string.goal_reached_on, reachedAt)
        }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    goal.checkCommand?.let {
        Text(ui.getString(R.string.goal_checked_by, it), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = scheme.onSurfaceVariant)
    }

    if (goal.parts.isNotEmpty()) {
        Label(ui.getString(R.string.goal_parts_title, goal.parts.count { it.done }, goal.parts.size))
        goal.parts.forEach { part ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (part.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(18.dp),
                    tint = if (part.done) scheme.primary else scheme.outline)
                Text(part.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (part.done) scheme.onSurface else scheme.onSurfaceVariant)
                part.doneAtTurn?.let { Text(ui.getString(R.string.goal_part_turn, it), style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant) }
            }
        }
    }

    Label(ui.getString(R.string.goal_checks_title))
    if (goal.history.isEmpty()) {
        Text(ui.getString(R.string.goal_checks_empty), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    } else goal.history.asReversed().forEach { check -> CheckRow(check, goal.turnLimit) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (goal.status) {
            GoalState.Status.ACTIVE -> {
                FilledTonalButton(onClick = onPause) { Icon(Icons.Outlined.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(ui.getString(R.string.goal_action_pause)) }
                OutlinedButton(onClick = { onExtend(EXTRA_TURNS) }, enabled = goal.turnLimit < GoalJudge.MAX_TURN_LIMIT) {
                    Text(ui.getString(R.string.goal_action_more_turns, EXTRA_TURNS))
                }
            }
            GoalState.Status.PAUSED, GoalState.Status.BLOCKED -> {
                FilledTonalButton(onClick = { onResume(0) }) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(ui.getString(R.string.goal_action_resume)) }
                OutlinedButton(onClick = { onExtend(EXTRA_TURNS) }, enabled = goal.turnLimit < GoalJudge.MAX_TURN_LIMIT) {
                    Text(ui.getString(R.string.goal_action_more_turns, EXTRA_TURNS))
                }
            }
            GoalState.Status.LIMIT -> FilledTonalButton(onClick = { onResume(EXTRA_TURNS) }, enabled = goal.turnLimit < GoalJudge.MAX_TURN_LIMIT) {
                Text(ui.getString(R.string.goal_action_more_turns_resume, EXTRA_TURNS))
            }
            // A reached goal can go on improving, here or with the switch in its setup.
            GoalState.Status.MET -> FilledTonalButton(onClick = { onResume(if (goal.turnsUsed >= goal.turnLimit) EXTRA_TURNS else 0) },
                enabled = goal.keepImproving || goal.turnLimit < GoalJudge.MAX_TURN_LIMIT) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(ui.getString(R.string.goal_action_keep_improving))
            }
        }
        OutlinedButton(onClick = onNew) { Text(ui.getString(R.string.goal_action_new)) }
        TextButton(onClick = onClear) { Text(ui.getString(R.string.goal_action_clear), color = scheme.error) }
    }
}

@Composable
private fun CheckRow(check: GoalCheck, limit: Int) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (tint, icon, label) = when (check.outcome) {
        GoalCheck.Outcome.MET -> Triple(scheme.primary, Icons.Outlined.TaskAlt, R.string.goal_outcome_met)
        GoalCheck.Outcome.CONTINUE -> Triple(scheme.onSurfaceVariant, Icons.Outlined.Replay, R.string.goal_outcome_continue)
        GoalCheck.Outcome.BLOCKED -> Triple(scheme.tertiary, Icons.Outlined.ErrorOutline, R.string.goal_outcome_blocked)
        GoalCheck.Outcome.FAILED -> Triple(scheme.error, Icons.Outlined.ErrorOutline, R.string.goal_outcome_failed)
        GoalCheck.Outcome.IMPROVE -> Triple(scheme.primary, Icons.Outlined.AutoAwesome, R.string.goal_outcome_improve)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(ui.getString(R.string.goal_check_heading, check.turn, limit, ui.getString(label)),
                style = MaterialTheme.typography.labelMedium, color = tint)
            Text(check.reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (check.next.isNotBlank()) Text(ui.getString(R.string.goal_next, check.next), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** Marks the message that started a goal; the bubble below it shows only the goal text. */
@Composable
fun GoalBadge() {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.padding(bottom = 4.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.Flag, null, Modifier.size(12.dp))
            Text(LocalContext.current.getString(R.string.goal_badge), style = MaterialTheme.typography.labelSmall)
        }
    }
}
