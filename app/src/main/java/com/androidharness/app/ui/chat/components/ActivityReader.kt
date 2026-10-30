package com.androidharness.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.R
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolOutputText
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.chat.LocalChatDetailScroll
import com.androidharness.app.ui.chat.rememberChatListState
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** Only the activity viewport scrolls vertically; tool disclosures inside it flow inline. */
internal val LocalActivityDepth = staticCompositionLocalOf { 0 }

@Composable
internal fun ActivityScrollFrame(state: LazyListState, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        content()
        if (state.canScrollBackward) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(12.dp)
            .background(Brush.verticalGradient(listOf(scheme.surfaceContainerLow, scheme.surfaceContainerLow.copy(alpha = 0f)))))
        if (state.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
            .background(Brush.verticalGradient(listOf(scheme.surfaceContainerLow.copy(alpha = 0f), scheme.surfaceContainerLow))))
        Canvas(Modifier.matchParentSize().padding(vertical = 8.dp, horizontal = 3.dp)) {
            val info = state.layoutInfo
            if ((!state.canScrollBackward && !state.canScrollForward) || info.visibleItemsInfo.isEmpty()) return@Canvas
            val average = info.visibleItemsInfo.map { it.size }.average().toFloat().coerceAtLeast(1f)
            val total = average * info.totalItemsCount
            val height = (size.height * (size.height / total).coerceIn(0f, 1f)).coerceIn(minOf(24.dp.toPx(), size.height), size.height)
            val fraction = ((state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset) / (total - size.height).coerceAtLeast(1f)).coerceIn(0f, 1f)
            drawRoundRect(scheme.onSurfaceVariant.copy(alpha = .4f), Offset(size.width - 3.dp.toPx(), (size.height - height) * fraction),
                Size(3.dp.toPx(), height), CornerRadius(3.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityReader(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    tabs: (@Composable () -> Unit)? = null, contentKey: Any? = null, itemSpacing: Dp = 10.dp, content: LazyListScope.() -> Unit) {
    val state = key(contentKey) { rememberChatListState() }
    val scope = rememberCoroutineScope()
    val owner = LocalChatDetailScroll.current
    val key = remember { Any() }
    DisposableEffect(owner) { owner?.begin(key); onDispose { owner?.end(key) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(title = { Text(title, maxLines = 2, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_action_close)) } },
                    actions = actions, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                tabs?.invoke()
                val depth = LocalActivityDepth.current
                CompositionLocalProvider(LocalActivityDepth provides depth + 1) {
                    ActivityScrollFrame(state, Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(itemSpacing), content = content)
                    }
                }
                if (state.canScrollForward) TextButton(onClick = { scope.launch { state.animateScrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                    modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    Text(stringResource(R.string.chat_activity_latest)); Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
            }
        }
    }
}

@Composable
internal fun ToolActivityReader(call: ToolCallData, result: ChatMessage?, onDismiss: () -> Unit) {
    var output by remember(call.id) { mutableStateOf(result != null) }
    val input by produceState(call.argumentsJson, call.argumentsJson) {
        value = withContext(Dispatchers.Default) { runCatching {
            Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(call.argumentsJson))
        }.getOrDefault(call.argumentsJson) }
    }
    val text = if (output) result?.text.orEmpty() else input
    val chunks by produceState<List<String>>(emptyList(), text) { value = withContext(Dispatchers.Default) { ToolOutputText.chunks(text) } }
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    ActivityReader(title = call.name, onDismiss = onDismiss, contentKey = output, itemSpacing = 0.dp, actions = {
        IconButton(enabled = text.isNotEmpty(), onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(call.name, text)); copied = true
        }) { Icon(Icons.Outlined.ContentCopy, stringResource(if (copied) R.string.chat_activity_copied else R.string.chat_activity_copy)) }
    }, tabs = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!output, { output = false }, label = { Text(stringResource(R.string.chat_activity_input)) })
            FilterChip(output, { output = true }, label = { Text(stringResource(R.string.chat_activity_output)) })
        }
    }) {
        if (text.isEmpty()) item { Text(stringResource(R.string.chat_tool_result_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(chunks, key = { index, _ -> "${if (output) "out" else "in"}-$index" }) { _, chunk ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(chunk.removeSuffix("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
