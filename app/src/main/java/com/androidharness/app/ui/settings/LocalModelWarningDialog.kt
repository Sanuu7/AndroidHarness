package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.local.LocalModelManager
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun LocalModelWarningDialog(manager: LocalModelManager) {
    val warning by manager.consent.warning.collectAsStateWithLifecycle()
    warning?.let { request ->
        AlertDialog(onDismissRequest = request::cancel, title = { Text(request.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.messages.forEach { Text(it) }
            } },
            confirmButton = { TextButton(onClick = request::continueAnyway) { Text(stringResource(R.string.settings_action_continue)) } },
            dismissButton = { TextButton(onClick = request::cancel) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
}
