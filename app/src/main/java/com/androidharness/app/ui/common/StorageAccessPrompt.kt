package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.env.StorageGate

/**
 * Shown over any screen when a tool was stopped at shared storage. While it is
 * visible the stopped call waits, so granting lets the agent carry on by itself.
 */
@Composable
fun StorageAccessPrompt() {
    val request by StorageGate.request.collectAsStateWithLifecycle()
    val access = rememberStorageAccess()
    // Covers the return from Android's settings page and a grant made anywhere else in the app.
    LaunchedEffect(access.granted, request) { StorageGate.refresh() }
    val current = request ?: return
    AlertDialog(
        onDismissRequest = { StorageGate.decline() },
        icon = { Icon(Icons.Rounded.FolderOff, contentDescription = null) },
        title = { Text(stringResource(R.string.storage_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.storage_prompt_body))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        current.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                Text(
                    stringResource(R.string.storage_prompt_alternative),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = access.request) { Text(stringResource(R.string.storage_prompt_grant)) } },
        dismissButton = { TextButton(onClick = { StorageGate.decline() }) { Text(stringResource(R.string.storage_prompt_later)) } },
    )
}
