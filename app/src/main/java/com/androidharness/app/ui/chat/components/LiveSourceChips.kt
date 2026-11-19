package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.ChatActivityPresentation
import kotlinx.coroutines.delay

/** Time each site stays on screen before the next one takes its place. */
private const val STEP_MS = 650L

/** Fastest the ticker goes when a big batch is waiting, so it never falls far behind. */
private const val FAST_STEP_MS = 280L

/** Soft ease-out: things glide in and settle, with no bounce. */
private val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Ease-in for leaving, so the old site drifts off before the new one lands. */
private val Leave = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private fun <T> settle(ms: Int, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, Settle)
private fun <T> leave(ms: Int): FiniteAnimationSpec<T> = tween(ms, 0, Leave)

/**
 * How far a turn's ticker got. The ticker moves from under the activity row
 * to under the streaming answer; the new spot picks up where the old one
 * stopped instead of counting from zero again.
 */
private object ShownSources {
    class Turn {
        val counted = LinkedHashSet<String>()
        val readShown = HashSet<String>()
        var current: String? = null
    }
    private val turns = HashMap<String, Turn>()
    fun of(turn: String?): Turn = if (turn == null) Turn() else turns.getOrPut(turn) { Turn() }
}

/**
 * The sites a live research turn has found, one at a time. A single chip
 * shows the site the search just found and gently gives way to the next,
 * while a counter beside it ticks up by one for every new site. When a page
 * is actually read, its site comes back once with a check.
 */
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
    val memory = remember(turnId) { ShownSources.of(turnId) }
    var count by remember(turnId) { mutableIntStateOf(memory.counted.size) }
    var current by remember(turnId) { mutableStateOf(memory.current) }
    val latest by rememberUpdatedState(sources)
    LaunchedEffect(turnId, motion) {
        snapshotFlow { latest }.collect {
            while (true) {
                val list = latest
                // Read while it is on screen: the chip just turns, it does not need to come back later.
                if (list.any { it.host == memory.current && it.read }) memory.current?.let { memory.readShown += it }
                val fresh = list.firstOrNull { it.host !in memory.counted }
                val read = if (fresh != null) null
                    else list.firstOrNull { it.read && it.host !in memory.readShown && it.host != memory.current }
                val next = fresh ?: read ?: break
                if (motion && memory.current != null) {
                    val waiting = list.count { it.host !in memory.counted }
                    delay(if (waiting > 4) FAST_STEP_MS else STEP_MS)
                }
                if (next.read) memory.readShown += next.host
                if (fresh != null) memory.counted += next.host
                memory.current = next.host
                count = memory.counted.size
                current = next.host
            }
        }
    }
    val source = current?.let { host -> sources.firstOrNull { it.host == host } } ?: return
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedContent(
            source,
            contentKey = { it.host },
            transitionSpec = { siteSwap() },
            contentAlignment = Alignment.CenterStart,
            label = "liveSource",
        ) { shown -> SourceChip(shown, onOpenUrl) }
        SiteCounter(count)
    }
}

/** The old site drifts up and fades while the new one rises in under it. */
private fun androidx.compose.animation.AnimatedContentTransitionScope<ChatActivityPresentation.Source>.siteSwap(): ContentTransform =
    (fadeIn(settle(420, delay = 90)) +
        slideInVertically(settle(520, delay = 60)) { it / 2 } +
        scaleIn(settle(520, delay = 60), initialScale = 0.94f)) togetherWith
        (fadeOut(leave(200)) + slideOutVertically(leave(260)) { -it / 3 }) using
        SizeTransform(clip = false) { _, _ -> settle(420) }

/** "3 sites", where only the digits that change roll up to the next number. */
@Composable
private fun SiteCounter(count: Int) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(count > 1,
        enter = fadeIn(settle(400)) + expandHorizontally(settle(420), expandFrom = Alignment.Start)) {
        Row(Modifier.semantics(mergeDescendants = false) { contentDescription = "$count sites" },
            verticalAlignment = Alignment.CenterVertically) {
            val digits = count.toString()
            digits.forEachIndexed { index, digit ->
                // Keyed by place value, so the ones digit stays the ones digit when a tens digit appears.
                androidx.compose.runtime.key(digits.length - index) {
                    AnimatedContent(
                        digit,
                        transitionSpec = {
                            (fadeIn(settle(360, delay = 60)) + slideInVertically(settle(420)) { it }) togetherWith
                                (fadeOut(leave(180)) + slideOutVertically(leave(240)) { -it }) using SizeTransform(clip = true)
                        },
                        label = "siteCountDigit",
                    ) { d ->
                        Text(d.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Text(" sites", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceChip(source: ChatActivityPresentation.Source, onOpenUrl: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (dotShape, dotColor) = siteBadge(source.host)
    val container by animateColorAsState(if (source.read) scheme.secondaryContainer else scheme.surfaceContainerHigh, settle(400), label = "chipColor")
    val label by animateColorAsState(if (source.read) scheme.onSecondaryContainer else scheme.onSurfaceVariant, settle(400), label = "chipLabel")
    Surface(
        onClick = { onOpenUrl(source.url) },
        shape = CircleShape,
        color = container,
        modifier = Modifier.height(30.dp).markPreviewOrigin(),
    ) {
        Row(Modifier.padding(PaddingValues(start = 5.dp, end = 11.dp)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(20.dp).clip(dotShape).background(dotColor), contentAlignment = Alignment.Center) {
                AnimatedContent(source.read, transitionSpec = { fadeIn(settle(300)) togetherWith fadeOut(leave(150)) }, label = "chipBadge") { read ->
                    if (read) Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = scheme.onPrimary)
                    else Text(source.host.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
                }
            }
            Text(source.host, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp), color = label)
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
