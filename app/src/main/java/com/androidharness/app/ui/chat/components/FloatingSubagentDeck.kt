package com.androidharness.app.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.agent.RunManager
import com.androidharness.app.agent.SubagentRun
import com.androidharness.app.agent.SubagentStatus
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The anchor belongs ABOVE all composer accessories, not immediately above the text field. */
@Composable
internal fun FloatingSubagentDock(
    sessionId: String?,
    manager: RunManager,
    onOpen: (String) -> Unit,
    composerText: String = "",
) {
    if (sessionId == null) return
    val source = remember(sessionId, manager) { manager.subagents.forSession(sessionId).map { list ->
        val latestTurn = list.lastOrNull()?.turnId
        list.filter { it.status.active || it.turnId == latestTurn }
    }.distinctUntilChanged() }
    val runs by source.collectAsStateWithLifecycle(initialValue = emptyList())
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var selectedId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var seenTurn by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var previousText by remember(sessionId) { mutableStateOf(composerText) }
    var anchorTop by remember { mutableIntStateOf(0) }
    if (runs.isEmpty()) return
    val turn = runs.last().turnId
    fun collapse() { expanded = false; selectedId = null }
    // New work never steals space or opens a panel the user has just dismissed.
    LaunchedEffect(turn) { if (seenTurn != turn) { collapse(); seenTurn = turn } }
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen) collapse() }
    LaunchedEffect(composerText) {
        if (previousText != composerText) collapse()
        previousText = composerText
    }
    BackHandler(enabled = expanded) { collapse() }
    val gap = with(density) { 16.dp.roundToPx() }
    val edge = with(density) { 16.dp.roundToPx() }
    val position = remember(gap, edge) { SubagentBubblePositionProvider(gap, edge) }
    val panelHeight = with(density) { anchorTop.toDp() - 16.dp - 48.dp - 12.dp - 8.dp }.coerceAtLeast(0.dp)
    Box(Modifier.fillMaxWidth().height(0.dp).onGloballyPositioned { anchorTop = it.positionInWindow().y.toInt() }) {
        // IME panning can move the text field independently of this popup window.
        // Restore the bubble after typing, rather than drawing over the input controls.
        if (!keyboardOpen && anchorTop >= with(density) { 64.dp.roundToPx() }) {
            Popup(popupPositionProvider = position, onDismissRequest = { collapse() },
                properties = PopupProperties(focusable = false, dismissOnBackPress = true,
                    dismissOnClickOutside = expanded, clippingEnabled = true, usePlatformDefaultWidth = false)) {
                CompositionLocalProvider(LocalDensity provides density) {
                    FloatingSubagentDeck(runs, expanded, { if (expanded) collapse() else expanded = true },
                        onOpen = onOpen, onStop = { manager.subagents.stop(sessionId, it) },
                        selectedId = selectedId, onSelected = { selectedId = it }, maxPanelHeight = panelHeight)
                }
            }
        }
    }
}

/** Keep the bubble's bottom and right edge fixed while the panel expands upward. */
internal class SubagentBubblePositionProvider(private val gap: Int, private val edge: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.right - edge - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0),
    )
}

/** Collapsed popup window is exactly 48dp square: no invisible full-width touch blocker. */
@Composable
internal fun FloatingSubagentDeck(
    runs: List<SubagentRun>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    selectedId: String? = null,
    onSelected: (String?) -> Unit = {},
    maxPanelHeight: Dp = 380.dp,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val working = runs.count { it.status == SubagentStatus.RUNNING }
    val queued = runs.count { it.status == SubagentStatus.QUEUED }
    val active = working + queued
    val summary = when {
        working > 0 -> stringResource(R.string.subagents_running_count, working)
        queued > 0 -> stringResource(R.string.subagents_queued_count, queued)
        else -> stringResource(R.string.subagents_all_finished)
    }
    val selected = runs.firstOrNull { it.id == selectedId }
    val width = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceIn(0.dp, 320.dp)
    Column(modifier, horizontalAlignment = AbsoluteAlignment.Right) {
        AnimatedVisibility(expanded && maxPanelHeight >= 80.dp,
            enter = expandVertically(tween(180), expandFrom = Alignment.Bottom) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(150), shrinkTowards = Alignment.Bottom) + fadeOut(tween(150))) {
            Column(horizontalAlignment = AbsoluteAlignment.Right) {
                Surface(Modifier.width(width).heightIn(max = maxPanelHeight), shape = RoundedCornerShape(22.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)), shadowElevation = 9.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 7.dp, end = 7.dp, top = 5.dp, bottom = 9.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 45.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selected != null) IconButton(onClick = { onSelected(null) }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.subagents_back_to_list), Modifier.size(19.dp))
                            } else Spacer(Modifier.width(8.dp))
                            Text(selected?.name ?: stringResource(R.string.subagents_deck_title),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (selected == null) Text(summary, style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                            IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Default.Remove, stringResource(R.string.subagents_collapse), Modifier.size(19.dp), tint = scheme.onSurfaceVariant)
                            }
                        }
                        if (selected == null) runs.forEach { run ->
                            SubagentBubbleRow(run, onClick = { onSelected(run.id) })
                        } else {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val preview = (if (selected.status.active) selected.preview else selected.output).trim()
                                Text(preview.ifBlank { selected.prompt }, style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    SubagentBubbleStatus(selected.status)
                                    Text(if (selected.status.active) stringResource(R.string.subagents_running_background)
                                        else subagentStatusText(selected.status), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                }
                                // Keep full conversations and cancellation available inside the requested details view.
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onOpen(selected.id) }) { Text(stringResource(R.string.subagents_open_chat)) }
                                    Spacer(Modifier.weight(1f))
                                    if (selected.status.active) TextButton(onClick = { onStop(selected.id) }) {
                                        Text(stringResource(R.string.subagents_stop))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        val label = stringResource(if (expanded) R.string.subagents_collapse else R.string.subagents_expand)
        Box(Modifier.size(48.dp).semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = summary
        }.clickable(interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = false, radius = 24.dp), role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.size(44.dp), shape = CircleShape, color = scheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)), shadowElevation = 3.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AccountTree, null, Modifier.size(21.dp), tint = scheme.primary)
                }
            }
            Box(Modifier.align(AbsoluteAlignment.TopRight).size(18.dp).background(scheme.primary, CircleShape)
                .border(2.dp, scheme.surface, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text((if (active > 0) active else runs.size).let { if (it > 99) "99+" else it.toString() },
                    color = scheme.onPrimary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SubagentBubbleRow(run: SubagentRun, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val activity = run.steps.lastOrNull { !it.startsWith("Task") && !it.startsWith("Model:") }
        ?: if (run.status.active) run.prompt else subagentStatusText(run.status)
    Surface(onClick = onClick, color = scheme.surfaceContainerLow, shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(31.dp).background(scheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = scheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(run.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(activity, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SubagentBubbleStatus(run.status)
        }
    }
}

@Composable
private fun SubagentBubbleStatus(status: SubagentStatus) {
    val color = when (status) {
        SubagentStatus.RUNNING, SubagentStatus.COMPLETED -> LocalStatusColors.current.success
        SubagentStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (status == SubagentStatus.COMPLETED) Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = color)
    else Box(Modifier.size(6.dp).background(color, CircleShape))
}

@Composable
internal fun subagentStatusText(status: SubagentStatus): String = stringResource(when (status) {
    SubagentStatus.QUEUED -> R.string.subagents_queued
    SubagentStatus.RUNNING -> R.string.subagents_working
    SubagentStatus.COMPLETED -> R.string.subagents_completed
    SubagentStatus.FAILED -> R.string.subagents_failed
    SubagentStatus.STOPPED -> R.string.subagents_stopped
    SubagentStatus.INTERRUPTED -> R.string.subagents_interrupted
})
