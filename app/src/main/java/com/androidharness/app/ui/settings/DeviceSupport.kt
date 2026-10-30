package com.androidharness.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.common.deviceHasVibrator

/** Whether the phone in hand can use a setting, and if not, why. */
internal sealed interface SettingsAvailability {
    data object Available : SettingsAvailability
    data class NeedsAndroid(val version: String) : SettingsAvailability
    data object NoVibrator : SettingsAvailability
}

/** Marketing version for an API level, the way people name their Android release. */
internal fun androidVersionName(sdk: Int): String = when (sdk) {
    26 -> "8.0"
    27 -> "8.1"
    28 -> "9"
    29 -> "10"
    30 -> "11"
    31 -> "12"
    32 -> "12L"
    33 -> "13"
    34 -> "14"
    35 -> "15"
    36 -> "16"
    else -> (sdk - 20).toString()
}

internal fun settingsAvailability(entry: SettingsSearchEntry, sdk: Int, hasVibrator: Boolean): SettingsAvailability = when {
    sdk < entry.minSdk -> SettingsAvailability.NeedsAndroid(androidVersionName(entry.minSdk))
    entry.needsVibrator && !hasVibrator -> SettingsAvailability.NoVibrator
    else -> SettingsAvailability.Available
}

@Composable
internal fun rememberSettingsAvailability(entry: SettingsSearchEntry): SettingsAvailability {
    val context = LocalContext.current
    return remember(entry, context) { settingsAvailability(entry, Build.VERSION.SDK_INT, deviceHasVibrator(context)) }
}

/** Small "can't be used here" label shown under a search result. */
@Composable
internal fun SettingsAvailabilityTag(availability: SettingsAvailability, modifier: Modifier = Modifier) {
    val text = when (availability) {
        SettingsAvailability.Available -> return
        is SettingsAvailability.NeedsAndroid -> stringResource(R.string.settings_requires_android, availability.version)
        SettingsAvailability.NoVibrator -> stringResource(R.string.settings_requires_vibrator)
    }
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
