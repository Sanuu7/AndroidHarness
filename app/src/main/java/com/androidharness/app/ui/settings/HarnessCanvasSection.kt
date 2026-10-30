package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.HarnessCanvasSettings
import com.androidharness.app.data.HarnessCanvasStore
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.ui.theme.HarnessCanvasBackdrop
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun HarnessCanvasSection(settings: HarnessCanvasSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val store = remember(context, repository) { HarnessCanvasStore(context, repository) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var dim by remember(settings.dim, settings.imageId) { mutableFloatStateOf(settings.dim.coerceIn(0f, 1f)) }
    val hasImage = HarnessCanvasStore.image(context, settings.imageId) != null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = false
            try { store.import(uri) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = true } finally { busy = false }
        }
    }
    SettingsPanel(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.theme_canvas_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.theme_canvas_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasImage) {
                Surface(shape = RoundedCornerShape(24.dp)) {
                    HarnessCanvasBackdrop(settings.copy(enabled = true, chat = true, dim = dim), true, Modifier.fillMaxWidth().height(220.dp)) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(stringResource(R.string.theme_canvas_preview_message), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.theme_canvas_preview_reply), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                CanvasSwitch(stringResource(R.string.theme_canvas_enabled), checked = settings.enabled, enabled = !busy) { value ->
                    scope.launch { repository.updateHarnessCanvas { it.copy(enabled = value) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(settings.chat, { scope.launch { repository.updateHarnessCanvas { it.copy(chat = !it.chat) } } }, label = { Text(stringResource(R.string.theme_canvas_chat)) }, enabled = !busy)
                    FilterChip(settings.code, { scope.launch { repository.updateHarnessCanvas { it.copy(code = !it.code) } } }, label = { Text(stringResource(R.string.theme_canvas_code)) }, enabled = !busy)
                }
                val dimLabel = stringResource(R.string.theme_canvas_dim)
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(dimLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("${(dim * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(stringResource(R.string.theme_canvas_dim_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(dim, { dim = it }, onValueChangeFinished = { scope.launch { repository.updateHarnessCanvas { it.copy(dim = dim) } } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = dimLabel })
                }
                CanvasSwitch(stringResource(R.string.theme_canvas_colors), stringResource(R.string.theme_canvas_colors_hint), settings.adaptColors, !busy) { value ->
                    scope.launch { repository.updateHarnessCanvas { it.copy(adaptColors = value) } }
                }
            } else {
                Text(stringResource(R.string.theme_canvas_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.theme_canvas_working), style = MaterialTheme.typography.bodySmall) }
            if (error) Text(stringResource(R.string.theme_canvas_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (hasImage) R.string.theme_canvas_change else R.string.theme_canvas_add))
                }
                if (hasImage) TextButton(onClick = { scope.launch { busy = true; try { store.remove() } finally { busy = false } } }, enabled = !busy) {
                    Text(stringResource(R.string.theme_canvas_remove))
                }
            }
        }
    }
}

@Composable
private fun CanvasSwitch(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
