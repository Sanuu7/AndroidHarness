package com.androidharness.app.ui.chat.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.androidharness.app.ui.chat.UserScrollObserver
import com.androidharness.app.ui.chat.containChatDetailScroll
import com.androidharness.app.ui.chat.rememberChatListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ChatActivityPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** One chronological disclosure for a Chat turn; the final answer stays outside. */
@Composable
internal fun ChatActivity(
    messages: List<ChatMessage>,
    live: Boolean = false,
    streamingThinking: String = "",
    writing: Boolean = false,
    runningCalls: List<ToolCallData> = emptyList(),
    streamId: String = "live",
    streamingCommentary: String = "",
    autoExpand: Boolean = true,
    showThinkingMetrics: Boolean = false,
    thinkingTiming: com.androidharness.app.agent.ThinkingTiming? = null,
    reasoningTokens: Int? = null,
    turnStartedAt: Long? = null,
    onOpenUrl: (String) -> Unit = {},
) {
    val entries = remember(messages, runningCalls, streamingThinking, streamId, streamingCommentary) {
        ChatActivityPresentation.timeline(messages, runningCalls, streamingThinking, streamId, streamingCommentary)
    }
    if (!live && entries.isEmpty()) return
    val thoughtChunks = remember { mutableMapOf<String, com.androidharness.app.core.ReasoningTextChunks.Stream>() }
    val active = remember(messages, runningCalls) { ChatActivityPresentation.activeCall(messages, runningCalls) }
    val research = remember(messages) { ChatActivityPresentation.researchProgress(messages) }
    val lastTool = remember(entries) {
        entries.filterIsInstance<ChatActivityPresentation.Entry.Tool>().lastOrNull()
    }
    // Tool updates are two-second notices; resumed thinking does not shorten them.
    val phaseKey = active?.let { "${it.id}:running" }
        ?: lastTool?.takeIf { it.result != null }?.let { "${it.call.id}:finished" }
    var showToolPhase by remember(phaseKey) { mutableStateOf(phaseKey != null) }
    LaunchedEffect(phaseKey, live) {
        showToolPhase = live && phaseKey != null
        if (showToolPhase) kotlinx.coroutines.delay(2_000)
        showToolPhase = false
    }
    val completedPhase = showToolPhase && active == null && lastTool?.result != null
    val seconds = messages.sumOf { it.thinkingMs }.coerceAtLeast(0) / 1000
    val title = when {
        live && showToolPhase && active != null -> activityLabel(active.name, true)
        live && completedPhase -> if (lastTool!!.result?.isError == true) stringResource(R.string.chat_tool_failed) else activityLabel(lastTool.call.name, false)
        live && writing -> stringResource(R.string.chat_writing_reply)
        live -> stringResource(R.string.chat_thinking)
        seconds > 0 && !showThinkingMetrics -> stringResource(R.string.chat_thought_seconds, seconds)
        else -> stringResource(R.string.chat_thought)
    }
    ChatActivityRow(title, if (live && showToolPhase) (active ?: lastTool?.call?.takeIf { completedPhase })?.name else null, live, entries.isNotEmpty(), followLatest = live, autoExpand = autoExpand,
        progress = research,
        trailing = if (showThinkingMetrics) ({ ChatThinkingMetrics(messages, streamingThinking, thinkingTiming, reasoningTokens, live,
            turnStartedAt = turnStartedAt, streamId = streamId) }) else null) {
        entries.forEach { entry ->
            when (entry) {
                is ChatActivityPresentation.Entry.Thought -> {
                    item(key = entry.key + "-heading") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(painterResource(R.drawable.ic_brain), null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (entry.milliseconds >= 1000) stringResource(R.string.chat_thought_seconds, entry.milliseconds / 1000)
                            else stringResource(R.string.chat_thought), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                    thoughtChunks.getOrPut(entry.key) { com.androidharness.app.core.ReasoningTextChunks.Stream() }.update(entry.text).forEachIndexed { index, chunk ->
                        item(key = entry.key + "-text-" + index) {
                            Text(chunk, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is ChatActivityPresentation.Entry.Commentary -> item(key = entry.key) { Text(
                    com.androidharness.app.core.response.RichResponse.plainText(
                        com.androidharness.app.core.response.ChatRichResponse.normalize(entry.text)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ) }
                is ChatActivityPresentation.Entry.Tool -> item(key = entry.key) { ChatToolActivity(entry.call, entry.result,
                    running = live && entry.result == null, onOpenUrl) }
            }
        }
    }
}

/** A tool owns its own result disclosure, rather than being appended to a thinking block. */
@Composable
internal fun ChatToolActivity(call: ToolCallData, result: ChatMessage?, running: Boolean, onOpenUrl: (String) -> Unit) {
    if (call.name == "offer_code_handoff") return
    val title = when {
        running -> activityLabel(call.name, true)
        result?.isError == true -> stringResource(when (call.name) {
            "web_search" -> R.string.chat_search_failed
            "web_fetch", "browser_read" -> R.string.chat_page_failed
            "view_chat_image", "zoom_chat_image" -> R.string.chat_image_failed
            "github_api", "github_search", "github_file" -> R.string.chat_github_failed
            else -> R.string.chat_tool_failed
        })
        result != null -> activityLabel(call.name, false)
        else -> stringResource(R.string.chat_activity_interrupted)
    }
    val sources = remember(call, result) { ChatActivityPresentation.sources(listOfNotNull(ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)), result)) }
    val images = remember(call, result) {
        if (call.name != "web_fetch" && call.name != "browser_read") emptyList()
        else result?.let { com.androidharness.app.tools.PageImageText.parse(it.text) }.orEmpty()
    }
    val recall = remember(call, result) {
        if (call.name == "search_chats" || call.name == "recent_chats" || call.name == "read_chat_snippet")
            result?.text?.take(6_000)
        else null
    }
    val imagePreview = result?.images?.isNotEmpty() == true && call.name in setOf("view_chat_image", "zoom_chat_image", "browser_read")
    var readerOpen by rememberSaveable(call.id) { mutableStateOf(false) }
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if ((result?.text?.length ?: 0) > 6_000) R.string.chat_activity_output_preview else R.string.chat_activity_output), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { readerOpen = true }) {
                Icon(Icons.Outlined.OpenInFull, stringResource(R.string.chat_activity_tool_details), Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.chat_activity_full_view), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (result?.images?.isNotEmpty() == true && call.name in setOf("view_chat_image", "zoom_chat_image", "browser_read")) {
            result.images.forEach { image -> ToolResultImage(image, previewHeight = 120.dp) }
        } else if (recall != null) {
            Text(
                recall,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (images.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_image_count, images.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sources.isNotEmpty()) {
                sources.take(4).forEach { source ->
                    TextButton(onClick = { onOpenUrl(source.url) }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        Column {
                            Text(source.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(source.host + " · " + stringResource(if (source.read) R.string.chat_source_read else R.string.chat_source_found), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                ChatSources(listOfNotNull(ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)), result), onOpenUrl)
            } else if (images.isEmpty() && !result?.text.isNullOrBlank()) {
                Text(result!!.text.take(6_000), style = MaterialTheme.typography.bodySmall,
                    fontFamily = com.androidharness.app.ui.theme.HarnessMono, color = MaterialTheme.colorScheme.onSurface)
            } else if (images.isEmpty()) Text(stringResource(when {
                result?.isError == true -> R.string.chat_tool_result_failed
                result != null -> R.string.chat_tool_result_empty
                else -> R.string.chat_activity_interrupted
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
    ChatActivityRow(title, call.name, running, result != null || sources.isNotEmpty() || call.argumentsJson.isNotBlank(),
        compactPreview = imagePreview, inlineDetails = body) { item { body() } }
    if (readerOpen) ToolActivityReader(call, result) { readerOpen = false }
}

private val LocalActivityInteraction = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun ChatActivityRow(
    title: String,
    tool: String?,
    live: Boolean,
    expandable: Boolean,
    followLatest: Boolean = false,
    autoExpand: Boolean = true,
    progress: ChatActivityPresentation.ResearchProgress? = null,
    trailing: (@Composable () -> Unit)? = null,
    compactPreview: Boolean = false,
    inlineDetails: (@Composable () -> Unit)? = null,
    details: LazyListScope.() -> Unit,
) {
    val detailScroll = com.androidharness.app.ui.chat.LocalChatDetailScroll.current
    val pauseParent = LocalActivityInteraction.current
    val depth = LocalActivityDepth.current
    val inline = depth > 0 && inlineDetails != null
    var readerOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var autoOpened by remember { mutableStateOf(false) }
    // A live turn opens its working log so streamed commentary never disappears
    // into a collapsed row, then folds away when the turn finishes.
    LaunchedEffect(live, expandable, autoExpand) {
        if (autoExpand && live && expandable && !autoOpened) {
            expanded = true
            autoOpened = true
        }
        if (!live && autoOpened) {
            expanded = false
            autoOpened = false
        }
    }
    val scroll = rememberChatListState()
    // Retain the reader's follow preference when this disclosure survives a stream commit.
    val followingState = if (detailScroll == null) remember { mutableStateOf(true) }
        else rememberSaveable { mutableStateOf(true) }
    var following by followingState
    var wasExpanded by rememberSaveable { mutableStateOf(false) }
    var userDragged by remember { mutableStateOf(false) }
    var towardBottom by remember { mutableStateOf(false) }
    val userScroll = remember { UserScrollObserver { delta ->
        following = false
        userDragged = true
        towardBottom = delta < 0f
    } }
    // One layout-driven follower. Stream updates never restart/cancel an in-flight scroll.
    LaunchedEffect(expanded) {
        if (expanded && (detailScroll == null || !wasExpanded)) { following = true; userDragged = false }
        wasExpanded = expanded
    }
    LaunchedEffect(expanded, followLatest, following) {
        if (!expanded || !followLatest || !following) return@LaunchedEffect
        snapshotFlow {
            val info = scroll.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.let { it.index to (it.offset + it.size) }, scroll.isScrollInProgress)
        }.collect {
            if (!following || scroll.isScrollInProgress) return@collect
            withFrameNanos { }
            if (!following || scroll.isScrollInProgress) return@collect
            var info = scroll.layoutInfo
            val lastIndex = info.totalItemsCount - 1
            if (lastIndex < 0) return@collect
            if (info.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
                scroll.scrollToItem(lastIndex)
                if (!following || scroll.isScrollInProgress) return@collect
                info = scroll.layoutInfo
            }
            val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
            if (last.index == info.totalItemsCount - 1) {
                val overflow = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
                if (overflow > 0) scroll.scrollBy(overflow.toFloat())
            }
        }
    }
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userDragged = true
                following = false
            }
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to scroll.canScrollForward }.collect { (moving, canScroll) ->
            if (userDragged && towardBottom && !moving && !canScroll) {
                userDragged = false
                following = true
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val detailHeight = (LocalConfiguration.current.screenHeightDp * .5f).dp.coerceIn(220.dp, 420.dp)
    val context = androidx.compose.ui.platform.LocalContext.current
    val motion = remember(context) { android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    Column(Modifier.fillMaxWidth()) {
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatActivityGlyph(tool, live, motion)
                    AnimatedContent(title, transitionSpec = {
                        (fadeIn(tween(if (motion) 180 else 0)) + slideInVertically { if (motion) it / 3 else 0 }) togetherWith
                            fadeOut(tween(if (motion) 100 else 0))
                    }, label = "Chat activity phase", modifier = Modifier.weight(1f, fill = false)) { label -> ChatShimmerText(label, live && motion) }
                    if (expandable) Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        stringResource(if (expanded) R.string.ui_collapse else R.string.ui_expand), Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
                trailing?.invoke()
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (expandable) TextButton(onClick = { pauseParent(); detailScroll?.pause(); expanded = !expanded }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { header() }
            else Box(Modifier.weight(1f).heightIn(min = 36.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { header() }
            if (expanded && inlineDetails == null) IconButton(onClick = { pauseParent(); detailScroll?.pause(); following = false; readerOpen = true }) {
                Icon(Icons.Outlined.OpenInFull, stringResource(R.string.chat_activity_full_view), Modifier.size(18.dp))
            }
            }
        }
        AnimatedVisibility(expanded && expandable,
            enter = fadeIn(tween(120)) + expandVertically(com.androidharness.app.ui.theme.expressiveSpatialSpec()),
            exit = fadeOut(tween(100)) + shrinkVertically(com.androidharness.app.ui.theme.expressiveSpatialSpec())) {
            CompositionLocalProvider(LocalActivityInteraction provides { following = false; userDragged = true }, LocalActivityDepth provides depth + 1) {
                Surface(shape = RoundedCornerShape(if (inline) 16.dp else 20.dp), color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .45f))) {
                    if (inline) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { inlineDetails!!() }
                    else Column {
                        ActivityScrollFrame(scroll, Modifier.fillMaxWidth().heightIn(max = if (compactPreview) 180.dp else detailHeight)) {
                            LazyColumn(Modifier.fillMaxWidth().containChatDetailScroll().nestedScroll(userScroll), state = scroll,
                                contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp), content = details)
                        }
                        if (scroll.canScrollForward || live && !following && followLatest) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (scroll.canScrollForward) Text(stringResource(R.string.chat_activity_more), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            else Spacer(Modifier.weight(1f))
                            if (live && followLatest && !following) TextButton(onClick = { following = true; userDragged = false }) { Text(stringResource(R.string.chat_activity_latest)) }
                        }
                    }
                }
            }
        }
        if (readerOpen) ActivityReader(title, onDismiss = { readerOpen = false }, content = details)
        progress?.let { counters ->
            val style = MaterialTheme.typography.labelSmall
            val color = scheme.onSurfaceVariant
            Row(
                Modifier.padding(top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedCount(counters.sourcesUsed, motion) { count ->
                    Text("$count", style = style, color = color)
                }
                Text("/${counters.sourcesMax} sources", style = style, color = color)
                if (counters.readsRequired > 0 || counters.pagesRead > 0) {
                    Text(" · ", style = style, color = color)
                    AnimatedCount(counters.pagesRead, motion) { count ->
                        Text("$count", style = style, color = color)
                    }
                    Text(" read", style = style, color = color)
                }
            }
        }
    }
}

/** Slides and fades a changed number while the research counters grow. */
@Composable
private fun AnimatedCount(value: Int, motion: Boolean, content: @Composable (Int) -> Unit) {
    if (!motion) {
        content(value)
        return
    }
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(tween(120)) { -it / 2 } + fadeOut(tween(120)))
        },
        label = "Research count",
    ) { count -> content(count) }
}

/** Compact, left-aligned attribution below the reply, with domains inside the sources sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSources(messages: List<ChatMessage>, onOpenUrl: (String) -> Unit) {
    val sources = remember(messages) { ChatActivityPresentation.sources(messages) }
    if (sources.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
            Icon(Icons.Outlined.Public, null, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_source_count, sources.size), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.chat_sources), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.chat_sources_explanation), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            sources.forEach { source ->
                TextButton(onClick = { open = false; onOpenUrl(source.url) }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        Text(source.host, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(stringResource(if (source.read) R.string.chat_source_read else R.string.chat_source_found), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Public, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Quiet confirmation below a reply that saved something to memory. */
@Composable
internal fun ChatMemorySaved(label: Int = R.string.chat_memory_saved, onClick: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = scheme.secondaryContainer.copy(alpha = 0.65f)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = scheme.onSecondaryContainer)
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = scheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ChatActivityGlyph(name: String?, live: Boolean, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val animated = live && motion
    var angle = 0f
    var sparkleHop = 0f
    var starBlink = 1f
    var searchX = 0f
    var searchY = 0f
    var codeX = 0f
    var codeTilt = 0f
    var blink = 1f
    var blinkAlpha = 1f
    var lookX = 0f
    if (animated) {
        // lucide-animated (MIT) loops: sparkles hop then blink, search lifts and settles,
        // code nudges side to side, and the reading eyes scan a line then blink.
        val transition = rememberInfiniteTransition(label = "Chat activity orbit")
        angle = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "Chat orbit rotation").value
        sparkleHop = transition.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 3000
            0f at 150
            -1f at 420
            0f at 700
        }), label = "Chat sparkle hop").value
        starBlink = transition.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
            durationMillis = 3000
            1f at 1150
            0.15f at 1300
            1f at 1500
        }), label = "Chat star blink").value
        searchX = transition.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 3000
            0f at 300
            -3f at 480
            0f at 760
        }), label = "Chat search drift").value
        searchY = transition.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 3000
            0f at 260
            -4f at 420
            0f at 700
        }), label = "Chat search lift").value
        codeX = transition.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 3000
            0f at 150
            2f at 400
            0f at 650
            -2f at 900
            0f at 1150
        }), label = "Chat code nudge").value
        codeTilt = transition.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 3000
            0f at 150
            -8f at 400
            0f at 650
            8f at 900
            0f at 1150
        }), label = "Chat code tilt").value
        // Lucide Animated eye blink: squashes to 0.1 over 0.4s with an opacity dip.
        blink = transition.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
            durationMillis = 4200
            1f at 3000
            0.1f at 3140
            1f at 3280
        }), label = "Chat eye blink").value
        blinkAlpha = transition.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
            durationMillis = 4200
            1f at 3000
            0.3f at 3140
            1f at 3280
        }), label = "Chat eye blink alpha").value
        // The pupils sweep left to right before the blink, like reading a line.
        lookX = transition.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
            durationMillis = 4200
            0f at 400
            -1.2f at 650
            -1.2f at 950
            0f at 1150
            1.2f at 1400
            1.2f at 1900
            0f at 2200
        }), label = "Chat eye scan").value
    }
    Box(Modifier.size(28.dp).background(scheme.surfaceContainerHigh.copy(alpha = .65f), CircleShape), contentAlignment = Alignment.Center) {
        if (live) Canvas(Modifier.fillMaxSize()) {
            drawArc(scheme.onSurfaceVariant.copy(alpha = .35f), angle - 90f, 95f, false,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = size.copy(width = size.width - 2.dp.toPx(), height = size.height - 2.dp.toPx()),
                style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
        }
        Crossfade(name.orEmpty(), animationSpec = tween(if (motion) 180 else 0), label = "Chat activity icon") { phase ->
            when (phase) {
                "web_search" -> Icon(Icons.Outlined.Search, null, Modifier.size(15.dp).graphicsLayer {
                    translationX = searchX * size.width / 24f
                    translationY = searchY * size.height / 24f
                }, tint = scheme.onSurfaceVariant)
                "search_chats", "recent_chats", "read_chat_snippet" -> Icon(
                    Icons.Outlined.History, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant,
                )
                "web_fetch", "browser_read", "view_chat_image", "zoom_chat_image" -> Box(Modifier.size(15.dp)) {
                    Icon(painterResource(R.drawable.ic_chat_eyes), null, Modifier.fillMaxSize().graphicsLayer {
                        scaleY = blink
                        alpha = blinkAlpha
                    }, tint = scheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_chat_eyes_pupils), null, Modifier.fillMaxSize().graphicsLayer {
                        scaleY = blink
                        alpha = blinkAlpha
                        translationX = lookX * size.width / 24f
                    }, tint = scheme.onSurfaceVariant)
                }
                "github_api", "github_search", "github_file" -> Icon(Icons.Outlined.Code, null, Modifier.size(15.dp).graphicsLayer {
                    translationX = codeX * size.width / 24f
                    rotationZ = codeTilt
                }, tint = scheme.onSurfaceVariant)
                else -> Box(Modifier.size(15.dp)) {
                    Icon(painterResource(R.drawable.ic_chat_sparkle), null, Modifier.fillMaxSize().graphicsLayer {
                        translationY = sparkleHop * size.height / 24f
                    }, tint = scheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_chat_sparkle_stars), null, Modifier.fillMaxSize().graphicsLayer {
                        alpha = starBlink
                    }, tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun activityLabel(name: String, running: Boolean): String = stringResource(when (name) {
    "web_search" -> if (running) R.string.chat_searching else R.string.chat_searched
    "search_chats" -> if (running) R.string.chat_searching_chats else R.string.chat_searched_chats
    "recent_chats" -> if (running) R.string.chat_checking_recent_chats else R.string.chat_checked_recent_chats
    "read_chat_snippet" -> if (running) R.string.chat_reading_past_chat else R.string.chat_read_past_chat
    "web_fetch", "browser_read" -> if (running) R.string.chat_reading_page else R.string.chat_read_page
    "github_api", "github_search", "github_file" -> if (running) R.string.chat_reading_github else R.string.chat_read_github
    "create_artifact" -> if (running) R.string.chat_creating_artifact else R.string.chat_created_artifact
    "read_artifact" -> if (running) R.string.chat_reading_artifact else R.string.chat_read_artifact
    "search_agent_knowledge" -> if (running) R.string.chat_agents_knowledge_activity else R.string.chat_agents_knowledge_done
    "read_chat_text" -> if (running) R.string.chat_reading_text else R.string.chat_read_text
    "view_chat_image" -> if (running) R.string.chat_viewing_image else R.string.chat_viewed_image
    "zoom_chat_image" -> if (running) R.string.chat_zooming_image else R.string.chat_zoomed_image
    "remember" -> if (running) R.string.chat_saving_memory else R.string.chat_memory_saved
    "recall" -> if (running) R.string.chat_searching_memory else R.string.chat_searched_memory
    "read_tool_output" -> if (running) R.string.chat_reading_results else R.string.chat_read_results
    "visual_guide" -> if (running) R.string.chat_reading_design_guide else R.string.chat_read_design_guide
    "offer_code_handoff" -> R.string.chat_preparing_code_option
    else -> if (running) R.string.chat_working else R.string.chat_finished_activity
})

@Composable
private fun ChatShimmerText(text: String, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (!running) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "Chat activity")
    val x by transition.animateFloat(-180f, 650f,
        infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "Chat text shimmer")
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(brush = Brush.linearGradient(
            listOf(scheme.onSurfaceVariant, scheme.onSurface, scheme.onSurfaceVariant),
            start = Offset(x, 0f), end = Offset(x + 160f, 0f))),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
