package com.androidharness.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.toIntSize
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

internal enum class HarnessGlassMode {
    OFF, FROSTED, LIQUID;

    companion object {
        fun resolve(enabled: Boolean, liquid: Boolean, sdk: Int): HarnessGlassMode = when {
            !enabled || sdk < 31 -> OFF
            liquid && sdk >= 33 -> LIQUID
            else -> FROSTED
        }
    }
}

@Immutable
internal data class HarnessGlassStyle(val mode: HarnessGlassMode = HarnessGlassMode.OFF, val surface: Color = Color.Black, val transparency: Float = .5f)

/** The midpoint preserves the original material; endpoints control tint, never foreground text. */
internal fun liquidGlassOpacity(base: Float, transparency: Float): Float {
    val level = if (transparency.isFinite()) transparency.coerceIn(0f, 1f) else .5f
    return if (level <= .5f) 1f + (base - 1f) * level * 2f else base * (1f - level) * 2f
}

internal val LocalHarnessGlass = staticCompositionLocalOf { HarnessGlassStyle() }
internal val LocalHarnessGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalHarnessGlassScene = staticCompositionLocalOf<HarnessGlassScene?> { null }
internal val LocalHarnessGlassOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalHarnessGlassChromeBackdrop = staticCompositionLocalOf<Backdrop?> { null }
/** A retained but hidden drawer must not keep the full-screen popup capture alive. */
internal val LocalHarnessGlassCaptureActive = staticCompositionLocalOf { true }

/** Screen coordinates also align a backdrop with a menu hosted in another Android window. */
internal class HarnessGlassScene(val layer: LayerBackdrop, private val demandDriven: Boolean = false) : Backdrop {
    var coordinates: LayoutCoordinates? by mutableStateOf(null)
    var background: Backdrop? by mutableStateOf(null)
    private var readers by mutableIntStateOf(0)
    val captureRequired: Boolean get() = !demandDriven || readers > 0

    fun retainCapture(): (() -> Unit)? {
        if (!demandDriven) return null
        readers++
        return { readers-- }
    }
    override val isCoordinatesDependent = true
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val source = this@HarnessGlassScene.coordinates ?: return
        val target = coordinates ?: return
        if (!source.isAttached || !target.isAttached) return
        val offset = runCatching { source.localToScreen(Offset.Zero) - target.localToScreen(Offset.Zero) }.getOrNull() ?: return
        if (!offset.x.isFinite() || !offset.y.isFinite()) return
        withTransform({ translate(offset.x, offset.y) }) { drawLayer(layer.graphicsLayer) }
    }
}

@Composable
internal fun rememberHarnessGlassScene(backgroundOnly: Boolean = false): HarnessGlassScene? {
    if (LocalHarnessGlass.current.mode == HarnessGlassMode.OFF) return null
    return rememberCaptureScene(backgroundOnly)
}

@Composable
private fun rememberCaptureScene(backgroundOnly: Boolean = false, demandDriven: Boolean = false): HarnessGlassScene {
    val layer = rememberLayerBackdrop()
    return remember(layer, backgroundOnly, demandDriven) {
        layer.graphicsLayer.compositingStrategy = if (backgroundOnly) androidx.compose.ui.graphics.layer.CompositingStrategy.Auto
            else androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        HarnessGlassScene(layer, demandDriven)
    }
}

/** Rasterize native content once. Every lens reuses the texture instead of replaying
 * WebView functors into additional Vulkan targets (which can crash the render thread).
 * Its own layer keeps parent redraws, such as every frame of the drawer animation, from
 * re-running this recording and re-rasterizing every WebView and table in the scene. */
internal fun Modifier.recordHarnessGlassScene(scene: HarnessGlassScene?, fadeTop: Float = 0f, fadeBottom: Float = 0f): Modifier =
    if (scene == null) this else graphicsLayer().onGloballyPositioned { scene.coordinates = it }.drawWithCache {
        val height = size.height.coerceAtLeast(1f)
        val start = (fadeTop / height).coerceIn(0f, .45f)
        val end = (1f - fadeBottom / height).coerceIn(.55f, 1f)
        val fade = fadeTop > 0f || fadeBottom > 0f
        val mask = if (fade) Brush.verticalGradient(0f to Color.Transparent, start to Color.Black,
            end to Color.Black, 1f to Color.Transparent) else null
        val paint = Paint()
        val topEdge = Rect(0f, 0f, size.width, height * start)
        val bottomEdge = Rect(0f, height * end, size.width, size.height)
        val edges = arrayOf(topEdge, bottomEdge)
        onDrawWithContent {
            if (scene.captureRequired) {
                scene.layer.graphicsLayer.record(size.toIntSize()) { this@onDrawWithContent.drawContent() }
                if (mask == null) drawLayer(scene.layer.graphicsLayer) else {
                    // Only the edge strips need alpha layers; the center draws directly.
                    // All three draws reuse the same rasterized, unmasked scene.
                    clipRect(top = topEdge.bottom, bottom = bottomEdge.top) { drawLayer(scene.layer.graphicsLayer) }
                    for (edge in edges) {
                        if (edge.height > 0f) clipRect(edge.left, edge.top, edge.right, edge.bottom) {
                            drawContext.canvas.saveLayer(edge, paint)
                            try {
                                drawLayer(scene.layer.graphicsLayer)
                                drawRect(mask, blendMode = BlendMode.DstIn)
                            } finally { drawContext.canvas.restore() }
                        }
                    }
                }
            } else drawContent()
        }
    }

/** Preserve the wallpaper while letting a quiet trace of the actual content show through. */
private class GlassContentBackdrop(val base: Backdrop?, val content: Backdrop, val strength: Float) : Backdrop {
    override val isCoordinatesDependent = true
    private val paint = Paint().apply { alpha = strength }
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        base?.let { with(it) { drawBackdrop(density, coordinates, layerBlock) } }
        if (strength >= 1f) with(content) { drawBackdrop(density, coordinates, layerBlock) }
        else drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            try { with(content) { drawBackdrop(density, coordinates, layerBlock) } } finally { canvas.restore() }
        }
    }
}

/** Track the actual popup material, including mixtures, rather than individual UI actions. */
private fun Backdrop.retainGlassCapture(): (() -> Unit)? = when (this) {
    is HarnessGlassScene -> retainCapture()
    is GlassContentBackdrop -> {
        val releaseBase = base?.retainGlassCapture()
        val releaseContent = content.retainGlassCapture()
        val release: () -> Unit = { releaseContent?.invoke(); releaseBase?.invoke() }
        release
    }
    else -> null
}

/** A menu's modifier is built while it is closed, but attached only while visible.
 * Match capture ownership to the rendered surface, including its exit animation. */
private data class GlassCaptureElement(val source: Backdrop, val enabled: Boolean) : ModifierNodeElement<GlassCaptureNode>() {
    override fun create() = GlassCaptureNode(source, enabled)
    override fun update(node: GlassCaptureNode) = node.update(source, enabled)
    override fun InspectorInfo.inspectableProperties() { name = "glassCapture" }
}

private class GlassCaptureNode(private var source: Backdrop, private var enabled: Boolean) : Modifier.Node() {
    private var release: (() -> Unit)? = null
    override fun onAttach() { if (enabled) release = source.retainGlassCapture() }
    override fun onDetach() { release?.invoke(); release = null }
    fun update(source: Backdrop, enabled: Boolean) {
        if (this.source === source && this.enabled == enabled) return
        release?.invoke(); release = null
        this.source = source
        this.enabled = enabled
        if (isAttached && enabled) release = source.retainGlassCapture()
    }
}

@Composable
internal fun rememberGlassContentBackdrop(base: Backdrop?, content: Backdrop?, strength: Float): Backdrop? =
    if (content == null) base else remember(base, content, strength) { GlassContentBackdrop(base, content, strength) }

/** Ambient wallpaper and popup captures stay separate, so glass never samples itself. */
@Composable
internal fun HarnessGlassRoot(style: HarnessGlassStyle, content: @Composable () -> Unit) {
    val backdrop = if (style.mode != HarnessGlassMode.OFF) rememberLayerBackdrop() else null
    val scene = if (backdrop != null) rememberCaptureScene(demandDriven = true) else null
    val overlay = rememberGlassContentBackdrop(if (style.mode == HarnessGlassMode.LIQUID) scene?.background ?: backdrop else backdrop,
        scene, if (style.mode == HarnessGlassMode.LIQUID) .26f else 1f)
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val secondary = colors.tertiary
    val surface = style.surface
    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).drawWithCache {
                val primaryGlow = Brush.radialGradient(listOf(accent.copy(alpha = .27f), Color.Transparent),
                    center = Offset(size.width * .12f, size.height * .12f), radius = size.maxDimension * .7f)
                val secondaryGlow = Brush.radialGradient(listOf(secondary.copy(alpha = .21f), Color.Transparent),
                    center = Offset(size.width * .95f, size.height * .7f), radius = size.maxDimension * .6f)
                onDrawBehind { drawRect(surface); drawRect(primaryGlow); drawRect(secondaryGlow) }
            })
        }
        CompositionLocalProvider(LocalHarnessGlass provides style,
            LocalHarnessGlassBackdrop provides backdrop,
            LocalHarnessGlassScene provides scene,
            LocalHarnessGlassOverlayBackdrop provides overlay) {
            content()
        }
    }
}

/** Blur/refraction affects a copy of the backdrop, never the text or input controls. */
@Composable
internal fun Modifier.harnessGlass(color: Color, shape: Shape = RoundedCornerShape(0.dp),
    backdrop: Backdrop? = LocalHarnessGlassBackdrop.current, overlay: Boolean = false,
    minimumOpacity: Float = 0f, blurRadius: Dp? = null, exportedBackdrop: LayerBackdrop? = null,
    centerOpacityFloor: Float = 0f): Modifier {
    val style = LocalHarnessGlass.current
    if (style.mode == HarnessGlassMode.OFF || backdrop == null) return background(color, shape)
    val captureActive = overlay || LocalHarnessGlassCaptureActive.current
    val outline = if (shape == RectangleShape) RoundedCornerShape(0.dp) else shape
    val liquid = style.mode == HarnessGlassMode.LIQUID
    val tint = if (color.alpha == 0f) style.surface else color.copy(alpha = 1f)
    // Retain enough of a colored control's fill for its semantic foreground color.
    val colored = kotlin.math.abs(tint.red - tint.green) + kotlin.math.abs(tint.green - tint.blue) > .16f
    val baseOpacity = if (colored) { if (tint.luminance() < .3f && style.surface.luminance() > .5f) .84f else if (liquid) .48f else .62f } else if (liquid) { if (overlay) .18f else .20f } else .54f
    val opacity = ((if (liquid) liquidGlassOpacity(baseOpacity, style.transparency) else baseOpacity) * (if (color.alpha > 0f) color.alpha else 1f)).coerceAtLeast(minimumOpacity)
    val centerOpacity = liquidGlassOpacity(.14f, style.transparency).coerceAtLeast(centerOpacityFloor)
    return then(GlassCaptureElement(backdrop, captureActive)).then(remember(backdrop, outline, liquid, tint, colored, opacity, centerOpacity, centerOpacityFloor,
        overlay, blurRadius, exportedBackdrop) {
        val fill = tint.copy(alpha = opacity)
        val centerTint = Brush.verticalGradient(
            0f to tint.copy(alpha = 0f), (if (centerOpacityFloor > 0f) .06f else .22f) to tint.copy(alpha = centerOpacity),
            (if (centerOpacityFloor > 0f) .94f else .78f) to tint.copy(alpha = centerOpacity), 1f to tint.copy(alpha = 0f))
        val gloss = Brush.verticalGradient(0f to Color.White.copy(alpha = .10f),
            .3f to Color.Transparent, .7f to Color.Transparent, 1f to Color.Black.copy(alpha = .06f))
        Modifier.drawBackdrop(
            backdrop = backdrop,
            exportedBackdrop = exportedBackdrop,
            shape = { outline },
            effects = {
                if (!liquid) blur((blurRadius ?: 20.dp).toPx())
                if (liquid && outline is CornerBasedShape) {
                    lens(minOf((if (overlay) 20.dp else 16.dp).toPx(), size.minDimension * .35f),
                        minOf(32.dp.toPx(), size.minDimension * .65f), depthEffect = true, chromaticAberration = size.minDimension <= 44.dp.toPx())
                }
            },
            highlight = { Highlight(width = if (liquid) 1.dp else .75f.dp, alpha = if (liquid) .85f else .3f,
                style = if (outline is CornerBasedShape) HighlightStyle.Default() else HighlightStyle.Plain()) },
            shadow = if (overlay) { { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = .16f)) } } else null,
            onDrawSurface = {
                drawRect(fill)
                if (liquid && (!colored || centerOpacityFloor > 0f)) {
                    // Keep the lens edges clear while giving foreground text a calmer backing.
                    drawRect(centerTint)
                }
                if (liquid) {
                    drawRect(gloss)
                }
            },
        ).clip(outline)
    })
}

@Composable
internal fun Modifier.harnessGlassBar(color: Color): Modifier =
    if (LocalHarnessGlass.current.mode == HarnessGlassMode.OFF) background(color)
    else harnessGlassChrome(color, RoundedCornerShape(28.dp))

@Composable
internal fun Modifier.harnessGlassChrome(color: Color, shape: Shape, centerOpacityFloor: Float = 0f): Modifier = harnessGlass(color, shape,
    backdrop = LocalHarnessGlassChromeBackdrop.current ?: LocalHarnessGlassBackdrop.current, overlay = true, centerOpacityFloor = centerOpacityFloor)

@Composable
internal fun Modifier.harnessGlassOverlay(shape: Shape, exportedBackdrop: LayerBackdrop? = null): Modifier = harnessGlass(LocalHarnessGlass.current.surface, shape,
    backdrop = LocalHarnessGlassOverlayBackdrop.current ?: LocalHarnessGlassBackdrop.current,
    overlay = true, minimumOpacity = if (LocalHarnessGlass.current.mode == HarnessGlassMode.LIQUID) .65f else .5f,
    blurRadius = 20.dp, exportedBackdrop = exportedBackdrop)

/** Small inline controls keep their original Material appearance outside the experiment. */
@Composable
internal fun Modifier.harnessGlassControl(selected: Boolean = false): Modifier =
    if (LocalHarnessGlass.current.mode == HarnessGlassMode.OFF) this
    else harnessGlass(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        RoundedCornerShape(24.dp))

@Composable
internal fun HarnessGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    chrome: Boolean = false,
    centerOpacityFloor: Float = 0f,
    exportedBackdrop: LayerBackdrop? = null,
    content: @Composable () -> Unit,
) {
    val active = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF && LocalHarnessGlassBackdrop.current != null
    Surface(modifier = if (active) modifier.harnessGlass(color, shape,
        backdrop = if (chrome) LocalHarnessGlassChromeBackdrop.current ?: LocalHarnessGlassBackdrop.current else LocalHarnessGlassBackdrop.current,
        overlay = chrome, centerOpacityFloor = centerOpacityFloor, exportedBackdrop = exportedBackdrop) else modifier, shape = shape,
        color = if (active) Color.Transparent else color, contentColor = contentColor,
        tonalElevation = if (active) 0.dp else tonalElevation, shadowElevation = shadowElevation, border = border, content = content)
}

@Composable
internal fun HarnessGlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    chrome: Boolean = false,
    content: @Composable () -> Unit,
) {
    val active = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF && LocalHarnessGlassBackdrop.current != null
    Surface(onClick = onClick, modifier = if (active) { if (chrome) modifier.harnessGlassChrome(color, shape) else modifier.harnessGlass(color, shape) } else modifier,
        enabled = enabled, shape = shape, color = if (active) Color.Transparent else color, contentColor = contentColor,
        tonalElevation = if (active) 0.dp else tonalElevation, shadowElevation = shadowElevation,
        border = border, interactionSource = interactionSource, content = content)
}
