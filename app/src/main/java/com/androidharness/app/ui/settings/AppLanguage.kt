package com.androidharness.app.ui.settings

import java.util.Locale

/** Android can prioritize English over legacy Tagalog when both are requested. */
internal fun canonicalAppLanguageTags(tags: String): String = tags.split(',').joinToString(",") { tag ->
    val locale = Locale.forLanguageTag(tag)
    if (locale.language == "tl") Locale.Builder().setLocale(locale).setLanguage("fil").build().toLanguageTag()
    else tag
}

/** Normalize regional and legacy locale tags to a language offered by the picker. */
internal fun selectedAppLanguageTag(locale: Locale?): String = when (locale?.language) {
    null -> ""
    "zh" -> when {
        locale.script == "Hant" -> "zh-Hant"
        locale.script == "Hans" -> "zh-CN"
        locale.country in setOf("TW", "HK", "MO") -> "zh-Hant"
        else -> "zh-CN"
    }
    "pt" -> "pt-BR"
    "id", "in" -> "id"
    "fil", "tl" -> "fil"
    "en", "ru", "ar", "fa", "de", "es", "ja", "si", "vi", "tr", "ms", "th",
    "fr", "hi", "ko", "ta", "bn" -> locale.language
    else -> "en"
}
