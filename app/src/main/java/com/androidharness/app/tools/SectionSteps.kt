package com.androidharness.app.tools

import com.androidharness.app.data.CustomSettingsItem
import com.androidharness.app.data.CustomSettingsSection
import com.androidharness.app.data.CustomSettingsSections
import com.androidharness.app.ui.settings.SettingsPage
import com.androidharness.app.ui.settings.customSectionSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/**
 * The sections steps of app_settings_change: edits to the user's own settings
 * sections, run in order on a copy of the tree. A step that cannot run is
 * reported with the reason and the rest still run, so one bad path never
 * silently undoes the whole call.
 *
 * A section is named by its path from the top: each part is a section's id or
 * its name, so a step can reach a section an earlier step of the same call made.
 */
internal object SectionSteps {
    const val ACTIONS = "create_section, rename_section, remove_section, move_section, add_item, remove_item, move_item"

    class Outcome(val sections: List<CustomSettingsSection>, val done: List<String>, val errors: List<String>)

    fun run(start: List<CustomSettingsSection>, steps: JsonArray, newId: () -> String = { java.util.UUID.randomUUID().toString() }): Outcome {
        var sections = start
        val done = mutableListOf<String>()
        val errors = mutableListOf<String>()
        steps.forEachIndexed { index, element ->
            val step = element as? JsonObject
            val action = step?.text("action")?.lowercase(Locale.ROOT)
            val label = "Sections step ${index + 1}${action?.let { " ($it)" }.orEmpty()}"
            if (step == null || action == null) { errors += "$label needs an action: $ACTIONS."; return@forEachIndexed }
            runCatching { apply(sections, action, step, newId) }.fold(
                onSuccess = { (next, note) -> sections = next; done += note },
                onFailure = { errors += "$label: ${it.message}" },
            )
        }
        return Outcome(sections, done, errors)
    }

    private fun apply(
        sections: List<CustomSettingsSection>,
        action: String,
        step: JsonObject,
        newId: () -> String,
    ): Pair<List<CustomSettingsSection>, String> = when (action) {
        "create_section" -> {
            val parent = resolve(sections, step.path(), allowEmpty = true)
            val name = step.name() ?: fail("give the new section a name.")
            val icon = step.icon() ?: "TUNE"
            val siblings = if (parent.isEmpty()) sections else find(sections, parent).items.mapNotNull { it.section }
            if (siblings.any { it.name.equals(name, ignoreCase = true) }) fail("there is already a section named \"$name\" ${where(sections, parent)}.")
            var created = CustomSettingsSection(newId(), name, icon)
            (step["items"] as? JsonArray).orEmpty().forEach { spec ->
                created = addItem(created, spec as? JsonObject ?: fail("each of items must be an object."), null)
            }
            val next = if (parent.isEmpty()) {
                if (sections.size >= CustomSettingsSections.MAX_SECTIONS) fail("there are already ${CustomSettingsSections.MAX_SECTIONS} sections, the most allowed. Remove one first.")
                sections + created
            } else {
                if (parent.size + 1 > CustomSettingsSections.MAX_DEPTH) fail("sections go at most ${CustomSettingsSections.MAX_DEPTH} levels deep, so \"$name\" cannot go inside ${label(sections, parent)}.")
                CustomSettingsSections.update(sections, parent) { addChecked(it, CustomSettingsItem(section = created)) }
            }
            next to "created section \"$name\"${if (created.items.isNotEmpty()) " with ${created.items.size} item(s)" else ""} ${where(sections, parent)}"
        }
        "rename_section" -> {
            val path = resolve(sections, step.path())
            val name = step.name()
            val icon = step.icon()
            if (name == null && icon == null) fail("give a new name, a new icon or both.")
            val old = find(sections, path).name
            CustomSettingsSections.update(sections, path) { it.copy(name = name ?: it.name, icon = icon ?: it.icon) } to
                "renamed section \"$old\"${name?.let { " to \"$it\"" }.orEmpty()}${icon?.let { " with icon $it" }.orEmpty()}"
        }
        "remove_section" -> {
            val path = resolve(sections, step.path())
            CustomSettingsSections.remove(sections, path) to "removed section ${label(sections, path)}"
        }
        "move_section" -> {
            val path = resolve(sections, step.path())
            val position = step.position()
            if (path.size == 1) {
                val list = sections.toMutableList()
                val moving = list.removeAt(list.indexOfFirst { it.id == path[0] })
                list.add(position.coerceAtMost(list.size + 1) - 1, moving)
                list to "moved section ${label(sections, path)} to place $position"
            } else {
                val key = CustomSettingsItem(section = find(sections, path)).key
                CustomSettingsSections.update(sections, path.dropLast(1)) { moveItem(it, key, position) } to "moved section ${label(sections, path)} to place $position"
            }
        }
        "add_item" -> {
            val path = resolve(sections, step.path())
            val spec = step["item"] as? JsonObject ?: fail("give the item to add, like {\"setting\": \"theme_mode\"}.")
            CustomSettingsSections.update(sections, path) { addItem(it, spec, step.positionOrNull()) } to
                "added ${describe(spec)} to ${label(sections, path)}"
        }
        "remove_item" -> {
            val path = resolve(sections, step.path())
            val key = itemKey(find(sections, path), step["item"] as? JsonObject)
            CustomSettingsSections.update(sections, path) { CustomSettingsSections.drop(it, key) } to "removed $key from ${label(sections, path)}"
        }
        "move_item" -> {
            val path = resolve(sections, step.path())
            val key = itemKey(find(sections, path), step["item"] as? JsonObject)
            val position = step.position()
            CustomSettingsSections.update(sections, path) { moveItem(it, key, position) } to "moved $key in ${label(sections, path)} to place $position"
        }
        else -> fail("unknown action. Use one of: $ACTIONS.")
    }

    /** Turns a path of ids or names into ids, saying exactly which part was not found. */
    private fun resolve(sections: List<CustomSettingsSection>, path: List<String>, allowEmpty: Boolean = false): List<String> {
        if (path.isEmpty()) return if (allowEmpty) emptyList() else fail("give the section's path, like [\"My section\"].")
        val ids = mutableListOf<String>()
        var level = sections
        for (part in path) {
            val match = level.firstOrNull { it.id == part } ?: level.firstOrNull { it.name.equals(part.trim(), ignoreCase = true) }
                ?: fail("there is no section \"$part\" ${if (ids.isEmpty()) "at the top level" else "inside ${label(sections, ids)}"}. " +
                    "Sections here: ${level.joinToString { "\"${it.name}\"" }.ifEmpty { "none" }}.")
            ids += match.id
            level = match.items.mapNotNull { it.section }
        }
        return ids
    }

    private fun find(sections: List<CustomSettingsSection>, path: List<String>) =
        CustomSettingsSections.find(sections, path) ?: fail("that section no longer exists.")

    private fun label(sections: List<CustomSettingsSection>, path: List<String>): String =
        path.indices.joinToString(" > ", "\"", "\"") { CustomSettingsSections.find(sections, path.take(it + 1))?.name.orEmpty() }

    private fun where(sections: List<CustomSettingsSection>, parent: List<String>) =
        if (parent.isEmpty()) "at the top level" else "inside ${label(sections, parent)}"

    private fun addItem(section: CustomSettingsSection, spec: JsonObject, position: Int?): CustomSettingsSection {
        val item = newItem(spec)
        val added = addChecked(section, item)
        return if (position == null) added else moveItem(added, item.key, position)
    }

    /** [CustomSettingsSections.add] keeps the section as it is when the item cannot go in; this says why instead. */
    private fun addChecked(section: CustomSettingsSection, item: CustomSettingsItem): CustomSettingsSection {
        if (section.items.any { it.key == item.key }) fail("\"${section.name}\" already has ${item.key}.")
        if (section.items.size >= CustomSettingsSections.MAX_ITEMS) fail("\"${section.name}\" already has ${CustomSettingsSections.MAX_ITEMS} items, the most allowed.")
        return CustomSettingsSections.add(section, item)
    }

    private fun newItem(spec: JsonObject): CustomSettingsItem {
        val given = listOf("setting", "page", "header").filter { spec.text(it) != null }
        if (spec["section"] != null) fail("make a sub-section with create_section, giving the parent's path.")
        if (given.size != 1) fail("an item is exactly one of setting (a key from app_settings_find), page (a settings page name) or header (heading text).")
        return when (given.single()) {
            "setting" -> {
                val key = spec.text("setting")!!
                if (customSectionSettings.none { it.key == key }) fail("\"$key\" cannot be a live control in a section. Use a key from app_settings_find " +
                    "(app_lock, auto_lock_minutes and permission_mode stay on their own page; link the page instead).")
                CustomSettingsItem(setting = key)
            }
            "page" -> {
                val name = spec.text("page")!!.uppercase(Locale.ROOT)
                if (SettingsPage.entries.none { it.name == name }) fail("\"${spec.text("page")}\" is not a settings page. Pages are listed by app_settings_find.")
                CustomSettingsItem(page = name)
            }
            else -> {
                val text = spec.text("header")!!
                if (text.length > CustomSettingsSections.MAX_NAME) fail("a heading is at most ${CustomSettingsSections.MAX_NAME} characters.")
                CustomSettingsItem(header = text)
            }
        }
    }

    /** The key of an item already in [section], from {"key": ...} as app_settings_find lists it or from the same fields add_item takes. */
    private fun itemKey(section: CustomSettingsSection, spec: JsonObject?): String {
        spec ?: fail("give the item, like {\"setting\": \"theme_mode\"} or {\"key\": \"page:VOICE\"}.")
        val wanted = spec.text("key")
            ?: spec.text("setting")?.let { "setting:$it" }
            ?: spec.text("page")?.let { "page:${it.uppercase(Locale.ROOT)}" }
            ?: spec.text("header")?.let { "header:$it" }
            ?: spec.text("section")?.let { name -> section.items.firstNotNullOfOrNull { it.section?.takeIf { s -> s.id == name || s.name.equals(name, true) } }?.let { "section:${it.id}" } }
            ?: fail("give the item by key, setting, page, header or section.")
        return section.items.firstOrNull { it.key == wanted || it.key.equals(wanted, ignoreCase = true) }?.key
            ?: fail("\"${section.name}\" has no item $wanted. Its items: ${section.items.joinToString { it.key }.ifEmpty { "none" }}.")
    }

    private fun moveItem(section: CustomSettingsSection, key: String, position: Int): CustomSettingsSection {
        val list = section.items.toMutableList()
        val moving = list.removeAt(list.indexOfFirst { it.key == key })
        list.add(position.coerceAtMost(list.size + 1) - 1, moving)
        return section.copy(items = list)
    }

    private fun describe(spec: JsonObject) = spec.text("setting")?.let { "setting $it" }
        ?: spec.text("page")?.let { "a link to ${it.uppercase(Locale.ROOT)}" } ?: "heading \"${spec.text("header")}\""

    /** The tree for app_settings_find: each section's id, name, icon and items by key in order, sub-sections indented below. */
    fun outline(sections: List<CustomSettingsSection>): String = buildString {
        fun walk(section: CustomSettingsSection, depth: Int) {
            append("  ".repeat(depth)).append("- \"").append(section.name).append("\" id=").append(section.id).append(" icon=").append(section.icon)
            append(if (section.items.isEmpty()) " (empty)" else ", items in order: ${section.items.joinToString { it.key }}").append('\n')
            section.items.mapNotNull { it.section }.forEach { walk(it, depth + 1) }
        }
        sections.forEach { walk(it, 0) }
    }.trimEnd()

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.path(): List<String> = when (val raw = this["path"]) {
        is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(raw.contentOrNull?.takeIf(String::isNotBlank))
        else -> emptyList()
    }

    private fun JsonObject.name(): String? = text("name")?.also {
        if (it.length > CustomSettingsSections.MAX_NAME) fail("a section name is at most ${CustomSettingsSections.MAX_NAME} characters.")
    }

    private fun JsonObject.icon(): String? = text("icon")?.uppercase(Locale.ROOT)?.also {
        if (it !in CustomSettingsSections.ICONS) fail("icon must be one of ${CustomSettingsSections.ICONS.joinToString()}.")
    }

    private fun JsonObject.positionOrNull(): Int? = this["position"]?.let { raw ->
        (raw as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 } ?: fail("position is a place counted from 1.")
    }

    private fun JsonObject.position(): Int = positionOrNull() ?: fail("give the new place as position, counted from 1.")

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
