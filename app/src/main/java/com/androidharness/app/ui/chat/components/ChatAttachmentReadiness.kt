package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.*
import com.androidharness.app.ui.common.IconButton

@Composable
internal fun ChatAttachmentReadiness(state: ChatUiState, onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit, onRemoveImport: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.attachmentImports.forEach { pending ->
            val status = stringResource(when (pending.status) {
                AttachmentImportStatus.READING -> R.string.chat_attachment_reading
                AttachmentImportStatus.TOO_LARGE -> R.string.chat_attachment_too_large_title
                AttachmentImportStatus.UNSUPPORTED -> R.string.chat_attachment_unsupported
                AttachmentImportStatus.FAILED -> R.string.chat_attachment_failed
            })
            ReadinessRow(pending.name, listOfNotNull(status, pending.detail).joinToString(" · "), pending.status != AttachmentImportStatus.READING,
                pending.status == AttachmentImportStatus.READING) { onRemoveImport(pending.id) }
        }
        state.attachments.forEachIndexed { index, name ->
            val support = state.imageInputSupport()
            val label = stringResource(when (support) { true -> R.string.chat_attachment_ready; false -> R.string.chat_attachment_model_unsupported; null -> R.string.chat_attachment_image_unconfirmed })
            ReadinessRow(name, label, support == false, false) { onRemoveImage(index) }
        }
        state.fileAttachments.forEachIndexed { index, file ->
            ReadinessRow(file.name, file.sizeLabel + " · " + stringResource(R.string.chat_attachment_ready), false, false) { onRemoveFile(index) }
        }
    }
}

@Composable
private fun ReadinessRow(name: String, status: String, error: Boolean, reading: Boolean, onRemove: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (reading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.settings_action_remove)) }
        }
    }
}
