package com.androidharness.app.data

import kotlinx.serialization.Serializable

/** One feel for a rumble moment. [OFF] silences the moment without touching the others. */
@Serializable
enum class RumblePattern { OFF, SOFT_TICK, TICK, CLICK, DOUBLE_CLICK, TRIPLE_TICK, THUD, SWELL, RISE }

/** How a pattern is produced: the phone's built-in effects, our own waveform, or whichever works. */
@Serializable
enum class RumbleEngine { AUTO, SYSTEM, CUSTOM }

/** The moments of a turn that AH Rumble can mark. */
enum class RumbleCue { THINK, TOOL_START, TOOL_DONE, TOOL_FAILED, WRITE, ANSWER_DONE, APPROVAL }

/** A user's choice for one cue: [level] is a percentage of the overall strength. */
@Serializable
data class RumbleCueConfig(val pattern: RumblePattern, val level: Int)

const val RUMBLE_DEFAULT_STRENGTH = 65
const val RUMBLE_DEFAULT_SMOOTHNESS = 35
const val RUMBLE_DEFAULT_SPACING_MS = 150
const val RUMBLE_MIN_SPACING_MS = 30
const val RUMBLE_MAX_SPACING_MS = 1_000

private val rumbleJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Only cues the user changed are stored, so new defaults reach everyone who never touched them. */
fun encodeRumbleCues(cues: Map<String, RumbleCueConfig>): String =
    rumbleJson.encodeToString(kotlinx.serialization.builtins.MapSerializer(
        kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<RumbleCueConfig>()), cues)

fun decodeRumbleCues(raw: String?): Map<String, RumbleCueConfig> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        rumbleJson.decodeFromString(kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<RumbleCueConfig>()), raw)
    }.getOrDefault(emptyMap())
        .filterKeys { key -> RumbleCue.entries.any { it.name == key } }
        .mapValues { (_, config) -> config.copy(level = config.level.coerceIn(0, 100)) }
}

/**
 * Hand-tuned sets of event patterns, listed in [RumbleCue] order. Each keeps the signals that matter
 * (failure, finished answer, needs you) apart from the everyday ticks, so any of them is a good default.
 * A fresh install starts on a random one.
 */
private val RUMBLE_COMBOS: List<List<RumbleCueConfig>> = listOf(
    // Balanced
    listOf(cfg(RumblePattern.SOFT_TICK, 50), cfg(RumblePattern.CLICK, 85), cfg(RumblePattern.SOFT_TICK, 60),
        cfg(RumblePattern.DOUBLE_CLICK, 100), cfg(RumblePattern.RISE, 60), cfg(RumblePattern.THUD, 85), cfg(RumblePattern.TRIPLE_TICK, 100)),
    // Crisp
    listOf(cfg(RumblePattern.TICK, 45), cfg(RumblePattern.TICK, 80), cfg(RumblePattern.CLICK, 60),
        cfg(RumblePattern.THUD, 100), cfg(RumblePattern.SOFT_TICK, 50), cfg(RumblePattern.DOUBLE_CLICK, 80), cfg(RumblePattern.TRIPLE_TICK, 100)),
    // Soft
    listOf(cfg(RumblePattern.SOFT_TICK, 40), cfg(RumblePattern.TICK, 65), cfg(RumblePattern.SOFT_TICK, 45),
        cfg(RumblePattern.DOUBLE_CLICK, 90), cfg(RumblePattern.RISE, 50), cfg(RumblePattern.SWELL, 70), cfg(RumblePattern.TRIPLE_TICK, 85)),
    // Bold
    listOf(cfg(RumblePattern.TICK, 55), cfg(RumblePattern.CLICK, 90), cfg(RumblePattern.TICK, 70),
        cfg(RumblePattern.TRIPLE_TICK, 100), cfg(RumblePattern.SWELL, 70), cfg(RumblePattern.THUD, 100), cfg(RumblePattern.DOUBLE_CLICK, 100)),
    // Playful
    listOf(cfg(RumblePattern.SOFT_TICK, 55), cfg(RumblePattern.DOUBLE_CLICK, 70), cfg(RumblePattern.TICK, 60),
        cfg(RumblePattern.THUD, 95), cfg(RumblePattern.RISE, 65), cfg(RumblePattern.SWELL, 80), cfg(RumblePattern.TRIPLE_TICK, 95)),
)

private fun cfg(pattern: RumblePattern, level: Int) = RumbleCueConfig(pattern, level)

const val RUMBLE_COMBO_COUNT = 5

/** The combination used until a random one is saved; the first set. */
const val RUMBLE_DEFAULT_COMBO = 0

/** What [cue] does in [combo] before any override. */
fun rumbleComboConfig(combo: Int, cue: RumbleCue): RumbleCueConfig =
    RUMBLE_COMBOS[combo.coerceIn(0, RUMBLE_COMBOS.lastIndex)][cue.ordinal]

fun Map<String, RumbleCueConfig>.configFor(cue: RumbleCue, combo: Int = RUMBLE_DEFAULT_COMBO): RumbleCueConfig =
    this[cue.name] ?: rumbleComboConfig(combo, cue)
