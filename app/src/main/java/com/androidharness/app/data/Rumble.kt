package com.androidharness.app.data

import kotlinx.serialization.Serializable

/** One feel for a rumble moment. [OFF] silences the moment without touching the others. */
@Serializable
enum class RumblePattern { OFF, SOFT_TICK, TICK, CLICK, DOUBLE_CLICK, TRIPLE_TICK, THUD, SWELL, RISE }

/** How a pattern is produced: the phone's built-in effects, our own waveform, or whichever works. */
@Serializable
enum class RumbleEngine { AUTO, SYSTEM, CUSTOM }

/** The moments of a turn that AH Rumble can mark, with the pattern and level each starts with. */
enum class RumbleCue(val defaultPattern: RumblePattern, val defaultLevel: Int) {
    THINK(RumblePattern.SOFT_TICK, 55),
    TOOL_START(RumblePattern.CLICK, 100),
    TOOL_DONE(RumblePattern.TICK, 75),
    TOOL_FAILED(RumblePattern.DOUBLE_CLICK, 100),
    WRITE(RumblePattern.SWELL, 65),
    ANSWER_DONE(RumblePattern.THUD, 85),
    APPROVAL(RumblePattern.TRIPLE_TICK, 100),
}

/** A user's choice for one cue: [level] is a percentage of the overall strength. */
@Serializable
data class RumbleCueConfig(val pattern: RumblePattern, val level: Int)

const val RUMBLE_DEFAULT_STRENGTH = 60
const val RUMBLE_DEFAULT_SMOOTHNESS = 60
const val RUMBLE_DEFAULT_SPACING_MS = 90
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

fun Map<String, RumbleCueConfig>.configFor(cue: RumbleCue): RumbleCueConfig =
    this[cue.name] ?: RumbleCueConfig(cue.defaultPattern, cue.defaultLevel)
