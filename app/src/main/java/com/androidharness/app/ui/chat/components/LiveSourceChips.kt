package com.androidharness.app.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.ChatActivityPresentation
import kotlinx.coroutines.delay

/** How long the queue waits between two chips, so a batch lands one at a time. */
private const val CHIP_GAP_MS = 150L

private const val MAX_CHIPS = 8

/**
 * Sites already shown for a turn. The chips move from under the activity row
 * to under the streaming answer; a site that already jumped in once should
 * not jump again when it moves.
 */
private object ShownSourceChips {
    private val shown = HashMap<String, MutableSet<String>>()
    fun has(turn: String?, host: String) = turn != null && shown[turn]?.contains(host) == true
    fun add(turn: String?, host: String) { if (turn != null) shown.getOrPut(turn) { HashSet() } += host }
}

/**
 * The sites a live research turn has found, as small chips. They keep the
 * order they were found in, and new ones join a queue that lets one chip at a
 * time jump in, so a batch of ten results never lands as a single block. A
 * page that was actually read gets a check.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveSourceChips(
    sources: List<ChatActivityPresentation.Source>,
    turnId: String?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motion = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    // Arrival order, never re-sorted: a chip that moves after it lands looks like a glitch.
    val order = remember(turnId) { ArrayList<String>() }
    val byHost = remember(sources) { sources.associateBy { it.host } }
    sources.forEach { if (it.host !in order && order.size < MAX_CHIPS) order += it.host }
    var revealed by remember(turnId) {
        mutableIntStateOf(order.takeWhile { ShownSourceChips.has(turnId, it) || !motion }.size)
    }
    LaunchedEffect(order.size, turnId) {
        while (revealed < order.size) {
            if (motion && !ShownSourceChips.has(turnId, order[revealed])) delay(CHIP_GAP_MS)
            revealed++
        }
    }
    if (revealed == 0) return
    FlowRow(
        modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f))
            .padding(start = 2.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        order.take(revealed).forEach { host ->
            val source = byHost[host] ?: return@forEach
            key(host) {
                val jump = remember { !ShownSourceChips.has(turnId, host) && motion }
                SourceChip(source, jump, onOpenUrl)
                LaunchedEffect(Unit) { ShownSourceChips.add(turnId, host) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceChip(source: ChatActivityPresentation.Source, jump: Boolean, onOpenUrl: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val appear = remember { Animatable(if (jump) 0f else 1f) }
    if (jump) LaunchedEffect(Unit) {
        // Low damping so the chip overshoots and settles: a small hop, not a fade.
        appear.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
    }
    val (dotShape, dotColor) = siteBadge(source.host)
    Surface(
        onClick = { onOpenUrl(source.url) },
        shape = CircleShape,
        color = if (source.read) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        modifier = Modifier.height(30.dp).markPreviewOrigin().graphicsLayer {
            val p = appear.value
            alpha = p.coerceIn(0f, 1f)
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
            translationY = (1f - p) * 16.dp.toPx()
        },
    ) {
        Row(Modifier.padding(PaddingValues(start = 5.dp, end = 11.dp)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(20.dp).clip(dotShape).background(dotColor), contentAlignment = Alignment.Center) {
                if (source.read) Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = scheme.onPrimary)
                else Text(source.host.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
            }
            Text(source.host, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
                color = if (source.read) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        }
    }
}

/** A stable expressive shape and theme colour per site, so the same site always looks the same. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun siteBadge(host: String): Pair<androidx.compose.ui.graphics.Shape, Color> {
    val scheme = MaterialTheme.colorScheme
    val shapes = listOf(MaterialShapes.Cookie6Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny,
        MaterialShapes.Cookie9Sided, MaterialShapes.Puffy)
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    val hash = host.hashCode() and Int.MAX_VALUE
    return shapes[hash % shapes.size].toShape() to colors[(hash / shapes.size) % colors.size]
}
