package com.androidharness.app.ui.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sign

/**
 * Scroll motion blur, modelled on a camera shutter. Every frame the engine
 * measures how far the content actually moved, turns that into a velocity, and
 * sizes a vertical gaussian from the distance the content would travel while
 * the shutter is open. Slow reading stays sharp, a flick smears like a camera
 * pan, and the smear eases out with the fling instead of snapping off.
 *
 * What makes it dependable:
 * - It measures real content movement, not finger input. Dragging against the
 *   end of a list moves nothing and blurs nothing, and touch panels that report
 *   at 240 Hz behave the same as ones that report at 60 Hz.
 * - Velocity and smoothing are computed from frame time, so 60, 90 and 120 Hz
 *   screens look the same.
 * - Given the list or scroll state, it also follows animated programmatic
 *   scrolls such as "jump to latest" or a settings search reveal. Instant jumps
 *   and the small nudges a streaming reply makes are filtered out.
 * - It honours the system "Remove animations" setting.
 * - The radius is quantised and the RenderEffect cached, so a fling invalidates
 *   the layer only when the blur visibly changes and allocates almost nothing.
 */
@Immutable
internal data class MotionBlurStyle(val enabled: Boolean = false, val intensity: Float = .5f) {
    val strength: Float get() = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else .5f
}

internal val LocalMotionBlur = staticCompositionLocalOf { MotionBlurStyle() }

/** Blur for a lazy list. Follows user scrolling and animated programmatic scrolls. */
@Composable
internal fun Modifier.motionBlur(state: LazyListState, style: MotionBlurStyle = LocalMotionBlur.current): Modifier {
    val travel = remember(state) { LazyListTravel(state) }
    return motionBlurImpl(style, travel) { snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset } }
}

/** Blur for a verticalScroll column. Follows user scrolling and animated programmatic scrolls. */
@Composable
internal fun Modifier.motionBlur(state: ScrollState, style: MotionBlurStyle = LocalMotionBlur.current): Modifier {
    val travel = remember(state) { ScrollTravel(state) }
    return motionBlurImpl(style, travel) { snapshotFlow { state.value } }
}

/**
 * Blur driven by nested scroll alone. Attach it outside a vertical scroller
 * whose state is not at hand; it sees user drags and flings but not
 * programmatic scrolls.
 */
@Composable
internal fun Modifier.motionBlur(style: MotionBlurStyle = LocalMotionBlur.current): Modifier =
    motionBlurImpl(style, null, null)

@Composable
private fun Modifier.motionBlurImpl(
    style: MotionBlurStyle,
    travel: Travel?,
    changes: (() -> kotlinx.coroutines.flow.Flow<Any>)?,
): Modifier {
    if (!style.enabled || style.strength <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val context = LocalContext.current
    val animationsOff = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    if (animationsOff) return this
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val engine = remember(scope, travel) { MotionBlurEngine(scope, travel) }
    SideEffect { engine.tune(density, style.strength) }
    DisposableEffect(engine) { onDispose { engine.stop() } }
    if (changes != null) LaunchedEffect(engine) { changes().collect { engine.wake() } }
    return this
        .nestedScroll(engine)
        .graphicsLayer { renderEffect = engine.effect() }
}

/** A running total of how far the content has moved, in pixels. NaN marks a jump. */
private fun interface Travel {
    fun read(): Float
}

private class ScrollTravel(private val state: ScrollState) : Travel {
    override fun read(): Float = state.value.toFloat()
}

/**
 * Lazy lists have no pixel scroll position, so this follows one visible item
 * between frames and adds up how far it moved. When the anchor has left the
 * window entirely (scrollToItem across the list) the frame reports a jump.
 */
private class LazyListTravel(private val state: LazyListState) : Travel {
    private var key: Any? = null
    private var offset = 0
    private var total = 0f

    override fun read(): Float {
        val items = state.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) { key = null; return Float.NaN }
        val previous = key
        val same = if (previous == null) null else items.firstOrNull { it.key == previous }
        val jumped = previous != null && same == null
        if (same != null) total += (offset - same.offset).toFloat()
        // A middle item survives a fast fling frame; the first one may already be gone.
        val anchor = items[items.size / 2]
        key = anchor.key
        offset = anchor.offset
        return if (jumped || previous == null) Float.NaN else total
    }
}

private class MotionBlurEngine(private val scope: CoroutineScope, private val travel: Travel?) : NestedScrollConnection {
    private var thresholdPxPerSec = 0f
    private var shutterSec = 0f
    private var maxRadiusPx = 0f

    /** Quantised radius in steps; reading it in the layer block subscribes the layer only. */
    private val steps = mutableFloatStateOf(0f)
    private var cachedSteps = -1f
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    private var pendingDy = 0f
    private var userMotionAt = 0L
    private var userMotionPending = false
    private var job: Job? = null

    fun tune(density: Density, strength: Float) = with(density) {
        // Below ~260 dp/s is reading speed; nothing smears.
        thresholdPxPerSec = 260.dp.toPx()
        // Longer exposure at higher strength: 1/200 s up to 1/45 s.
        shutterSec = 1f / 200f + (1f / 45f - 1f / 200f) * strength
        maxRadiusPx = (2f + 12f * strength).dp.toPx()
    }

    fun effect(): androidx.compose.ui.graphics.RenderEffect? {
        val s = steps.floatValue
        if (s <= 0f) return null
        if (s != cachedSteps) {
            cachedSteps = s
            cachedEffect = RenderEffect.createBlurEffect(0f, s * STEP_PX, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
        return cachedEffect
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val dy = consumed.y
        if (dy.isFinite() && dy != 0f) {
            pendingDy += dy
            userMotionPending = true
            wake()
        }
        return Offset.Zero
    }

    fun wake() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        steps.floatValue = 0f
    }

    private suspend fun run() {
        var last = withFrameNanos { it }
        var lastTravel = travel?.read() ?: Float.NaN
        var velocity = 0f
        var streak = 0
        var direction = 0f
        var idleFrames = 0
        try {
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(1f / 240f, 1f / 24f)
                last = now

                val nested = pendingDy
                pendingDy = 0f
                val user = userMotionPending
                userMotionPending = false
                if (user) userMotionAt = now
                val recentUser = now - userMotionAt < 80_000_000L

                val moved: Float = if (travel == null) nested else {
                    val t = travel.read()
                    val d = if (t.isNaN() || lastTravel.isNaN()) Float.NaN else t - lastTravel
                    lastTravel = t
                    d
                }

                // Programmatic motion counts only once it has lasted a few frames
                // in one direction: that is an animated scroll, not a jump or the
                // list following a streaming reply.
                val sample = when {
                    moved.isNaN() -> { streak = 0; 0f }
                    moved == 0f -> { streak = 0; 0f }
                    else -> {
                        val dir = sign(moved)
                        streak = if (dir == direction) streak + 1 else 1
                        direction = dir
                        if (recentUser || streak >= 3) abs(moved) / dt else 0f
                    }
                }

                // Quick attack so a flick blurs on its first frames, slower release
                // so the smear trails off like a decelerating pan.
                val tau = if (sample > velocity) .022f else .085f
                velocity += (sample - velocity) * (1f - exp(-dt / tau))

                val radius = ((velocity - thresholdPxPerSec) * shutterSec * .5f).coerceIn(0f, maxRadiusPx)
                val q = floor(radius / STEP_PX)
                if (q != steps.floatValue) steps.floatValue = q

                idleFrames = if (q == 0f && moved.let { it.isNaN() || it == 0f } && !user) idleFrames + 1 else 0
                if (idleFrames >= 3) break
            }
        } finally {
            steps.floatValue = 0f
            pendingDy = 0f
        }
    }
}

/** Blur radius step. Finer than the eye can tell apart, coarse enough to skip most layer updates. */
private const val STEP_PX = .5f
