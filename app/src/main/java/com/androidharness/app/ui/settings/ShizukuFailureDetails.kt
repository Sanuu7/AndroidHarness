package com.androidharness.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.env.ShizukuConnectionFailure
import com.androidharness.app.data.env.ShizukuStartupReport
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ShizukuFailureDetails(failure: ShizukuConnectionFailure?, report: () -> String,
    startupReport: suspend () -> ShizukuStartupReport, recovered: Boolean = false) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (failure == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(failure) { mutableStateOf(false) }
    var copied by remember(failure) { mutableStateOf(false) }
    var collecting by remember(failure) { mutableStateOf(false) }
    var startup by remember(failure) { mutableStateOf<ShizukuStartupReport?>(null) }
    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(uiContext.getString(R.string.ui_shizuku_connection), text))
        copied = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${if (recovered) stringResource(R.string.settings_shizuku_standard_error) else stringResource(R.string.settings_shizuku_last_error)} · ${failure.reason.name}",
            style = MaterialTheme.typography.labelMedium,
            color = if (recovered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Text(if (recovered) stringResource(R.string.settings_shizuku_restored) else failure.reason.summary,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (failure.detail.isNotBlank() || startup != null) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.settings_shizuku_hide_details) else stringResource(R.string.settings_shizuku_show_details))
            }
            TextButton(onClick = {
                copy(startup?.report ?: report())
            }) { Text(if (copied) stringResource(R.string.settings_shizuku_copied) else stringResource(R.string.settings_shizuku_copy_error)) }
            TextButton(enabled = !collecting, onClick = {
                collecting = true
                scope.launch {
                    try {
                        val result = startupReport()
                        startup = result
                        copy(result.report)
                    } finally { collecting = false }
                }
            }) { Text(if (collecting) stringResource(R.string.settings_shizuku_reading_log) else stringResource(R.string.settings_shizuku_copy_log)) }
        }
        startup?.let { Text(stringResource(R.string.settings_shizuku_report_copied, it.status),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (expanded) SelectionContainer {
            Text(startup?.report ?: failure.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
