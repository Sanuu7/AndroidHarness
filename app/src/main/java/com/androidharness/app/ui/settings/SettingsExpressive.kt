package com.androidharness.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.HarnessGlassMode
import com.androidharness.app.ui.theme.HarnessGlassSurface
import com.androidharness.app.ui.theme.HarnessGlassSwitch
import com.androidharness.app.ui.theme.LocalHarnessGlass
import com.androidharness.app.ui.theme.LocalStatusColors

/*
 * The Material 3 Expressive vocabulary of the settings screens, in one place.
 *
 * Settings opts into the expressive motion scheme and a rounder shape scale as a
 * whole (the rest of the app keeps its calmer theme), and every page is built from
 * the pieces below: tonal panels without outlines, segmented groups whose rows
 * round off under the finger, connected toggle groups for short choices, tonal
 * icon badges and switches that carry a check mark.
 */

/** Outer corner of a panel or group, and the small corner between two segments of one group. */
internal val SettingsOuterCorner = 24.dp
internal val SettingsInnerCorner = 6.dp
internal val SettingsSegmentGap = 2.dp

/** Rounder than the app scale, so stock cards, dialogs, sheets, menus and fields match the panels. */
private val SettingsShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The colour behind the panels, used to cut a gap between two rows of one panel. Unspecified when there is none. */
internal val LocalSettingsBackdrop = staticCompositionLocalOf { Color.Unspecified }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsExpressiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(motionScheme = MotionScheme.expressive(), shapes = SettingsShapes) {
        val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
        CompositionLocalProvider(
            LocalSettingsBackdrop provides if (glass) Color.Unspecified else MaterialTheme.colorScheme.surface,
            content = content,
        )
    }
}

/** Panels are tonal and borderless; true black needs a hairline because its containers are nearly black too. */
@Composable
internal fun settingsPanelBorder(): androidx.compose.foundation.BorderStroke? {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surfaceContainer.luminance() < 0.01f)
        androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)) else null
}

/** Shape of row [index] of [count] in a segmented group: big corners outside, small ones between rows. */
internal fun settingsSegmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) SettingsOuterCorner else SettingsInnerCorner
    val bottom = if (index == count - 1) SettingsOuterCorner else SettingsInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * One row of a segmented group. Pressing or selecting it morphs the small inner
 * corners out to the full radius, which is the expressive list behaviour.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsSegment(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val round = pressed || selected
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(if (round || index == 0) SettingsOuterCorner else SettingsInnerCorner, spec, label = "segmentTop")
    val bottom by animateDpAsState(if (round || index == count - 1) SettingsOuterCorner else SettingsInnerCorner, spec, label = "segmentBottom")
    // A spring can undershoot, and a corner size below zero is rejected.
    val t = top.coerceAtLeast(0.dp)
    val b = bottom.coerceAtLeast(0.dp)
    val shape = RoundedCornerShape(topStart = t, topEnd = t, bottomStart = b, bottomEnd = b)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else color,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "segmentFill",
    )
    val onFill = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val border = settingsPanelBorder()
    if (onClick != null) HarnessGlassSurface(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = fill, contentColor = onFill,
        border = border, interactionSource = interaction, content = content,
    ) else HarnessGlassSurface(
        modifier = modifier.fillMaxWidth(), shape = shape, color = fill, contentColor = onFill, border = border, content = content,
    )
}

internal class SettingsGroupScope {
    internal class Item(val onClick: (() -> Unit)?, val selected: Boolean, val modifier: Modifier, val content: @Composable () -> Unit)
    internal val items = mutableListOf<Item>()

    fun item(
        onClick: (() -> Unit)? = null,
        selected: Boolean = false,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        items += Item(onClick, selected, modifier, content)
    }
}

/** A segmented group: related rows as separate tonal surfaces, a hair apart, sharing one rounded silhouette. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val items = SettingsGroupScope().apply(content).items
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsSegmentGap)) {
        items.forEachIndexed { index, item ->
            SettingsSegment(index, items.size, item.modifier, item.onClick, item.selected, content = item.content)
        }
    }
}

/**
 * Separates two rows that share one panel. On a settings page it cuts a gap in
 * the page colour, so the panel reads as segments; anywhere else it is a hairline.
 */
@Composable
internal fun SettingsGap(modifier: Modifier = Modifier) {
    val backdrop = LocalSettingsBackdrop.current
    if (backdrop == Color.Unspecified || backdrop.alpha == 0f || settingsPanelBorder() != null)
        HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    else Box(Modifier.fillMaxWidth().height(SettingsSegmentGap).background(backdrop))
}

internal enum class SettingsTone { PRIMARY, SECONDARY, TERTIARY, NEUTRAL }

/** Tonal container behind a leading icon. */
@Composable
internal fun SettingsIconBadge(
    tone: SettingsTone = SettingsTone.PRIMARY,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    modifier: Modifier = Modifier,
    content: @Composable (tint: Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (fill, tint) = when (tone) {
        SettingsTone.PRIMARY -> scheme.primaryContainer to scheme.onPrimaryContainer
        SettingsTone.SECONDARY -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SettingsTone.TERTIARY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SettingsTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Box(modifier.size(size).background(fill, shape), contentAlignment = Alignment.Center) { content(tint) }
}

@Composable
internal fun SettingsIconBadge(
    icon: ImageVector,
    tone: SettingsTone = SettingsTone.PRIMARY,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    modifier: Modifier = Modifier,
) = SettingsIconBadge(tone, size, shape, modifier) { tint -> Icon(icon, null, Modifier.size(size * 0.5f), tint = tint) }

/** Icon badge, title, supporting line and a trailing slot: the standard content of a segment. */
@Composable
internal fun SettingsItemRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Short state next to a title, as a tonal pill. */
@Composable
internal fun SettingsStatusPill(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    val success = LocalStatusColors.current.success
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(if (ok) success.copy(alpha = 0.16f) else scheme.surfaceContainerHighest, CircleShape)) {
        Text(
            text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1,
            style = MaterialTheme.typography.labelMediumEmphasized, color = if (ok) success else scheme.onSurfaceVariant,
        )
    }
}

/**
 * A short single choice as a connected toggle group, the expressive successor of
 * the segmented button: the chosen option turns into a filled pill and the
 * neighbours keep the tight inner corners.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> SettingsChoiceGroup(
    options: List<Pair<T, String>>,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (value, label) ->
            val checked = selected(value)
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) onSelect(value) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                enabled = enabled,
                shapes = when {
                    options.size == 1 -> ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.MinHeight)
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The settings switch: the app's glass-aware switch with a check mark in the thumb while it is on. */
@Composable
internal fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) = HarnessGlassSwitch(
    checked, onCheckedChange, modifier,
    thumbContent ?: if (checked) ({ Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }) else null,
    enabled, colors, interactionSource,
)

/** The soft polygons a page hero cycles through, so neighbouring pages do not share a silhouette. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val heroPolygons = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Cookie6Sided, MaterialShapes.Sunny,
    MaterialShapes.Cookie12Sided, MaterialShapes.Flower, MaterialShapes.Pentagon, MaterialShapes.Clover8Leaf,
    MaterialShapes.Cookie7Sided, MaterialShapes.SoftBurst, MaterialShapes.Cookie4Sided, MaterialShapes.Gem,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun settingsHeroShape(seed: Int): Shape = heroPolygons[Math.floorMod(seed, heroPolygons.size)].toShape()

/** Hero badge of a page: a Material shape that springs in with a small turn when the page opens. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsHeroBadge(seed: Int, modifier: Modifier = Modifier, size: Dp = 64.dp, content: @Composable (tint: Color) -> Unit) {
    val entrance = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { entrance.animateTo(1f, spec) }
    Box(
        modifier.size(size).graphicsLayer {
            val p = entrance.value
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
            rotationZ = -40f * (1f - p)
        }.background(MaterialTheme.colorScheme.primaryContainer, settingsHeroShape(seed)),
        contentAlignment = Alignment.Center,
    ) {
        // The icon stays upright while the shape turns behind it.
        Box(Modifier.graphicsLayer { rotationZ = 40f * (1f - entrance.value) }) {
            content(MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
