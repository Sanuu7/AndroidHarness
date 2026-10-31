package com.androidharness.app.local

import java.util.Locale

enum class ChipTier { FLAGSHIP, UPPER_MID, MID, ENTRY, UNKNOWN }

/** A chip family guess. Wrong guesses only change which models are suggested, never what can be installed. */
data class ChipInfo(val label: String, val tier: ChipTier)

/** What this phone can run: memory and storage from [profile], and a speed class from its processor. */
data class LocalDeviceCapability(val profile: LocalDeviceProfile, val chip: ChipInfo)

enum class SuggestionKind { BEST, CODING, FAST, LIGHT }

data class ModelSuggestion(val model: LocalModelSpec, val kind: SuggestionKind)

/**
 * Processor speed class from the SoC model Android reports. The tables cover the common Snapdragon, Dimensity,
 * Tensor and Exynos generations. Anything unrecognised falls back to the fastest CPU core and the core count.
 */
object ChipClassifier {
    private val codenames = mapOf(
        "kalama" to ChipTier.FLAGSHIP, "pineapple" to ChipTier.FLAGSHIP, "sun" to ChipTier.FLAGSHIP,
        "taro" to ChipTier.FLAGSHIP, "crow" to ChipTier.FLAGSHIP, "waipio" to ChipTier.FLAGSHIP,
        "lahaina" to ChipTier.UPPER_MID, "kona" to ChipTier.UPPER_MID,
    )

    fun classify(manufacturer: String, soc: String, hardware: String, board: String, maxCoreKhz: Long?, cores: Int): ChipInfo {
        val label = listOf(manufacturer, soc).filter { it.isNotBlank() && it != "unknown" }.joinToString(" ")
            .ifBlank { hardware.ifBlank { board } }
        val text = "$soc $hardware $board".lowercase(Locale.ROOT)
        return ChipInfo(label, byModel(text) ?: byFrequency(maxCoreKhz, cores))
    }

    private fun byModel(text: String): ChipTier? {
        Regex("sm(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return when {
                n >= 8450 -> ChipTier.FLAGSHIP
                n >= 8000 -> ChipTier.UPPER_MID
                n >= 7450 -> ChipTier.UPPER_MID
                n >= 6450 || n in 7000..7449 -> ChipTier.MID
                else -> ChipTier.ENTRY
            }
        }
        Regex("mt(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return when (n) {
                6983, 6985, in 6989..6999 -> ChipTier.FLAGSHIP
                6895, 6896, 6897, 6899 -> ChipTier.UPPER_MID
                6877, 6891, 6893 -> ChipTier.MID
                else -> ChipTier.ENTRY
            }
        }
        Regex("s5e(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return when {
                n >= 9945 -> ChipTier.FLAGSHIP
                n >= 9000 -> ChipTier.UPPER_MID
                n >= 8000 -> ChipTier.MID
                else -> ChipTier.ENTRY
            }
        }
        if (Regex("tensor|gs[1-9]0[1-9]|zuma|laguna").containsMatchIn(text)) return ChipTier.UPPER_MID
        if (Regex("\\bums|\\bsc98|\\bt[67]\\d\\d\\b|spreadtrum|unisoc").containsMatchIn(text)) return ChipTier.ENTRY
        return codenames.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(text) }?.value
    }

    private fun byFrequency(maxCoreKhz: Long?, cores: Int): ChipTier = when {
        maxCoreKhz == null -> ChipTier.UNKNOWN
        cores >= 8 && maxCoreKhz >= 3_000_000 -> ChipTier.UPPER_MID
        maxCoreKhz >= 2_400_000 -> ChipTier.MID
        else -> ChipTier.ENTRY
    }
}

object LocalModelSuggestions {
    /** Largest Q4 file worth suggesting per processor class, in bytes. Bigger models still install on request. */
    fun sizeCap(tier: ChipTier): Long = when (tier) {
        ChipTier.FLAGSHIP -> 3_000_000_000L
        ChipTier.UPPER_MID -> 2_200_000_000L
        ChipTier.MID, ChipTier.UNKNOWN -> 1_300_000_000L
        ChipTier.ENTRY -> 600_000_000L
    }

    /** Size of the GGUF the engine loads. Safetensors imports shrink to roughly a third when converted to Q4. */
    fun loadedBytes(model: LocalModelSpec): Long =
        if (model.format == LocalModelFormat.SAFETENSORS) (model.bytes * 0.3).toLong() else model.bytes

    /** Whether [device] has the memory for [model], counting safetensors at their converted size. */
    fun runsOn(device: LocalDeviceProfile, model: LocalModelSpec): Boolean = fits(device, model)

    private fun fits(device: LocalDeviceProfile, model: LocalModelSpec): Boolean {
        val memory = loadedBytes(model) + (model.projector?.bytes ?: 0) + 640L * 1024 * 1024 + model.defaultContext.toLong() * model.kvBytesPerToken
        return device.supported && device.totalRam >= model.minimumRamGiB * GIB * 9 / 10 && memory <= device.totalRam * 55 / 100
    }

    private fun hasRoom(device: LocalDeviceProfile, model: LocalModelSpec): Boolean {
        val needed = if (model.format == LocalModelFormat.SAFETENSORS) model.downloadBytes + loadedBytes(model) else model.downloadBytes
        return device.freeStorage >= needed + 256L * 1024 * 1024
    }

    /**
     * Up to three models for [format], best first. Abliterated community variants are left to the filter.
     * Only models this phone can hold in memory, with room on disk, and that its processor can run at a usable speed.
     */
    fun suggest(capability: LocalDeviceCapability, models: List<LocalModelSpec>, installed: Set<String>,
        format: LocalModelFormat): List<ModelSuggestion> {
        val device = capability.profile
        val cap = sizeCap(capability.chip.tier)
        val pool = models.filter { it.format == format && !it.localFile && !it.abliterated && it.id !in installed &&
            fits(device, it) && hasRoom(device, it) && loadedBytes(it) <= cap }
            .sortedWith(compareByDescending<LocalModelSpec> { loadedBytes(it) }.thenBy { it.title })
        val best = pool.firstOrNull() ?: return emptyList()
        val picks = LinkedHashMap<String, ModelSuggestion>()
        fun add(model: LocalModelSpec?, kind: SuggestionKind) { if (model != null && model.id !in picks) picks[model.id] = ModelSuggestion(model, kind) }
        add(best, SuggestionKind.BEST)
        add(pool.firstOrNull { "coder" in it.title.lowercase(Locale.ROOT) && it.id != best.id }, SuggestionKind.CODING)
        add(pool.firstOrNull { loadedBytes(it) <= loadedBytes(best) * 6 / 10 }, SuggestionKind.FAST)
        add(pool.last(), SuggestionKind.LIGHT)
        return picks.values.take(3)
    }
}
