package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.CustomThemePalette
import com.androidharness.app.data.parseThemeHex
import com.androidharness.app.data.themeHex
import com.androidharness.app.ui.theme.colorScheme
import java.util.UUID
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun CustomPaletteSection(
    palettes: List<CustomThemePalette>, activeId: String?,
    onSave: (CustomThemePalette) -> Unit, onSelect: (String) -> Unit, onRemove: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<CustomThemePalette?>(null) }
    var dialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomThemePalette?>(null) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.theme_custom_title), style = MaterialTheme.typography.titleSmall)
        palettes.forEach { palette ->
            val selected = palette.id == activeId
            val scheme = remember(palette, dark) { palette.colorScheme(dark) }
            Surface(shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = { onSelect(palette.id) })) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaletteSwatch(scheme, Modifier.size(38.dp))
                    Text(palette.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { editing = palette; dialog = true }) {
                        Icon(Icons.Rounded.Edit, stringResource(R.string.theme_custom_edit))
                    }
                }
            }
        }
        FilledTonalButton(onClick = { editing = null; dialog = true }, enabled = palettes.size < 24) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.theme_custom_add))
        }
        if (palettes.size >= 24) Text(stringResource(R.string.theme_custom_limit), style = MaterialTheme.typography.bodySmall)
    }
    if (dialog) CustomPaletteDialog(editing, onDismiss = { dialog = false },
        onSave = { onSave(it); dialog = false }, onDelete = { deleting = it; dialog = false })
    deleting?.let { palette -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.theme_custom_delete)) }, text = { Text(stringResource(R.string.theme_custom_delete_message)) },
        confirmButton = { TextButton(onClick = { onRemove(palette.id); deleting = null }) { Text(stringResource(R.string.theme_custom_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_action_cancel)) } }) }
}

@Composable
internal fun CustomPaletteDialog(existing: CustomThemePalette?, onDismiss: () -> Unit, onSave: (CustomThemePalette) -> Unit, onDelete: (CustomThemePalette) -> Unit = {}) {
    val defaultName = stringResource(R.string.theme_custom_default)
    val initial = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFF
    var name by remember { mutableStateOf(existing?.name ?: defaultName) }
    var main by remember { mutableLongStateOf(existing?.primary ?: initial) }
    var accent by remember { mutableStateOf(existing?.secondary) }
    var highlight by remember { mutableStateOf(existing?.tertiary) }
    var role by remember { mutableIntStateOf(0) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val draft = CustomThemePalette(existing?.id ?: "preview", name, main, accent, highlight)
    val scheme = remember(main, accent, highlight, dark) { draft.colorScheme(dark) }
    val active = when (role) { 1 -> accent ?: (scheme.secondary.toArgb().toLong() and 0xFFFFFFFF); 2 -> highlight ?: (scheme.tertiary.toArgb().toLong() and 0xFFFFFFFF); else -> main }
    var hex by remember(role, active) { mutableStateOf(themeHex(active).removePrefix("#")) }
    fun setColor(color: Long) { when (role) { 1 -> accent = color; 2 -> highlight = color; else -> main = color } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.theme_custom_add else R.string.theme_custom_edit)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.theme_custom_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.theme_custom_hint), style = MaterialTheme.typography.bodySmall)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.theme_custom_main, R.string.theme_custom_accent, R.string.theme_custom_highlight).forEachIndexed { index, label ->
                    FilterChip(selected = role == index, onClick = { role = index }, label = { Text(stringResource(label), maxLines = 1) })
                }
            }
            key(role) { HsvColorPicker(active, ::setColor) }
            OutlinedTextField(hex, { value -> hex = value.take(7); parseThemeHex(hex)?.let(::setColor) },
                label = { Text(stringResource(R.string.theme_custom_hex)) }, prefix = { Text("#") },
                isError = parseThemeHex(hex) == null, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (parseThemeHex(hex) == null) Text(stringResource(R.string.theme_custom_invalid_hex), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { accent = null; highlight = null }) { Text(stringResource(R.string.theme_custom_automatic)) }
            Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainer, contentColor = scheme.onSurface) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaletteSwatch(scheme, Modifier.size(40.dp)); Text(stringResource(R.string.theme_custom_preview), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (existing != null) TextButton(onClick = { onDelete(existing) }) { Text(stringResource(R.string.theme_custom_delete), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && parseThemeHex(hex) != null,
            onClick = { onSave(draft.copy(id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim())) }) { Text(stringResource(R.string.theme_custom_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
}

@Composable
private fun HsvColorPicker(color: Long, onColor: (Long) -> Unit) {
    var hsv by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }) }
    var lastEmitted by remember { mutableLongStateOf(color) }
    LaunchedEffect(color) {
        if (color != lastEmitted) hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }
    }
    val latestHsv by rememberUpdatedState(hsv)
    val change by rememberUpdatedState<(Float, Float, Float) -> Unit>({ hue, saturation, value ->
        hsv = floatArrayOf(hue, saturation, value)
        lastEmitted = hsvArgb(hue, saturation, value)
        onColor(lastEmitted)
    })
    val areaLabel = stringResource(R.string.theme_canvas_color_area)
    Canvas(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = areaLabel }
        .pointerInput(Unit) { detectTapGestures { point -> change(latestHsv[0], (point.x / size.width).coerceIn(0f, 1f), 1f - (point.y / size.height).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectDragGestures { event, _ ->
            event.consume(); change(latestHsv[0], (event.position.x / size.width).coerceIn(0f, 1f), 1f - (event.position.y / size.height).coerceIn(0f, 1f))
        } }) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hsv[0], 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val point = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
        drawCircle(Color.Black, 8.dp.toPx(), point, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
    }
    val rainbow = remember { (0..6).map { Color.hsv(minOf(it * 60f, 359.99f), 1f, 1f) } }
    val hueLabel = stringResource(R.string.theme_custom_hue)
    Text(hueLabel, style = MaterialTheme.typography.labelMedium)
    Slider(value = hsv[0], valueRange = 0f..359.99f, onValueChange = { change(it, hsv[1], hsv[2]) },
        modifier = Modifier.fillMaxWidth().background(Brush.horizontalGradient(rainbow), RoundedCornerShape(24.dp)).semantics(mergeDescendants = true) { contentDescription = hueLabel },
        colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = Color(color)))
    listOf(R.string.theme_custom_saturation, R.string.theme_custom_brightness).forEachIndexed { index, res ->
        val label = stringResource(res)
        Text("$label ${(hsv[index + 1] * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(hsv[index + 1], { value -> if (index == 0) change(hsv[0], value, hsv[2]) else change(hsv[0], hsv[1], value) },
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label })
    }
}

private fun hsvArgb(hue: Float, saturation: Float, value: Float): Long =
    android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)).toLong() and 0xFFFFFFFF
