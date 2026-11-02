package com.androidharness.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.LiveTranscript
import com.androidharness.app.ui.chat.components.LiveGlyph

/** A saved Live Mode conversation in the chat, collapsed to one line until opened. */
@Composable
internal fun LiveTranscriptCard(messageId: String, text: String, onLongPress: () -> Unit = {}) {
    val exchanges = remember(text) { LiveTranscript.parse(text) }
    var open by rememberSaveable(messageId) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiveGlyph(color = MaterialTheme.colorScheme.primary, size = 18.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_transcript_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        exchanges.firstOrNull()?.user?.takeIf { !open }
                            ?: pluralStringResource(R.plurals.live_transcript_count, exchanges.size, exchanges.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    stringResource(if (open) R.string.live_transcript_collapse else R.string.live_transcript_expand),
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    exchanges.forEach { exchange ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(exchange.user, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (exchange.reply.isNotBlank()) Text(exchange.reply, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
