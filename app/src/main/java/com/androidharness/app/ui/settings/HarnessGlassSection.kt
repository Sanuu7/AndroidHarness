package com.androidharness.app.ui.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.ui.theme.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun HarnessGlassSection(settings: AppSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val supported = Build.VERSION.SDK_INT >= 31
    var transparency by remember(settings.harnessGlassTransparency) { mutableFloatStateOf(settings.harnessGlassTransparency) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.glass_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.glass_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            GlassPreview(settings.harnessLiquidGlass && settings.harnessGlassEnabled, transparency)
            GlassSwitch(stringResource(R.string.glass_enable), stringResource(R.string.glass_enable_hint),
                settings.harnessGlassEnabled, supported) { scope.launch { repository.setHarnessGlassEnabled(it) } }
            GlassSwitch(stringResource(R.string.glass_liquid), stringResource(R.string.glass_liquid_hint),
                settings.harnessLiquidGlass, supported && settings.harnessGlassEnabled && Build.VERSION.SDK_INT >= 33) {
                scope.launch { repository.setHarnessLiquidGlass(it) }
            }
            if (supported && Build.VERSION.SDK_INT >= 33 && settings.harnessGlassEnabled && settings.harnessLiquidGlass) {
                val label = stringResource(R.string.glass_transparency)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.glass_transparency_percent, (transparency * 100).roundToInt()),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(value = transparency, onValueChange = { transparency = it },
                        onValueChangeFinished = { scope.launch { repository.setHarnessGlassTransparency(transparency) } },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
                    Text(stringResource(R.string.glass_transparency_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!supported) Text(stringResource(R.string.glass_unsupported), style = MaterialTheme.typography.bodySmall)
            else if (Build.VERSION.SDK_INT < 33) Text(stringResource(R.string.glass_liquid_unsupported), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GlassSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A local backdrop makes both materials visible before changing the entire app. */
@Composable
private fun GlassPreview(liquid: Boolean, transparency: Float) {
    val backdrop = rememberLayerBackdrop()
    val surface = LocalHarnessGlass.current.surface
    val colors = MaterialTheme.colorScheme
    val style = HarnessGlassStyle(HarnessGlassMode.resolve(true, liquid, Build.VERSION.SDK_INT), surface, transparency)
    val previewLabel = stringResource(R.string.glass_preview_label)
    Box(Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(20.dp)).semantics { contentDescription = previewLabel }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF3767B9), Color(0xFF855EC9), Color(0xFF278C91))))
            drawCircle(Color(0xFFEDBC8C), size.height * .45f, Offset(size.width * .25f, size.height * .8f))
            for (index in 0..12) drawLine(Color.White.copy(alpha = .28f), Offset(size.width * index / 12f, 0f),
                Offset(size.width * index / 12f + size.height * .4f, size.height), 2.dp.toPx())
        }
        CompositionLocalProvider(LocalHarnessGlass provides style, LocalHarnessGlassBackdrop provides backdrop) {
            HarnessGlassSurface(Modifier.padding(20.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                color = surface, contentColor = colors.onSurface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.glass_preview_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.glass_preview_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
