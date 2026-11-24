package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.SettingChange
import com.androidharness.app.core.SettingTodo
import com.androidharness.app.core.SettingsReceipt
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.CustomSettingsSection
import com.androidharness.app.data.CustomSettingsSections
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.ThemePalette
import com.androidharness.app.ui.settings.AgentSetting
import com.androidharness.app.ui.settings.AgentSettingsCatalog
import com.androidharness.app.ui.settings.RangeUnit
import com.androidharness.app.ui.settings.SettingKind
import com.androidharness.app.ui.settings.SettingsPage
import com.androidharness.app.ui.settings.formatSettingNumber
import com.androidharness.app.ui.theme.colorScheme
import kotlinx.coroutines.launch

/** Where each change stands now, read from the live settings rather than stored. */
private enum class ChangeState { APPLIED, UNDONE, MOVED_ON }

private fun SettingChange.state(setting: AgentSetting?, settings: AppSettings): ChangeState {
    val now = setting?.read(settings) ?: return ChangeState.MOVED_ON
    return when (now) {
        after -> ChangeState.APPLIED
        before -> ChangeState.UNDONE
        else -> ChangeState.MOVED_ON
    }
}

/**
 * The card an app_settings_change call leaves under the turn: what changed,
 * what still needs the user, and one tap to undo or to open each setting.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsChangeCard(
    receipt: SettingsReceipt,
    settings: AppSettings,
    repository: SettingsRepository,
    onOpen: (SettingsPage, Int?) -> Unit,
    /** Opens Settings at its home, where Your sections lives. */
    onOpenSections: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val rows = receipt.changes.map { it to AgentSettingsCatalog[it.key] }
    val states = rows.map { (change, setting) -> change.state(setting, settings) }
    val canUndo = states.any { it == ChangeState.APPLIED }
    val canRedo = !canUndo && states.any { it == ChangeState.UNDONE }

    fun apply(undo: Boolean) {
        working = true
        scope.launch {
            try {
                rows.zip(states).forEach { (row, state) ->
                    val (change, setting) = row
                    if (setting == null || state != (if (undo) ChangeState.APPLIED else ChangeState.UNDONE)) return@forEach
                    val target = if (undo) change.before else change.after
                    if (target.startsWith("custom:") && settings.customPalettes.none { it.id == target.removePrefix("custom:") }) return@forEach
                    runCatching { setting.write(repository, target) }
                }
            } finally { working = false }
        }
    }

    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("settings-change-card"),
        ) {
            Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(vertical = 12.dp)) {
                Header(receipt, states)
                if (rows.isNotEmpty()) Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    rows.zip(states).forEach { (row, state) ->
                        if (row.first.key == AgentSettingsCatalog.SECTIONS) SectionsRow(row.first, state, onOpenSections)
                        else ChangeRow(row.first, row.second, state, settings, onOpen)
                    }
                }
                receipt.needsUser.forEach { TodoRow(it, settings, onOpen) }
                receipt.skipped.forEach { SkippedRow(it) }
                if (canUndo || canRedo || rows.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(canUndo, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "undo redo") { undo ->
                        when {
                            undo -> FilledTonalButton(onClick = { apply(undo = true) }, enabled = !working) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(18.dp))
                                Text(stringResource(if (rows.size > 1) R.string.as_undo_all else R.string.as_undo), Modifier.padding(start = 8.dp))
                            }
                            canRedo -> FilledTonalButton(onClick = { apply(undo = false) }, enabled = !working) {
                                Icon(Icons.AutoMirrored.Outlined.Redo, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.as_redo), Modifier.padding(start = 8.dp))
                            }
                            else -> Box {}
                        }
                    }
                    Box(Modifier.weight(1f))
                    rows.firstOrNull()?.let { (change, first) ->
                        val open = if (change.key == AgentSettingsCatalog.SECTIONS) onOpenSections
                            else first?.let { { onOpen(it.page, it.anchor) } }
                        open?.let { TextButton(onClick = it) { Text(stringResource(R.string.as_open_settings)) } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Header(receipt: SettingsReceipt, states: List<ChangeState>) {
    val scheme = MaterialTheme.colorScheme
    val applied = states.count { it == ChangeState.APPLIED }
    val title = when {
        receipt.changes.isEmpty() && receipt.needsUser.isNotEmpty() -> stringResource(R.string.as_title_needs_you)
        receipt.changes.isEmpty() -> stringResource(R.string.as_title_not_changed)
        applied == 0 && states.all { it == ChangeState.UNDONE } -> stringResource(R.string.as_title_undone)
        else -> stringResource(R.string.as_title_changed)
    }
    val subtitle = buildList {
        if (receipt.changes.isNotEmpty()) add(pluralStringResource(R.plurals.as_changes_count, receipt.changes.size, receipt.changes.size))
        if (receipt.needsUser.isNotEmpty()) add(pluralStringResource(R.plurals.as_needs_you_count, receipt.needsUser.size, receipt.needsUser.size))
    }.joinToString(" · ")
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = scheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Tune, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChangeRow(change: SettingChange, setting: AgentSetting?, state: ChangeState, settings: AppSettings, onOpen: (SettingsPage, Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val clickable = setting != null
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .then(if (clickable) Modifier.clickable(role = Role.Button) { onOpen(setting!!.page, setting.anchor) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            setting?.let { Text(stringResource(it.page.title), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(setting?.let { it.name ?: stringResource(it.title) } ?: change.key, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (state) {
                ChangeState.UNDONE -> StateLabel(stringResource(R.string.as_state_undone), scheme.onSurfaceVariant)
                ChangeState.MOVED_ON -> StateLabel(stringResource(R.string.as_state_moved_on), scheme.tertiary)
                ChangeState.APPLIED -> Unit
            }
        }
        val faded = state == ChangeState.UNDONE
        ValueChip(setting, change.before, null, settings, emphasized = faded, struck = !faded)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
        ValueChip(setting, change.after, change.label, settings, emphasized = !faded, struck = faded)
        if (clickable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.as_open_setting), Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
    }
}

/** One part of what a call did to Your sections, worked out from the tree before and after so the card can say it in the user's language. */
internal sealed interface SectionsDiff {
    data class Added(val name: String) : SectionsDiff
    data class Removed(val name: String) : SectionsDiff
    data class Renamed(val name: String) : SectionsDiff
    data class Rearranged(val name: String) : SectionsDiff
    data class Items(val name: String, val count: Int) : SectionsDiff
    data object RearrangedAll : SectionsDiff
}

internal fun sectionsDiff(before: String, after: String): List<SectionsDiff> {
    class Node(val section: CustomSettingsSection, val parent: String?)
    fun flatten(sections: List<CustomSettingsSection>): Map<String, Node> = buildMap {
        fun walk(section: CustomSettingsSection, parent: String?) {
            put(section.id, Node(section, parent))
            section.items.mapNotNull { it.section }.forEach { walk(it, section.id) }
        }
        sections.forEach { walk(it, null) }
    }
    val old = CustomSettingsSections.decode(before)
    val new = CustomSettingsSections.decode(after)
    val was = flatten(old)
    val now = flatten(new)
    return buildList {
        // A new section's own sub-sections are part of adding it, so only the outermost one is named.
        now.values.filter { it.section.id !in was && it.parent?.let { p -> p in was } != false }.forEach { add(SectionsDiff.Added(it.section.name)) }
        was.values.filter { it.section.id !in now && it.parent?.let { p -> p in now } != false }.forEach { add(SectionsDiff.Removed(it.section.name)) }
        now.values.filter { it.section.id in was }.forEach { node ->
            val prior = was.getValue(node.section.id).section
            val section = node.section
            if (prior.name != section.name || prior.icon != section.icon) add(SectionsDiff.Renamed(section.name))
            val keys = section.items.filter { it.section == null }.map { it.key }
            val priorKeys = prior.items.filter { it.section == null }.map { it.key }
            val changed = (keys - priorKeys.toSet()).size + (priorKeys - keys.toSet()).size
            val order = section.items.map { it.key }
            val priorOrder = prior.items.map { it.key }
            if (changed > 0) add(SectionsDiff.Items(section.name, changed))
            else if (order.filter { it in priorOrder } != priorOrder.filter { it in order }) add(SectionsDiff.Rearranged(section.name))
        }
        val common = new.map { it.id }.filter { id -> old.any { it.id == id } }
        if (common != old.map { it.id }.filter { it in common }) add(SectionsDiff.RearrangedAll)
    }
}

@Composable
private fun SectionsRow(change: SettingChange, state: ChangeState, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val parts = sectionsDiff(change.before, change.after).map { part ->
        when (part) {
            is SectionsDiff.Added -> stringResource(R.string.as_sections_added, part.name)
            is SectionsDiff.Removed -> stringResource(R.string.as_sections_removed, part.name)
            is SectionsDiff.Renamed -> stringResource(R.string.as_sections_renamed, part.name)
            is SectionsDiff.Rearranged -> stringResource(R.string.as_sections_rearranged, part.name)
            is SectionsDiff.Items -> pluralStringResource(R.plurals.as_sections_items_changed, part.count, part.count, part.name)
            SectionsDiff.RearrangedAll -> stringResource(R.string.as_sections_rearranged_all)
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.custom_sections_group), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                textDecoration = if (state == ChangeState.UNDONE) TextDecoration.LineThrough else null)
            when (state) {
                ChangeState.UNDONE -> StateLabel(stringResource(R.string.as_state_undone), scheme.onSurfaceVariant)
                ChangeState.MOVED_ON -> StateLabel(stringResource(R.string.as_state_moved_on), scheme.tertiary)
                ChangeState.APPLIED -> Unit
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.as_open_settings), Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun StateLabel(text: String, color: Color) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)

@Composable
private fun ValueChip(setting: AgentSetting?, value: String, label: String?, settings: AppSettings, emphasized: Boolean, struck: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val swatch = swatchFor(value, settings)
    Row(
        Modifier.widthIn(max = 120.dp).clip(CircleShape)
            .background(if (emphasized) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        swatch?.let { Box(Modifier.size(12.dp).clip(CircleShape).background(it).border(1.dp, scheme.outline.copy(alpha = .4f), CircleShape)) }
        Text(
            settingValueLabel(setting, value, label, settings),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            textDecoration = if (struck && !emphasized) TextDecoration.LineThrough else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun swatchFor(value: String, settings: AppSettings): Color? {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when {
        value.startsWith("custom:") -> settings.customPalettes.firstOrNull { it.id == value.removePrefix("custom:") }?.let { Color(it.primary.toInt()) }
        else -> ThemePalette.entries.firstOrNull { it.name == value }?.colorScheme(dark)?.primary
    }
}

/** A value as the user reads it: On/Off, 70%, a localized option name, or a palette name. */
@Composable
internal fun settingValueLabel(setting: AgentSetting?, value: String, label: String? = null, settings: AppSettings = AppSettings()): String {
    val kind = setting?.kind ?: return value
    return when (kind) {
        SettingKind.Toggle -> stringResource(if (value == "true") R.string.as_on else R.string.as_off)
        is SettingKind.Range -> value.toDoubleOrNull()?.let { number ->
            if (kind.unit == RangeUnit.COUNT && number == 0.0) stringResource(R.string.as_unlimited)
            else formatSettingNumber(number) + kind.unit.suffix
        } ?: value
        is SettingKind.Choice -> kind.labels[value]?.let { stringResource(it) }
            ?: value.takeIf { it.startsWith("custom:") }?.let { id -> settings.customPalettes.firstOrNull { it.id == id.removePrefix("custom:") }?.name ?: label ?: stringResource(R.string.as_custom_palette) }
            ?: value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        is SettingKind.Text -> value.ifBlank { stringResource(R.string.as_empty) }.let { if (it.length > 24) it.take(23) + "…" else it }
    }
}

@Composable
private fun TodoRow(todo: SettingTodo, settings: AppSettings, onOpen: (SettingsPage, Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val page = SettingsPage.entries.firstOrNull { it.name == todo.page } ?: return
    val anchor = todo.key?.let { AgentSettingsCatalog[it]?.anchor }
    Surface(
        onClick = { onOpen(page, anchor) },
        shape = MaterialTheme.shapes.large,
        color = scheme.tertiaryContainer.copy(alpha = .55f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.TouchApp, null, Modifier.size(20.dp), tint = scheme.onTertiaryContainer)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.as_needs_you_label, stringResource(page.title)), style = MaterialTheme.typography.labelSmall, color = scheme.onTertiaryContainer)
                Text(todo.note, style = MaterialTheme.typography.bodyMedium, color = scheme.onTertiaryContainer)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.as_open_setting), Modifier.size(20.dp), tint = scheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun SkippedRow(todo: SettingTodo) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp).padding(top = 2.dp), tint = scheme.error)
        Text(todo.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}
