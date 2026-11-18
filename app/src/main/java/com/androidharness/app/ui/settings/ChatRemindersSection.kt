package com.androidharness.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.androidharness.app.R
import com.androidharness.app.reminders.ReminderManager
import com.androidharness.app.reminders.ReminderTime
import kotlinx.coroutines.launch

/** The Chat reminders switch, the on-time permission and the reminders still to come. */
@Composable
internal fun ChatRemindersSection(enabled: Boolean, reminders: ReminderManager, onEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exact by remember { mutableStateOf(reminders.exactTiming()) }
    var notificationsAllowed by remember { mutableStateOf(reminders.notificationsAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exact = reminders.exactTiming()
        notificationsAllowed = reminders.notificationsAllowed()
    }
    val all by reminders.store.reminders.collectAsState()
    if (all == null) LaunchedOnce { reminders.store.all() }
    val upcoming = all.orEmpty().filter { it.pending }.sortedBy { it.at }

    SettingsAnchor(R.string.reminders_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabled).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.reminders_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = null)
                }
                if (enabled && !notificationsAllowed) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Text(stringResource(R.string.reminders_notifications_blocked), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().clickable {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }.padding(16.dp))
                }
                val exactIntent = reminders.exactTimingSettingsIntent()
                if (enabled && exactIntent != null) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.fillMaxWidth().settingsAnchor(R.string.reminders_on_time)
                        .clickable { runCatching { context.startActivity(exactIntent) } }.padding(16.dp)) {
                        Text(stringResource(R.string.reminders_on_time), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (exact) R.string.reminders_on_time_on else R.string.reminders_on_time_off),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (upcoming.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Text(stringResource(R.string.reminders_upcoming), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
                    upcoming.forEach { reminder ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(reminder.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(ReminderTime.describe(reminder), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { reminders.cancel(reminder.id) } }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.reminders_cancel))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedOnce(block: suspend () -> Unit) = androidx.compose.runtime.LaunchedEffect(Unit) { block() }
