@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import androidx.compose.material.icons.rounded.CloudSync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.androidharness.app.ui.theme.motionBlur
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.androidharness.app.ui.common.FilledTonalButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.androidharness.app.R
import com.androidharness.app.ui.common.ProviderGlyph
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.IconButton
import androidx.compose.material.icons.rounded.Vibration

@Composable
internal fun SettingsHome(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (SettingsPage, Int?) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    customSections: List<com.androidharness.app.data.CustomSettingsSection> = emptyList(),
    onOpenSection: (String) -> Unit = {},
    onAddSection: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val index = remember(context) { settingsSearchIndex(context) }
    val results = remember(query, index) { index.search(query) }
    val sections = remember(query, customSections) { customSections.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    val groups = remember(results, query) {
        settingsDisplayGroups(results.filterNot { query.isBlank() && it.page == SettingsPage.LEARN }).entries.toList()
    }
    LazyColumn(
        modifier.motionBlur(state),
        state = state,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (query.isBlank()) item(key = "learn") {
            com.androidharness.app.ui.learn.LearnEntryCard(onClick = { onOpen(SettingsPage.LEARN, null) }, modifier = Modifier.settingsEntrance(0))
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_search_clear))
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = colors.surfaceContainerHigh,
                    focusedContainerColor = colors.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Same footprint as the search field above it, so the two read as a pair.
        if (query.isBlank()) item(key = "add-section") {
            FilledTonalButton(
                onClick = onAddSection,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.custom_sections_add), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (sections.isNotEmpty()) item(key = "custom-sections") { CustomSectionsHomeGroup(sections, onOpenSection) }
        if (results.isEmpty() && sections.isEmpty()) item(key = "empty") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsHeroBadge(seed = 3, size = 72.dp, settingsScreen = true) { tint -> Icon(Icons.Rounded.Search, null, tint = tint, modifier = Modifier.size(32.dp)) }
                Text(stringResource(R.string.settings_search_empty_title), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(stringResource(R.string.settings_search_empty_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        groups.forEachIndexed { groupIndex, (group, entries) ->
            item(key = "group:$group:${entries.first().page}") {
                SettingsHomeGroup(group, entries, SettingsTone.entries[groupIndex % 3], onOpen, Modifier.settingsEntrance(groupIndex + 1))
            }
        }
        item(key = "end") { Spacer(Modifier.height(12.dp)) }
    }
}

/** One labelled group of the settings home: a heading over a segmented list of pages. */
@Composable
private fun SettingsHomeGroup(
    @androidx.annotation.StringRes group: Int,
    entries: List<SettingsSearchEntry>,
    tone: SettingsTone,
    onOpen: (SettingsPage, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entries.size > 1) SettingsSectionLabel(stringResource(group), top = 0.dp)
        val titles = entries.map { stringResource(it.title) }
        val descriptions = entries.map { entry -> entry.description?.let { stringResource(it) } }
        val paths = entries.map { settingsResultPath(it) }
        val availability = entries.map { rememberSettingsAvailability(it) }
        SettingsGroup {
            entries.forEachIndexed { i, entry ->
                navItem(
                    seed = entry.page.ordinal, tone = tone, title = titles[i], subtitle = descriptions[i], path = paths[i],
                    onClick = { onOpen(entry.page, entry.anchor) },
                    extra = { SettingsAvailabilityTag(availability[i], Modifier.padding(top = 2.dp)) },
                ) { tint -> SettingsPageIcon(entry.page, tint, Modifier.size(22.dp)) }
            }
        }
    }
}

/** The page's icon, including the one page drawn from a resource rather than a vector. */
@Composable
internal fun SettingsPageIcon(page: SettingsPage, tint: Color, modifier: Modifier = Modifier) {
    if (page == SettingsPage.MEMORY) Icon(painterResource(R.drawable.ic_brain), null, modifier, tint = tint)
    else Icon(settingsIcon(page), null, modifier, tint = tint)
}

@Composable
internal fun DesktopSettingsNavigation(
    selected: SettingsPage?, query: String, onQueryChange: (String) -> Unit,
    onOpen: (SettingsPage, Int?) -> Unit, modifier: Modifier = Modifier,
    customSections: List<com.androidharness.app.data.CustomSettingsSection> = emptyList(),
    selectedSection: String? = null,
    onOpenSection: (String) -> Unit = {},
    onAddSection: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val index = remember(context) { settingsSearchIndex(context) }
    val results = remember(query, index) { index.search(query) }
    Surface(modifier, color = scheme.surfaceContainerLow, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxSize().semantics { contentDescription = context.getString(R.string.settings_search_categories) }) {
            com.androidharness.app.ui.learn.LearnEntryCard(
                onClick = { onOpen(SettingsPage.LEARN, null) }, compact = true, modifier = Modifier.padding(12.dp),
            )
            OutlinedTextField(query, onQueryChange, singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_search_placeholder), style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.settings_search_clear), Modifier.size(18.dp))
                } },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(12.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                val sections = customSections.filter { it.name.contains(query.trim(), ignoreCase = true) }
                if (query.isBlank() || sections.isNotEmpty()) item(key = "custom-sections-header") {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.custom_sections_group), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        IconButton(onClick = onAddSection) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.custom_sections_add), Modifier.size(18.dp))
                        }
                    }
                }
                items(sections, key = { "custom-section:${it.id}" }) { section ->
                    SettingsRailItem(section.id == selectedSection, { onOpenSection(section.id) },
                        icon = { Icon(customSectionIcon(section.icon), null, Modifier.size(18.dp)) }) {
                        Text(section.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (results.isEmpty() && sections.isEmpty()) item { Text(stringResource(R.string.settings_search_empty_title), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                settingsDisplayGroups(results.filterNot { query.isBlank() && it.page == SettingsPage.LEARN }).forEach { (group, entries) ->
                    if (entries.size > 1) item(key = "group:$group") {
                        Text(stringResource(group), Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelMediumEmphasized, color = scheme.primary)
                    }
                    items(entries, key = { "${it.page}:${it.title}:${it.anchor}" }) { entry ->
                        val active = entry.page == selected || entry.page == SettingsPage.CODING && selected in settingsCodingPages
                        SettingsRailItem(active, { onOpen(entry.page, entry.anchor) },
                            icon = { SettingsPageIcon(entry.page, LocalContentColor.current, Modifier.size(18.dp)) }) {
                            Text(stringResource(entry.title), style = MaterialTheme.typography.labelLarge, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            settingsResultPath(entry)?.let { path ->
                                Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = LocalContentColor.current.copy(alpha = 0.72f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where a setting lives, such as "Coding › Environment › Advanced device access"; null for a page itself. */
@Composable
internal fun settingsResultPath(entry: SettingsSearchEntry): String? {
    if (entry.primary) return null
    val parts = buildList {
        if (!entry.page.onHome) add(stringResource(SettingsPage.CODING.title))
        add(stringResource(entry.page.title))
        entry.within.forEach { add(stringResource(it)) }
    }
    return parts.joinToString(" › ")
}

@Composable
internal fun SettingsPageIntro(page: SettingsPage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingsHeroBadge(seed = page.ordinal, settingsScreen = true) { tint ->
            if (page == SettingsPage.MEMORY) Icon(painterResource(R.drawable.ic_brain), null, tint = tint, modifier = Modifier.size(30.dp))
            else Icon(settingsIcon(page), null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Text(
            stringResource(page.description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SettingsPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(SettingsOuterCorner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = settingsPanelBorder(),
    ) {
        Column(content = content)
    }
}

internal fun settingsIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.LEARN -> Icons.Rounded.AutoStories
    SettingsPage.LANGUAGE -> Icons.Rounded.Translate
    SettingsPage.CHAT_OPTIONS -> Icons.Rounded.ChatBubbleOutline
    SettingsPage.MEMORY -> Icons.Rounded.Memory
    SettingsPage.CODING -> Icons.Rounded.Code
    SettingsPage.NINE_ROUTER -> Icons.Rounded.AccountTree
    SettingsPage.CONNECTED_ACCOUNTS -> Icons.Rounded.AccountCircle
    SettingsPage.MODELS -> ProviderGlyph
    SettingsPage.SUBAGENT -> Icons.Rounded.AccountTree
    SettingsPage.LOCAL_MODELS -> Icons.Rounded.Memory
    SettingsPage.PLUGINS -> Icons.Rounded.Extension
    SettingsPage.CONFIGURATION -> Icons.Rounded.DataObject
    SettingsPage.AGENT -> Icons.Rounded.Tune
    SettingsPage.CHAT -> Icons.Rounded.ChatBubbleOutline
    SettingsPage.CODE_INTELLIGENCE -> Icons.Rounded.AccountTree
    SettingsPage.CAVEMAN -> Icons.Rounded.ShortText
    SettingsPage.VOICE -> Icons.Rounded.Mic
    SettingsPage.LIVE_MODE -> Icons.Rounded.GraphicEq
    SettingsPage.SKILLS -> Icons.Rounded.AutoStories
    SettingsPage.APPEARANCE -> Icons.Rounded.Palette
    SettingsPage.PRIVACY -> Icons.Rounded.Shield
    SettingsPage.RUMBLE -> Icons.Rounded.Vibration
    SettingsPage.GITHUB -> Icons.Rounded.Code
    SettingsPage.SEARCH -> Icons.Rounded.TravelExplore
    SettingsPage.MCP -> Icons.Rounded.Extension
    SettingsPage.TELEGRAM -> Icons.AutoMirrored.Rounded.Send
    SettingsPage.WORKSPACE -> Icons.Rounded.Folder
    SettingsPage.BACKGROUND -> Icons.Rounded.Notifications
    SettingsPage.STORAGE -> Icons.Rounded.Storage
    SettingsPage.ENVIRONMENT -> Icons.Rounded.Terminal
    SettingsPage.TROUBLESHOOTING -> Icons.Rounded.Build
    SettingsPage.PROVIDER_DEBUG -> Icons.Rounded.BugReport
    SettingsPage.BACKUP -> Icons.Rounded.Archive
    SettingsPage.SYNC -> Icons.Rounded.CloudSync
    SettingsPage.FTP_SERVER -> Icons.Rounded.Storage
    SettingsPage.USAGE -> Icons.Rounded.BarChart
    SettingsPage.UPDATES -> Icons.Rounded.SystemUpdate
    SettingsPage.SETUP -> Icons.Rounded.Checklist
    SettingsPage.ABOUT -> Icons.Rounded.Info
}

@Composable
internal fun ThemeChoice(
    mode: com.androidharness.app.data.ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = when (mode) {
        com.androidharness.app.data.ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
        com.androidharness.app.data.ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
        com.androidharness.app.data.ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
        com.androidharness.app.data.ThemeMode.AMOLED -> stringResource(R.string.settings_theme_amoled)
    }
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "themeChoiceCorner")
    val fill by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "themeChoiceFill")
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        color = fill,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val swatch = when (mode) {
                com.androidharness.app.data.ThemeMode.LIGHT -> androidx.compose.ui.graphics.Color(0xFFF5F5F5)
                com.androidharness.app.data.ThemeMode.DARK -> androidx.compose.ui.graphics.Color(0xFF25252A)
                com.androidharness.app.data.ThemeMode.AMOLED -> androidx.compose.ui.graphics.Color.Black
                else -> colors.surfaceContainerHigh
            }
            Column(
                Modifier.fillMaxWidth().background(swatch, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.55f).height(5.dp).background(colors.primary, RoundedCornerShape(3.dp)))
                Box(Modifier.fillMaxWidth(0.85f).height(5.dp).background(colors.primary.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
                Box(Modifier.fillMaxWidth(0.7f).height(5.dp).background(colors.primary.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (selected) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.settings_selected), tint = colors.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
