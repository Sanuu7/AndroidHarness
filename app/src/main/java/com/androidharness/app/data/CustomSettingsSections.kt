package com.androidharness.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A settings section the user built: their own name, icon, and an ordered list of items. */
@Serializable
data class CustomSettingsSection(
    val id: String,
    val name: String,
    val icon: String = "TUNE",
    val items: List<CustomSettingsItem> = emptyList(),
)

/**
 * One row of a custom section. Exactly one of [setting], [page], [section] and
 * [header] is set: the key of a setting shown as a live control, the name of a
 * settings page to jump to, a sub-section that opens as its own page, or the
 * text of a heading that splits the rows into groups.
 *
 * [anchor] goes with [page] and narrows the jump to one control on that page.
 * It is the resource name of the control's label, which stays the same across
 * app versions where a resource id would not.
 */
@Serializable
data class CustomSettingsItem(
    val setting: String? = null,
    val page: String? = null,
    val section: CustomSettingsSection? = null,
    val anchor: String? = null,
    val header: String? = null,
) {
    /** Stable identity inside its section, used for list keys and reordering. */
    val key: String get() = when {
        setting != null -> "setting:$setting"
        page != null -> if (anchor == null) "page:$page" else "page:$page#$anchor"
        header != null -> "header:$header"
        else -> "section:${section?.id}"
    }
}

/**
 * Tree edits for custom sections. A section is addressed by its path: the ids
 * from a top-level section down to the one meant.
 */
object CustomSettingsSections {
    const val MAX_SECTIONS = 24
    const val MAX_ITEMS = 60
    const val MAX_DEPTH = 3
    const val MAX_NAME = 40

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): List<CustomSettingsSection> =
        runCatching { json.decodeFromString<List<CustomSettingsSection>>(raw ?: "[]") }.getOrDefault(emptyList()).takeIf(::valid).orEmpty()

    fun encode(sections: List<CustomSettingsSection>): String = json.encodeToString(sections)

    fun valid(sections: List<CustomSettingsSection>): Boolean {
        if (sections.size > MAX_SECTIONS) return false
        val ids = mutableSetOf<String>()
        fun check(section: CustomSettingsSection, depth: Int): Boolean =
            depth <= MAX_DEPTH && section.id.length in 1..100 && ids.add(section.id) &&
                section.name.isNotBlank() && section.name.length <= MAX_NAME && section.icon.length <= 40 &&
                section.items.size <= MAX_ITEMS && section.items.map { it.key }.distinct().size == section.items.size &&
                section.items.all { item ->
                    listOfNotNull(item.setting, item.page, item.section, item.header).size == 1 &&
                        (item.setting ?: item.page ?: "x").length in 1..100 &&
                        (item.anchor == null || item.page != null && item.anchor.length in 1..200) &&
                        (item.header == null || item.header.isNotBlank() && item.header.length <= MAX_NAME) &&
                        (item.section == null || check(item.section, depth + 1))
                }
        return sections.all { check(it, 1) }
    }

    fun find(sections: List<CustomSettingsSection>, path: List<String>): CustomSettingsSection? {
        var current = sections.firstOrNull { it.id == path.firstOrNull() } ?: return null
        for (id in path.drop(1)) current = current.items.firstNotNullOfOrNull { item -> item.section?.takeIf { it.id == id } } ?: return null
        return current
    }

    fun update(
        sections: List<CustomSettingsSection>,
        path: List<String>,
        transform: (CustomSettingsSection) -> CustomSettingsSection,
    ): List<CustomSettingsSection> {
        fun inside(section: CustomSettingsSection, rest: List<String>): CustomSettingsSection =
            if (rest.isEmpty()) transform(section)
            else section.copy(items = section.items.map { item ->
                if (item.section?.id == rest.first()) item.copy(section = inside(item.section, rest.drop(1))) else item
            })
        val head = path.firstOrNull() ?: return sections
        return sections.map { if (it.id == head) inside(it, path.drop(1)) else it }
    }

    fun remove(sections: List<CustomSettingsSection>, path: List<String>): List<CustomSettingsSection> = when (path.size) {
        0 -> sections
        1 -> sections.filterNot { it.id == path[0] }
        else -> update(sections, path.dropLast(1)) { parent -> parent.copy(items = parent.items.filterNot { it.section?.id == path.last() }) }
    }

    /** Adds [item] at the end, unless it is already there or the section is full. */
    fun add(section: CustomSettingsSection, item: CustomSettingsItem): CustomSettingsSection = with(section) {
        if (items.size >= MAX_ITEMS || items.any { it.key == item.key }) this else copy(items = items + item)
    }

    /** Moves the top-level section [id] one place up (-1) or down (+1). */
    fun move(sections: List<CustomSettingsSection>, id: String, delta: Int): List<CustomSettingsSection> {
        val from = sections.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in sections.indices) return sections
        return sections.toMutableList().also { it[from] = sections[to]; it[to] = sections[from] }
    }

    fun drop(section: CustomSettingsSection, key: String): CustomSettingsSection =
        section.copy(items = section.items.filterNot { it.key == key })

    /** Swaps the places of two items, which is all a one-step move up or down needs. */
    fun swap(section: CustomSettingsSection, first: String, second: String): CustomSettingsSection = with(section) {
        val a = items.indexOfFirst { it.key == first }
        val b = items.indexOfFirst { it.key == second }
        if (a < 0 || b < 0 || a == b) this
        else copy(items = items.toMutableList().also { it[a] = items[b]; it[b] = items[a] })
    }
}
