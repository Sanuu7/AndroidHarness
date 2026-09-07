package com.androidharness.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.VisualDiffViewer
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFileDiffSheet(
    path: String,
    loadDiff: suspend () -> String,
    onOpenFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reviewHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val diff by produceState<Result<String>?>(null, path) {
        value = try {
            Result.success(loadDiff())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("File changes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenFile) { Text("Open file") }
            }
            Text(path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                "Before this turn → current file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val result = diff
            when {
                result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result.isFailure -> Text(
                    result.exceptionOrNull()?.message ?: "Could not load the diff.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> VisualDiffViewer(
                    diffText = result.getOrThrow(),
                    filePath = path,
                    maxHeight = reviewHeight,
                    showFileHeader = false,
                )
            }
        }
    }
}
