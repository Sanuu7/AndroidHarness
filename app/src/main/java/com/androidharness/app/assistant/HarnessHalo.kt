package com.androidharness.app.assistant

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The assistant's light: a pool of the theme's colors resting on the bottom edge, under the
 * pill. On Android 13 and up it is a small shader, a slow liquid of three colors whose top edge
 * rolls and ripples outward with the voice. Older phones get two soft gradients that sway past
 * each other. Either way it rises with the pill, sinks lower while the user types or reads,
 * breathes while an answer is written, and swells with the voice.
 *
 * The screen's edge lights up too. When the assistant opens, a thread of light runs from the
 * bottom up both sides and meets at the top. After that the edge glows softly while the
 * assistant listens and pulses with the voice, two comets circle it while an answer is being
 * worked on, and it flashes once when the answer lands.
 *
 * The edge is the screen's outline stroked three times with sweep gradients, so it costs the
 * same on a budget phone as on a flagship.
 */

/** How long the opening thread takes to reach the top. */
private const val TRACE_MS = 1_350

/** How far behind each head the thread's tail reaches, in degrees around the screen center. */
private const val TRAIL = 80f

/** One color stop every three degrees, fine enough that the head stays crisp. */
private const val TRACE_STEPS = 120

/** Quick away from the bottom, slowing as the two heads meet at the top. */
private val TraceEase = CubicBezierEasing(0.35f, 0f, 0.15f, 1f)

/** How tall the pool stands at rest. */
private val PoolHeight = 300.dp

/** How far above its rest height the shader's rolling top edge can reach. */
private const val CREST_ROOM = 1.3f

/** A bell curve that reaches exactly zero at its edge, so the light has no rim or band. */
private val PoolStops = FloatArray(17) { it / 16f }
private val PoolCurve = PoolStops.map { s -> ((exp(-3.4f * s * s) - exp(-3.4f)) / (1f - exp(-3.4f))).coerceIn(0f, 1f) }

/** The theme's hue at full color, so dark themes' pastel tones still read as light. */
private fun vivid(color: Color, hueShift: Float = 0f): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
    hsv[1] = maxOf(hsv[1], 0.6f)
    hsv[2] = 1f
    return android.graphics.Color.HSVToColor(hsv)
}

private fun pool(color: Int) = Paint().apply {
    isDither = true
    shader = RadialGradient(0f, 0f, 1f,
        PoolCurve.map { a -> android.graphics.Color.argb((a * 255f).roundToInt(), android.graphics.Color.red(color),
            android.graphics.Color.green(color), android.graphics.Color.blue(color)) }.toIntArray(),
        PoolStops, Shader.TileMode.CLAMP)
}

private fun clear(color: Int) = color and 0x00FFFFFF

/**
 * [fade] shows and hides the light with the panel, [rise] is the pill's entrance (0 to 1, with
 * its spring's small overshoot), and [level] the microphone's loudness.
 */
@Composable
internal fun HarnessHalo(
    showKey: Int,
    engaged: Boolean,
    listening: Boolean,
    working: Boolean,
    reduceMotion: Boolean,
    level: () -> Float,
    fade: () -> Float,
    rise: () -> Float,
) {
    val scheme = MaterialTheme.colorScheme
    val head = remember(scheme) { vivid(scheme.primary) }
    val tail = remember(scheme) { vivid(scheme.tertiary, hueShift = 12f) }
    val third = remember(scheme) { vivid(scheme.secondary, hueShift = -24f) }
    val fluid = remember(scheme) { if (Build.VERSION.SDK_INT >= 33) FluidLight.create(head, tail, third) else null }
    val paints = remember(scheme) { listOf(pool(head), pool(tail)) }
    val spot = remember { Matrix() }
    val corner = rememberScreenCorner()

    // The opening thread, once per call.
    val trace = remember { Animatable(1f) }
    LaunchedEffect(showKey) {
        if (reduceMotion) trace.snapTo(1f)
        else { trace.snapTo(0f); trace.animateTo(1f, tween(TRACE_MS, easing = TraceEase)) }
    }

    // Full height while it waits, low and quiet once the user is typing or reading.
    val stand by animateFloatAsState(if (engaged && !working) 0.42f else 1f,
        spring(dampingRatio = 1f, stiffness = if (reduceMotion) 400f else 50f), label = "haloStand")
    // Follows the voice smoothly, read only while drawing.
    val voice = remember { Animatable(0f) }
    LaunchedEffect(showKey) {
        snapshotFlow { level().coerceIn(0f, 1f) }.collectLatest { voice.animateTo(it, spring(dampingRatio = 1f, stiffness = 160f)) }
    }
    // One clock for everything that moves on its own, in seconds. Much slower with reduced motion.
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        val speed = if (reduceMotion) 0.13f else 1f
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) clock += (now - last).coerceAtMost(100_000_000L) / 1e9f * speed
            last = now
        }
    }
    val busy by animateFloatAsState(if (working && !reduceMotion) 1f else 0f, tween(600), label = "haloBusy")
    val listen by animateFloatAsState(if (listening) 1f else 0f, tween(if (reduceMotion) 150 else 450), label = "haloListen")
    // The answer landing: one soft flash around the edge.
    val flash = remember { Animatable(0f) }
    val wasWorking = remember { booleanArrayOf(working) }
    LaunchedEffect(working) {
        val landed = wasWorking[0] && !working
        wasWorking[0] = working
        if (landed) {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(if (reduceMotion) 80 else 160))
            flash.animateTo(0f, tween(if (reduceMotion) 300 else 1_100, easing = FastOutSlowInEasing))
        }
    }

    Spacer(Modifier.fillMaxSize().drawWithCache {
        val w = size.width
        val h = size.height
        val r = (corner ?: 24.dp.toPx()).coerceIn(0f, minOf(w, h) / 2f)
        // The stroke is centered on the glass, so only its inner half shows: a wide faint glow,
        // a softer band and a fine bright line.
        // A round rect, not a path: the GPU strokes it directly instead of drawing a mask on the CPU.
        val edge = RectF(0f, 0f, w, h)
        val widths = listOf(18.dp.toPx(), 8.dp.toPx(), 2.5.dp.toPx())
        fun strokes() = widths.map { width -> Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width; isDither = true } }
        val threads = strokes().zip(listOf(0.2f, 0.45f, 1f))
        val stops = FloatArray(TRACE_STEPS + 1) { it / TRACE_STEPS.toFloat() }
        val colors = IntArray(TRACE_STEPS + 1)

        // The listening glow: the whole edge in the theme's colors, turning slowly.
        val ringShader = SweepGradient(w / 2f, h / 2f, intArrayOf(head, tail, third, head, tail, head), null)
        val ring = strokes().onEach { it.shader = ringShader }.zip(listOf(0.22f, 0.4f, 0.6f))
        // The working comets: two bright heads, each with a tail behind it, half a turn apart.
        val cometShader = SweepGradient(w / 2f, h / 2f,
            intArrayOf(clear(tail), clear(tail), tail, head, clear(head), clear(tail), tail, head, clear(head)),
            floatArrayOf(0f, 0.06f, 0.2f, 0.24f, 0.26f, 0.56f, 0.7f, 0.74f, 0.76f))
        val comets = strokes().onEach { it.shader = cometShader }.zip(listOf(0.25f, 0.5f, 1f))
        val turn = Matrix()

        onDrawBehind {
            val shown = fade().coerceIn(0f, 1f)
            if (shown <= 0.003f) return@onDrawBehind
            drawIntoCanvas { c ->
                val canvas = c.nativeCanvas
                val t = clock
                val loud = voice.value
                val pulse = 1f - busy * 0.22f * (0.5f + 0.5f * sin(t / 2.4f * 2f * PI.toFloat()))
                val tall = PoolHeight.toPx() * stand * rise().coerceIn(0f, 1.2f) * (1f + 0.45f * loud)
                if (tall >= 1f) {
                    if (fluid != null) {
                        fluid.draw(canvas, w, h, tall, t, loud, 0.85f * shown * pulse)
                    } else {
                        // The two colors sway past each other, never far from the middle.
                        val sway = t / 16f * 2f * PI.toFloat()
                        canvas.spot(spot, paints[0], w / 2f + w * 0.13f * sin(sway), h, w * 0.78f, tall, 0.62f * shown * pulse)
                        canvas.spot(spot, paints[1], w / 2f - w * 0.15f * sin(sway + 1.9f), h, w * 0.6f, tall * 0.8f, 0.5f * shown * pulse)
                    }
                }

                val p = trace.value
                if (p < 1f) {
                    // Angles run clockwise from three o'clock, so the bottom is 90 and the top 270.
                    val travel = 180f * p
                    val left = 90f + travel
                    val right = 90f - travel
                    for (i in colors.indices) {
                        val at = 360f * stops[i]
                        val k = maxOf(thread(around(left - at), travel), thread(around(at - right), travel))
                        colors[i] = mix(tail, head, k, k)
                    }
                    val shader = SweepGradient(w / 2f, h / 2f, colors, stops)
                    // In quickly, out softly as the heads meet.
                    val strength = shown * (p / 0.06f).coerceAtMost(1f) * (1f - smooth((p - 0.72f) / 0.28f))
                    for ((paint, alpha) in threads) {
                        paint.shader = shader
                        paint.alpha = (alpha * strength * 255f).roundToInt().coerceIn(0, 255)
                        canvas.drawRoundRect(edge, r, r, paint)
                    }
                }

                // The living edge takes over as the opening thread fades.
                val after = shown * smooth((p - 0.75f) / 0.25f)
                if (after > 0.003f) {
                    val glow = after * maxOf(listen * (0.3f + 0.7f * loud), flash.value * 0.9f)
                    if (glow > 0.003f) {
                        turn.setRotate(t * 24f, w / 2f, h / 2f)
                        ringShader.setLocalMatrix(turn)
                        ring.forEachIndexed { i, (paint, alpha) ->
                            // The wide glow breathes out with the voice.
                            paint.strokeWidth = widths[i] * if (i == 0) 1f + 1.2f * loud * listen else 1f
                            paint.alpha = (alpha * glow * 255f).roundToInt().coerceIn(0, 255)
                            canvas.drawRoundRect(edge, r, r, paint)
                        }
                    }
                    val orbit = after * busy
                    if (orbit > 0.003f) {
                        turn.setRotate(t * 150f, w / 2f, h / 2f)
                        cometShader.setLocalMatrix(turn)
                        for ((paint, alpha) in comets) {
                            paint.alpha = (alpha * orbit * 255f).roundToInt().coerceIn(0, 255)
                            canvas.drawRoundRect(edge, r, r, paint)
                        }
                    }
                }
            }
        }
    })
}

/**
 * The pool as a shader: three of the theme's colors folded through each other by layered
 * noise, so it moves like slow liquid. Its top edge rolls on its own and, while the user
 * speaks, ripples out from the middle. Null where the shader can't be built.
 */
@RequiresApi(33)
private class FluidLight private constructor(private val shader: RuntimeShader) {
    private val paint = Paint().apply { shader = this@FluidLight.shader }

    fun draw(canvas: Canvas, w: Float, bottom: Float, tall: Float, time: Float, voice: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        shader.setFloatUniform("area", w, bottom)
        shader.setFloatUniform("tall", tall)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("voice", voice)
        shader.setFloatUniform("alpha", alpha.coerceIn(0f, 1f))
        canvas.drawRect(0f, bottom - tall * CREST_ROOM, w, bottom, paint)
    }

    companion object {
        fun create(a: Int, b: Int, c: Int): FluidLight? = runCatching {
            RuntimeShader(FLUID_SRC).apply {
                setColorUniform("c1", a)
                setColorUniform("c2", b)
                setColorUniform("c3", c)
            }
        }.getOrNull()?.let(::FluidLight)
    }
}

private const val FLUID_SRC = """
uniform float2 area;
uniform float tall;
uniform float time;
uniform float voice;
uniform float alpha;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = p * 2.02 + float2(3.1, 1.7);
        a *= 0.5;
    }
    return v / 0.875;
}

half4 main(float2 xy) {
    float x = xy.x / area.x;
    float y = (area.y - xy.y) / max(tall, 1.0);
    float side = abs(x - 0.5) * 2.0;
    float t = time;

    float2 p = float2(x * 2.6, y * 1.4 - t * 0.08);
    float2 q = float2(fbm(p + float2(0.0, t * 0.11)), fbm(p + float2(4.7, -t * 0.09)));
    float n = fbm(p + 1.6 * q);

    float crest = 0.6 + 0.4 * fbm(float2(x * 2.4 + t * 0.07, t * 0.13));
    crest += voice * 0.25 * sin(side * 14.0 - t * 7.0) * (1.0 - side);
    crest *= 1.0 - 0.45 * side * side;
    crest = max(crest, 0.08);
    float h = y / crest;
    if (h >= 1.0) { return half4(0.0); }

    float body = (exp(-3.4 * h * h) - exp(-3.4)) / (1.0 - exp(-3.4));
    body *= 0.7 + 0.5 * n;

    half3 col = mix(c1.rgb, c2.rgb, smoothstep(0.3, 0.7, n));
    col = mix(col, c3.rgb, smoothstep(0.5, 0.85, q.x) * 0.75);
    float core = exp(-y * 9.0) * (0.2 + 0.5 * voice);
    col = mix(col, half3(1.0), core * 0.55);

    float a = clamp(body * alpha, 0.0, 1.0);
    a = clamp(a + (hash(xy + fract(t)) - 0.5) / 255.0, 0.0, 1.0);
    return half4(col * a, a);
}
"""

private fun around(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

/** Brightness [behind] degrees back from a head that has come [travel] degrees from the bottom. */
private fun thread(behind: Float, travel: Float): Float {
    if (behind > travel || behind > TRAIL) return 0f
    val k = 1f - behind / TRAIL
    // Fades out over the last stretch before the starting point, so the bottom has no hard end.
    val start = ((travel - behind) / 12f).coerceIn(0f, 1f)
    return k * k * start
}

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** [from] to [to] by [t], with [alpha] as opacity. */
private fun mix(from: Int, to: Int, t: Float, alpha: Float): Int {
    fun ch(a: Int, b: Int) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    return android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
        ch(android.graphics.Color.red(from), android.graphics.Color.red(to)),
        ch(android.graphics.Color.green(from), android.graphics.Color.green(to)),
        ch(android.graphics.Color.blue(from), android.graphics.Color.blue(to)))
}

/** The display's own corner radius, so the thread follows the glass. */
@Composable
private fun rememberScreenCorner(): Float? {
    val view = LocalView.current
    return remember(view) {
        if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
        else null
    }
}

/** One flattened pool of light centered on the bottom edge. */
private fun Canvas.spot(matrix: Matrix, paint: Paint, x: Float, bottom: Float, rx: Float, ry: Float, alpha: Float) {
    if (alpha <= 0.003f) return
    matrix.setScale(rx, ry)
    matrix.postTranslate(x, bottom)
    paint.shader.setLocalMatrix(matrix)
    paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    drawRect(x - rx, bottom - ry, x + rx, bottom, paint)
}
