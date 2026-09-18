package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.caveman.CavemanIntensity
import com.androidharness.app.caveman.CavemanPolicy
import com.androidharness.app.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CavemanSection(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    fun save(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save Caveman settings." }
            finally { busy = false }
        }
    }
    CavemanPanel("Caveman") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Native skill pack", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (settings.cavemanInstalled) "Installed" else "Not installed",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text("Shorter replies and five focused coding skills. Uses your existing model, with no extra API key, terminal or proxy.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.cavemanInstalled) {
            OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("Uninstall") }
        } else {
            Button(onClick = { save { container.settings.setCavemanInstalled(true) } }, enabled = !busy) { Text("Install Caveman") }
            Text("Installs from the app. Reply mode starts Off; coding skills can be switched off individually.", style = MaterialTheme.typography.labelSmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (settings.cavemanInstalled) {
        CavemanPanel("Reply intensity") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CavemanIntensity.entries.forEachIndexed { index, intensity ->
                    SegmentedButton(
                        selected = settings.cavemanIntensity == intensity,
                        onClick = { save { container.settings.setCavemanIntensity(intensity) } },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(index, CavemanIntensity.entries.size),
                    ) { Text(intensity.label, maxLines = 1) }
                }
            }
            Text(settings.cavemanIntensity.description, style = MaterialTheme.typography.bodyMedium)
            Text("Automatically applied from the next model request, across chats. Off removes style instructions; skills stay independent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Classical Chinese", style = MaterialTheme.typography.bodyMedium)
                    Text("Wenyan variant of the selected intensity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.cavemanWenyan,
                    onCheckedChange = { save { container.settings.setCavemanWenyan(it) } },
                    enabled = !busy && settings.cavemanIntensity != CavemanIntensity.OFF,
                    modifier = Modifier.semantics { contentDescription = "Classical Chinese" })
            }
            Text("Code, paths and exact errors stay intact. Warnings and unclear instructions use full sentences.", style = MaterialTheme.typography.labelSmall)
        }
        CavemanPanel("Coding skills") {
            Text("Loaded only when relevant. Available in the Skills picker after installation.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val labels = listOf("Commit messages", "Code reviews", "Investigate first", "Targeted patches", "Verify and stop")
            CavemanPolicy.skills.keys.forEachIndexed { index, name ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(labels[index], style = MaterialTheme.typography.bodyMedium)
                        Text("/$name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = name !in settings.disabledSkills,
                        onCheckedChange = { enabled -> save { container.settings.setSkillEnabled(name, enabled) } },
                        enabled = !busy, modifier = Modifier.semantics { contentDescription = labels[index] })
                }
            }
        }
        CavemanPanel("Token reports") {
            Text("Token savings from Caveman only. No total usage or unrelated context reductions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { showReports = !showReports }) { Text(if (showReports) "Hide report" else "Show report") }
            if (showReports) {
                Text("Tokens saved: Not measured", style = MaterialTheme.typography.titleSmall)
                Text("Reply modes guide your model; they do not produce a normal reply to compare against. A matched run with Caveman off is needed to measure savings. No extra model requests are made for this report.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Shorter replies do not guarantee lower total cost. Style instructions also use input tokens.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { uri.openUri("https://github.com/JuliusBrussee/caveman") }) { Text("Source") }
        TextButton(onClick = { showLicense = true }) { Text("MIT license") }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false }, title = { Text("Uninstall Caveman?") },
        text = { Text("Removes the optional skill pack and turns reply mode off. Chats and your own skill copies are kept. Instructions already loaded into a chat remain in its history.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; save { container.settings.setCavemanInstalled(false) } }) { Text("Uninstall") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("Caveman skills") },
        text = { Text("Adapted from Julius Brussee's MIT-licensed Caveman skills. Copyright (c) 2026 Julius Brussee. This integration does not include the BSL compression engine or proxy.") },
        confirmButton = { TextButton(onClick = { uri.openUri("https://github.com/JuliusBrussee/caveman/blob/main/LICENSE") }) { Text("Full license") } },
        dismissButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } })
}

@Composable
private fun CavemanPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

