package com.androidharness.app.ui.common

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.androidharness.app.chatgpt.ChatGptProtocol

/** Account-limit reads are separate from inference; this sign-in currently opens the usage page. */
@Composable
fun ChatGptUsageLimits(compact: Boolean = false) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Limits & resets", style = MaterialTheme.typography.titleSmall)
                    Text("View live limits in ChatGPT", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { openOAuthBrowser(context, Uri.parse(ChatGptProtocol.USAGE_URL)) }) {
                    Text("View usage")
                }
            }
            if (!compact) Text("Check remaining usage, reset times and banked resets in ChatGPT. Only limits that apply to your plan are shown there.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
