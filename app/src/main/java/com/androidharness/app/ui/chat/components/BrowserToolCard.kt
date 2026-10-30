package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.browser.BrowserToolActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.common.FilledTonalButton

/** Browser activity is text-only in chat. Watch is the only entry to the page. */
@Composable
internal fun BrowserToolCard(call: ToolCallData, result: ChatMessage?, running: Boolean,
    target: String?, awaitingApproval: Boolean = false, onWatch: ((String?) -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(call, result, running, target, awaitingApproval) {
        BrowserToolActivity.from(call, result, running, target, awaitingApproval)
    }
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        result?.isError == true -> scheme.error
        result != null -> LocalStatusColors.current.success
        running || awaitingApproval -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Surface(modifier = Modifier.fillMaxWidth().semantics { stateDescription = activity.status },
        shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Language, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                Text(uiContext.getString(R.string.ui_browser), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                Text(activity.status, Modifier.widthIn(max = 140.dp), style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Text(activity.site, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(activity.action, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                FilledTonalButton(onClick = { onWatch?.invoke(activity.target) }, enabled = onWatch != null,
                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = uiContext.getString(R.string.ui_watch_browser) }) {
                    Text(uiContext.getString(R.string.ui_watch), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.OpenInFull, null, Modifier.size(16.dp))
                }
            }
        }
    }
}
