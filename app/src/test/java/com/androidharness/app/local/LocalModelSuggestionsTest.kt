package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test

class LocalModelSuggestionsTest {
    private fun phone(ramGiB: Int, free: Long = 64 * GIB) = LocalDeviceProfile(ramGiB * GIB, ramGiB * GIB / 2, free, "arm64-v8a", 8, false)
    private fun capability(ramGiB: Int, tier: ChipTier, free: Long = 64 * GIB) =
        LocalDeviceCapability(phone(ramGiB, free), ChipInfo("test", tier))
    private fun suggest(capability: LocalDeviceCapability, format: LocalModelFormat = LocalModelFormat.GGUF) =
        LocalModelSuggestions.suggest(capability, LocalModelCatalog.models, emptySet(), format)

    @Test fun `common chips map to the expected speed class`() {
        fun tier(soc: String, hardware: String = "qcom", khz: Long? = null, cores: Int = 8) =
            ChipClassifier.classify("", soc, hardware, "", khz, cores).tier
        assertEquals(ChipTier.FLAGSHIP, tier("SM8650"))
        assertEquals(ChipTier.FLAGSHIP, tier("SM8750"))
        assertEquals(ChipTier.UPPER_MID, tier("SM8250"))
        assertEquals(ChipTier.UPPER_MID, tier("SM7475"))
        assertEquals(ChipTier.MID, tier("SM7325"))
        assertEquals(ChipTier.ENTRY, tier("SM4450"))
        assertEquals(ChipTier.FLAGSHIP, tier("MT6989", "mt6989"))
        assertEquals(ChipTier.UPPER_MID, tier("MT6897", "mt6897"))
        assertEquals(ChipTier.ENTRY, tier("MT6765", "mt6765"))
        assertEquals(ChipTier.FLAGSHIP, tier("s5e9945", "exynos2400"))
        assertEquals(ChipTier.UPPER_MID, tier("GS201", "tensor"))
        assertEquals(ChipTier.ENTRY, tier("SC9863A", "ums9863a"))
    }

    @Test fun `unknown chips fall back on the fastest core and core count`() {
        assertEquals(ChipTier.UNKNOWN, ChipClassifier.classify("", "", "mystery", "", null, 8).tier)
        assertEquals(ChipTier.UPPER_MID, ChipClassifier.classify("", "x", "mystery", "", 3_200_000, 8).tier)
        assertEquals(ChipTier.MID, ChipClassifier.classify("", "x", "mystery", "", 2_500_000, 8).tier)
        assertEquals(ChipTier.ENTRY, ChipClassifier.classify("", "x", "mystery", "", 1_800_000, 8).tier)
    }

    @Test fun `bigger memory and faster chips are offered bigger models`() {
        val small = suggest(capability(4, ChipTier.MID)).first().model
        val large = suggest(capability(12, ChipTier.FLAGSHIP)).first().model
        assertTrue(LocalModelSuggestions.loadedBytes(large) > LocalModelSuggestions.loadedBytes(small))
        assertEquals(SuggestionKind.BEST, suggest(capability(12, ChipTier.FLAGSHIP)).first().kind)
    }

    @Test fun `a fast chip does not unlock models that do not fit in memory`() {
        val picks = suggest(capability(3, ChipTier.FLAGSHIP))
        assertTrue(picks.isNotEmpty())
        picks.forEach { assertTrue(LocalModelSuggestions.runsOn(phone(3), it.model)) }
        assertTrue(picks.none { it.model.id == "qwen3-4b" })
    }

    @Test fun `an entry chip gets only the smallest models`() {
        suggest(capability(12, ChipTier.ENTRY)).forEach { assertTrue(LocalModelSuggestions.loadedBytes(it.model) <= 600_000_000L) }
    }

    @Test fun `suggestions are distinct capped at three and skip installed abliterated and unsupported models`() {
        val picks = LocalModelSuggestions.suggest(capability(12, ChipTier.FLAGSHIP), LocalModelCatalog.models, setOf("qwen3-4b"), LocalModelFormat.GGUF)
        assertTrue(picks.size in 1..3)
        assertEquals(picks.map { it.model.id }.distinct().size, picks.size)
        assertTrue(picks.none { it.model.id == "qwen3-4b" || it.model.abliterated || it.model.format != LocalModelFormat.GGUF })
        val unsupported = LocalDeviceCapability(phone(12).copy(abi = "armeabi-v7a"), ChipInfo("x", ChipTier.FLAGSHIP))
        assertTrue(suggest(unsupported).isEmpty())
    }

    @Test fun `low free storage removes models that would not finish downloading`() {
        val tight = suggest(capability(12, ChipTier.FLAGSHIP, free = 400L * 1024 * 1024))
        assertTrue(tight.isNotEmpty())
        assertTrue(tight.all { it.model.downloadBytes <= 150_000_000L })
        assertTrue(suggest(capability(12, ChipTier.FLAGSHIP, free = 100L * 1024 * 1024)).isEmpty())
    }

    @Test fun `safetensors suggestions account for the converted size and extra storage`() {
        val picks = suggest(capability(12, ChipTier.FLAGSHIP), LocalModelFormat.SAFETENSORS)
        assertTrue(picks.isNotEmpty())
        picks.forEach { assertEquals(LocalModelFormat.SAFETENSORS, it.model.format) }
        val bf16Only = suggest(capability(12, ChipTier.FLAGSHIP, free = 2 * GIB), LocalModelFormat.SAFETENSORS)
        bf16Only.forEach { assertTrue(it.model.downloadBytes + LocalModelSuggestions.loadedBytes(it.model) < 2 * GIB) }
    }
}
