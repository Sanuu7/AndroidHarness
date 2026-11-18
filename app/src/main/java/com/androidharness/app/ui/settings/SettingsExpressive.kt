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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.material3.toPath
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
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
    contentColor: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
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
    val onTarget = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        contentColor != Color.Unspecified -> contentColor
        else -> MaterialTheme.colorScheme.onSurface
    }
    val onFill by animateColorAsState(onTarget, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "segmentContent")
    val border = settingsPanelBorder()
    if (onClick != null) HarnessGlassSurface(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = fill, contentColor = onFill,
        border = border, interactionSource = interaction, content = content,
    ) else HarnessGlassSurface(
        modifier = modifier.fillMaxWidth(), shape = shape, color = fill, contentColor = onFill, border = border, content = content,
    )
}

internal class SettingsGroupScope {
    internal class Item(
        val onClick: (() -> Unit)?, val selected: Boolean, val modifier: Modifier,
        val color: Color, val contentColor: Color, val content: @Composable () -> Unit,
        val pressedContent: (@Composable (pressed: Boolean) -> Unit)? = null,
    )
    internal val items = mutableListOf<Item>()

    /**
     * [color] and [contentColor] tint a row that should stand out from its neighbours,
     * such as a call to action; leave them off for the ordinary tonal row.
     */
    fun item(
        onClick: (() -> Unit)? = null,
        selected: Boolean = false,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        content: @Composable () -> Unit,
    ) {
        items += Item(onClick, selected, modifier, color, contentColor, content)
    }

    /** A row whose content follows the finger, for shapes that react while the row is held. */
    fun pressItem(onClick: () -> Unit, selected: Boolean = false, content: @Composable (pressed: Boolean) -> Unit) {
        items += Item(onClick, selected, Modifier, Color.Unspecified, Color.Unspecified, {}, content)
    }
}

/** A segmented group: related rows as separate tonal surfaces, a hair apart, sharing one rounded silhouette. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val items = SettingsGroupScope().apply(content).items
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsSegmentGap)) {
        items.forEachIndexed { index, item ->
            val interaction = remember { MutableInteractionSource() }
            val pressedContent = item.pressedContent
            SettingsSegment(
                index, items.size, item.modifier, item.onClick, item.selected,
                color = if (item.color != Color.Unspecified) item.color else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = item.contentColor, interactionSource = interaction,
                content = if (pressedContent == null) item.content else ({
                    val pressed by interaction.collectIsPressedAsState()
                    pressedContent(pressed)
                }),
            )
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

/**
 * The wide palette for hero badges outside settings, where a project card or an
 * empty state can afford its own silhouette. Only rounded shapes belong here: the
 * spiky cookies, bursts and suns read as sharp stars at badge size.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val heroPolygons = listOf(
    MaterialShapes.Clover4Leaf, MaterialShapes.Cookie4Sided, MaterialShapes.Cookie6Sided, MaterialShapes.Flower,
    MaterialShapes.Puffy, MaterialShapes.PuffyDiamond, MaterialShapes.ClamShell, MaterialShapes.Clover8Leaf,
    MaterialShapes.Cookie7Sided, MaterialShapes.Bun, MaterialShapes.Ghostish, MaterialShapes.Oval,
)

/**
 * The settings screen keeps to six silhouettes, the rounded ones that read best at
 * badge size. The list still has twelve slots so every page keeps the shape it always
 * had: setup and language stay on the cookie, about on the six-sided cookie, sync and
 * FTP on the seven-sided cookie and the bun, updates on the clover, backup on the ghost.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val settingsPolygons = listOf(
    MaterialShapes.Clover4Leaf, MaterialShapes.Cookie4Sided, MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie4Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie7Sided, MaterialShapes.Bun, MaterialShapes.Cookie7Sided,
    MaterialShapes.Bun, MaterialShapes.Ghostish, MaterialShapes.Ghostish,
)

/** The shared hero silhouette, for badges outside the settings screen. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun settingsHeroShape(seed: Int): Shape = heroPolygons[Math.floorMod(seed, heroPolygons.size)].toShape()

/** The settings screen's shorter silhouette palette. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun settingsPageShape(seed: Int): Shape =
    settingsPolygons[Math.floorMod(seed, settingsPolygons.size)].toShape()

/** Hero badge of a page: a Material shape that springs in with a small turn when the page opens. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsHeroBadge(
    seed: Int,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    settingsScreen: Boolean = false,
    content: @Composable (tint: Color) -> Unit,
) {
    val entrance = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { entrance.animateTo(1f, spec) }
    val shape = if (settingsScreen) settingsPageShape(seed) else settingsHeroShape(seed)
    Box(
        modifier.size(size).graphicsLayer {
            val p = entrance.value
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
            rotationZ = -40f * (1f - p)
        }.background(MaterialTheme.colorScheme.primaryContainer, shape),
        contentAlignment = Alignment.Center,
    ) {
        // The icon stays upright while the shape turns behind it.
        Box(Modifier.graphicsLayer { rotationZ = 40f * (1f - entrance.value) }) {
            content(MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/**
 * A tonal badge resting in one of the page polygons. Under the finger it morphs into a
 * circle and turns a little, then springs back. The morph is drawn in the draw phase,
 * so a press never recomposes the row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsMorphBadge(
    seed: Int,
    pressed: Boolean,
    modifier: Modifier = Modifier,
    tone: SettingsTone = SettingsTone.PRIMARY,
    size: Dp = 44.dp,
    content: @Composable (tint: Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (fill, tint) = when (tone) {
        SettingsTone.PRIMARY -> scheme.primaryContainer to scheme.onPrimaryContainer
        SettingsTone.SECONDARY -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SettingsTone.TERTIARY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SettingsTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    val polygon = settingsPolygons[Math.floorMod(seed, settingsPolygons.size)]
    val morph = remember(polygon) { Morph(polygon, MaterialShapes.Circle) }
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(pressed) { progress.animateTo(if (pressed) 1f else 0f, spec) }
    Box(
        modifier.size(size).drawWithCache {
            val path = Path()
            val matrix = Matrix()
            onDrawBehind {
                // A spring may overshoot past either end; the morph only accepts 0..1.
                val p = progress.value
                morph.toPath(p.coerceIn(0f, 1f), path)
                matrix.reset()
                matrix.scale(this.size.width, this.size.height)
                path.transform(matrix)
                rotate(30f * p) { drawPath(path, fill) }
            }
        },
        contentAlignment = Alignment.Center,
    ) { content(tint) }
}

/**
 * Fades and lifts content in when it first appears, [order] steps after the first item,
 * so a list of groups arrives as a short cascade. Runs once per composition and only
 * touches the layer, never layout.
 */
internal fun Modifier.settingsEntrance(order: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(35L * order.coerceAtMost(8))
        progress.animateTo(1f, spec)
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 28.dp.toPx()
    }
}

/** The label over a group of rows, marked as a heading so screen readers can jump between groups. */
@Composable
internal fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier, top: Dp = 10.dp) {
    Text(
        text,
        modifier.padding(start = 16.dp, top = top, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One entry of the desktop settings rail. The selected entry fills in and rounds out to a
 * full pill; a press squeezes the corners the other way, so the rail answers the finger
 * the same way the segmented rows do.
 */
@Composable
internal fun SettingsRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        when { pressed -> 12.dp; selected -> 28.dp; else -> 20.dp },
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "railCorner",
    )
    val fill by animateColorAsState(
        if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "railFill",
    )
    val onFill by animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "railContent",
    )
    HarnessGlassSurface(
        onClick = onClick, color = fill, contentColor = onFill, interactionSource = interaction,
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp).semantics { this.selected = selected },
    ) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Column(Modifier.weight(1f), content = content)
        }
    }
}

/**
 * One row that opens a page: a morphing badge, the title, optional supporting lines and a
 * chevron. Used by the settings home and the Coding hub so both feel the same.
 */
internal fun SettingsGroupScope.navItem(
    seed: Int,
    tone: SettingsTone,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    selected: Boolean = false,
    path: String? = null,
    extra: @Composable () -> Unit = {},
    icon: @Composable (tint: Color) -> Unit,
) = pressItem(onClick, selected) { pressed ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsMorphBadge(seed, pressed, tone = tone, content = icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (path != null) Text(path, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                extra()
            }
            val nudge = remember { Animatable(0f) }
            val toward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
            val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            LaunchedEffect(pressed) { nudge.animateTo(if (pressed) 1f else 0f, spec) }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                Modifier.size(22.dp).graphicsLayer { translationX = toward * nudge.value * 4.dp.toPx() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
