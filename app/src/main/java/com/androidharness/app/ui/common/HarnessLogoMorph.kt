package com.androidharness.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the AH monogram turns into while it plays. */
enum class LogoMorph { Discover, Code }

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
    val slots = remember(morph) { buildSlots(morph) }
    val paths = remember { List(slots.size) { Path() } }
    Canvas(modifier) {
        val p = progress().coerceIn(0f, 1f)
        val unit = size.minDimension / VIEWPORT
        val origin = Offset((size.width - VIEWPORT * unit) / 2f, (size.height - VIEWPORT * unit) / 2f)
        // A small dip mid-morph reads as the glyph "breathing" between shapes.
        val breathe = 1f - 0.08f * sin(PI.toFloat() * p)
        scale(breathe) {
            slots.forEachIndexed { i, slot ->
                val path = paths[i]
                path.reset()
                for (v in 0 until slot.count) {
                    val x = slot.from[v * 2] + (slot.to[v * 2] - slot.from[v * 2]) * p
                    val y = slot.from[v * 2 + 1] + (slot.to[v * 2 + 1] - slot.from[v * 2 + 1]) * p
                    val px = origin.x + x * unit
                    val py = origin.y + y * unit
                    if (v == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, color)
            }
        }
    }
}

private const val VIEWPORT = 24f
private const val VERTICES = 12

/** One morphing piece: matched vertex lists of equal length, flat x,y pairs. */
private class Slot(val from: FloatArray, val to: FloatArray) {
    val count = from.size / 2
}

// The monogram, same geometry as HarnessMark's PromptMark (24 unit viewport).
private val A_STEM = floatArrayOf(2f, 20.36f, 5.6f, 20.36f, 10.69f, 10.99f, 10.69f, 20.36f, 13.97f, 20.36f, 13.97f, 3.64f, 10.69f, 3.64f)
private val H_LEG = floatArrayOf(18.72f, 3.64f, 22f, 3.64f, 22f, 20.36f, 18.72f, 20.36f)
private val STRAP = floatArrayOf(4.55f, 15.44f, 5.92f, 12.82f, 22f, 12.82f, 22f, 15.44f)

// "</>"
private val LESS = floatArrayOf(1f, 12f, 6f, 5.5f, 8.2f, 7.3f, 4.4f, 12f, 8.2f, 16.7f, 6f, 18.5f)
private val GREATER = floatArrayOf(23f, 12f, 18f, 18.5f, 15.8f, 16.7f, 19.6f, 12f, 15.8f, 7.3f, 18f, 5.5f)
private val SLASH = floatArrayOf(13f, 4.5f, 15f, 4.5f, 11f, 19.5f, 9f, 19.5f)

private fun buildSlots(morph: LogoMorph): List<Slot> {
    val targets = when (morph) {
        LogoMorph.Code -> listOf(LESS, GREATER, SLASH)
        LogoMorph.Discover -> listOf(
            sparkle(9.5f, 13.5f, 8.5f, 2.2f),
            sparkle(18.5f, 5.5f, 4f, 1.1f),
            sparkle(18.5f, 18.5f, 2.8f, 0.8f),
        )
    }
    return listOf(A_STEM, H_LEG, STRAP).zip(targets) { from, to ->
        val a = resample(clockwise(from), VERTICES)
        val b = align(a, resample(clockwise(to), VERTICES))
        Slot(a, b)
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
