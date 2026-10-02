package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.androidharness.app.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectorDialog(model: LocalModelSpec, manager: LocalModelManager, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Image support") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            Text("For a model that supports images, add its matching vision file (often called mmproj). Find it on the model's download page.", style = MaterialTheme.typography.bodySmall)
            model.projector?.let { Text("Added: ${it.filename.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall) }
            if (!model.localFile) TextButton(onClick = { uri.openUri(model.modelPage) }, enabled = !busy) { Text("Open model page") }
            OutlinedTextField(link, { link = it }, label = { Text("Vision file link (.gguf)") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && link.isNotBlank(), onClick = {
        scope.launch {
            busy = true; error = null
            try { manager.installProjector(model.id, link); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }) { Text("Add vision file") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
