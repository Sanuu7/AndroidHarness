package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.selection.triStateToggleable
import com.androidharness.app.R
import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryFolderTarget
import com.androidharness.app.data.MemoryFolders
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * One color per folder, spread around the color wheel from the theme's primary
 * hue so neighbouring folders never look alike and the palette still matches
 * the rest of the app. [key] is "c:CATEGORY" or "f:Name".
 */
@Composable
internal fun memoryFolderColor(key: String, folderNames: List<String>): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminanceIsDark()
    val index = memoryFolderIndex(key, folderNames)
    return remember(scheme.primary, dark, index) { folderHue(scheme.primary, index, dark) }
}

internal fun memoryFolderIndex(key: String, folderNames: List<String>): Int =
    if (key.startsWith("c:")) MemoryCategory.entries.indexOfFirst { it.name == key.removePrefix("c:") }.coerceAtLeast(0)
    else MemoryCategory.entries.size + folderNames.indexOf(key.removePrefix("f:")).coerceAtLeast(0)

internal fun folderHue(primary: Color, index: Int, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
    // The golden angle keeps any number of folders evenly apart.
    val hue = (hsv[0] + index * 137.508f) % 360f
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, if (dark) .46f else .62f, if (dark) .96f else .72f))
    return Color(argb)
}

internal fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < .5f

@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MemoryGroupHeader(
    name: String,
    count: Int,
    color: Color,
    open: Boolean,
    selecting: Boolean,
    selection: ToggleableState,
    onToggleOpen: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "groupArrow")
    Row(
        Modifier.fillMaxWidth()
            .then(if (selecting) Modifier.triStateToggleable(selection, role = Role.Checkbox, onClick = onToggleSelection)
                else Modifier.clickable(onClickLabel = stringResource(if (open) R.string.memory_group_collapse else R.string.memory_group_expand), onClick = onToggleOpen))
            .heightIn(min = 60.dp)
            .padding(start = if (selecting) 8.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) TriStateCheckbox(state = selection, onClick = null)
        Box(Modifier.size(36.dp).background(color.copy(alpha = .22f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(count.toString(), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!selecting) IconButton(onClick = onToggleOpen) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(if (open) R.string.memory_group_collapse else R.string.memory_group_expand),
                modifier = Modifier.rotate(turn))
        }
    }
}

/** The button on the memory page that opens the map. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MemoryMapEntry(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(scheme.primary, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Hub, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.memory_map), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.memory_map_desc), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onPrimaryContainer.copy(alpha = .8f))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * Floats above the list while memories are selected. A popup keeps it pinned to
 * the bottom of the screen however far the settings page has scrolled.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MemorySelectionToolbar(
    count: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    // Placed against the window, not the page: the page is as tall as all its memories.
    val bottomOfWindow = remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = androidx.compose.ui.unit.IntOffset((windowSize.width - popupContentSize.width) / 2, windowSize.height - popupContentSize.height)
        }
    }
    Popup(popupPositionProvider = bottomOfWindow, properties = PopupProperties(focusable = false)) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(shown, modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.memory_selection_clear)) }
                Text(stringResource(R.string.settings_bulk_selected_count, count.toString()),
                    Modifier.align(Alignment.CenterVertically).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
                IconButton(enabled = enabled, onClick = onSelectAll) {
                    Icon(if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                        contentDescription = stringResource(if (allSelected) R.string.memory_select_none else R.string.memory_select_all))
                }
                IconButton(enabled = enabled, onClick = onMove) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = stringResource(R.string.memory_move))
                }
                IconButton(enabled = enabled, onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.settings_action_delete))
                }
            }
        }
    }
}

/** Picks where the selected memories go: a built-in folder, one of the user's, or a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryMoveDialog(
    count: Int,
    folders: List<String>,
    builtInLabels: Map<MemoryCategory, String>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onMove: (MemoryFolderTarget) -> Unit,
) {
    var destination by remember { mutableStateOf<MemoryFolderTarget?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val target = if (creating) newName.trim().takeIf { it.isNotEmpty() && it.length <= MemoryFolders.MAX_NAME }?.let { MemoryFolderTarget(MemoryCategory.FACT, it) }
        else destination
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.memory_move_title, count, count)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryCategory.entries.forEach { category ->
                        val option = MemoryFolderTarget(category, null)
                        FilterChip(enabled = !saving, selected = !creating && destination == option,
                            onClick = { creating = false; destination = option }, label = { Text(builtInLabels.getValue(category)) })
                    }
                    folders.forEach { name ->
                        val option = MemoryFolderTarget(MemoryCategory.FACT, name)
                        FilterChip(enabled = !saving, selected = !creating && destination == option,
                            onClick = { creating = false; destination = option }, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    FilterChip(enabled = !saving, selected = creating, onClick = { creating = true },
                        label = { Text(stringResource(R.string.memory_folder_new)) })
                }
                if (creating) OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    enabled = !saving,
                    singleLine = true,
                    label = { Text(stringResource(R.string.memory_folder_name)) },
                    isError = newName.length > MemoryFolders.MAX_NAME,
                    supportingText = { Text("${newName.length} / ${MemoryFolders.MAX_NAME}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && target != null, onClick = { target?.let(onMove) }) { Text(stringResource(R.string.memory_move)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}
