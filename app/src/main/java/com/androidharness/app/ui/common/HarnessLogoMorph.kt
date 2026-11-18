package com.androidharness.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What the AH monogram turns into while it plays. */
enum class LogoMorph { Discover, Code }

/** Every outline the monogram can become; each is three filled polygons. */
enum class LogoShape { Monogram, Sparkles, Code, Eye, Magnifier, Pencil, Terminal, Lines }

/**
 * The AH monogram drawn as three polygons (A with the shared stem, the H's right
 * leg, the strap) that morph vertex by vertex into another three shapes and back.
 * Discover turns into a cluster of sparkles, Code into "</>".
 *
 * All geometry is resolved once per [morph]; each frame only lerps a few dozen
 * floats into reused [Path]s inside the draw phase, so animating [progress]
 * never recomposes.
 */
@Composable
fun HarnessLogoMorph(
    morph: LogoMorph,
    color: Color,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val target = if (morph == LogoMorph.Code) LogoShape.Code else LogoShape.Sparkles
    val slots = remember(morph) { slotsBetween(LogoShape.Monogram, target) }
    val paths = remember { List(slots.size) { Path() } }
    Canvas(modifier) {
        val p = progress().coerceIn(0f, 1f)
        val unit = size.minDimension / VIEWPORT
        val origin = Offset((size.width - VIEWPORT * unit) / 2f, (size.height - VIEWPORT * unit) / 2f)
        // A small dip mid-morph reads as the glyph "breathing" between shapes.
        val breathe = 1f - 0.08f * sin(PI.toFloat() * p)
        scale(breathe) {
            slots.forEachIndexed { i, slot ->
                slot.trace(paths[i], p, origin, unit, Tweak.NONE)
                drawPath(paths[i], color)
            }
        }
    }
}

/**
 * The monogram as a live activity glyph: whenever [shape] changes it springs from
 * whatever it currently shows into the new outline (overshooting a touch), then keeps
 * a small idle motion of its own: the eye scans and blinks, the magnifier circles, the
 * pencil scribbles, the terminal cursor blinks, the sparkles twinkle, the lines type.
 * With [animate] off it snaps between shapes and holds still.
 */
@Composable
fun HarnessActivityLogo(
    shape: LogoShape,
    color: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    var from by remember { mutableStateOf(shape) }
    var to by remember { mutableStateOf(shape) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(shape, animate) {
        if (shape == to) return@LaunchedEffect
        // Start from the outline on screen, so a quick second change never jumps.
        from = if (progress.value < 0.5f) from else to
        to = shape
        if (!animate) { progress.snapTo(1f); return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
    }
    val slots = remember(from, to) { slotsBetween(from, to) }
    val paths = remember { List(3) { Path() } }
    val clock: State<Float> = if (animate) rememberInfiniteTransition(label = "logo idle").animateFloat(
        0f, 1f, infiniteRepeatable(tween(IDLE_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart), label = "logo idle clock",
    ) else remember { mutableFloatStateOf(0f) }
    Canvas(modifier) {
        val p = progress.value
        val t = clock.value
        val unit = size.minDimension / VIEWPORT
        val origin = Offset((size.width - VIEWPORT * unit) / 2f, (size.height - VIEWPORT * unit) / 2f)
        // Idle motion belongs to the destination and fades in as the morph lands.
        val settle = p.coerceIn(0f, 1f)
        val squash = 1f - 0.1f * sin(PI.toFloat() * settle)
        val blink = if (animate && to == LogoShape.Eye) eyeBlink(t) else 1f
        scale(squash, squash * blink) {
            slots.forEachIndexed { i, slot ->
                val tweak = if (animate) idle(to, i, t).scaled(settle) else Tweak.NONE
                slot.trace(paths[i], p, origin, unit, tweak)
                drawPath(paths[i], color.copy(alpha = color.alpha * tweak.alpha))
            }
        }
    }
}

private const val VIEWPORT = 24f
private const val MIN_VERTICES = 12
private const val IDLE_PERIOD_MS = 2400

/** Per-slot idle offset in viewport units, a scale around the slot's own center, and opacity. */
private data class Tweak(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val alpha: Float = 1f) {
    fun scaled(k: Float) = Tweak(dx * k, dy * k, 1f + (scale - 1f) * k, 1f + (alpha - 1f) * k)
    companion object { val NONE = Tweak() }
}

private fun wave(t: Float, cycles: Float = 1f, phase: Float = 0f) = sin(2f * PI.toFloat() * (t * cycles + phase))

private fun eyeBlink(t: Float): Float {
    val d = abs(t - 0.9f)
    return if (d < 0.05f) 0.15f + 0.85f * (d / 0.05f) else 1f
}

private fun idle(shape: LogoShape, slot: Int, t: Float): Tweak = when (shape) {
    LogoShape.Monogram -> Tweak(scale = 1f + 0.03f * wave(t))
    LogoShape.Sparkles -> Tweak(scale = 1f + 0.16f * wave(t, phase = slot / 3f), alpha = 0.8f + 0.2f * wave(t, phase = slot / 3f))
    LogoShape.Code -> when (slot) {
        0 -> Tweak(dx = -0.9f * abs(wave(t)))
        1 -> Tweak(dx = 0.9f * abs(wave(t)))
        else -> Tweak(dy = 0.6f * wave(t, 2f))
    }
    // The pupil reads a line left to right, then the whole eye blinks.
    LogoShape.Eye -> if (slot == 2) Tweak(dx = 2.2f * wave(t, phase = 0.25f)) else Tweak.NONE
    LogoShape.Magnifier -> Tweak(dx = 1.2f * cos(2f * PI.toFloat() * t), dy = 1.2f * sin(2f * PI.toFloat() * t),
        alpha = if (slot == 2) 0.6f + 0.4f * wave(t, 2f) else 1f)
    LogoShape.Pencil -> Tweak(dx = 1.4f * wave(t, 2f), dy = 0.7f * wave(t, 4f, 0.25f))
    LogoShape.Terminal -> if (slot == 2) Tweak(alpha = if ((t * 3f) % 1f < 0.55f) 1f else 0.1f) else Tweak.NONE
    LogoShape.Lines -> Tweak(alpha = 0.55f + 0.45f * ((wave(t, phase = -slot / 4f) + 1f) / 2f), scale = 1f)
}

/** One morphing piece: matched vertex lists of equal length, flat x,y pairs. */
private class Slot(val from: FloatArray, val to: FloatArray) {
    val count = from.size / 2

    fun trace(path: Path, p: Float, origin: Offset, unit: Float, tweak: Tweak) {
        path.reset()
        var cx = 0f
        var cy = 0f
        if (tweak.scale != 1f) {
            for (v in 0 until count) {
                cx += from[v * 2] + (to[v * 2] - from[v * 2]) * p
                cy += from[v * 2 + 1] + (to[v * 2 + 1] - from[v * 2 + 1]) * p
            }
            cx /= count; cy /= count
        }
        for (v in 0 until count) {
            var x = from[v * 2] + (to[v * 2] - from[v * 2]) * p
            var y = from[v * 2 + 1] + (to[v * 2 + 1] - from[v * 2 + 1]) * p
            if (tweak.scale != 1f) { x = cx + (x - cx) * tweak.scale; y = cy + (y - cy) * tweak.scale }
            val px = origin.x + (x + tweak.dx) * unit
            val py = origin.y + (y + tweak.dy) * unit
            if (v == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }
}

// The monogram, same geometry as HarnessMark's PromptMark (24 unit viewport).
private val A_STEM = floatArrayOf(2f, 20.36f, 5.6f, 20.36f, 10.69f, 10.99f, 10.69f, 20.36f, 13.97f, 20.36f, 13.97f, 3.64f, 10.69f, 3.64f)
private val H_LEG = floatArrayOf(18.72f, 3.64f, 22f, 3.64f, 22f, 20.36f, 18.72f, 20.36f)
private val STRAP = floatArrayOf(4.55f, 15.44f, 5.92f, 12.82f, 22f, 12.82f, 22f, 15.44f)

// "</>"
private val LESS = floatArrayOf(1f, 12f, 6f, 5.5f, 8.2f, 7.3f, 4.4f, 12f, 8.2f, 16.7f, 6f, 18.5f)
private val GREATER = floatArrayOf(23f, 12f, 18f, 18.5f, 15.8f, 16.7f, 19.6f, 12f, 15.8f, 7.3f, 18f, 5.5f)
private val SLASH = floatArrayOf(13f, 4.5f, 15f, 4.5f, 11f, 19.5f, 9f, 19.5f)

private fun outline(shape: LogoShape): List<FloatArray> = when (shape) {
    LogoShape.Monogram -> listOf(A_STEM, H_LEG, STRAP)
    LogoShape.Code -> listOf(LESS, GREATER, SLASH)
    LogoShape.Sparkles -> listOf(
        sparkle(9.5f, 13.5f, 8.5f, 2.2f),
        sparkle(18.5f, 5.5f, 4f, 1.1f),
        sparkle(18.5f, 18.5f, 2.8f, 0.8f),
    )
    LogoShape.Eye -> listOf(lid(up = true), lid(up = false), circle(12f, 12f, 3.1f, 14))
    LogoShape.Magnifier -> listOf(
        ring(10f, 10f, 7.2f, 4.7f, 18),
        bar(14.6f, 14.6f, 21.2f, 21.2f, 1.7f),
        sparkle(7.8f, 7.8f, 1.9f, 0.55f),
    )
    LogoShape.Pencil -> {
        // Diagonal pencil from the lower-left tip to the upper-right eraser.
        val tipX = 2.6f; val tipY = 21.4f
        val dx = 0.7071f; val dy = -0.7071f
        val nx = 0.7071f; val ny = 0.7071f
        val w = 2.3f
        fun at(along: Float, side: Float) = floatArrayOf(tipX + dx * along + nx * side, tipY + dy * along + ny * side)
        val tip = at(0f, 0f) + at(5f, w) + at(5f, -w)
        val body = at(5.6f, w) + at(19.2f, w) + at(19.2f, -w) + at(5.6f, -w)
        val cap = at(20f, w) + at(23.2f, w) + at(23.2f, -w) + at(20f, -w)
        listOf(tip, body, cap)
    }
    LogoShape.Terminal -> listOf(
        floatArrayOf(2.5f, 6.2f, 4.6f, 4.3f, 11.6f, 11.5f, 4.6f, 18.7f, 2.5f, 16.8f, 7.6f, 11.5f),
        floatArrayOf(12.5f, 17.2f, 18f, 17.2f, 18f, 19.8f, 12.5f, 19.8f),
        floatArrayOf(19.3f, 11.6f, 21.6f, 11.6f, 21.6f, 19.8f, 19.3f, 19.8f),
    )
    LogoShape.Lines -> listOf(
        floatArrayOf(2.5f, 4.8f, 21.5f, 4.8f, 21.5f, 7.6f, 2.5f, 7.6f),
        floatArrayOf(2.5f, 10.6f, 21.5f, 10.6f, 21.5f, 13.4f, 2.5f, 13.4f),
        floatArrayOf(2.5f, 16.4f, 14.5f, 16.4f, 14.5f, 19.2f, 2.5f, 19.2f),
    )
}

private val slotCache = HashMap<Pair<LogoShape, LogoShape>, List<Slot>>()

private fun slotsBetween(from: LogoShape, to: LogoShape): List<Slot> = synchronized(slotCache) {
    slotCache.getOrPut(from to to) {
        outline(from).zip(outline(to)) { a, b ->
            val n = maxOf(MIN_VERTICES, a.size / 2, b.size / 2)
            val start = resample(clockwise(a), n)
            Slot(start, align(start, resample(clockwise(b), n)))
        }
    }
}

/** Four-point star with [outer] tips and [inner] waist, as a closed polygon. */
private fun sparkle(cx: Float, cy: Float, outer: Float, inner: Float): FloatArray {
    val out = FloatArray(16)
    for (i in 0 until 8) {
        val r = if (i % 2 == 0) outer else inner
        val angle = -PI / 2 + i * PI / 4
        out[i * 2] = cx + (r * cos(angle)).toFloat()
        out[i * 2 + 1] = cy + (r * sin(angle)).toFloat()
    }
    return out
}

private fun circle(cx: Float, cy: Float, r: Float, n: Int): FloatArray = FloatArray(n * 2) {
    val angle = 2 * PI * (it / 2) / n
    if (it % 2 == 0) cx + (r * cos(angle)).toFloat() else cy + (r * sin(angle)).toFloat()
}

/** A ring as one polygon: the outer circle one way, a hairline slit, the inner circle back. */
private fun ring(cx: Float, cy: Float, outer: Float, inner: Float, n: Int): FloatArray {
    val out = ArrayList<Float>((n + 1) * 4)
    for (i in 0..n) {
        val angle = PI / 4 + 2 * PI * i / n
        out += cx + (outer * cos(angle)).toFloat(); out += cy + (outer * sin(angle)).toFloat()
    }
    for (i in n downTo 0) {
        val angle = PI / 4 + 2 * PI * i / n
        out += cx + (inner * cos(angle)).toFloat(); out += cy + (inner * sin(angle)).toFloat()
    }
    return out.toFloatArray()
}

/** A thick straight segment from (x1, y1) to (x2, y2). */
private fun bar(x1: Float, y1: Float, x2: Float, y2: Float, half: Float): FloatArray {
    val len = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    val nx = -(y2 - y1) / len * half
    val ny = (x2 - x1) / len * half
    return floatArrayOf(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny)
}

/** An eyelid: a crescent band between two arcs bowing away from the eye's center line. */
private fun lid(up: Boolean): FloatArray {
    val sign = if (up) -1f else 1f
    val steps = 10
    val out = ArrayList<Float>((steps + 1) * 4)
    for (i in 0..steps) {
        val x = 1.5f + 21f * i / steps
        val bow = sin(PI.toFloat() * i / steps)
        out += x; out += 12f + sign * 7.6f * bow
    }
    for (i in steps downTo 0) {
        val x = 1.5f + 21f * i / steps
        val bow = sin(PI.toFloat() * i / steps)
        out += x; out += 12f + sign * 5.4f * bow
    }
    return out.toFloatArray()
}

/** Same winding everywhere, otherwise a morph between two shapes flips inside out. */
private fun clockwise(poly: FloatArray): FloatArray {
    val n = poly.size / 2
    var area = 0f
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
    }
    if (area >= 0f) return poly
    val out = FloatArray(poly.size)
    for (i in 0 until n) {
        out[i * 2] = poly[(n - 1 - i) * 2]
        out[i * 2 + 1] = poly[(n - 1 - i) * 2 + 1]
    }
    return out
}

/** Pads a polygon to [n] vertices by splitting its longest edge, so outlines stay put. */
private fun resample(poly: FloatArray, n: Int): FloatArray {
    val xs = ArrayList<Float>(n)
    val ys = ArrayList<Float>(n)
    for (i in 0 until poly.size / 2) { xs += poly[i * 2]; ys += poly[i * 2 + 1] }
    while (xs.size < n) {
        var best = 0
        var bestLen = -1f
        for (i in xs.indices) {
            val j = (i + 1) % xs.size
            val dx = xs[j] - xs[i]
            val dy = ys[j] - ys[i]
            val len = dx * dx + dy * dy
            if (len > bestLen) { bestLen = len; best = i }
        }
        val j = (best + 1) % xs.size
        xs.add(best + 1, (xs[best] + xs[j]) / 2f)
        ys.add(best + 1, (ys[best] + ys[j]) / 2f)
    }
    return FloatArray(n * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
}

/** Rotates [to]'s starting vertex so each point travels the shortest total distance from [from]. */
private fun align(from: FloatArray, to: FloatArray): FloatArray {
    val n = to.size / 2
    var bestShift = 0
    var bestCost = Float.MAX_VALUE
    for (shift in 0 until n) {
        var cost = 0f
        for (i in 0 until n) {
            val k = (i + shift) % n
            val dx = to[k * 2] - from[i * 2]
            val dy = to[k * 2 + 1] - from[i * 2 + 1]
            cost += dx * dx + dy * dy
        }
        if (cost < bestCost) { bestCost = cost; bestShift = shift }
    }
    return FloatArray(to.size) {
        val k = (it / 2 + bestShift) % n
        to[k * 2 + it % 2]
    }
}
