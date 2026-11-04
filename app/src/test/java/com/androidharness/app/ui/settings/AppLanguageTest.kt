package com.androidharness.app.ui.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun legacyTagalogOverridesNormalizeWithoutLosingRegionOrFallbackLanguages() {
        assertEquals("fil-PH,en-US", canonicalAppLanguageTags("tl-PH,en-US"))
        assertEquals("fil-Latn-PH", canonicalAppLanguageTags("tl-Latn-PH"))
        assertEquals("fil-PH", canonicalAppLanguageTags("fil-PH"))
        assertEquals("", canonicalAppLanguageTags(""))
    }

    @Test fun systemDefaultAndUnsupportedLanguageRemainAvailable() {
        assertEquals("", selectedAppLanguageTag(null))
        assertEquals("en", selectedAppLanguageTag(Locale.forLanguageTag("zz")))
    }

    @Test fun traditionalChineseScriptAndRegionsSelectTheTraditionalOption() {
        for (tag in listOf("zh-Hant", "zh-Hant-CN", "zh-TW", "zh-HK", "zh-MO")) {
            assertEquals(tag, "zh-Hant", selectedAppLanguageTag(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun explicitSimplifiedScriptTakesPrecedenceOverRegion() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-TW")) {
            assertEquals(tag, "zh-CN", selectedAppLanguageTag(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun newRegionalLocalesKeepTheirLanguageSelection() {
        for ((tag, expected) in listOf("fr-CA" to "fr", "hi-IN" to "hi", "ko-KR" to "ko",
            "ta-LK" to "ta", "bn-BD" to "bn", "fil-PH" to "fil")) {
            assertEquals(tag, expected, selectedAppLanguageTag(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun legacyAliasesAndExistingRegionalSelectionsStillWork() {
        for ((tag, expected) in listOf("tl-PH" to "fil", "in-ID" to "id", "id-ID" to "id",
            "pt-BR" to "pt-BR", "tr-TR" to "tr", "ms-MY" to "ms", "th-TH" to "th")) {
            assertEquals(tag, expected, selectedAppLanguageTag(Locale.forLanguageTag(tag)))
        }
    }
}
