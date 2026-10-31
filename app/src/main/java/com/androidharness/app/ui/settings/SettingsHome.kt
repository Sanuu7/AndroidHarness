package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import androidx.compose.material.icons.outlined.CloudSync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Send
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
import androidx.compose.material.icons.outlined.Vibration

@Composable
internal fun SettingsHome(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (SettingsPage, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val results = remember(query, context) { matchingSettingsEntries(query, context) }
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_clear))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = colors.surfaceContainerLow,
                unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (results.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.settings_search_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_search_empty_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        settingsDisplayGroups(results, query).forEach { (group, entries) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val showHeader = entries.size > 1
                if (showHeader) Text(
                    stringResource(group),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                SettingsPanel(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        Surface(
                            onClick = { onOpen(entry.page, entry.anchor) },
                            color = colors.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    Modifier.size(40.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (entry.page == SettingsPage.MEMORY)
                                        Icon(painterResource(R.drawable.ic_brain), null, tint = colors.primary, modifier = Modifier.size(21.dp))
                                    else Icon(settingsIcon(entry.page), null, tint = colors.primary, modifier = Modifier.size(21.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(stringResource(entry.title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(stringResource(entry.description), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    SettingsAvailabilityTag(rememberSettingsAvailability(entry), Modifier.padding(top = 2.dp))
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (index != entries.lastIndex) HorizontalDivider(
                            modifier = Modifier.padding(start = 70.dp, end = 16.dp),
                            color = colors.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun DesktopSettingsNavigation(
    selected: SettingsPage?, query: String, onQueryChange: (String) -> Unit,
    onOpen: (SettingsPage, Int?) -> Unit, modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val results = remember(query, context) { matchingSettingsEntries(query, context) }
    Surface(modifier, color = scheme.surfaceContainerLow, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxSize().semantics { contentDescription = context.getString(R.string.settings_search_categories) }) {
            OutlinedTextField(query, onQueryChange, singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_search_placeholder), style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.settings_search_clear), Modifier.size(18.dp))
                } },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(12.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                if (results.isEmpty()) item { Text(stringResource(R.string.settings_search_empty_title), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                settingsDisplayGroups(results, query).forEach { (group, entries) ->
                    if (entries.size > 1) {
                        item { Text(stringResource(group), Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
                    }
                    items(entries, key = { "${it.page}:${it.title}" }) { entry ->
                        val active = entry.page == selected || entry.page == SettingsPage.CODING && selected in settingsCodingPages
                        Surface(onClick = { onOpen(entry.page, entry.anchor) },
                            color = if (active) scheme.secondaryContainer else scheme.surfaceContainerLow,
                            contentColor = if (active) scheme.onSecondaryContainer else scheme.onSurface,
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (entry.page == SettingsPage.MEMORY)
                                    Icon(painterResource(R.drawable.ic_brain), null, Modifier.size(18.dp))
                                else Icon(settingsIcon(entry.page), null, Modifier.size(18.dp))
                                Text(stringResource(entry.title), style = MaterialTheme.typography.labelLarge, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsPageIntro(page: SettingsPage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(settingsIcon(page), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(content = content)
    }
}

internal fun settingsIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.LANGUAGE -> Icons.Outlined.Translate
    SettingsPage.CHAT_OPTIONS -> Icons.Outlined.ChatBubbleOutline
    SettingsPage.MEMORY -> Icons.Outlined.Memory
    SettingsPage.CODING -> Icons.Outlined.Code
    SettingsPage.NINE_ROUTER -> Icons.Outlined.AccountTree
    SettingsPage.CONNECTED_ACCOUNTS -> Icons.Outlined.AccountCircle
    SettingsPage.MODELS -> ProviderGlyph
    SettingsPage.SUBAGENT -> Icons.Outlined.AccountTree
    SettingsPage.LOCAL_MODELS -> Icons.Outlined.Memory
    SettingsPage.CONFIGURATION -> Icons.Outlined.DataObject
    SettingsPage.AGENT -> Icons.Outlined.Tune
    SettingsPage.CHAT -> Icons.Outlined.ChatBubbleOutline
    SettingsPage.CODE_INTELLIGENCE -> Icons.Outlined.AccountTree
    SettingsPage.CAVEMAN -> Icons.Outlined.ShortText
    SettingsPage.VOICE -> Icons.Outlined.Mic
    SettingsPage.SKILLS -> Icons.Outlined.AutoStories
    SettingsPage.APPEARANCE -> Icons.Outlined.Palette
    SettingsPage.PRIVACY -> Icons.Outlined.Shield
    SettingsPage.RUMBLE -> Icons.Outlined.Vibration
    SettingsPage.GITHUB -> Icons.Outlined.Code
    SettingsPage.SEARCH -> Icons.Outlined.TravelExplore
    SettingsPage.MCP -> Icons.Outlined.Extension
    SettingsPage.TELEGRAM -> Icons.AutoMirrored.Outlined.Send
    SettingsPage.WORKSPACE -> Icons.Outlined.Folder
    SettingsPage.BACKGROUND -> Icons.Outlined.Notifications
    SettingsPage.STORAGE -> Icons.Outlined.Storage
    SettingsPage.ENVIRONMENT -> Icons.Outlined.Terminal
    SettingsPage.TROUBLESHOOTING -> Icons.Outlined.Build
    SettingsPage.BACKUP -> Icons.Outlined.Archive
    SettingsPage.SYNC -> Icons.Outlined.CloudSync
    SettingsPage.FTP_SERVER -> Icons.Outlined.Storage
    SettingsPage.USAGE -> Icons.Outlined.BarChart
    SettingsPage.UPDATES -> Icons.Outlined.SystemUpdate
    SettingsPage.SETUP -> Icons.Outlined.Checklist
    SettingsPage.ABOUT -> Icons.Outlined.Info
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
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surface,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val swatch = when (mode) {
                com.androidharness.app.data.ThemeMode.LIGHT -> androidx.compose.ui.graphics.Color(0xFFF5F5F5)
                com.androidharness.app.data.ThemeMode.DARK -> androidx.compose.ui.graphics.Color(0xFF25252A)
                com.androidharness.app.data.ThemeMode.AMOLED -> androidx.compose.ui.graphics.Color.Black
                else -> colors.surfaceContainerHigh
            }
            Column(
                Modifier.fillMaxWidth().background(swatch, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.55f).height(5.dp).background(colors.primary, RoundedCornerShape(3.dp)))
                Box(Modifier.fillMaxWidth(0.85f).height(5.dp).background(colors.primary.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
                Box(Modifier.fillMaxWidth(0.7f).height(5.dp).background(colors.primary.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (selected) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.settings_selected), tint = colors.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
