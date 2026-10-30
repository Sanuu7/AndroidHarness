package com.androidharness.app.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
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
import com.androidharness.app.ui.common.formatDuration
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** Zero-height composer anchor. The popup overlays the transcript without reflowing it. */
@Composable
internal fun FloatingSubagentDock(sessionId: String?, manager: RunManager, onOpen: (String) -> Unit) {
    if (sessionId == null) return
    val source = remember(sessionId, manager) { manager.subagents.forSession(sessionId).map { list ->
        val latestTurn = list.lastOrNull()?.turnId
        list.filter { it.status.active || it.turnId == latestTurn }
    }.distinctUntilChanged() }
    val runs by source.collectAsStateWithLifecycle(initialValue = emptyList())
    var expanded by rememberSaveable(sessionId) { mutableStateOf(true) }
    var selectedId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var seenTurn by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    if (runs.isEmpty()) return
    val turn = runs.last().turnId
    LaunchedEffect(turn) { if (seenTurn != turn) { expanded = true; seenTurn = turn } }
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen) expanded = false }
    BackHandler(enabled = expanded) { expanded = false }
    val gap = with(density) { 6.dp.roundToPx() }
    val position = remember(gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
            ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(gap))
    } }
    Box(Modifier.fillMaxWidth().height(0.dp)) {
        Popup(popupPositionProvider = position, onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false, dismissOnBackPress = true,
                dismissOnClickOutside = false, clippingEnabled = true, usePlatformDefaultWidth = false)) {
            CompositionLocalProvider(LocalDensity provides density) {
            FloatingSubagentDeck(runs, expanded, { expanded = !expanded }, { id -> selectedId = id; onOpen(id) },
                onStop = { manager.subagents.stop(sessionId, it) },
                selectedId = selectedId, onSelected = { selectedId = it },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 12.dp))
            }
        }
    }
}

/** Only this small surface observes subagent progress; streaming never invalidates the main list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingSubagentDeck(
    runs: List<SubagentRun>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    selectedId: String? = null,
    onSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sessionId = runs.first().sessionId
    val pageCount by rememberUpdatedState(runs.size)
    // The anchor owns selection, outside the popup's separate Android window.
    // A fresh popup restores that id rather than an unrelated window's saved page.
    val pager = remember(sessionId) { PagerState(currentPage = runs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0), pageCount = { pageCount }) }
    val tabs = rememberLazyListState()
    LaunchedEffect(pager.currentPage, runs.map { it.id }) {
        val index = pager.currentPage.coerceIn(runs.indices)
        onSelected(runs[index].id)
        tabs.animateScrollToItem(index)
    }
    val rotation by animateFloatAsState(if (expanded) 0f else 180f, expressiveSpatialSpec(), label = "subagent deck chevron")
    val working = runs.count { it.status == SubagentStatus.RUNNING }
    val queued = runs.count { it.status == SubagentStatus.QUEUED }
    val summary = when {
        working > 0 -> stringResource(R.string.subagents_active_count, working)
        queued > 0 -> stringResource(R.string.subagents_queued_count, queued)
        else -> stringResource(R.string.subagents_all_finished)
    }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)), shadowElevation = 8.dp) {
        Column {
            Surface(onClick = onToggle, color = scheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (expanded) Icon(Icons.Outlined.AccountTree, null, Modifier.size(18.dp), tint = scheme.primary)
                    else DeckDot(working > 0 || queued > 0)
                    Text(if (expanded) stringResource(R.string.subagents_deck_title)
                        else pluralStringResource(R.plurals.subagents_count, runs.size, runs.size),
                        style = MaterialTheme.typography.labelLarge, modifier = if (expanded) Modifier else Modifier.weight(1f), maxLines = 1)
                    Text(if (expanded) runs.size.toString() else summary,
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (expanded) Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.KeyboardArrowDown,
                        stringResource(if (expanded) R.string.subagents_collapse else R.string.subagents_expand),
                        Modifier.size(24.dp).graphicsLayer { rotationZ = rotation }, tint = scheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(expanded,
                enter = expandVertically(expressiveSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(expressiveEffectsSpec()),
                exit = shrinkVertically(expressiveSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(expressiveEffectsSpec())) {
                Column {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val tabWidth = ((maxWidth - 36.dp) / minOf(3, runs.size)).coerceIn(76.dp, 124.dp)
                        LazyRow(state = tabs, contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            itemsIndexed(runs, key = { _, run -> run.id }) { index, run ->
                                val selected = pager.currentPage == index
                                Surface(onClick = { scope.launch { pager.animateScrollToPage(index, animationSpec = expressiveSpatialSpec()) } },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                                    contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                                    modifier = Modifier.width(tabWidth).semantics(mergeDescendants = true) {
                                        contentDescription = run.name
                                        this.selected = selected
                                        role = Role.Tab
                                    }) {
                                    Row(Modifier.padding(horizontal = 10.dp).heightIn(min = 40.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(run.name, style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        if (run.status == SubagentStatus.COMPLETED) Icon(Icons.Default.Check, null, Modifier.size(12.dp))
                                        else DeckDot(run.status.active, Modifier.size(5.dp))
                                    }
                                }
                            }
                        }
                    }
                    HorizontalPager(state = pager, key = { runs[it].id }, beyondViewportPageCount = 0,
                        verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) { index ->
                        DeckPreview(runs[index], index, runs.size, onOpen, onStop,
                            onPrevious = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + runs.size) % runs.size, animationSpec = expressiveSpatialSpec()) } },
                            onNext = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % runs.size, animationSpec = expressiveSpatialSpec()) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckPreview(run: SubagentRun, index: Int, count: Int, onOpen: (String) -> Unit,
    onStop: (String) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val height = (LocalConfiguration.current.screenHeightDp.dp * .52f).coerceIn(200.dp, 380.dp)
    val stackPager = LocalDensity.current.fontScale > 1.2f || LocalConfiguration.current.screenWidthDp < 350
    Column(Modifier.fillMaxWidth().heightIn(max = height).verticalScroll(rememberScrollState())
        .padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(36.dp).background(scheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(run.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(run.model, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(subagentStatusText(run.status), style = MaterialTheme.typography.labelSmall,
                color = if (run.status == SubagentStatus.FAILED) scheme.error else scheme.primary)
        }
        Text(run.prompt, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 10.dp))
        Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.subagents_latest_activity), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                DeckElapsed(run)
            }
            val steps = remember(run.steps) { run.steps.filterNot { it.startsWith("Task") || it.startsWith("Model:") }.takeLast(2) }
            if (steps.isEmpty()) {
                Text(stringResource(if (run.status == SubagentStatus.QUEUED) R.string.subagents_waiting_slot else R.string.subagents_starting),
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2)
            } else steps.forEachIndexed { position, line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (position < steps.lastIndex || run.status == SubagentStatus.COMPLETED)
                        Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = LocalStatusColors.current.success)
                    else DeckDot(run.status.active)
                    Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val preview = (if (run.status.active) run.preview else run.output).trim()
            if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onOpen(run.id) }, shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.subagents_open_chat), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(14.dp))
            }
            if (run.status.active) TextButton(onClick = { onStop(run.id) },
                contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.widthIn(min = 48.dp)) {
                Text(stringResource(R.string.subagents_stop), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            if (count > 1 && !stackPager) DeckPager(index, count, onPrevious, onNext)
        }
        if (count > 1 && stackPager) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) { DeckPager(index, count, onPrevious, onNext) }
    }
}

@Composable
private fun DeckPager(index: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.subagents_previous), Modifier.size(18.dp))
    }
    Text(stringResource(R.string.subagents_page_count, index + 1, count),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.subagents_next), Modifier.size(18.dp))
    }
}

@Composable
private fun DeckElapsed(run: SubagentRun) {
    val now by produceState(System.currentTimeMillis(), run.startedAt, run.finishedAt) {
        while (run.status.active) { value = System.currentTimeMillis(); delay(1000) }
    }
    val start = run.startedAt ?: return
    Text(stringResource(R.string.subagents_elapsed, formatDuration(((run.finishedAt ?: now) - start).coerceAtLeast(0))),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeckDot(active: Boolean, modifier: Modifier = Modifier.size(6.dp)) {
    Box(modifier.clip(RoundedCornerShape(50)).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
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
