package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import com.androidharness.app.agent.GoalPart
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.delay
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
private fun statusLabel(goal: GoalState, stalled: Boolean = false): String {
    val ui = LocalContext.current
    if (stalled && goal.status == GoalState.Status.ACTIVE) return ui.getString(R.string.goal_state_stopped)
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
fun GoalProgressPill(
    goal: GoalState,
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stalled: Boolean = false,
    onResume: () -> Unit = {},
) {
    val ui = LocalContext.current
    val (tone, icon) = if (stalled) MaterialTheme.colorScheme.tertiary to Icons.Outlined.Pause else goalTone(goal.status)
    val partsDone = goal.parts.count { it.done }
    val description = ui.getString(R.string.goal_pill_description, statusLabel(goal, stalled), goal.turnsUsed, goal.turnLimit)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = tone)
            Text(statusLabel(goal, stalled), style = MaterialTheme.typography.labelMedium, color = tone, maxLines = 1)
            GoalSegments(goal, running, Modifier.weight(1f), 6.dp)
            if (goal.parts.isNotEmpty()) Text(ui.getString(R.string.goal_parts_short, partsDone, goal.parts.size),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("${goal.turnsUsed}/${goal.turnLimit}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            AnimatedVisibility(stalled, enter = scaleIn(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                exit = scaleOut(expressiveEffectsSpec()) + fadeOut(expressiveEffectsSpec())) {
                Surface(onClick = onResume, shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(14.dp))
                        Text(ui.getString(R.string.goal_action_resume), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * A goal-loop message (a check's continuation or a resume) drawn as one slim row
 * instead of a user bubble: a turn badge and what that turn changed. Tapping it
 * shows the check's badges in full.
 */
@Composable
fun GoalCheckpointRow(text: String, modifier: Modifier = Modifier) {
    val ui = LocalContext.current
    val checkpoint = remember(text) { GoalJudge.checkpoint(text) }
    var open by rememberSaveable(text) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp).animateContentSize(expressiveDefaultSpatialSpec())) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = checkpoint != null) { open = !open }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val improving = checkpoint?.improving == true
            Surface(shape = CircleShape, color = if (improving) scheme.tertiaryContainer else scheme.secondaryContainer,
                contentColor = if (improving) scheme.onTertiaryContainer else scheme.onSecondaryContainer) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(when { checkpoint == null -> Icons.Outlined.PlayArrow; improving -> Icons.Outlined.AutoAwesome
                        else -> Icons.Outlined.Replay }, null, Modifier.size(12.dp))
                    Text(if (checkpoint == null) ui.getString(R.string.goal_row_resumed) else "${checkpoint.turn}/${checkpoint.limit}",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            if (checkpoint != null) Text(
                checkpoint.changed.ifBlank { checkpoint.reason.lineSequence().first() },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(open && checkpoint != null) {
            Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                checkpoint?.let {
                    if (it.changed.isNotBlank()) BadgeLine(if (it.improving) Badge.IMPROVED else Badge.DONE, it.changed)
                    Text(it.reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (it.next.isNotBlank()) BadgeLine(Badge.NEXT, it.next)
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
    stalled: Boolean,
    onStart: (ChatViewModel.GoalDraft) -> Unit,
    onPause: () -> Unit,
    onResume: (extraTurns: Int) -> Unit,
    onExtend: (extraTurns: Int) -> Unit,
    onImprovements: (focus: List<String>, queue: List<String>) -> Unit,
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
                    initialFocus = goal?.improveFocus.orEmpty(),
                    initialList = goal?.improveQueue.orEmpty().joinToString("\n"),
                    limits = limits,
                    replacing = goal != null,
                    onStart = onStart,
                    onDismiss = onDismiss,
                )
            } else {
                GoalProgress(goal, running, stalled, onPause, onResume, onExtend, onImprovements, onClear, onNew = { setup = true }, onDismiss = onDismiss)
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
    initialFocus: List<String>,
    initialList: String,
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
    var focus by rememberSaveable { mutableStateOf(initialFocus) }
    var improveList by rememberSaveable { mutableStateOf(initialList) }
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
    AnimatedVisibility(keepImproving, enter = expandVertically(expressiveDefaultSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
        exit = shrinkVertically(expressiveDefaultSpatialSpec()) + fadeOut(expressiveEffectsSpec())) {
        ImprovementEditor(focus, { focus = it }, improveList, { improveList = it })
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
                    (minutes.toLongOrNull() ?: 0).coerceIn(0, 525_600)), notify, keepImproving,
                if (keepImproving) focus else emptyList(), if (keepImproving) GoalJudge.improvementList(improveList) else emptyList()))
        },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(ui.getString(R.string.goal_start)) }
}

@Composable
private fun GoalProgress(
    goal: GoalState,
    running: Boolean,
    stalled: Boolean,
    onPause: () -> Unit,
    onResume: (Int) -> Unit,
    onExtend: (Int) -> Unit,
    onImprovements: (List<String>, List<String>) -> Unit,
    onClear: () -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    ExpressiveSheetHeader(goal.text, null, ui.getString(R.string.goal_close), onDismiss)
    GoalHero(goal, running, stalled)

    if (goal.parts.isNotEmpty()) {
        Label(ui.getString(R.string.goal_parts_title, goal.parts.count { it.done }, goal.parts.size))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            goal.parts.forEach { part -> PartChip(part) }
        }
    }

    if (goal.keepImproving || goal.status == GoalState.Status.MET) GoalImprovements(goal, onImprovements)

    Label(ui.getString(R.string.goal_checks_title))
    GoalTimeline(goal, running)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (goal.status) {
            GoalState.Status.ACTIVE -> {
                if (stalled) Button(onClick = { onResume(0) }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(ui.getString(R.string.goal_action_resume))
                } else FilledTonalButton(onClick = onPause) { Icon(Icons.Outlined.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(ui.getString(R.string.goal_action_pause)) }
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

/** The status, the turn count and the segments, as one tonal card. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GoalHero(goal: GoalState, running: Boolean, stalled: Boolean) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (tone, icon) = if (stalled) scheme.tertiary to Icons.Outlined.Pause else goalTone(goal.status)
    val working = running && goal.status == GoalState.Status.ACTIVE
    val spin = if (working) rememberInfiniteTransition(label = "goal-hero")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "goal-hero-spin").value else 0f
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().graphicsLayer { rotationZ = spin }
                        .background(tone.copy(alpha = 0.18f), MaterialShapes.Cookie9Sided.toShape()))
                    Icon(icon, null, Modifier.size(22.dp), tint = tone)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(statusLabel(goal, stalled), style = MaterialTheme.typography.titleMedium, color = tone)
                    if (stalled) Text(ui.getString(R.string.goal_stopped_hint), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                    val reachedLine = goal.reachedAtTurn?.let { reachedAt ->
                        val passes = goal.turnsUsed - reachedAt
                        when {
                            goal.improving -> ui.getString(R.string.goal_reached_on_improving, reachedAt)
                            passes > 0 -> ui.getString(R.string.goal_reached_then_improved, reachedAt, passes)
                            else -> ui.getString(R.string.goal_reached_on, reachedAt)
                        }
                    }
                    if (reachedLine != null) Text(reachedLine, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedContent(goal.turnsUsed, transitionSpec = {
                        (slideInVertically(expressiveDefaultSpatialSpec()) { it } + fadeIn(expressiveEffectsSpec())) togetherWith
                            (slideOutVertically(expressiveDefaultSpatialSpec()) { -it } + fadeOut(expressiveEffectsSpec()))
                    }, label = "goal-turns") { used ->
                        Text("$used", style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                    }
                    Text("/${goal.turnLimit}", Modifier.padding(bottom = 4.dp, start = 2.dp), style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurfaceVariant)
                }
            }
            GoalSegments(goal, running, Modifier.fillMaxWidth(), 8.dp)
            goal.checkCommand?.let {
                Text(ui.getString(R.string.goal_checked_by, it), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** A part of the goal as a small chip that fills in once the check finds it done. */
@Composable
private fun PartChip(part: GoalPart) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (part.done) scheme.secondaryContainer else scheme.surfaceContainerHigh, expressiveEffectsSpec(), label = "part-fill")
    val content by animateColorAsState(if (part.done) scheme.onSecondaryContainer else scheme.onSurfaceVariant, expressiveEffectsSpec(), label = "part-tint")
    Surface(shape = CircleShape, color = fill, contentColor = content) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AnimatedContent(part.done, transitionSpec = { scaleIn(expressiveSpatialSpec()) togetherWith scaleOut(expressiveEffectsSpec()) },
                label = "part-icon") { done ->
                Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(14.dp))
            }
            Text(part.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp))
            part.doneAtTurn?.let {
                Text(ui.getString(R.string.goal_part_turn, it), style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f))
            }
        }
    }
}

internal enum class Badge { NOW, IMPROVED, DONE, NEXT }

/** A short tonal label in front of a line: what is running now, what a turn changed, what comes next. */
@Composable
private fun BadgeChip(kind: Badge) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (fill, content) = when (kind) {
        Badge.NOW -> scheme.primary to scheme.onPrimary
        Badge.IMPROVED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Badge.DONE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Badge.NEXT -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    val (icon, label) = when (kind) {
        Badge.NOW -> Icons.Outlined.PlayArrow to R.string.goal_badge_now
        Badge.IMPROVED -> Icons.Outlined.AutoAwesome to R.string.goal_badge_improved
        Badge.DONE -> Icons.Outlined.Check to R.string.goal_badge_done
        Badge.NEXT -> Icons.AutoMirrored.Outlined.ArrowForward to R.string.goal_badge_next
    }
    Surface(shape = RoundedCornerShape(6.dp), color = fill, contentColor = content) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, Modifier.size(11.dp))
            Text(ui.getString(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun BadgeLine(kind: Badge, text: String, maxLines: Int = Int.MAX_VALUE) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        BadgeChip(kind)
        Text(text, Modifier.weight(1f).padding(top = 1.dp), style = MaterialTheme.typography.bodySmall,
            color = if (kind == Badge.NEXT) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private const val TIMELINE_PREVIEW = 4

/**
 * Every check as a timeline, newest first. A running goal starts with a "Now" node for
 * the turn in progress. Each check shows what its turn changed and, on the newest one,
 * what comes next. A reason that only repeats the one before is left out.
 */
@Composable
private fun GoalTimeline(goal: GoalState, running: Boolean) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val history = goal.history
    val now = running && goal.status == GoalState.Status.ACTIVE
    if (history.isEmpty() && !now) {
        Text(ui.getString(R.string.goal_checks_empty), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        return
    }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val newestFirst = history.indices.reversed().toList()
    val shown = if (showAll) newestFirst else newestFirst.take(TIMELINE_PREVIEW)
    Column(Modifier.fillMaxWidth().animateContentSize(expressiveDefaultSpatialSpec())) {
        if (now) {
            val working = history.lastOrNull()?.next?.takeIf { it.isNotBlank() } ?: ui.getString(R.string.goal_now_working)
            TimelineItem(key = "now", order = 0, first = true, last = shown.isEmpty(), node = { NowNode() }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.getString(R.string.goal_turn_label, goal.turnsUsed), style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary)
                }
                BadgeLine(Badge.NOW, working, maxLines = 3)
            }
        }
        shown.forEachIndexed { position, index ->
            val check = history[index]
            val previous = history.getOrNull(index - 1)
            val newest = index == history.lastIndex
            TimelineItem(
                key = check.at, order = position + if (now) 1 else 0,
                first = position == 0 && !now, last = position == shown.lastIndex,
                node = { CheckNode(check) },
            ) { CheckBody(check, previous, goal.turnLimit, showNext = check.changed.isBlank() || (newest && !now)) }
        }
        if (history.size > TIMELINE_PREVIEW) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(start = 28.dp)) {
            Text(if (showAll) ui.getString(R.string.goal_checks_fewer) else ui.getString(R.string.goal_checks_all, history.size))
        }
    }
}

@Composable
private fun CheckBody(check: GoalCheck, previous: GoalCheck?, limit: Int, showNext: Boolean) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(check.at) { mutableStateOf(false) }
    val repeated = previous != null && previous.reason.trim().equals(check.reason.trim(), ignoreCase = true)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }
        .animateContentSize(expressiveDefaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.getString(R.string.goal_turn_of, check.turn, limit), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            OutcomePill(check.outcome)
        }
        if (check.changed.isNotBlank()) BadgeLine(
            if (check.outcome == GoalCheck.Outcome.IMPROVE || check.outcome == GoalCheck.Outcome.MET) Badge.IMPROVED else Badge.DONE,
            check.changed, maxLines = if (open) Int.MAX_VALUE else 2,
        )
        if (!repeated || open) Text(check.reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        if (check.next.isNotBlank() && (showNext || open)) BadgeLine(Badge.NEXT, check.next, maxLines = if (open) Int.MAX_VALUE else 2)
    }
}

@Composable
private fun OutcomePill(outcome: GoalCheck.Outcome) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (fill, content, label) = when (outcome) {
        GoalCheck.Outcome.MET -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.string.goal_pill_met)
        GoalCheck.Outcome.IMPROVE -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, R.string.goal_pill_improve)
        GoalCheck.Outcome.CONTINUE -> Triple(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, R.string.goal_pill_continue)
        GoalCheck.Outcome.BLOCKED -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, R.string.goal_pill_blocked)
        GoalCheck.Outcome.FAILED -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.string.goal_pill_failed)
    }
    Surface(shape = CircleShape, color = fill, contentColor = content) {
        Text(ui.getString(label), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckNode(check: GoalCheck) {
    val scheme = MaterialTheme.colorScheme
    val (fill, content, shape) = when (check.outcome) {
        GoalCheck.Outcome.MET -> Triple(scheme.primary, scheme.onPrimary, MaterialShapes.Cookie6Sided)
        GoalCheck.Outcome.IMPROVE -> Triple(scheme.tertiary, scheme.onTertiary, MaterialShapes.Cookie6Sided)
        GoalCheck.Outcome.CONTINUE -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, MaterialShapes.Circle)
        GoalCheck.Outcome.BLOCKED -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, MaterialShapes.Square)
        GoalCheck.Outcome.FAILED -> Triple(scheme.errorContainer, scheme.onErrorContainer, MaterialShapes.Square)
    }
    Box(Modifier.size(NODE).background(fill, shape.toShape()), contentAlignment = Alignment.Center) {
        Text("${check.turn}", style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** The turn in progress: a turning cookie that breathes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowNode() {
    val scheme = MaterialTheme.colorScheme
    val motion = rememberInfiniteTransition(label = "goal-now")
    val spin by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "goal-now-spin")
    val breathe by motion.animateFloat(0.86f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "goal-now-scale")
    Box(Modifier.size(NODE), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = spin; scaleX = breathe; scaleY = breathe }
            .background(scheme.primary, MaterialShapes.Cookie9Sided.toShape()))
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(12.dp), tint = scheme.onPrimary)
    }
}

private val NODE = 24.dp

/** One timeline entry: a node on a connecting line, and the entry's content. Springs in once. */
@Composable
private fun TimelineItem(
    key: Any,
    order: Int,
    first: Boolean,
    last: Boolean,
    node: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val entrance = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(order.coerceAtMost(6) * 45L)
        entrance.animateTo(1f, expressiveDefaultSpatialSpec())
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).graphicsLayer {
        alpha = entrance.value.coerceIn(0f, 1f)
        translationY = (1f - entrance.value) * 12.dp.toPx()
    }) {
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Canvas(Modifier.matchParentSize()) {
                val x = size.width / 2
                val centre = 2.dp.toPx() + NODE.toPx() / 2
                val top = if (first) centre else 0f
                val bottom = if (last) centre else size.height
                if (bottom > top) drawLine(line, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 2.dp)) { node() }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 4.dp, bottom = if (last) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
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

private val FOCUS_PRESETS = listOf(
    R.string.goal_focus_bugs, R.string.goal_focus_polish, R.string.goal_focus_accessibility, R.string.goal_focus_speed,
    R.string.goal_focus_small_screens, R.string.goal_focus_tests, R.string.goal_focus_security, R.string.goal_focus_docs,
)

/**
 * What the improvement passes work on: areas to stay within (presets plus the user's
 * own), and an ordered list of specific improvements, one per pass.
 */
@Composable
private fun ImprovementEditor(
    focus: List<String>,
    onFocus: (List<String>) -> Unit,
    list: String,
    onList: (String) -> Unit,
) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val presets = FOCUS_PRESETS.map { ui.getString(it) }
    var custom by rememberSaveable { mutableStateOf("") }
    fun addCustom() {
        val area = custom.trim().take(60)
        if (area.isNotEmpty() && focus.none { it.equals(area, ignoreCase = true) }) onFocus(focus + area)
        custom = ""
    }
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ui.getString(R.string.goal_improve_focus), style = MaterialTheme.typography.titleSmall)
            Text(ui.getString(R.string.goal_improve_focus_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                (presets + focus.filter { it !in presets }).forEach { area ->
                    val on = area in focus
                    FilterChip(
                        selected = on,
                        onClick = { onFocus(if (on) focus - area else focus + area) },
                        label = { Text(area) },
                        leadingIcon = { AnimatedVisibility(on, enter = scaleIn(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                            exit = scaleOut(expressiveEffectsSpec()) + fadeOut(expressiveEffectsSpec())) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) } },
                    )
                }
            }
            OutlinedTextField(custom, { custom = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text(ui.getString(R.string.goal_improve_focus_custom)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addCustom() }),
                trailingIcon = { IconButton(onClick = { addCustom() }, enabled = custom.isNotBlank()) {
                    Icon(Icons.Outlined.Add, ui.getString(R.string.goal_improve_focus_add)) } })
            Text(ui.getString(R.string.goal_improve_list), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(list, { onList(it.take(GoalJudge.MAX_GOAL_CHARS)) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(ui.getString(R.string.goal_improve_list_placeholder)) },
                supportingText = {
                    val count = GoalJudge.improvementList(list).size
                    Text(if (count == 0) ui.getString(R.string.goal_improve_list_hint)
                        else ui.resources.getQuantityString(R.plurals.goal_improve_list_count, count, count))
                })
        }
    }
}

/** The improvement settings of a running or reached goal, saved without restarting it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GoalImprovements(goal: GoalState, onSave: (List<String>, List<String>) -> Unit) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable(goal.setAt) { mutableStateOf(goal.improveFocus) }
    var list by rememberSaveable(goal.setAt) { mutableStateOf(goal.improveQueue.joinToString("\n")) }
    // The loop drops each used item, so follow it while nothing is being edited.
    val savedList = goal.improveQueue.joinToString("\n")
    LaunchedEffect(savedList) { if (!open) list = savedList }
    val changed = focus != goal.improveFocus || GoalJudge.improvementList(list) != goal.improveQueue
    Column(Modifier.fillMaxWidth().animateContentSize(expressiveDefaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(scheme.tertiaryContainer, MaterialShapes.Cookie6Sided.toShape()),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = scheme.onTertiaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(ui.getString(R.string.goal_improve_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        goal.improveFocus.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        goal.improveQueue.size.takeIf { it > 0 }?.let { ui.resources.getQuantityString(R.plurals.goal_improve_left, it, it) },
                    ).joinToString(" · ").ifEmpty { ui.getString(R.string.goal_improve_any) },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val turn by animateFloatAsState(if (open) 180f else 0f, expressiveSpatialSpec(), label = "improve-chevron")
            Icon(Icons.Outlined.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn }, tint = scheme.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = expandVertically(expressiveDefaultSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
            exit = shrinkVertically(expressiveDefaultSpatialSpec()) + fadeOut(expressiveEffectsSpec())) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ImprovementEditor(focus, { focus = it }, list, { list = it })
                Button(onClick = { onSave(focus, GoalJudge.improvementList(list)); open = false }, enabled = changed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ui.getString(R.string.goal_improve_save)) }
            }
        }
    }
}
