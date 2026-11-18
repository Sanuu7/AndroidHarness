package com.androidharness.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.LocalHarnessGlass
import kotlin.math.PI

/**
 * The sidebar's brand mark with an entrance that replays every time [opened] turns true.
 *
 * The tile spins up from a small sparkle cluster, the glyph morphs into the AH monogram
 * as it lands, a ring ripples out and a light sweep crosses the tile. Meanwhile the name
 * rises in letter by letter, each one arriving in the accent color and cooling to ink.
 * The whole thing is one 0..1 clock read only in the draw phase, so it never recomposes.
 */
@Composable
fun SidebarBrandLogo(opened: Boolean, size: Dp, modifier: Modifier = Modifier, description: String? = null, replay: Int = 0) {
    val clock = rememberBrandClock(opened, replay)
    val scheme = MaterialTheme.colorScheme
    val tile = scheme.onSurface
    val accent = scheme.primary
    val ink = scheme.surface.takeIf { it.alpha > 0f } ?: LocalHarnessGlass.current.surface
    Box(modifier.size(size).semantics { description?.let { contentDescription = it } }
        .drawBehind {
            // A ring that ripples out from the tile right as it lands.
            val r = phase(clock.value, 0.38f, 0.78f)
            if (r > 0f && r < 1f) {
                val grow = 1f + 0.55f * easeOut(r)
                val corner = this.size.minDimension * 0.28f * grow
                val s = Size(this.size.width * grow, this.size.height * grow)
                drawRoundRect(accent.copy(alpha = 0.55f * (1f - r)), Offset((this.size.width - s.width) / 2f, (this.size.height - s.height) / 2f),
                    s, CornerRadius(corner), style = Stroke(width = 2.dp.toPx() * (1f - r) + 0.5f))
            }
        }
        .graphicsLayer {
            val t = phase(clock.value, 0f, 0.5f)
            val e = backOut(t)
            scaleX = 0.25f + 0.75f * e
            scaleY = scaleX
            rotationZ = -140f * (1f - e)
            alpha = (t * 3f).coerceAtMost(1f)
        },
        contentAlignment = Alignment.Center) {
        val shape = remember { Path() }
        Canvas(Modifier.matchParentSize()) {
            val corner = CornerRadius(this.size.minDimension * 0.28f)
            // The tile warms through the accent color before settling to its monochrome look.
            val warm = phase(clock.value, 0.1f, 0.6f)
            drawRoundRect(lerp(accent, tile, easeOut(warm)), cornerRadius = corner)
            val s = phase(clock.value, 0.62f, 0.98f)
            if (s > 0f && s < 1f) {
                shape.reset()
                shape.addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, this.size.width, this.size.height, corner))
                clipPath(shape) {
                    val w = this.size.width
                    val x = -w + 3f * w * easeInOut(s)
                    rotate(25f) {
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                            startX = x, endX = x + w * 0.7f), Offset(x, -this.size.height), Size(w * 0.7f, this.size.height * 3f))
                    }
                }
            }
        }
        HarnessLogoMorph(LogoMorph.Discover, ink, progress = {
            1f - easeInOut(phase(clock.value, 0.15f, 0.62f))
        }, Modifier.size(size * 0.7f))
    }
}

/** The product name, rising in letter by letter whenever [opened] turns true. */
@Composable
fun SidebarBrandName(text: String, opened: Boolean, style: TextStyle, modifier: Modifier = Modifier, replay: Int = 0) {
    val clock = rememberBrandClock(opened, replay)
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    val accent = scheme.primary
    val resolved = style.copy(fontWeight = FontWeight.SemiBold, color = ink)
    val density = LocalDensity.current
    val height = with(density) { measurer.measure(text, resolved, maxLines = 1).size.height.toDp() }
    Box(modifier.fillMaxWidth().height(height).semantics { contentDescription = text }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            val layout = measurer.measure(text, resolved, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = this.size.width.toInt().coerceAtLeast(0)))
            val t = clock.value
            if (t >= 1f) { drawText(layout); return@drawWithContent }
            val n = text.length.coerceAtLeast(1)
            val rise = 14.dp.toPx()
            for (i in text.indices) {
                if (i >= layout.layoutInput.text.length) break
                val box = layout.getBoundingBox(i)
                if (box.width <= 0f) continue
                // Letters start one after another across the first 60% of the clock.
                val start = 0.18f + 0.42f * i / n
                val local = phase(t, start, start + 0.32f)
                if (local <= 0f) continue
                val e = backOut(local)
                val color = lerp(accent, ink, phase(t, start + 0.2f, start + 0.55f))
                clipRect(box.left - 1f, -rise, box.right + 1f, this.size.height + rise) {
                    translate(0f, rise * (1f - e)) {
                        scale(0.6f + 0.4f * e, box.center) {
                            drawText(layout, color = color, alpha = local.coerceIn(0f, 1f))
                        }
                    }
                }
            }
            // A soft shine passes over the finished word.
            val shine = phase(t, 0.7f, 1f)
            if (shine > 0f) {
                val w = this.size.width.coerceAtLeast(1f)
                val x = -w * 0.4f + w * 1.4f * easeInOut(shine)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.6f), Color.Transparent),
                    startX = x, endX = x + w * 0.4f), blendMode = BlendMode.SrcAtop)
            }
        }) {}
}

private const val BRAND_INTRO_MS = 1300

/** A 0..1 intro clock that restarts from zero each time [opened] turns true or [replay] changes. */
@Composable
private fun rememberBrandClock(opened: Boolean, replay: Int): Animatable<Float, *> {
    val context = LocalContext.current
    val motion = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true)
    }
    val clock = remember { Animatable(if (opened && motion) 0f else 1f) }
    LaunchedEffect(opened, replay, motion) {
        if (!motion) { clock.snapTo(1f); return@LaunchedEffect }
        // Closing leaves the mark as it is, so it never blinks out while the drawer slides away.
        if (opened) {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(BRAND_INTRO_MS, easing = LinearEasing))
        }
    }
    return clock
}

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
private fun easeInOut(t: Float) = (0.5f - 0.5f * kotlin.math.cos(PI.toFloat() * t))

/** Overshoots past 1 and settles back, the "pop" in the entrance. */
private fun backOut(t: Float): Float {
    val c = 1.9f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}
