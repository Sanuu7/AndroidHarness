package com.androidharness.app.ui.common

import com.androidharness.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.data.env.ShizukuWarningController

/** Attach to workspace entry points; one app-level host avoids stacked alerts. */
@Composable
internal fun ShizukuWorkspaceWarningEffect(manager: ShizukuManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(manager, state) { manager.checkWorkspaceWarning() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.checkWorkspaceWarning() }
}

@Composable
internal fun ShizukuDisabledWarning(controller: ShizukuWarningController) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val visible by controller.visible.collectAsStateWithLifecycle()
    if (!visible) return
    var neverShowAgain by rememberSaveable { mutableStateOf(false) }
    fun dismiss() = controller.dismiss(neverShowAgain)

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(uiContext.getString(R.string.ui_shizuku_is_disabled)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    uiContext.getString(R.string.ui_shizuku_is_no_longer_available_its_server) +
                        uiContext.getString(R.string.ui_in_the_background_or_this_app_s) +
                        uiContext.getString(R.string.ui_start_shizuku_and_allow_androidharness_access_to),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = neverShowAgain,
                        role = Role.Checkbox,
                        onValueChange = { neverShowAgain = it },
                    ),
                ) {
                    Checkbox(checked = neverShowAgain, onCheckedChange = null)
                    Text(uiContext.getString(R.string.ui_never_show_this_message_again))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text(uiContext.getString(R.string.ui_ok)) } },
    )
}
