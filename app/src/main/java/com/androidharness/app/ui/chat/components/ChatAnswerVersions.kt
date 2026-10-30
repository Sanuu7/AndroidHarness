package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.ui.chat.CopyIconButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAnswerVersions(sessionId: String, messages: List<ChatMessage>, repository: SessionRepository,
    enabled: Boolean, turnId: String? = null, readOnly: Boolean = false, onOpenUrl: ((String) -> Unit)? = null, onSelect: (String, String) -> Unit) {
    val flow = remember(sessionId, repository, turnId) { repository.answerVersionsForTurn(sessionId, turnId) }
    val archived by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val question = messages.lastOrNull { it.role == Role.USER && (turnId == null || it.turnId == turnId) } ?: return
    val userId = question.id ?: return
    val versions = (archived.map { it.id to it.createdAt } +
        (userId to question.createdAt)).distinctBy { it.first }.sortedWith(compareBy({ it.second }, { it.first }))
    if (versions.size < 2) return
    val position = versions.indexOfFirst { it.first == userId }
    var previewPosition by remember(userId) { mutableStateOf<Int?>(null) }
    fun choose(index: Int) {
        if (readOnly) previewPosition = index else onSelect(userId, versions[index].first)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = enabled && position > 0, onClick = { choose(position - 1) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.chat_previous_answer), Modifier.size(18.dp))
        }
        Text(stringResource(R.string.chat_answer_version, position + 1, versions.size), style = MaterialTheme.typography.labelMedium)
        IconButton(enabled = enabled && position < versions.lastIndex, onClick = { choose(position + 1) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.chat_next_answer), Modifier.size(18.dp))
        }
    }
    previewPosition?.let { selected ->
        val selectedId = versions.getOrNull(selected)?.first ?: return@let
        val preview by produceState<List<ChatMessage>?>(null, selectedId, userId) {
            value = null
            value = if (selectedId == userId) messages.filter { it.turnId == question.turnId }
                else try { repository.answerVersionPreview(sessionId, selectedId) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
        }
        ModalBottomSheet(onDismissRequest = { previewPosition = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_saved_answers), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.chat_saved_answers_explanation), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = selected > 0, onClick = { previewPosition = selected - 1 }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.chat_previous_answer))
                    }
                    Text(stringResource(R.string.chat_answer_version, selected + 1, versions.size))
                    IconButton(enabled = selected < versions.lastIndex, onClick = { previewPosition = selected + 1 }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.chat_next_answer))
                    }
                }
                val content = preview
                if (content == null) CircularProgressIndicator(Modifier.size(24.dp))
                else if (content.isEmpty()) Text(stringResource(R.string.chat_saved_answer_unavailable))
                else {
                    ChatActivity(content, autoExpand = false, showThinkingMetrics = true)
                    val answer = content.lastOrNull { it.role == Role.ASSISTANT && it.toolCalls.isEmpty() && it.text.isNotBlank() }?.text.orEmpty()
                    AssistantText(answer, chatImages = com.androidharness.app.core.ChatResponseImages.available(content), onOpenUrl = onOpenUrl?.let { open -> { url -> previewPosition = null; open(url) } })
                    if (onOpenUrl != null) ChatSources(content) { url -> previewPosition = null; onOpenUrl(url) }
                    CopyIconButton(answer)
                }
            }
        }
    }

}

@Composable
internal fun ChatRecovery(reason: String?, onContinue: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chat_response_interrupted), style = MaterialTheme.typography.titleSmall)
        Text(reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_recovery_explanation), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.chat_continue_reply)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry_reply)) }
        }
    }
}
