@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.androidharness.app.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors

/**
 * Shared visual language for the coding feed: every tool row, receipt, question
 * and approval is built from these pieces so the whole transcript reads as one
 * designed system instead of a stack of unrelated boxes.
 *
 * - Cards are tonal (no hairlines), their corners and scale spring on press and
 *   on expand, so touching anything feels physical.
 * - State lives in a morphing Material shape badge: a spinning cookie while
 *   working, a calm circle when done, a squared tile when it failed, a clover
 *   while it waits on you. The shape changes through a real [Morph].
 */
internal enum class WorkState { Idle, Running, Waiting, Done, Failed }

private fun polygonFor(state: WorkState): RoundedPolygon = when (state) {
    WorkState.Running -> MaterialShapes.Cookie9Sided
    WorkState.Waiting -> MaterialShapes.Clover4Leaf
    WorkState.Done -> MaterialShapes.Circle
    WorkState.Failed -> MaterialShapes.Square
    WorkState.Idle -> MaterialShapes.Square
}

/** Springy spatial motion with a little overshoot, used for shape and size changes. */
internal fun <T> bouncySpec() = spring<T>(dampingRatio = 0.62f, stiffness = 520f)

private fun <T> settleSpec() = spring<T>(dampingRatio = 0.82f, stiffness = 650f)

private val morphCache = HashMap<Pair<RoundedPolygon, RoundedPolygon>, Morph>()

/** Morph matching is costly; a code chat can show dozens of badges, so each shape pair is built once. */
private fun morphBetween(from: RoundedPolygon, to: RoundedPolygon): Morph =
    morphCache.getOrPut(from to to) { Morph(from, to) }

/** An endless animation while [active]; a still value otherwise, so idle screens stop drawing. */
@Composable
private fun rememberLoop(active: Boolean, from: Float, to: Float, periodMs: Int, reverse: Boolean): androidx.compose.runtime.State<Float> =
    if (active) {
        rememberInfiniteTransition(label = "loop").animateFloat(
            from, to,
            infiniteRepeatable(tween(periodMs, easing = LinearEasing), if (reverse) RepeatMode.Reverse else RepeatMode.Restart),
            label = "loop value",
        )
    } else {
        remember(from) { androidx.compose.runtime.mutableFloatStateOf(if (reverse) to else from) }
    }

@Composable
internal fun workContainer(state: WorkState): Color {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        WorkState.Running -> scheme.primaryContainer
        WorkState.Waiting -> scheme.tertiaryContainer
        WorkState.Done -> scheme.secondaryContainer
        WorkState.Failed -> scheme.errorContainer
        WorkState.Idle -> scheme.surfaceContainerHighest
    }
}

@Composable
internal fun onWorkContainer(state: WorkState): Color {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        WorkState.Running -> scheme.onPrimaryContainer
        WorkState.Waiting -> scheme.onTertiaryContainer
        WorkState.Done -> scheme.onSecondaryContainer
        WorkState.Failed -> scheme.onErrorContainer
        WorkState.Idle -> scheme.onSurfaceVariant
    }
}

@Composable
internal fun MorphingStatusBadge(
    state: WorkState,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    showOutcome: Boolean = true,
) = MorphingStatusBadge(state, rememberVectorPainter(icon), modifier, size, showOutcome)

/**
 * Icon on a Material shape that morphs with [state]. Running spins and breathes;
 * finishing springs into the resting shape. A small pip at the corner confirms
 * success or failure without stealing the icon's spot.
 */
@Composable
internal fun MorphingStatusBadge(
    state: WorkState,
    painter: Painter,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    showOutcome: Boolean = true,
) {
    var from by remember { mutableStateOf(polygonFor(state)) }
    var to by remember { mutableStateOf(polygonFor(state)) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(state) {
        val target = polygonFor(state)
        if (target !== to) {
            from = to
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, bouncySpec())
        }
    }
    val morph = remember(from, to) { morphBetween(from, to) }
    val container by animateColorAsState(workContainer(state), settleSpec(), label = "badge container")
    val content by animateColorAsState(onWorkContainer(state), settleSpec(), label = "badge content")
    val moving = state == WorkState.Running || state == WorkState.Waiting
    // Endless motion only exists while the badge is actually working. A resting badge draws
    // once and stays still; otherwise every finished card on screen redraws every frame.
    val spinState = rememberLoop(moving, 0f, 360f, if (state == WorkState.Waiting) 9000 else 4200, reverse = false)
    val breatheState = rememberLoop(moving, 0.9f, 1f, 900, reverse = true)
    val spin by spinState
    val breathe by breatheState
    val entrance = if (moving) 1f else 0f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val pulse = 1f - (1f - breathe) * entrance
                    scaleX = pulse
                    scaleY = pulse
                }
                .drawWithCache {
                    val path = Path()
                    val matrix = Matrix()
                    onDrawBehind {
                        path.rewind()
                        morph.toPath(progress.value.coerceIn(-0.15f, 1.15f), path)
                        matrix.reset()
                        matrix.scale(this.size.width, this.size.height)
                        path.transform(matrix)
                        rotate(spin * entrance) { drawPath(path, container) }
                    }
                },
        )
        Icon(painter, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.45f))
        if (showOutcome && (state == WorkState.Done || state == WorkState.Failed)) {
            val pip = remember(state) { Animatable(0f) }
            LaunchedEffect(state) { pip.animateTo(1f, bouncySpec()) }
            val pipColor = if (state == WorkState.Done) LocalStatusColors.current.success else MaterialTheme.colorScheme.error
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.38f)
                    .graphicsLayer { scaleX = pip.value; scaleY = pip.value }
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                    .padding(2.dp)
                    .background(pipColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state == WorkState.Done) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(size * 0.22f),
                )
            }
        }
    }
}

/**
 * Tonal card for the coding feed. Corners open up when the card expands and the
 * whole surface squishes slightly while pressed, both on springs.
 */
@Composable
internal fun CodeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    accent: WorkState? = null,
    collapsedRadius: Dp = 22.dp,
    expandedRadius: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(
        when {
            pressed -> collapsedRadius + 8.dp
            expanded -> expandedRadius
            else -> collapsedRadius
        },
        bouncySpec(), label = "code card radius",
    )
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, bouncySpec(), label = "code card press")
    val color by animateColorAsState(
        when {
            accent == WorkState.Failed -> scheme.errorContainer.copy(alpha = 0.32f)
            accent == WorkState.Waiting -> scheme.surfaceContainerHigh
            expanded -> scheme.surfaceContainer
            else -> scheme.surfaceContainerLow
        },
        settleSpec(), label = "code card color",
    )
    val shape = RoundedCornerShape(radius)
    val layered = modifier
        .fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, interactionSource = interaction, modifier = layered) {
            Column(content = content)
        }
    } else {
        Surface(shape = shape, color = color, modifier = layered) { Column(content = content) }
    }
}

/** Round chevron button face that springs half a turn. */
@Composable
internal fun ExpandChevron(expanded: Boolean, contentDescription: String?, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, bouncySpec(), label = "chevron")
    val tone by animateColorAsState(
        if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        settleSpec(), label = "chevron tone",
    )
    Box(
        modifier.size(28.dp).background(tone, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown, contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/** Small tonal pill for counts and states. */
@Composable
internal fun StatusPill(text: String, state: WorkState, modifier: Modifier = Modifier) {
    val container by animateColorAsState(workContainer(state), settleSpec(), label = "pill container")
    val content by animateColorAsState(onWorkContainer(state), settleSpec(), label = "pill content")
    Box(modifier.background(container, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}

/**
 * One rounded segment per step, colored by outcome. Running segments glow.
 * Long histories collapse into proportional runs so the strip never overflows.
 */
@Composable
internal fun OutcomeStrip(outcomes: List<WorkState>, modifier: Modifier = Modifier) {
    if (outcomes.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val runs = remember(outcomes) {
        if (outcomes.size <= 28) outcomes.map { it to 1 }
        else buildList {
            outcomes.forEach { state ->
                val last = lastOrNull()
                if (last != null && last.first == state) set(size - 1, state to last.second + 1) else add(state to 1)
            }
        }
    }
    val glowState = rememberLoop(outcomes.any { it == WorkState.Running }, 0.45f, 1f, 700, reverse = true)
    val glow by glowState
    Row(modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        runs.forEach { (state, weight) ->
            val color = when (state) {
                WorkState.Done -> success.copy(alpha = 0.75f)
                WorkState.Failed -> scheme.error
                WorkState.Running -> scheme.primary
                WorkState.Waiting -> scheme.tertiary
                WorkState.Idle -> scheme.surfaceContainerHighest
            }
            Box(
                Modifier
                    .weight(weight.toFloat())
                    .height(6.dp)
                    .graphicsLayer { alpha = if (state == WorkState.Running) glow else 1f }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Terminal-style panel for arguments and output. A slim caption row names what
 * it holds; [prompt] prefixes the first line (for shell commands, "$").
 */
@Composable
internal fun CodePanel(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    prompt: String? = null,
    maxHeight: Dp = 260.dp,
    tone: WorkState? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (tone == WorkState.Failed) scheme.errorContainer.copy(alpha = 0.45f) else scheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (label != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Box(
                        Modifier.size(6.dp).background(
                            if (tone == WorkState.Failed) scheme.error else scheme.primary, CircleShape,
                        ),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = HarnessMono,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                if (prompt != null) "$prompt $text" else text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = HarnessMono,
                color = if (tone == WorkState.Failed) scheme.onErrorContainer else scheme.onSurface,
                modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Row scaffold shared by every collapsible coding card header. */
@Composable
internal fun CodeCardHeader(
    badge: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        badge()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (subtitleMono) HarnessMono else null,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
