package com.androidharness.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import kotlinx.coroutines.launch

@Composable
internal fun BackgroundSettingsSection(container: AppContainer, settings: AppSettings) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var androidAllowsNotifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) androidAllowsNotifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun openAndroidNotifications() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        BackgroundSwitch(R.string.background_run_title, R.string.background_run_description, settings.runInBackground) {
            scope.launch { container.settings.setRunInBackground(it) }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        BackgroundSwitch(R.string.background_notifications_title, R.string.background_notifications_description,
            settings.notificationsEnabled && androidAllowsNotifications) { enabled ->
            scope.launch {
                container.settings.setNotificationsEnabled(enabled)
                if (!enabled || !androidAllowsNotifications) openAndroidNotifications()
            }
        }
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        SettingRow(Icons.Rounded.Notifications, stringResource(R.string.background_android_title),
            stringResource(R.string.background_android_description), onClick = ::openAndroidNotifications,
            trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) })
    }
    LiveUpdatesSetting(settings) { enabled -> scope.launch { container.settings.setLiveUpdatesEnabled(enabled) } }
}

@Composable
private fun BackgroundSwitch(title: Int, description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsAnchor(title) {
        Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null)
        }
    }
}
