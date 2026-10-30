package com.androidharness.app.ui.chat.components

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.PendingShare
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.OutlinedButton

/** The destination list is supplied by AppNav and contains regular Chat sessions only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareToChatSheet(
    share: PendingShare,
    chats: List<SessionEntity>,
    opening: Boolean,
    error: String?,
    onOpen: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(share.id) { mutableStateOf<String?>(null) }
    var question by rememberSaveable(share.id) { mutableStateOf("") }
    var destinations by remember { mutableStateOf(false) }
    var chatQuery by remember { mutableStateOf("") }
    val newChat = stringResource(R.string.chat_share_new)
    val selectedChat = chats.firstOrNull { it.id == selected }
    ModalBottomSheet(onDismissRequest = { if (!opening) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_share_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.chat_share_review), style = MaterialTheme.typography.bodySmall)
            share.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            share.streams.forEach { uri -> key(uri) { SharedAttachmentPreview(uri) } }
            Box {
                OutlinedButton(enabled = !opening, onClick = { chatQuery = ""; destinations = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedChat?.title ?: newChat, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = destinations, onDismissRequest = { destinations = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    DropdownMenuItem(text = { Text(newChat) }, onClick = { selected = null; destinations = false })
                    OutlinedTextField(value = chatQuery, onValueChange = { chatQuery = it }, singleLine = true,
                        label = { Text(stringResource(R.string.chat_share_find)) }, modifier = Modifier.padding(horizontal = 12.dp))
                    chats.filter { it.title.contains(chatQuery.trim(), ignoreCase = true) }.forEach { chat -> DropdownMenuItem(text = { Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { selected = chat.id; destinations = false }) }
                }
            }
            OutlinedTextField(value = question, onValueChange = { question = it }, enabled = !opening,
                label = { Text(stringResource(R.string.chat_share_question)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.chat_share_summarize, R.string.chat_share_explain,
                    R.string.chat_share_translate, R.string.chat_share_check).forEach { label ->
                    val prompt = stringResource(label)
                    SuggestionChip(enabled = !opening, onClick = { question = prompt }, label = { Text(prompt) })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !opening, onClick = { onOpen(selectedChat?.id, question.takeIf(String::isNotBlank)) },
                modifier = Modifier.fillMaxWidth()) {
                if (opening) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.chat_share_open))
            }
        }
    }
}

@Composable
private fun SharedAttachmentPreview(uri: android.net.Uri) {
    val resolver = LocalContext.current.contentResolver
    val fallback = stringResource(R.string.chat_share_attachment)
    val preview by produceState<Pair<String, android.graphics.Bitmap?>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: fallback
            val bitmap = runCatching {
                if (resolver.getType(uri)?.startsWith("image/") == true) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                } else null
            }.getOrNull()
            name to bitmap
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val bitmap = preview?.second
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.Description, null, Modifier.size(24.dp))
            Text(preview?.first ?: fallback, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
