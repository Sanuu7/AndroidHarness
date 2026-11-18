package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.browser.BrowserToolActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.common.TextButton

/**
 * One browser step as an inline line: what it did and on which site. Browser activity
 * stays text-only in chat; Watch is the only way into the page. It sits on the line until
 * the step finishes, then moves into the details that open underneath on tap.
 */
@Composable
internal fun BrowserToolCard(call: ToolCallData, result: ChatMessage?, running: Boolean,
    target: String?, awaitingApproval: Boolean = false, onWatch: ((String?) -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(call, result, running, target, awaitingApproval) {
        BrowserToolActivity.from(call, result, running, target, awaitingApproval)
    }
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val state = when {
        awaitingApproval -> WorkState.Waiting
        running && result == null -> WorkState.Running
        result?.isError == true -> WorkState.Failed
        result != null -> WorkState.Done
        else -> WorkState.Idle
    }
    InlineActivityRow(
        icon = Icons.Outlined.Language,
        title = activity.action,
        // Approval is the one status worth words; the icon and shimmer carry the rest.
        detail = if (awaitingApproval) activity.status else activity.site,
        state = state,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        // A finished step keeps Watch inside its details, so a long run of steps stays quiet.
        trailing = if (onWatch != null && state != WorkState.Done) ({
            TextButton(onClick = { onWatch(activity.target) }, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = uiContext.getString(R.string.ui_watch_browser) }) {
                Text(uiContext.getString(R.string.ui_watch), style = MaterialTheme.typography.labelMedium)
            }
        }) else null,
        content = result?.text?.takeIf { it.isNotBlank() }?.let { text -> {
            CodePanel(text.take(4000),
                label = uiContext.getString(if (result.isError) R.string.ui_failed_64 else R.string.ui_output_1407),
                tone = if (result.isError) WorkState.Failed else null)
            if (onWatch != null) TextButton(onClick = { onWatch(activity.target) }, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.padding(top = 2.dp)) {
                Text(uiContext.getString(R.string.ui_watch_browser), style = MaterialTheme.typography.labelMedium)
            }
        } },
    )
}
