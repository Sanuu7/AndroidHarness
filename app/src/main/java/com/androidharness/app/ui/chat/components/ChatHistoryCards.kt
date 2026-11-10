package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ChatHistoryMatch
import com.androidharness.app.data.ConversationKind

/** Native cards remain visible outside the collapsed activity disclosure in both modes. */
@Composable
internal fun ChatHistoryCards(matches: List<ChatHistoryMatch>, onOpen: (ChatHistoryMatch) -> Unit) {
    if (matches.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.chat_history_matches), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        matches.forEach { hit ->
            OutlinedCard(onClick = { onOpen(hit) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val chat = hit.kind == ConversationKind.CHAT
                        Icon(if (chat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Code, null,
                            Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (chat) R.string.chat_history_kind_chat else R.string.chat_history_kind_code),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        Text(java.time.Instant.ofEpochMilli(hit.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (hit.excerpt.isNotBlank()) Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (hit.messageId != null) R.string.chat_history_open_message else R.string.chat_history_open_chat),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
