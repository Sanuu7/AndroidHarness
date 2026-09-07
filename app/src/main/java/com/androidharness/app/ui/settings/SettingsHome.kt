package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.androidharness.app.ui.common.ProviderGlyph
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsHome(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val results = remember(query) { matchingSettingsPages(query) }
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search settings") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
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
                Text("No matching settings", style = MaterialTheme.typography.titleMedium)
                Text("Try “theme”, “voice” or “permissions”.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        results.groupBy { it.group }.forEach { (group, pages) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    group,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                SettingsPanel(Modifier.fillMaxWidth()) {
                    pages.forEachIndexed { index, page ->
                        Surface(
                            onClick = { onOpen(page) },
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
                                    Icon(settingsIcon(page), null, tint = colors.primary, modifier = Modifier.size(21.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(page.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(page.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (index != pages.lastIndex) HorizontalDivider(
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
            page.description,
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

private fun settingsIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.MODELS -> ProviderGlyph
    SettingsPage.AGENT -> Icons.Outlined.Tune
    SettingsPage.CHAT -> Icons.Outlined.ChatBubbleOutline
    SettingsPage.VOICE -> Icons.Outlined.Mic
    SettingsPage.SKILLS -> Icons.Outlined.AutoStories
    SettingsPage.APPEARANCE -> Icons.Outlined.Palette
    SettingsPage.PRIVACY -> Icons.Outlined.Shield
    SettingsPage.GITHUB -> Icons.Outlined.Code
    SettingsPage.SEARCH -> Icons.Outlined.TravelExplore
    SettingsPage.MCP -> Icons.Outlined.Extension
    SettingsPage.WORKSPACE -> Icons.Outlined.Folder
    SettingsPage.ENVIRONMENT -> Icons.Outlined.Terminal
    SettingsPage.BACKUP -> Icons.Outlined.Archive
    SettingsPage.USAGE -> Icons.Outlined.BarChart
    SettingsPage.UPDATES -> Icons.Outlined.SystemUpdate
    SettingsPage.SETUP -> Icons.Outlined.Checklist
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
        com.androidharness.app.data.ThemeMode.SYSTEM -> "System"
        com.androidharness.app.data.ThemeMode.LIGHT -> "Light"
        com.androidharness.app.data.ThemeMode.DARK -> "Dark"
        com.androidharness.app.data.ThemeMode.AMOLED -> "AMOLED"
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surface,
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
                if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = colors.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
