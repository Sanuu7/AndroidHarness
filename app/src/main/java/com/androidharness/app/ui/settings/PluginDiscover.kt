package com.androidharness.app.ui.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.plugins.*
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.formatTokenCount

/** A pill-shaped search field shared by Discover, Installed and the skills manager. */
@Composable
internal fun ExpressiveSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            when {
                busy -> @OptIn(ExperimentalMaterial3ExpressiveApi::class) LoadingIndicator(Modifier.size(28.dp))
                value.isNotEmpty() -> IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.pm_clear_search))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHigh,
            unfocusedContainerColor = scheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
    )
}

/** A filter chip that opens a menu of choices; it reads as selected whenever the choice is not the default. */
@Composable
internal fun <T> MenuFilterChip(
    label: String,
    value: T,
    default: T,
    options: List<Pair<T, String>>,
    icon: ImageVector,
    onSelect: (T) -> Unit,
    hint: String? = null,
    alwaysShowValue: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: label
    Box {
        FilterChip(
            selected = value != default,
            onClick = { open = true },
            label = { Text(if (value == default && !alwaysShowValue) label else current, maxLines = 1) },
            leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            shape = CircleShape,
            modifier = Modifier.semantics { contentDescription = "$label: $current" },
        )
        ExpressiveDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            hint?.let {
                Text(it, Modifier.padding(horizontal = 18.dp, vertical = 8.dp).widthIn(max = 280.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            options.forEach { (option, text) ->
                ExpressiveDropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(option); open = false },
                    trailingIcon = if (option == value) ({ Icon(Icons.Rounded.Check, null) }) else null,
                )
            }
        }
    }
}

/** Connected toggle buttons for one exclusive choice, in the Material 3 Expressive button group style. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ConnectedChoice(options: List<Triple<T, String, Int?>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (value, label, count) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                if (value == selected) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(ToggleButtonDefaults.IconSize))
                    Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                }
                Text(if (count != null) "$label $count" else label, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun DiscoverControls(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    filter: CatalogFilter,
    onFilter: (CatalogFilter) -> Unit,
    categories: List<String>,
    counts: Map<CatalogStatus, Int>?,
    shown: Int?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpressiveSearchField(query, { onQuery(it.take(160)) }, stringResource(R.string.pm_search_hint), busy = searching, onSearch = onSearch)
        ConnectedChoice(
            listOf(
                Triple(CatalogStatus.ALL, stringResource(R.string.pm_status_all), null),
                Triple(CatalogStatus.INSTALLED, stringResource(R.string.pm_status_installed), counts?.get(CatalogStatus.INSTALLED)),
                Triple(CatalogStatus.NOT_INSTALLED, stringResource(R.string.pm_status_not_installed), null),
                Triple(CatalogStatus.UPDATES, stringResource(R.string.pm_status_updates), counts?.get(CatalogStatus.UPDATES)),
            ),
            filter.status, { onFilter(filter.copy(status = it)) },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuFilterChip(stringResource(R.string.pm_sort), filter.sort, CatalogSort.RELEVANCE, listOf(
                CatalogSort.RELEVANCE to stringResource(R.string.pm_sort_relevance),
                CatalogSort.STARS to stringResource(R.string.pm_sort_stars),
                CatalogSort.UPDATED to stringResource(R.string.pm_sort_updated),
                CatalogSort.DOWNLOADS to stringResource(R.string.pm_sort_downloads),
                CatalogSort.NEWEST to stringResource(R.string.pm_sort_newest),
                CatalogSort.OLDEST to stringResource(R.string.pm_sort_oldest),
                CatalogSort.NAME to stringResource(R.string.pm_sort_name),
            ), Icons.Rounded.SwapVert, { onFilter(filter.copy(sort = it)) }, hint = stringResource(R.string.pm_sort_hint), alwaysShowValue = true)
            MenuFilterChip(stringResource(R.string.pm_filter_type), filter.kind, "all", listOf(
                "all" to stringResource(R.string.pm_type_any), "skill" to stringResource(R.string.pm_kind_skills),
                "plugin" to stringResource(R.string.pm_kind_plugins), "mcp" to stringResource(R.string.pm_kind_mcp),
            ), Icons.Rounded.Category, { onFilter(filter.copy(kind = it)) })
            MenuFilterChip(stringResource(R.string.pm_filter_source), filter.source, null, listOf(
                null to stringResource(R.string.pm_source_any),
                CatalogSource.FEATURED to stringResource(R.string.pm_source_featured),
                CatalogSource.MARKETPLACE to stringResource(R.string.pm_marketplaces),
                CatalogSource.GITHUB to "GitHub",
                CatalogSource.NPM to "npm",
                CatalogSource.MCP_REGISTRY to stringResource(R.string.pm_source_mcp),
            ), Icons.Rounded.Public, { onFilter(filter.copy(source = it)) })
            if (categories.isNotEmpty() || filter.category.isNotBlank()) {
                MenuFilterChip(stringResource(R.string.pm_filter_category), filter.category, "",
                    listOf("" to stringResource(R.string.pm_category_any)) + categories.map { it to it },
                    Icons.Rounded.Label, { onFilter(filter.copy(category = it)) })
            }
            MenuFilterChip(stringResource(R.string.pm_filter_setup), filter.setup, CatalogSetup.ANY, listOf(
                CatalogSetup.ANY to stringResource(R.string.pm_setup_any),
                CatalogSetup.NONE_NEEDED to stringResource(R.string.pm_setup_none),
                CatalogSetup.RUNTIME to stringResource(R.string.pm_setup_runtime),
            ), Icons.Rounded.Memory, { onFilter(filter.copy(setup = it)) }, hint = stringResource(R.string.pm_setup_hint))
        }
        if (shown != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.pm_results, shown, shown), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(filter.active > 0 || filter.sort != CatalogSort.RELEVANCE) {
                TextButton(onClick = { onFilter(CatalogFilter()) }) {
                    Icon(Icons.Rounded.FilterAltOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pm_clear_filters))
                }
            }
        }
    }
}

/** The shaped badge for a skill, plugin or MCP server, so the three kinds can be told apart at a glance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun KindBadge(kind: String, modifier: Modifier = Modifier, size: Int = 48) {
    val scheme = MaterialTheme.colorScheme
    val (shape, container, content) = when (kind) {
        "skill" -> Triple(MaterialShapes.Cookie9Sided, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        "mcp" -> Triple(MaterialShapes.Clover4Leaf, scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> Triple(MaterialShapes.Pill, scheme.primaryContainer, scheme.onPrimaryContainer)
    }
    Surface(modifier.size(size.dp), shape = shape.toShape(), color = container, contentColor = content) {
        Box(contentAlignment = Alignment.Center) {
            Icon(when (kind) { "skill" -> Icons.Rounded.AutoAwesome; "mcp" -> Icons.Rounded.Hub; else -> Icons.Rounded.Extension },
                null, Modifier.size((size * .46f).dp))
        }
    }
}

/** A small tonal pill holding one fact, such as a star count or an update time. */
@Composable
internal fun StatPill(icon: ImageVector?, text: String, modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(modifier, shape = CircleShape, color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            icon?.let { Icon(it, null, Modifier.size(14.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
internal fun scopeName(record: InstalledPlugin): String =
    if (record.scope == "global") stringResource(R.string.config_global) else record.scopeLabel.ifBlank { stringResource(R.string.config_project) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogListingCard(
    entry: CatalogEntry,
    enabled: Boolean,
    preparing: Boolean,
    progress: PluginProgress?,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onUpdate: (InstalledPlugin) -> Unit,
) {
    val listing = entry.listing
    val scheme = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(28.dp),
        color = if (entry.isInstalled) scheme.surfaceContainer else scheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                KindBadge(listing.kind)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(listing.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (listing.builtIn) stringResource(R.string.pm_built_in) else listOf(listing.publisher, entry.section).filter(String::isNotBlank).distinct().joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (listing.homepage.startsWith("https://")) IconButton(onClick = { runCatching { uri.openUri(listing.homepage) } }) {
                    Icon(Icons.Rounded.OpenInNew, stringResource(R.string.pm_open_page, listing.name))
                }
            }
            if (entry.isInstalled) InstalledBanner(entry)
            val description = if (listing.builtIn) starterDescription(listing.name) else listing.description
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listing.stars?.let { stars ->
                    val described = pluralStringResource(R.plurals.pm_stars, stars, stars)
                    StatPill(Icons.Rounded.StarOutline, formatTokenCount(stars.toLong()), Modifier.semantics { contentDescription = described },
                        container = scheme.tertiaryContainer.copy(alpha = .7f), content = scheme.onTertiaryContainer)
                }
                listing.downloads?.takeIf { it > 0 }?.let { StatPill(Icons.Rounded.Download, stringResource(R.string.pm_downloads_month, formatTokenCount(it))) }
                listing.updatedAt?.let { StatPill(Icons.Rounded.Update, relativeTime(it)) }
                StatPill(null, kindLabel(listing.kind))
                if (listing.category.isNotBlank()) StatPill(null, listing.category.replaceFirstChar(Char::titlecase))
                if (listing.version.isNotBlank()) StatPill(null, listing.version.let { if (it.first().isDigit()) "v$it" else it })
            }
            val update = entry.update
            when {
                update != null -> {
                    val label = stringResource(R.string.pm_update_named, listing.name)
                    com.androidharness.app.ui.common.Button(onClick = { onUpdate(update) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = label }) {
                        Icon(Icons.Rounded.Upgrade, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.pm_update))
                    }
                }
                entry.isInstalled && !preparing -> OutlinedButton(onClick = onInstall, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.AddCircleOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pm_install_again))
                }
                else -> {
                    val installLabel = stringResource(R.string.pm_install_named, listing.name)
                    PluginInstallButton(enabled, preparing, progress, Modifier.semantics { contentDescription = installLabel }, onInstall)
                }
            }
            if (preparing) TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}

@Composable
private fun InstalledBanner(entry: CatalogEntry) {
    val scheme = MaterialTheme.colorScheme
    val update = entry.update != null
    Surface(shape = RoundedCornerShape(20.dp), color = if (update) scheme.primaryContainer else scheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (update) Icons.Rounded.NewReleases else Icons.Rounded.CheckCircle, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (update) R.string.pm_update_available else R.string.pm_status_installed), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.pm_installed_in, entry.installed.map { scopeName(it) }.distinct().joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun relativeTime(time: Long): String = stringResource(R.string.pm_updated,
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString())

@Composable
internal fun DiscoverEmpty(onClear: () -> Unit) = Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        Surface(Modifier.size(72.dp), shape = MaterialShapes.Puffy.toShape(), color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp)) }
        }
        Text(stringResource(R.string.pm_no_matches), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.pm_no_matches_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onClear) { Text(stringResource(R.string.pm_clear_filters)) }
    }
}
