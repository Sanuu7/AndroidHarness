package com.androidharness.app.ui.learn

import android.content.Context
import java.util.Locale

/** Locales that ship as guide-<language>.json next to the English guide. */
private val directGuideLanguages = setOf(
    "ru", "ar", "fa", "de", "es", "ja", "si", "vi", "tr", "ms", "th",
    "fr", "hi", "ko", "ta", "bn",
)

/** The bundled guide for [locale], or null when English is the right choice. */
internal fun learnGuideAsset(locale: Locale): String? = when {
    locale.language == "zh" ->
        if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) {
            "learn/guide-zh-Hant.json"
        } else {
            "learn/guide-zh-CN.json"
        }
    locale.language == "pt" -> "learn/guide-pt-BR.json"
    locale.language == "id" || locale.language == "in" -> "learn/guide-id.json"
    locale.language == "fil" || locale.language == "tl" -> "learn/guide-fil.json"
    locale.language in directGuideLanguages -> "learn/guide-${locale.language}.json"
    else -> null
}

/** Load the guide for the current app language, falling back to English. */
internal fun loadLearnGuide(context: Context): Result<LearnCatalog> {
    val locale = context.resources.configuration.locales[0]
    val candidates = listOfNotNull(learnGuideAsset(locale), "learn/guide.json")
    var failure: Throwable? = null
    for (asset in candidates) {
        val parsed = runCatching {
            context.assets.open(asset).bufferedReader().use { LearnCatalog.parse(it.readText()) }
        }
        if (parsed.isSuccess) return parsed
        failure = parsed.exceptionOrNull()
    }
    return Result.failure(failure ?: IllegalStateException("No Learn guide is bundled"))
}
