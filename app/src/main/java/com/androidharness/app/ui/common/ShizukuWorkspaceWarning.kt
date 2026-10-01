package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val visible by controller.visible.collectAsStateWithLifecycle()
    if (!visible) return
    var neverShowAgain by rememberSaveable { mutableStateOf(false) }
    fun dismiss() = controller.dismiss(neverShowAgain)

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Shizuku is disabled") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Shizuku is no longer available. Its server may have stopped or been killed " +
                        "in the background, or this app's access may have been revoked. " +
                        "Start Shizuku and allow AndroidHarness access to use it again.",
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
                    Text("Never show this message again")
                }
            }
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("OK") } },
    )
}
