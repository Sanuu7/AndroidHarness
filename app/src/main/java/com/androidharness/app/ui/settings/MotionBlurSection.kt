package com.androidharness.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Scroll motion blur: strength slider plus the Android 12 requirement note. */
@Composable
internal fun MotionBlurSection(settings: AppSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val supported = Build.VERSION.SDK_INT >= 31
    var intensity by remember(settings.motionBlurIntensity) { mutableFloatStateOf(settings.motionBlurIntensity) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.motion_blur_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.motion_blur_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().toggleable(settings.motionBlurEnabled, enabled = supported, role = Role.Switch) {
                scope.launch { repository.setMotionBlurEnabled(it) }
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.motion_blur_enable), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.motion_blur_enable_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.motionBlurEnabled, onCheckedChange = null, enabled = supported)
            }
            if (supported && settings.motionBlurEnabled) {
                val label = stringResource(R.string.motion_blur_intensity)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.glass_transparency_percent, (intensity * 100).roundToInt()),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(value = intensity, onValueChange = { intensity = it },
                        onValueChangeFinished = { scope.launch { repository.setMotionBlurIntensity(intensity) } },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
                    Text(stringResource(R.string.motion_blur_intensity_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!supported) Text(stringResource(R.string.motion_blur_unsupported), style = MaterialTheme.typography.bodySmall)
        }
    }
}
