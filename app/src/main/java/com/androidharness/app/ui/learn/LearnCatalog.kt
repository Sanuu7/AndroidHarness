package com.androidharness.app.ui.learn

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
internal data class LearnCatalog(
    val categories: List<LearnCategory>,
    val lessons: List<LearnLesson>,
) {
    fun lesson(id: String?) = lessons.firstOrNull { it.id == id }

    fun search(query: String, category: String? = null, saved: Set<String>? = null): List<LearnLesson> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        return lessons.filter { lesson ->
            (category == null || lesson.category == category) && (saved == null || lesson.id in saved) &&
                (terms.isEmpty() || lesson.searchText.let { text -> terms.all { it in text } })
        }
    }

    companion object {
        fun parse(text: String): LearnCatalog = Json.decodeFromString(text)
    }
}

@Serializable
internal data class LearnCategory(val id: String, val title: String, val description: String)

@Serializable
internal data class LearnLesson(
    val id: String,
    val category: String,
    val title: String,
    val summary: String,
    val location: String,
    val before: List<String>,
    val steps: List<LearnStep>,
    val outcome: String,
    val limits: List<String>,
    val fixes: List<LearnFix>,
    val related: List<String>,
    val actions: List<LearnAction>,
    val links: List<LearnLink> = emptyList(),
    val example: String? = null,
    val keywords: String = "",
    /** Owning implementations used to review this lesson; never displayed as user instructions. */
    val sources: List<String>,
) {
    val searchText: String get() = buildString {
        append(title).append(' ').append(summary).append(' ').append(location).append(' ').append(keywords)
        before.forEach { append(' ').append(it) }
        steps.forEach { append(' ').append(it.title).append(' ').append(it.body) }
        append(' ').append(outcome)
        limits.forEach { append(' ').append(it) }
        fixes.forEach { append(' ').append(it.problem).append(' ').append(it.solution) }
        actions.forEach { append(' ').append(it.label) }
        example?.let { append(' ').append(it) }
    }.lowercase(Locale.ROOT)
}

@Serializable internal data class LearnStep(val title: String, val body: String)
@Serializable internal data class LearnFix(val problem: String, val solution: String)
@Serializable internal data class LearnAction(val label: String, val kind: String, val target: String)
@Serializable internal data class LearnLink(val label: String, val url: String)

/** Only known app destinations can be opened by guide content. */
enum class LearnDestination { CHAT, CODE, PROJECTS, FILES, TERMINAL, BUILD_TEST, AUTOMATION, PROVIDERS }
