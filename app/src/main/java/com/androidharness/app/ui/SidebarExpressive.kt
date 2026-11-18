@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.androidharness.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.HarnessGlassMode
import com.androidharness.app.ui.theme.LocalHarnessGlass
import com.androidharness.app.ui.theme.harnessGlass

internal data class SegmentOption(val label: String, val icon: ImageVector? = null)

/**
 * Segmented switch with one pill that slides to the chosen option. The leading
 * edge moves on a stiffer spring than the trailing edge, so the pill stretches
 * toward its target and settles, the way Material 3 Expressive indicators move.
 * The pill is drawn in the draw phase: animating it never recomposes the row.
 */
@Composable
internal fun SlidingSegmentedControl(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val count = options.size.coerceAtLeast(1)
    var previous by remember { mutableIntStateOf(selectedIndex) }
    val forward = selectedIndex >= previous
    LaunchedEffect(selectedIndex) { previous = selectedIndex }
    val fast = spring<Float>(dampingRatio = 0.7f, stiffness = 900f)
    val slow = spring<Float>(dampingRatio = 0.78f, stiffness = 320f)
    val start by animateFloatAsState(selectedIndex.toFloat(), if (forward) slow else fast, label = "segment start")
    val end by animateFloatAsState(selectedIndex + 1f, if (forward) fast else slow, label = "segment end")
    val pill = scheme.secondaryContainer
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .harnessGlass(scheme.surfaceContainerHigh, CircleShape)
            .padding(4.dp),
    ) {
        val narrow = maxWidth < 170.dp * LocalDensity.current.fontScale
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEachIndexed { index, option ->
                    val active = index == selectedIndex
                    val tone by animateColorAsState(if (active) pill else Color.Transparent, label = "segment stack")
                    SegmentLabel(option, active, enabled, Modifier.fillMaxWidth().height(height - 8.dp).background(tone, CircleShape)) {
                        if (!active) onSelect(index)
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(height - 8.dp)
                    .drawBehind {
                        val segment = size.width / count
                        val left = (if (rtl) count - end else start) * segment
                        val right = (if (rtl) count - start else end) * segment
                        drawRoundRect(
                            pill,
                            topLeft = Offset(left, 0f),
                            size = Size((right - left).coerceAtLeast(0f), size.height),
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                    },
            ) {
                options.forEachIndexed { index, option ->
                    val active = index == selectedIndex
                    SegmentLabel(option, active, enabled, Modifier.weight(1f).fillMaxHeight()) { if (!active) onSelect(index) }
                }
            }
        }
    }
}

@Composable
private fun SegmentLabel(option: SegmentOption, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content by animateColorAsState(
        if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant, tween(220), label = "segment content",
    )
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squish by animateFloatAsState(if (pressed) 0.94f else 1f, spring(0.6f, 700f), label = "segment press")
    Row(
        modifier
            .clip(CircleShape)
            .clickable(press, ripple(color = scheme.primary), enabled = enabled, onClick = onClick)
            .semantics { role = Role.Tab; selected = active }
            .graphicsLayer { scaleX = squish; scaleY = squish }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        option.icon?.let {
            Icon(it, null, Modifier.size(18.dp), tint = content)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            option.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Plays a short slide and fade whenever [key] changes; drives list swaps without recomposing per frame. */
@Composable
internal fun Modifier.swapEntrance(key: Any, direction: Int): Modifier {
    val reveal = remember { Animatable(1f) }
    var first by remember { mutableIntStateOf(1) }
    LaunchedEffect(key) {
        if (first == 1) { first = 0; return@LaunchedEffect }
        reveal.snapTo(0f)
        reveal.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f))
    }
    val shift = with(LocalDensity.current) { 28.dp.toPx() }
    return graphicsLayer {
        alpha = 0.2f + 0.8f * reveal.value
        translationX = (1f - reveal.value) * shift * direction
    }
}

/** Small spinning cookie used to mark running chats. */
@Composable
internal fun SpinningCookie(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val spin by rememberInfiniteTransition(label = "cookie").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "cookie spin",
    )
    Box(modifier.size(size).graphicsLayer { rotationZ = spin }.background(color, MaterialShapes.Cookie9Sided.toShape()))
}

/** Tonal tile for the sidebar tool grid; squishes on press and lights up when it is the open screen. */
@Composable
internal fun SidebarToolTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 26.dp else if (selected) 22.dp else 16.dp, spring(0.6f, 600f), label = "tile corner")
    val squish by animateFloatAsState(if (pressed) 0.95f else 1f, spring(0.6f, 700f), label = "tile press")
    val fill by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHighest, label = "tile fill")
    val shape = RoundedCornerShape(corner)
    Row(
        modifier
            .graphicsLayer { scaleX = squish; scaleY = squish }
            .clip(shape)
            .harnessGlass(fill, shape)
            .clickable(press, ripple(color = scheme.primary), onClick = onClick)
            .semantics { this.selected = selected }
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(30.dp).background(
                if (selected) scheme.primary else scheme.surfaceContainerLow,
                MaterialShapes.Cookie6Sided.toShape(),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Full-width pill for the sidebar's bottom destinations (Providers, Settings). */
@Composable
internal fun SidebarFooterPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squish by animateFloatAsState(if (pressed) 0.94f else 1f, spring(0.6f, 700f), label = "pill press")
    val fill by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh, label = "pill fill")
    Row(
        modifier
            .graphicsLayer { scaleX = squish; scaleY = squish }
            .clip(CircleShape)
            .harnessGlass(fill, CircleShape)
            .clickable(press, ripple(color = scheme.primary), onClick = onClick)
            .semantics { this.selected = selected }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface)
    }
}
