package com.androidharness.app.ui.common

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.RumbleCue
import com.androidharness.app.data.RumbleCueConfig
import com.androidharness.app.data.RumbleEngine
import com.androidharness.app.data.RumblePattern
import com.androidharness.app.data.configFor
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything the player needs, taken from [AppSettings] so a change applies to the next pulse. */
internal data class RumbleStyle(
    val enabled: Boolean,
    val strength: Int,
    val smoothness: Int,
    val engine: RumbleEngine = RumbleEngine.AUTO,
    val spacingMs: Int = com.androidharness.app.data.RUMBLE_DEFAULT_SPACING_MS,
    val skipOnBatterySaver: Boolean = true,
    val cues: Map<String, RumbleCueConfig> = emptyMap(),
    val combo: Int = com.androidharness.app.data.RUMBLE_DEFAULT_COMBO,
) {
    fun config(cue: RumbleCue) = cues.configFor(cue, combo)
}

internal val AppSettings.rumbleStyle: RumbleStyle
    get() = RumbleStyle(rumbleEnabled, rumbleStrength, rumbleSmoothness, rumbleEngine, rumbleSpacingMs,
        rumbleSkipOnBatterySaver, rumbleCues, rumbleCombo)

/** Timings and amplitudes for [VibrationEffect.createWaveform]; amplitude 0 is a gap. */
internal class RumbleWaveform(val timings: LongArray, val amplitudes: IntArray)

/** How a pattern is built from our own pulses: count, gap, base weight, length and shape. */
private class PulseSpec(
    val pulses: Int,
    val gapMs: Long = 0,
    val weight: Float = 1f,
    val length: Float = 1f,
    val shape: Shape = Shape.BELL,
) {
    enum class Shape { BELL, RISING }
}

private fun specFor(pattern: RumblePattern): PulseSpec? = when (pattern) {
    RumblePattern.OFF -> null
    RumblePattern.SOFT_TICK -> PulseSpec(1, weight = .6f)
    RumblePattern.TICK -> PulseSpec(1, length = .7f)
    RumblePattern.CLICK -> PulseSpec(1)
    RumblePattern.DOUBLE_CLICK -> PulseSpec(2, gapMs = 70)
    RumblePattern.TRIPLE_TICK -> PulseSpec(3, gapMs = 55, weight = .9f, length = .7f)
    RumblePattern.THUD -> PulseSpec(1, length = 2.2f)
    RumblePattern.SWELL -> PulseSpec(1, length = 3f)
    RumblePattern.RISE -> PulseSpec(1, length = 2.4f, shape = PulseSpec.Shape.RISING)
}

/**
 * Builds the custom waveform for [pattern]. [strength] is the final 0..100 amplitude after the
 * overall strength and the event's level are combined; [smoothness] stretches each pulse from one
 * crisp tick into a swell of small steps.
 */
internal fun rumbleWaveform(pattern: RumblePattern, strength: Int, smoothness: Int): RumbleWaveform? {
    val spec = specFor(pattern) ?: return null
    val smooth = smoothness.coerceIn(0, 100) / 100f
    val force = (strength.coerceIn(0, 100) / 100f * spec.weight).coerceIn(0f, 1f)
    val swelling = pattern == RumblePattern.SWELL || pattern == RumblePattern.RISE
    val steps = (1 + (if (swelling) maxOf(smooth, .75f) else smooth) * 8).roundToInt()
    val totalMs = (12f + smooth * 98f) * spec.length
    val stepMs = (totalMs / steps).roundToInt().coerceAtLeast(6).toLong()
    val timings = ArrayList<Long>()
    val amplitudes = ArrayList<Int>()
    repeat(spec.pulses) { pulse ->
        if (pulse > 0) { timings += spec.gapMs; amplitudes += 0 }
        for (i in 0 until steps) {
            val envelope = when {
                steps == 1 -> 1f
                spec.shape == PulseSpec.Shape.RISING -> sin(PI.toFloat() / 2f * (i + 1f) / steps)
                else -> sin(PI.toFloat() * (i + .5f) / steps)
            }
            timings += stepMs
            amplitudes += (255f * force * envelope).roundToInt().coerceIn(if (force > 0f) MIN_AMPLITUDE else 1, 255)
        }
    }
    return RumbleWaveform(timings.toLongArray(), amplitudes.toIntArray())
}

/** One step of a system-effect composition: a built-in primitive and the pause before it. */
internal class RumbleStep(val primitive: Int, val delayMs: Int = 0)

/**
 * The built-in haptic primitives (Android 11+) for a pattern. A smooth setting swaps the sharp
 * click and tick for the softer low tick, so Feel still shapes the system engine.
 */
internal fun systemSteps(pattern: RumblePattern, smoothness: Int): List<RumbleStep> {
    val soft = smoothness >= 50
    val tick = if (soft) PRIMITIVE_LOW_TICK else PRIMITIVE_TICK
    val click = if (soft) PRIMITIVE_LOW_TICK else PRIMITIVE_CLICK
    return when (pattern) {
        RumblePattern.OFF -> emptyList()
        RumblePattern.SOFT_TICK -> listOf(RumbleStep(PRIMITIVE_LOW_TICK))
        RumblePattern.TICK -> listOf(RumbleStep(tick))
        RumblePattern.CLICK -> listOf(RumbleStep(click))
        RumblePattern.DOUBLE_CLICK -> listOf(RumbleStep(click), RumbleStep(click, 70))
        RumblePattern.TRIPLE_TICK -> listOf(RumbleStep(tick), RumbleStep(tick, 60), RumbleStep(tick, 60))
        RumblePattern.THUD -> listOf(RumbleStep(PRIMITIVE_THUD))
        RumblePattern.SWELL -> listOf(RumbleStep(PRIMITIVE_QUICK_RISE), RumbleStep(PRIMITIVE_QUICK_FALL))
        RumblePattern.RISE -> listOf(RumbleStep(PRIMITIVE_QUICK_RISE))
    }
}

// Mirrors VibrationEffect.Composition.PRIMITIVE_*; kept as plain ints so the shape logic is unit-testable.
internal const val PRIMITIVE_CLICK = 1
internal const val PRIMITIVE_THUD = 2
internal const val PRIMITIVE_QUICK_RISE = 4
internal const val PRIMITIVE_QUICK_FALL = 6
internal const val PRIMITIVE_TICK = 7
internal const val PRIMITIVE_LOW_TICK = 8

private const val MIN_AMPLITUDE = 14

private fun systemVibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
} else {
    @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

/** Tablets, emulators and some TVs have no motor at all; AH Rumble says so instead of silently doing nothing. */
internal fun deviceHasVibrator(context: Context): Boolean = systemVibrator(context)?.hasVibrator() == true

/** Whether this phone implements every built-in primitive AH Rumble uses (Android 11+ with a capable motor). */
internal fun deviceSupportsSystemHaptics(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    val vibrator = systemVibrator(context)?.takeIf { it.hasVibrator() } ?: return false
    return vibrator.areAllPrimitivesSupported(PRIMITIVE_CLICK, PRIMITIVE_THUD, PRIMITIVE_QUICK_RISE,
        PRIMITIVE_QUICK_FALL, PRIMITIVE_TICK, PRIMITIVE_LOW_TICK)
}

/** Plays [RumbleCue]s on the phone's vibrator, following the system's touch-feedback setting. */
internal class Rumble(private val context: Context) {
    private val vibrator: Vibrator? = systemVibrator(context)
    private val systemHaptics: Boolean by lazy(LazyThreadSafetyMode.NONE) { deviceSupportsSystemHaptics(context) }
    private var lastPlayed = 0L

    /** A preview ignores the on/off switch, battery saver and spacing so the settings page always answers a tap. */
    fun play(cue: RumbleCue, style: RumbleStyle, preview: Boolean = false) {
        play(style.config(cue), style, preview)
    }

    @Suppress("DEPRECATION")
    fun play(config: RumbleCueConfig, style: RumbleStyle, preview: Boolean = false) {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (config.pattern == RumblePattern.OFF || config.level <= 0 || style.strength <= 0) return
        if (!preview) {
            if (!style.enabled) return
            if (style.skipOnBatterySaver && context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastPlayed < style.spacingMs) return
            lastPlayed = now
        }
        val strength = (style.strength * config.level / 100f).roundToInt().coerceIn(1, 100)
        val effect = runCatching { effectFor(vibrator, config.pattern, strength, style) }.getOrNull() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                vibrator.vibrate(effect, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        }
    }

    private fun effectFor(vibrator: Vibrator, pattern: RumblePattern, strength: Int, style: RumbleStyle): VibrationEffect? {
        val useSystem = style.engine != RumbleEngine.CUSTOM && systemHaptics && Build.VERSION.SDK_INT >= 30
        if (useSystem) {
            val composition = VibrationEffect.startComposition()
            val scale = (strength / 100f).coerceIn(.05f, 1f)
            systemSteps(pattern, style.smoothness).forEach { composition.addPrimitive(it.primitive, scale, it.delayMs) }
            return composition.compose()
        }
        val wave = rumbleWaveform(pattern, strength, style.smoothness) ?: return null
        val amplitudes = if (vibrator.hasAmplitudeControl()) wave.amplitudes
        else IntArray(wave.amplitudes.size) { if (wave.amplitudes[it] > 0) VibrationEffect.DEFAULT_AMPLITUDE else 0 }
        return VibrationEffect.createWaveform(wave.timings, amplitudes, -1)
    }
}

/** A player bound to the latest [style]; safe to call from effects without restarting them. */
@Composable
internal fun rememberRumble(style: RumbleStyle): (RumbleCue) -> Unit {
    val context = LocalContext.current.applicationContext
    val rumble = remember(context) { Rumble(context) }
    val current by rememberUpdatedState(style)
    return remember(rumble) { { cue -> rumble.play(cue, current) } }
}

/** Plays with explicit settings regardless of the saved on/off switch, for the settings page. */
@Composable
internal fun rememberRumblePreview(): (RumbleCueConfig, RumbleStyle) -> Unit {
    val context = LocalContext.current.applicationContext
    val rumble = remember(context) { Rumble(context) }
    return remember(rumble) { { config, style -> rumble.play(config, style, preview = true) } }
}
