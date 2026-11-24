package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R

/**
 * The marker after a sentence whose sources disagree or rest on one site.
 * Tapping it explains why, in the model's words, and lists the sources so the
 * reader can check them.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ClaimCheckMarker(reason: String, sources: List<String>, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.chat_claim_check)
    Box(Modifier.fillMaxSize().clickable(onClickLabel = label) { open = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.ErrorOutline, label, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        properties = com.androidharness.app.ui.common.hostedDialog(),
        icon = {
            val cookie = androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape()
            Box(Modifier.size(48.dp).clip(cookie).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reason.ifBlank { stringResource(R.string.chat_claim_sources) }, style = MaterialTheme.typography.bodyMedium)
                sources.forEach { source ->
                    com.androidharness.app.ui.common.OutlinedButton(onClick = { open = false; onOpen(source) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(source.substringAfter("://").removePrefix("www."), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { com.androidharness.app.ui.common.TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } },
    )
}
