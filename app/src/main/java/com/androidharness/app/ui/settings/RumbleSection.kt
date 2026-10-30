package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RumbleCue
import com.androidharness.app.ui.common.RumbleStyle
import com.androidharness.app.ui.common.deviceHasVibrator
import com.androidharness.app.ui.common.rememberRumblePreview
import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** AH Rumble: whether Chat turns pulse the phone on each step, and how firm and how smooth the pulse is. */
@Composable
internal fun RumbleSection(
    settings: AppSettings,
    onEnabled: (Boolean) -> Unit,
    onStrength: (Int) -> Unit,
    onSmoothness: (Int) -> Unit,
) {
    val preview = rememberRumblePreview()
    val context = LocalContext.current
    val hasMotor = remember(context) { deviceHasVibrator(context) }
    val scope = rememberCoroutineScope()
    var strength by remember(settings.rumbleStrength) { mutableFloatStateOf(settings.rumbleStrength.toFloat()) }
    var smoothness by remember(settings.rumbleSmoothness) { mutableFloatStateOf(settings.rumbleSmoothness.toFloat()) }
    fun style() = RumbleStyle(true, strength.roundToInt(), smoothness.roundToInt())
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(settings.rumbleEnabled, role = Role.Switch, onValueChange = onEnabled),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.rumble_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.rumble_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.rumbleEnabled, onCheckedChange = null)
            }
            if (!hasMotor) Text(stringResource(R.string.rumble_unsupported), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            if (settings.rumbleEnabled && hasMotor) {
                val strengthLabel = stringResource(R.string.rumble_strength)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(strengthLabel, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.rumble_percent, strength.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(value = strength, onValueChange = { strength = it }, valueRange = 5f..100f,
                        onValueChangeFinished = { onStrength(strength.roundToInt()); preview(RumbleCue.TOOL_START, style()) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = strengthLabel })
                }
                val feelLabel = stringResource(R.string.rumble_feel)
                Column {
                    Text(feelLabel, style = MaterialTheme.typography.titleSmall)
                    Slider(value = smoothness, onValueChange = { smoothness = it }, valueRange = 0f..100f,
                        onValueChangeFinished = { onSmoothness(smoothness.roundToInt()); preview(RumbleCue.TOOL_START, style()) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = feelLabel })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.rumble_feel_crisp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.rumble_feel_smooth), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        preview(RumbleCue.TOOL_START, style())
                        delay(350)
                        preview(RumbleCue.TOOL_DONE, style())
                    }
                }) { Text(stringResource(R.string.rumble_try)) }
                Text(stringResource(R.string.rumble_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
