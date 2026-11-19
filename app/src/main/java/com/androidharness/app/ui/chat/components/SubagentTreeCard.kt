package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.SubagentRun
import com.androidharness.app.agent.SubagentStatus
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.formatDuration
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.delay

/**
 * What a turn's subagents did, once all of them have stopped. Folded it is one line;
 * opened, the agents hang off the header as a tree whose branches draw themselves in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SubagentTreeCard(runs: List<SubagentRun>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (runs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(runs.first().id) { mutableStateOf(false) }
    val done = runs.count { it.status == SubagentStatus.COMPLETED }
    val title = pluralStringResource(R.plurals.subagents_finished_title, runs.size, runs.size)
    val summary = if (done == runs.size) stringResource(R.string.subagents_all_finished)
        else stringResource(R.string.subagents_finished_with_failures, done, runs.size - done)
    val corner by animateDpAsState(if (open) 24.dp else 18.dp, expressiveSpatialSpec(), label = "Subagent tree corner")
    val fill by animateColorAsState(if (open) scheme.surfaceContainer else scheme.surfaceContainerLow, expressiveEffectsSpec(),
        label = "Subagent tree fill")
    val turn by animateFloatAsState(if (open) 180f else 0f, expressiveSpatialSpec(), label = "Subagent tree chevron")
    val shape = RoundedCornerShape(corner)
    Surface(onClick = { open = !open }, shape = shape, color = fill,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics {
            contentDescription = title
            stateDescription = summary
        }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The root node: a cookie that turns a quarter while the tree is open.
                val spin by animateFloatAsState(if (open) 90f else 0f, expressiveDefaultSpatialSpec(), label = "Subagent tree root")
                Box(Modifier.size(ROOT).graphicsLayer { rotationZ = spin }
                    .background(scheme.primaryContainer, MaterialShapes.Cookie6Sided.toShape()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp).graphicsLayer { rotationZ = -spin }, tint = scheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (open) summary else runs.joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
                    tint = scheme.onSurfaceVariant)
            }
            AnimatedVisibility(open,
                enter = expandVertically(expressiveSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(expressiveEffectsSpec()),
                exit = shrinkVertically(expressiveEffectsSpec(), shrinkTowards = Alignment.Top) + fadeOut(expressiveEffectsSpec())) {
                Column(Modifier.padding(top = 6.dp)) {
                    runs.forEachIndexed { index, run ->
                        key(run.id) { SubagentTreeNode(run, index, last = index == runs.lastIndex, onOpen = { onOpen(run.id) }) }
                    }
                }
            }
        }
    }
}

/** One branch: an elbow from the trunk, a status node and the agent's result behind a tap. */
@Composable
private fun SubagentTreeNode(run: SubagentRun, index: Int, last: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showResult by rememberSaveable(run.id) { mutableStateOf(false) }
    // Branches grow in one after another, then their nodes pop onto the ends.
    val grow = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(6) * 70L)
        grow.animateTo(1f, expressiveEffectsSpec())
        pop.animateTo(1f, expressiveSpatialSpec())
    }
    val line = scheme.outlineVariant
    val density = LocalDensity.current
    val ok = run.status == SubagentStatus.COMPLETED
    val nodeColor = if (ok) LocalStatusColors.current.success else if (run.status == SubagentStatus.FAILED) scheme.error else scheme.onSurfaceVariant
    val took = run.startedAt?.let { start -> run.finishedAt?.let { end -> formatDuration(end - start) } }.orEmpty()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Trunk and elbow. The trunk stops at the last child so the tree reads closed.
        Canvas(Modifier.width(ROOT + 6.dp).fillMaxHeight()) {
            val x = ROOT.toPx() / 2
            val elbowY = with(density) { 22.dp.toPx() }
            val stroke = 1.5.dp.toPx()
            val trunkEnd = if (last) elbowY else size.height
            drawLine(line, Offset(x, 0f), Offset(x, trunkEnd * grow.value), stroke, StrokeCap.Round)
            if (grow.value > .4f) {
                val reach = ((grow.value - .4f) / .6f).coerceIn(0f, 1f)
                drawLine(line, Offset(x, elbowY), Offset(x + (size.width - x) * reach, elbowY), stroke, StrokeCap.Round)
            }
        }
        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp).graphicsLayer {
            alpha = pop.value.coerceIn(0f, 1f)
            translationX = (1f - pop.value) * 24f
        }) {
            Surface(onClick = { showResult = !showResult }, shape = RoundedCornerShape(if (showResult) 18.dp else 14.dp),
                color = if (showResult) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(20.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                        .background(nodeColor.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (ok) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(12.dp), tint = nodeColor)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(run.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(subagentStatusText(run.status), took.takeIf { it.isNotEmpty() }?.let {
                            stringResource(R.string.subagents_took, it) }).filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                    val chevron by animateFloatAsState(if (showResult) 180f else 0f, expressiveSpatialSpec(), label = "Subagent node chevron")
                    Icon(Icons.Default.KeyboardArrowDown,
                        stringResource(if (showResult) R.string.subagents_hide_result else R.string.subagents_show_result),
                        Modifier.size(18.dp).graphicsLayer { rotationZ = chevron }, tint = scheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(showResult,
                enter = expandVertically(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                exit = shrinkVertically(expressiveEffectsSpec()) + fadeOut(expressiveEffectsSpec())) {
                Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp)) {
                    val output = run.output.trim()
                    if (output.isBlank()) Text(stringResource(R.string.subagents_no_output), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                    else Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        MarkdownText(if (output.length > 4000) output.take(4000) + "…" else output)
                    }
                    TextButton(onClick = onOpen) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.subagents_open_chat))
                    }
                }
            }
        }
    }
}

private val ROOT = 32.dp
