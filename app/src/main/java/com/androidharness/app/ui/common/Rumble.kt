package com.androidharness.app.ui.common

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The moments of a Chat turn that AH Rumble marks. [weight] scales how firmly each one lands. */
internal enum class RumbleCue(val weight: Float, val pulses: Int = 1) {
    THINK(.55f),
    TOOL_START(1f),
    TOOL_DONE(.75f),
    TOOL_FAILED(1f, pulses = 2),
    WRITE(.65f),
}

internal data class RumbleStyle(val enabled: Boolean, val strength: Int, val smoothness: Int)

internal val AppSettings.rumbleStyle: RumbleStyle
    get() = RumbleStyle(rumbleEnabled, rumbleStrength, rumbleSmoothness)

/** Timings and amplitudes for [VibrationEffect.createWaveform]; amplitude 0 is a gap. */
internal class RumbleWaveform(val timings: LongArray, val amplitudes: IntArray)

/**
 * Smoothness sets the shape: crisp is one short strong tick, smooth stretches the same energy over
 * a rising and falling swell of small steps. Strength scales amplitude.
 */
internal fun rumbleWaveform(cue: RumbleCue, style: RumbleStyle): RumbleWaveform {
    val smooth = style.smoothness.coerceIn(0, 100) / 100f
    val force = (style.strength.coerceIn(0, 100) / 100f * cue.weight).coerceIn(0f, 1f)
    val steps = (1 + smooth * 8).roundToInt()
    val stepMs = (12f + smooth * 98f).div(steps).roundToInt().coerceAtLeast(6).toLong()
    val timings = ArrayList<Long>()
    val amplitudes = ArrayList<Int>()
    repeat(cue.pulses) { pulse ->
        if (pulse > 0) { timings += GAP_MS; amplitudes += 0 }
        for (i in 0 until steps) {
            val envelope = if (steps == 1) 1f else sin(PI.toFloat() * (i + .5f) / steps)
            timings += stepMs
            amplitudes += (255f * force * envelope).roundToInt().coerceIn(if (force > 0f) MIN_AMPLITUDE else 1, 255)
        }
    }
    return RumbleWaveform(timings.toLongArray(), amplitudes.toIntArray())
}

private const val GAP_MS = 70L
private const val MIN_AMPLITUDE = 14
private const val MIN_SPACING_MS = 90L

/** Plays [RumbleCue]s on the phone's vibrator, following the system's touch-feedback setting. */
internal class Rumble(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var lastPlayed = 0L

    @Suppress("DEPRECATION")
    fun play(cue: RumbleCue, style: RumbleStyle) {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (!style.enabled || style.strength <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayed < MIN_SPACING_MS) return
        lastPlayed = now
        val wave = rumbleWaveform(cue, style)
        val effect = runCatching {
            if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(wave.timings, wave.amplitudes, -1)
            else VibrationEffect.createWaveform(wave.timings, IntArray(wave.timings.size) { if (wave.amplitudes[it] > 0) VibrationEffect.DEFAULT_AMPLITUDE else 0 }, -1)
        }.getOrNull() ?: return
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
}

/** A player bound to the latest [style]; safe to call from effects without restarting them. */
@Composable
internal fun rememberRumble(style: RumbleStyle): (RumbleCue) -> Unit {
    val context = LocalContext.current.applicationContext
    val rumble = remember(context) { Rumble(context) }
    val current by rememberUpdatedState(style)
    return remember(rumble) { { cue -> rumble.play(cue, current) } }
}

/** Plays with explicit [style] regardless of the saved on/off switch, for the settings preview. */
@Composable
internal fun rememberRumblePreview(): (RumbleCue, RumbleStyle) -> Unit {
    val context = LocalContext.current.applicationContext
    val rumble = remember(context) { Rumble(context) }
    return remember(rumble) { { cue, style -> rumble.play(cue, style.copy(enabled = true)) } }
}
