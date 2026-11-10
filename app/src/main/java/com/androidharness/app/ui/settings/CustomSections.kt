package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.CustomSettingsItem
import com.androidharness.app.data.CustomSettingsSection
import com.androidharness.app.data.CustomSettingsSections
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.FilledTonalIconButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import kotlinx.coroutines.launch

internal val customSectionIcons: List<Pair<String, ImageVector>> = listOf(
    "TUNE" to Icons.Outlined.Tune, "STAR" to Icons.Outlined.StarOutline, "FAVORITE" to Icons.Outlined.FavoriteBorder,
    "BOLT" to Icons.Outlined.Bolt, "ROCKET" to Icons.Outlined.RocketLaunch, "CODE" to Icons.Outlined.Code,
    "CHAT" to Icons.Outlined.ChatBubbleOutline, "PALETTE" to Icons.Outlined.Palette, "MIC" to Icons.Outlined.Mic,
    "SHIELD" to Icons.Outlined.Shield, "BUILD" to Icons.Outlined.Build, "FOLDER" to Icons.Outlined.Folder,
)

internal fun customSectionIcon(name: String): ImageVector =
    customSectionIcons.firstOrNull { it.first == name }?.second ?: Icons.Outlined.Tune

/**
 * Settings whose own page asks for something first (the screen lock, or a
 * warning before full access). They stay there; a section can link to the page.
 */
private val guardedSettings = setOf("app_lock", "auto_lock_minutes", "permission_mode")

internal val customSectionSettings: List<AgentSetting> by lazy { AgentSettingsCatalog.all.filterNot { it.key in guardedSettings } }

private fun CustomSettingsItem.catalogSetting(): AgentSetting? =
    setting?.takeIf { it !in guardedSettings }?.let { AgentSettingsCatalog[it] }

private fun CustomSettingsItem.settingsPage(): SettingsPage? =
    page?.takeIf { anchor == null }?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }

/**
 * Shortcuts to the controls that have no live control here: everything the
 * settings search can land on, minus what the catalog already covers. Each is
 * saved under the resource name of its label, see [CustomSettingsItem.anchor].
 */
private class SettingLinks(context: android.content.Context) {
    val entries: List<Pair<CustomSettingsItem, SettingsSearchEntry>>
    private val byKey: Map<String, SettingsSearchEntry>

    init {
        val live = customSectionSettings.flatMap { listOfNotNull(it.page to it.title, it.anchor?.let { anchor -> it.page to anchor }) }.toSet()
        entries = settingsControlEntries.filter { (it.page to it.title) !in live }.distinctBy { it.page to it.title }.mapNotNull { entry ->
            runCatching { context.resources.getResourceEntryName(entry.title) }.getOrNull()
                ?.let { name -> CustomSettingsItem(page = entry.page.name, anchor = name) to entry }
        }
        byKey = entries.associate { it.first.key to it.second }
    }

    fun entry(item: CustomSettingsItem): SettingsSearchEntry? = if (item.anchor != null) byKey[item.key] else null

    /** False for a row saved by a newer version of the app that this one cannot show. */
    fun resolves(item: CustomSettingsItem): Boolean =
        item.catalogSetting() != null || item.settingsPage() != null || item.section != null || item.header != null || entry(item) != null
}

@Composable
private fun rememberSettingLinks(): SettingLinks {
    val context = LocalContext.current
    return remember(context) { SettingLinks(context) }
}

@Composable
private fun itemTitle(item: CustomSettingsItem, links: SettingLinks): String =
    item.catalogSetting()?.let { stringResource(it.title) } ?: item.settingsPage()?.let { stringResource(it.title) }
        ?: links.entry(item)?.let { stringResource(it.title) } ?: item.header ?: item.section?.name.orEmpty()

@Composable
private fun itemCount(section: CustomSettingsSection, links: SettingLinks): String {
    val count = section.items.count { it.header == null && links.resolves(it) }
    return pluralStringResource(R.plurals.custom_sections_items, count, count)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SectionBadge(icon: String, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(size).background(scheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
        Icon(customSectionIcon(icon), null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
    }
}

/** The user's sections on the settings home, in the same list style as the built-in pages. */
@Composable
internal fun CustomSectionsHomeGroup(sections: List<CustomSettingsSection>, onOpen: (String) -> Unit) {
    if (sections.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val links = rememberSettingLinks()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.custom_sections_group),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsPanel(Modifier.fillMaxWidth()) {
            sections.forEachIndexed { index, section ->
                Surface(onClick = { onOpen(section.id) }, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SectionBadge(section.icon, 40.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(section.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(itemCount(section, links), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                if (index != sections.lastIndex) HorizontalDivider(
                    modifier = Modifier.padding(start = 70.dp, end = 16.dp),
                    color = colors.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/** Name and icon for a new section, or for one being renamed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomSectionEditorDialog(
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String) -> Unit,
    initialName: String = "",
    initialIcon: String = customSectionIcons.first().first,
) {
    val scheme = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf(initialName) }
    var icon by rememberSaveable { mutableStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomSettingsSections.MAX_NAME) },
                    label = { Text(stringResource(R.string.custom_sections_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("custom-section-name"),
                )
                Text(stringResource(R.string.custom_sections_icon), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                FlowRow(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    customSectionIcons.forEach { (id, vector) ->
                        val selected = id == icon
                        // The picked icon squares off, the expressive way of showing selection.
                        val corner by animateDpAsState(if (selected) 14.dp else 24.dp, label = "icon-corner")
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(corner))
                                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
                                .selectable(selected, role = Role.RadioButton, onClick = { icon = id }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(vector, id.lowercase(), tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name.trim(), icon) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_sections_cancel)) } },
    )
}

@Composable
private fun DeleteSectionDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_sections_delete_title, name)) },
        text = { Text(stringResource(R.string.custom_sections_delete_body)) },
        confirmButton = {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.custom_sections_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_sections_cancel)) } },
    )
}

/** Rows of one group share a card look: wide corners on the outside, tight ones where rows meet. */
private fun groupedShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) 22.dp else 6.dp
    val bottom = if (last) 22.dp else 6.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * One custom section as a page: its settings as live controls, shortcuts to
 * pages, and sub-sections. Edit mode swaps the controls for move and remove
 * buttons. Every change goes through [onChange] and is saved at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CustomSectionPage(
    section: CustomSettingsSection,
    depth: Int,
    settings: AppSettings,
    repository: SettingsRepository,
    onChange: ((CustomSettingsSection) -> CustomSettingsSection) -> Unit,
    onDelete: () -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenPage: (SettingsPage, Int?) -> Unit,
    modifier: Modifier = Modifier,
    /** Moves this section among its neighbours on the settings home; null where that does not apply. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val links = rememberSettingLinks()
    var addingHeading by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var addingSub by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var removingSub by remember { mutableStateOf<CustomSettingsSection?>(null) }
    val rows = remember(section, links) { section.items.filter { links.resolves(it) } }
    val full = section.items.size >= CustomSettingsSections.MAX_ITEMS

    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp)) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionBadge(section.icon, 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(section.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(itemCount(section, links), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { editing = !editing }, modifier = Modifier.testTag("custom-section-edit")) {
                            Icon(if (editing) Icons.Rounded.Check else Icons.Outlined.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (editing) R.string.custom_sections_done else R.string.custom_sections_arrange))
                        }
                    }
                    if (editing) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { renaming = true }) {
                            Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.custom_sections_rename))
                        }
                        OutlinedButton(onClick = { deleting = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.custom_sections_delete))
                        }
                        if (depth == 1) {
                            FilledTonalIconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                                Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.custom_sections_move_section_up))
                            }
                            FilledTonalIconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.custom_sections_move_section_down))
                            }
                        }
                    }
                }
            }
            if (rows.isEmpty()) item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(88.dp).background(scheme.tertiaryContainer, MaterialShapes.Cookie6Sided.toShape()), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(36.dp))
                    }
                    Text(stringResource(R.string.custom_sections_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.custom_sections_empty_body), style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            itemsIndexed(rows, key = { _, item -> item.key }) { index, item ->
                val placement = Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>())
                val heading = item.header
                if (heading != null && !editing) {
                    Text(heading, style = MaterialTheme.typography.titleSmall, color = scheme.primary,
                        modifier = placement.fillMaxWidth().padding(start = 8.dp, top = if (index == 0) 0.dp else 18.dp, bottom = 8.dp))
                    return@itemsIndexed
                }
                // A heading starts a new card group; while editing, everything is one plain list.
                val first = index == 0 || !editing && rows[index - 1].header != null
                val last = index == rows.lastIndex || !editing && rows[index + 1].header != null
                Surface(
                    shape = groupedShape(first, last),
                    color = scheme.surfaceContainer,
                    modifier = placement.fillMaxWidth().padding(bottom = 2.dp),
                ) {
                    val setting = item.catalogSetting()
                    val page = item.settingsPage()
                    val link = links.entry(item)
                    val sub = item.section
                    when {
                        editing -> ArrangeRow(
                            title = itemTitle(item, links),
                            icon = when {
                                sub != null -> customSectionIcon(sub.icon)
                                heading != null -> Icons.Outlined.Title
                                else -> (page ?: link?.page)?.let(::settingsIcon) ?: kindIcon(setting?.kind)
                            },
                            onUp = rows.getOrNull(index - 1)?.let { above -> { onChange { CustomSettingsSections.swap(it, item.key, above.key) } } },
                            onDown = rows.getOrNull(index + 1)?.let { below -> { onChange { CustomSettingsSections.swap(it, item.key, below.key) } } },
                            onRemove = {
                                if (sub != null && sub.items.isNotEmpty()) removingSub = sub
                                else onChange { CustomSettingsSections.drop(it, item.key) }
                            },
                        )
                        setting != null -> SettingControl(setting, settings, repository)
                        page != null -> LinkRow(
                            leading = { Icon(settingsIcon(page), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
                            title = stringResource(page.title), subtitle = stringResource(R.string.custom_sections_kind_page),
                            onClick = { onOpenPage(page, null) },
                        )
                        link != null -> LinkRow(
                            leading = { Icon(settingsIcon(link.page), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
                            title = stringResource(link.title), subtitle = settingsResultPath(link) ?: stringResource(link.page.title),
                            onClick = { onOpenPage(link.page, link.anchor) },
                        )
                        sub != null -> LinkRow(
                            leading = { SectionBadge(sub.icon, 32.dp) },
                            title = sub.name, subtitle = itemCount(sub, links), onClick = { onOpenSection(sub.id) },
                        )
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().testTag("custom-section-add-settings")) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.custom_sections_add_settings))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (depth < CustomSettingsSections.MAX_DEPTH) FilledTonalButton(onClick = { addingSub = true }, enabled = !full, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.custom_sections_add_sub), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(onClick = { addingHeading = true }, enabled = !full, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Title, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.custom_sections_add_heading), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (addingHeading) ValueDialog(
        title = stringResource(R.string.custom_sections_heading), initial = "", number = false, singleLine = true,
        maxLength = CustomSettingsSections.MAX_NAME, error = null, valid = { it.isNotBlank() },
        onDismiss = { addingHeading = false },
        onSave = { text -> onChange { CustomSettingsSections.add(it, CustomSettingsItem(header = text.trim())) }; addingHeading = false },
    )
    if (picking) CustomSectionPickerSheet(section, onChange, onDismiss = { picking = false })
    if (renaming) CustomSectionEditorDialog(
        title = stringResource(R.string.custom_sections_edit), confirm = stringResource(R.string.custom_sections_save),
        onDismiss = { renaming = false }, initialName = section.name, initialIcon = section.icon,
        onSave = { name, icon -> onChange { it.copy(name = name, icon = icon) }; renaming = false },
    )
    if (addingSub) CustomSectionEditorDialog(
        title = stringResource(R.string.custom_sections_new_sub), confirm = stringResource(R.string.custom_sections_create),
        onDismiss = { addingSub = false },
        onSave = { name, icon ->
            val created = CustomSettingsSection(java.util.UUID.randomUUID().toString(), name, icon)
            onChange { CustomSettingsSections.add(it, CustomSettingsItem(section = created)) }
            addingSub = false
        },
    )
    if (deleting) DeleteSectionDialog(section.name, onDismiss = { deleting = false }, onDelete = { deleting = false; onDelete() })
    removingSub?.let { sub ->
        DeleteSectionDialog(sub.name, onDismiss = { removingSub = null }, onDelete = {
            onChange { CustomSettingsSections.drop(it, "section:${sub.id}") }
            removingSub = null
        })
    }
}

@Composable
private fun RowLabels(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LinkRow(leading: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading()
        RowLabels(title, subtitle, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ArrangeRow(title: String, icon: ImageVector, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) {
            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.custom_sections_move_up, title))
        }
        IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) {
            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.custom_sections_move_down, title))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.RemoveCircleOutline, stringResource(R.string.custom_sections_remove, title), tint = scheme.error)
        }
    }
}

private fun kindIcon(kind: SettingKind?): ImageVector = when (kind) {
    SettingKind.Toggle -> Icons.Outlined.ToggleOn
    is SettingKind.Range -> Icons.Outlined.Tune
    is SettingKind.Choice -> Icons.Outlined.Checklist
    is SettingKind.Text -> Icons.Outlined.Notes
    null -> Icons.Outlined.Settings
}

/** A range small enough to drag across; wider ones are typed in. */
private fun SettingKind.Range.slides(): Boolean = max - min <= 1_000.0

@Composable
private fun kindLabel(kind: SettingKind): String = stringResource(when (kind) {
    SettingKind.Toggle -> R.string.custom_sections_kind_switch
    is SettingKind.Range -> if (kind.slides()) R.string.custom_sections_kind_slider else R.string.custom_sections_kind_number
    is SettingKind.Choice -> R.string.custom_sections_kind_choice
    is SettingKind.Text -> R.string.custom_sections_kind_text
})

@Composable
private fun choiceLabel(kind: SettingKind.Choice, option: String, settings: AppSettings): String =
    kind.labels[option]?.let { stringResource(it) }
        ?: settings.customPalettes.firstOrNull { "custom:${it.id}" == option }?.name
        ?: if (option.isNotEmpty() && option == option.uppercase()) option.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() } else option

/** The live control for one catalog setting, picked by the shape of its value. */
@Composable
private fun SettingControl(setting: AgentSetting, settings: AppSettings, repository: SettingsRepository) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(settings)
    val value = setting.read(settings)
    val title = stringResource(setting.title)
    val where = stringResource(setting.page.title)
    fun write(raw: String) {
        scope.launch {
            val next = setting.normalize(raw, latest).getOrNull() ?: return@launch
            try {
                // Some switches do nothing unless another one is on too, so that one comes along.
                if (next == "true") setting.requires?.let { (key, needed) ->
                    AgentSettingsCatalog[key]?.takeIf { it.read(latest) != needed }?.write(repository, needed)
                }
                setting.write(repository, next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.localizedMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }
    }
    val rowPadding = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
    when (val kind = setting.kind) {
        SettingKind.Toggle -> Row(
            Modifier.fillMaxWidth().toggleable(value == "true", role = Role.Switch, onValueChange = { write(it.toString()) }).then(rowPadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowLabels(title, where, Modifier.weight(1f))
            Switch(value == "true", onCheckedChange = null)
        }
        is SettingKind.Range -> if (kind.slides()) {
            val stored = value.toFloatOrNull() ?: kind.min.toFloat()
            var draft by remember(stored) { mutableFloatStateOf(stored) }
            fun snapped(): String = formatSettingNumber(
                if (kind.wholeNumber || kind.max - kind.min >= 10.0) Math.round(draft).toDouble() else Math.round(draft * 20.0) / 20.0,
            )
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RowLabels(title, where, Modifier.weight(1f))
                    Text(snapped() + kind.unit.suffix, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                }
                Slider(
                    value = draft.coerceIn(kind.min.toFloat(), kind.max.toFloat()),
                    onValueChange = { draft = it },
                    valueRange = kind.min.toFloat()..kind.max.toFloat(),
                    onValueChangeFinished = { write(snapped()) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
                )
            }
        } else {
            var open by rememberSaveable { mutableStateOf(false) }
            ValueRow(title, where, value + kind.unit.suffix, rowPadding) { open = true }
            if (open) ValueDialog(
                title = title, initial = value, number = true, maxLength = 12,
                error = stringResource(R.string.custom_sections_number_range, formatSettingNumber(kind.min), formatSettingNumber(kind.max)),
                valid = { setting.normalize(it, latest).isSuccess },
                onDismiss = { open = false }, onSave = { write(it); open = false },
            )
        }
        is SettingKind.Choice -> {
            var open by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.DropdownList) { open = true }.then(rowPadding),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RowLabels(title, where, Modifier.weight(1f))
                Box {
                    Text(choiceLabel(kind, value, settings), style = MaterialTheme.typography.labelLarge, color = scheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    ExpressiveDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        kind.options(settings).forEach { option ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(choiceLabel(kind, option, settings)) },
                                onClick = { open = false; if (option != value) write(option) },
                                trailingIcon = if (option == value) { { Icon(Icons.Rounded.Check, null, tint = scheme.primary) } } else null,
                            )
                        }
                    }
                }
                Icon(Icons.Outlined.UnfoldMore, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        is SettingKind.Text -> {
            var open by rememberSaveable { mutableStateOf(false) }
            ValueRow(title, value.ifBlank { stringResource(R.string.custom_sections_text_empty) }, null, rowPadding) { open = true }
            if (open) ValueDialog(
                title = title, initial = value, number = false, maxLength = kind.maxLength, error = null, valid = { true },
                onDismiss = { open = false }, onSave = { write(it); open = false },
            )
        }
    }
}

@Composable
private fun ValueRow(title: String, subtitle: String, value: String?, padding: Modifier, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).then(padding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RowLabels(title, subtitle, Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ValueDialog(
    title: String, initial: String, number: Boolean, maxLength: Int, error: String?,
    valid: (String) -> Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit,
    singleLine: Boolean = number,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val ok = valid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                maxLines = if (singleLine) 1 else 8,
                isError = !ok,
                supportingText = if (!ok && error != null) { { Text(error) } } else null,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }, enabled = ok) { Text(stringResource(R.string.custom_sections_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_sections_cancel)) } },
    )
}

private enum class PickerFilter(val label: Int) {
    ALL(R.string.custom_sections_filter_all), SWITCHES(R.string.custom_sections_filter_switches),
    SLIDERS(R.string.custom_sections_filter_sliders), CHOICES(R.string.custom_sections_filter_choices),
    TEXT(R.string.custom_sections_filter_text), SHORTCUTS(R.string.custom_sections_filter_shortcuts),
    PAGES(R.string.custom_sections_filter_pages),
}

/** One thing the picker can add: a live control, or a shortcut to a control on its own page. */
private class PickerEntry(
    val item: CustomSettingsItem, val title: Int, val page: SettingsPage, val filter: PickerFilter,
    val keywords: String, val kind: SettingKind?,
)

private fun pickerEntries(links: SettingLinks): List<PickerEntry> =
    customSectionSettings.map { setting ->
        val filter = when (setting.kind) {
            SettingKind.Toggle -> PickerFilter.SWITCHES
            is SettingKind.Range -> PickerFilter.SLIDERS
            is SettingKind.Choice -> PickerFilter.CHOICES
            is SettingKind.Text -> PickerFilter.TEXT
        }
        PickerEntry(CustomSettingsItem(setting = setting.key), setting.title, setting.page, filter,
            "${setting.keywords} ${setting.key.replace('_', ' ')}", setting.kind)
    } + links.entries.map { (item, entry) -> PickerEntry(item, entry.title, entry.page, PickerFilter.SHORTCUTS, entry.keywords, null) }

/** Checks add and remove at once, so closing the sheet never loses anything. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSectionPickerSheet(
    section: CustomSettingsSection,
    onChange: ((CustomSettingsSection) -> CustomSettingsSection) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val links = rememberSettingLinks()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PickerFilter.ALL) }
    val chosen = section.items.map { it.key }.toSet()
    val full = section.items.size >= CustomSettingsSections.MAX_ITEMS
    val terms = remember(query) { query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    fun matches(text: String) = text.lowercase().let { hay -> terms.all { it in hay } }
    val everything = remember(links) { pickerEntries(links) }
    val groups = remember(terms, filter, everything) {
        everything.filter { (filter == PickerFilter.ALL || it.filter == filter) &&
            matches("${context.getString(it.title)} ${context.getString(it.page.title)} ${it.keywords}") }
            .groupBy { it.page }.toSortedMap(compareBy { it.ordinal })
    }
    val pages = remember(terms, filter) {
        if (filter != PickerFilter.ALL && filter != PickerFilter.PAGES) emptyList()
        else SettingsPage.entries.filter { matches("${context.getString(it.title)} ${it.keywords}") }
    }
    fun toggle(item: CustomSettingsItem, on: Boolean) =
        onChange { if (on) CustomSettingsSections.add(it, item) else CustomSettingsSections.drop(it, item.key) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .9f).dp)
                .navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.custom_sections_picker_title, section.name), style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.custom_sections_done)) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.custom_sections_picker_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PickerFilter.entries, key = { it.name }) { option ->
                    FilterChip(selected = option == filter, onClick = { filter = option }, label = { Text(stringResource(option.label)) },
                        leadingIcon = if (option == filter) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null)
                }
            }
            if (full) Text(stringResource(R.string.custom_sections_picker_full), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            LazyColumn(Modifier.weight(1f)) {
                groups.forEach { (page, list) ->
                    item(key = "group:${page.name}") { PickerHeader(stringResource(page.title)) }
                    items(list, key = { it.item.key }) { entry ->
                        val checked = entry.item.key in chosen
                        val subtitle = entry.kind?.let { kindLabel(it) } ?: stringResource(R.string.custom_sections_kind_shortcut)
                        PickerRow(stringResource(entry.title), subtitle, checked, enabled = checked || !full) { toggle(entry.item, it) }
                    }
                }
                if (pages.isNotEmpty()) {
                    item(key = "group-pages") { PickerHeader(stringResource(R.string.custom_sections_picker_pages)) }
                    items(pages, key = { "page:${it.name}" }) { page ->
                        val item = CustomSettingsItem(page = page.name)
                        val checked = item.key in chosen
                        PickerRow(stringResource(page.title), stringResource(R.string.custom_sections_kind_page), checked, enabled = checked || !full) { toggle(item, it) }
                    }
                }
                if (groups.isEmpty() && pages.isEmpty()) item(key = "empty") {
                    Text(stringResource(R.string.custom_sections_picker_empty), Modifier.padding(vertical = 24.dp), color = scheme.onSurfaceVariant)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(stringResource(R.string.custom_sections_done))
            }
        }
    }
}

@Composable
private fun PickerHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        RowLabels(title, subtitle, Modifier.weight(1f))
    }
}
