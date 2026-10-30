package com.androidharness.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.ChatRichResponse
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.db.SavedAnswerRow
import com.androidharness.app.ui.chat.CopyIconButton
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton

private val link = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val lineMarker = Regex("""(?m)^\s{0,3}(?:#{1,6}|>|[-*+]|\d+[.)])\s+""")
// Underscores only count as emphasis at a word edge, so snake_case identifiers survive.
private val emphasis = Regex("""\*{1,3}|`+|~~|(?<![\p{L}\p{N}])_{1,2}|_{1,2}(?![\p{L}\p{N}])""")
private val blanks = Regex("""\s+""")

/**
 * One calm line of prose for a sidebar row: rich fences turned into text, Markdown markers
 * dropped, links reduced to their label, whitespace collapsed.
 */
internal fun savedAnswerPreview(raw: String): String = RichResponse.plainText(raw)
    .replace(link, "$1")
    .replace(lineMarker, "")
    .replace(emphasis, "")
    .replace(blanks, " ")
    .trim()

/** A saved answer in the sidebar's Saved list: where it came from, how long ago, and its opening lines. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SavedAnswerListRow(answer: SavedAnswerRow, chatTitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val preview = remember(answer.messageId, answer.excerpt) { savedAnswerPreview(answer.excerpt) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bookmark, null, Modifier.size(13.dp), tint = scheme.tertiary)
            Spacer(Modifier.width(6.dp))
            Text(
                chatTitle.ifBlank { context.getString(R.string.ui_chat) },
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(formatRelativeTime(answer.savedAt), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Text(
            preview.ifBlank { context.getString(R.string.sidebar_saved_answer) },
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The saved answer on its own, read from the copy taken when it was saved: it stays complete even
 * if the chat has since been regenerated or rewound. From here the chat can be opened at the
 * message, the text copied, or the bookmark removed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SavedAnswerSheet(
    answer: SavedAnswerRow,
    chatTitle: String,
    loadText: suspend (String) -> String?,
    onOpenChat: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val text by produceState<String?>(null, answer.messageId) { value = loadText(answer.messageId) ?: answer.excerpt }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bookmark, null, Modifier.size(16.dp), tint = scheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(context.getString(R.string.sidebar_saved_answer), style = MaterialTheme.typography.labelLarge, color = scheme.tertiary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    chatTitle.ifBlank { context.getString(R.string.ui_chat) },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    android.text.format.DateUtils.formatDateTime(
                        context, answer.answeredAt.takeIf { it > 0 } ?: answer.savedAt,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
                    ),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f, fill = false).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                text?.let { AssistantText(remember(it) { ChatRichResponse.normalize(it) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenChat) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.sidebar_open_in_chat))
                }
                text?.let { CopyIconButton(it) }
                FilledTonalButton(onClick = onRemove) {
                    Icon(Icons.Outlined.BookmarkRemove, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.sidebar_remove_saved))
                }
            }
        }
    }
}
