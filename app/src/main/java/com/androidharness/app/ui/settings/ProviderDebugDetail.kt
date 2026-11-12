package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.llm.debug.DebugJson
import com.androidharness.app.llm.debug.DebugTrace
import com.androidharness.app.llm.debug.DebugTurns
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailTab(val label: Int) {
    OVERVIEW(R.string.debug_console_tab_overview),
    REQUEST(R.string.debug_console_tab_request),
    RESPONSE(R.string.debug_console_tab_response),
    STREAM(R.string.debug_console_tab_stream),
}

/** Above this a body is shown without colours: laying out that many spans stalls the sheet. */
private const val HIGHLIGHT_LIMIT = 60_000
private const val COLLAPSED_LINES = 40

/**
 * One request, read side by side with its answer.
 *
 * The header, the tabs and the find bar stay put; only the content below them
 * scrolls, so a long body never pushes the controls away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderDebugDetailSheet(
    turn: DebugTurns.Turn,
    attempt: DebugTurns.Attempt,
    chatTitle: String?,
    onOpen: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val trace = attempt.trace
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val tabs = remember(trace.stream) { DetailTab.entries.filter { it != DetailTab.STREAM || trace.stream } }
    var pickedTab by rememberSaveable { mutableStateOf(DetailTab.OVERVIEW) }
    // Stepping to an attempt that was not streamed drops the Stream tab from under the reader.
    val tab = if (pickedTab in tabs) pickedTab else DetailTab.OVERVIEW
    var find by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var bodyIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(tab, trace.id) {
        matches = emptyList()
        current = 0
        bodyLayout = null
        listState.scrollToItem(0)
    }

    fun reveal(match: Int) {
        val layout = bodyLayout ?: return
        val offset = matches.getOrNull(match) ?: return
        if (bodyIndex < 0 || offset > layout.layoutInput.text.length) return
        // The body text starts below its card header; land the match a little under the top edge.
        val top = layout.getLineTop(layout.getLineForOffset(offset)).toInt()
        val lead = with(density) { 48.dp.roundToPx() }
        scope.launch { listState.animateScrollToItem(bodyIndex, (top - lead).coerceAtLeast(0)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .92f).dp)
            .navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusBadge(trace, 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(trace.providerName.ifBlank { trace.url.substringAfter("//").substringBefore('/') },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("${trace.method} ${trace.url.substringAfter("//")}", style = MaterialTheme.typography.bodySmall,
                        fontFamily = HarnessMono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.settings_debug_close)) }
            }

            if (turn.retried) AttemptStrip(turn, trace.id, onOpen)

            SegmentRow(tabs, tab) { pickedTab = it }

            if (tab != DetailTab.OVERVIEW) {
                FindBar(
                    query = find,
                    onQuery = {
                        find = it
                        current = 0
                    },
                    // The stream tab filters its events instead of stepping through matches.
                    count = if (tab == DetailTab.STREAM) null else matches.size,
                    current = current,
                    onStep = { delta ->
                        if (matches.isNotEmpty()) {
                            current = (current + delta + matches.size) % matches.size
                            reveal(current)
                        }
                    },
                )
            }

            val onBody: (Int, List<Int>, TextLayoutResult?) -> Unit = { index, found, layout ->
                bodyIndex = index
                if (found != matches) {
                    matches = found
                    current = 0
                }
                bodyLayout = layout
            }
            val query = find.trim()
            val streamBody = trace.streamBody
            val streamLines by produceState(emptyList<StreamLine>(), streamBody, tab) {
                value = if (streamBody == null || tab != DetailTab.STREAM) emptyList()
                else withContext(Dispatchers.Default) { parseStream(streamBody) }
            }
            val opened = remember(trace.id) { mutableStateMapOf<Int, Boolean>() }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    DetailTab.OVERVIEW -> overview(trace, attempt, chatTitle)
                    DetailTab.REQUEST -> request(trace, query, onBody)
                    DetailTab.RESPONSE -> response(trace, query, onBody)
                    DetailTab.STREAM -> stream(trace, streamLines, query, opened)
                }
            }

            ActionBar(trace, context, scope)
        }
    }
}

private fun LazyListScope.overview(trace: DebugTrace, attempt: DebugTurns.Attempt, chatTitle: String?) {
    if (trace.failed) item(key = "hint") {
        val scheme = MaterialTheme.colorScheme
        NoteCard(statusHint(trace), scheme.errorContainer, scheme.onErrorContainer,
            detail = trace.error?.takeIf { trace.statusCode == null })
    }
    if (attempt.previous != null) item(key = "reason") {
        val scheme = MaterialTheme.colorScheme
        attemptReason(attempt, detailed = true)?.let { NoteCard(it, scheme.secondaryContainer, scheme.onSecondaryContainer) }
    }
    item(key = "facts") { Facts(trace, chatTitle) }
    if (trace.limitHeaders.isNotEmpty()) item(key = "limits") {
        HeadersCard(stringResource(R.string.debug_console_limits), trace.limitHeaders, "")
    }
    item(key = "endpoint") {
        DetailCard(stringResource(R.string.debug_console_endpoint), copy = trace.url) {
            SelectionContainer {
                Text("${trace.method} ${trace.url}", style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono)
            }
        }
    }
}

private fun LazyListScope.request(trace: DebugTrace, query: String, onBody: (Int, List<Int>, TextLayoutResult?) -> Unit) {
    item(key = "request-headers") { HeadersCard(stringResource(R.string.debug_console_headers), trace.requestHeaders, query) }
    item(key = "request-body") {
        val note = when {
            trace.requestBodyElided && trace.requestBodyValidJson -> stringResource(R.string.debug_console_elided, trace.requestBodyOmitted)
            trace.requestBodyElided -> stringResource(R.string.debug_console_cut, trace.requestBodyOmitted)
            else -> null
        }
        BodyCard(stringResource(R.string.debug_console_body), trace.requestBody, stringResource(R.string.debug_console_no_body),
            note, query) { found, layout -> onBody(1, found, layout) }
    }
}

private fun LazyListScope.response(trace: DebugTrace, query: String, onBody: (Int, List<Int>, TextLayoutResult?) -> Unit) {
    val limits = trace.limitHeaders
    if (limits.isNotEmpty()) item(key = "response-limits") {
        HeadersCard(stringResource(R.string.debug_console_limits), limits, query)
    }
    item(key = "response-headers") { HeadersCard(stringResource(R.string.debug_console_headers), trace.responseHeaders, query) }
    val index = if (limits.isNotEmpty()) 2 else 1
    item(key = "response-body") {
        val body = trace.errorBody ?: trace.responsePreview
        val empty = when {
            trace.statusCode == null -> stringResource(R.string.debug_console_no_response)
            trace.stream -> stringResource(R.string.debug_console_streamed_body)
            else -> stringResource(R.string.debug_console_no_body)
        }
        BodyCard(
            stringResource(if (trace.errorBody != null) R.string.debug_console_error_body else R.string.debug_console_body),
            body?.takeIf { it.isNotEmpty() }, empty,
            if (trace.truncated && !trace.stream) stringResource(R.string.debug_console_truncated) else null,
            query,
        ) { found, layout -> onBody(index, found, layout) }
    }
}

/** What the status usually means, so the reader knows where to look first. */
@Composable
private fun statusHint(trace: DebugTrace): String {
    val status = trace.statusCode ?: return stringResource(R.string.debug_console_hint_network)
    return when (status) {
        400, 422 -> stringResource(R.string.debug_console_hint_400)
        401, 403 -> stringResource(R.string.debug_console_hint_401)
        402 -> stringResource(R.string.debug_console_hint_402)
        404 -> stringResource(R.string.debug_console_hint_404)
        408 -> stringResource(R.string.debug_console_hint_408)
        413 -> stringResource(R.string.debug_console_hint_413)
        429 -> trace.retryAfter?.let { wait ->
            // Retry-After is either a number of seconds or a date.
            val shown = if (wait.all { it.isDigit() }) stringResource(R.string.debug_console_seconds, wait) else wait
            stringResource(R.string.debug_console_hint_429_wait, shown)
        } ?: stringResource(R.string.debug_console_hint_429)
        in 500..599 -> stringResource(R.string.debug_console_hint_5xx)
        else -> stringResource(R.string.debug_console_hint_other)
    }
}

/** The attempts of this turn as a row of steps; the open one squares off. */
@Composable
private fun AttemptStrip(turn: DebugTurns.Turn, openId: Long, onOpen: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val position = turn.attempts.indexOfFirst { it.trace.id == openId } + 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            turn.attempts.take(8).forEachIndexed { index, attempt ->
                val selected = attempt.trace.id == openId
                val failed = attempt.trace.failed
                val corner by animateDpAsState(if (selected) 12.dp else 20.dp, label = "attempt-corner")
                val color = when {
                    selected && failed -> scheme.error
                    selected -> scheme.primary
                    failed -> scheme.errorContainer
                    else -> scheme.surfaceContainerHighest
                }
                val content = when {
                    selected && failed -> scheme.onError
                    selected -> scheme.onPrimary
                    failed -> scheme.onErrorContainer
                    else -> scheme.onSurfaceVariant
                }
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(corner)).background(color)
                    .selectable(selected, role = Role.Tab, onClick = { onOpen(attempt.trace.id) }),
                    contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = content)
                }
            }
        }
        Text(stringResource(R.string.debug_console_attempt_of, position, turn.attempts.size),
            style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

/** Tabs in the app's own style: the picked one fills with the primary colour and squares off. */
@Composable
private fun SegmentRow(tabs: List<DetailTab>, selected: DetailTab, onSelect: (DetailTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { tab ->
            val picked = tab == selected
            val corner by animateDpAsState(if (picked) 12.dp else 20.dp, label = "tab-corner")
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(corner))
                .background(if (picked) scheme.primary else scheme.surfaceContainerHigh)
                .selectable(picked, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center) {
                Text(stringResource(tab.label), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (picked) scheme.onPrimary else scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, count: Int?, current: Int, onStep: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.debug_console_find)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (query.isBlank()) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (count != null) {
                        Text(
                            if (count == 0) stringResource(R.string.debug_console_find_none)
                            else stringResource(R.string.debug_console_find_count, current + 1, count),
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onStep(-1) }, enabled = count > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.debug_console_find_previous))
                        }
                        IconButton(onClick = { onStep(1) }, enabled = count > 0) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.debug_console_find_next))
                        }
                    }
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.debug_console_clear_search)) }
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NoteCard(text: String, color: Color, content: Color, detail: String? = null) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = color, contentColor = content) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(trace: DebugTrace, chatTitle: String?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Fact(stringResource(R.string.debug_console_fact_duration), shortDuration(trace.durationMs))
        Fact(stringResource(R.string.debug_console_fact_started),
            SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(trace.startedAt)))
        if (trace.model.isNotBlank()) Fact(stringResource(R.string.debug_console_fact_model), trace.model)
        if (trace.protocol.isNotBlank()) Fact(stringResource(R.string.debug_console_fact_protocol), trace.protocol)
        Fact(stringResource(R.string.debug_console_fact_attempt), trace.attempt.toString())
        Fact(stringResource(R.string.debug_console_fact_key), (trace.keySlot + 1).toString())
        trace.streamUsage?.let { Fact(stringResource(R.string.debug_console_fact_usage), it) }
        if (trace.stream) Fact(stringResource(R.string.debug_console_fact_events), trace.streamEvents.toString())
        trace.providerTraceId?.let { Fact(stringResource(R.string.debug_console_fact_request_id), it) }
        chatTitle?.let { Fact(stringResource(R.string.debug_console_fact_chat), it) }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = HarnessMono, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    copy: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 14.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                actions()
                if (copy != null) IconButton(onClick = { copyText(context, copy) }) {
                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.ui_copy), Modifier.size(18.dp))
                }
            }
            Column(Modifier.padding(end = 8.dp), content = content)
        }
    }
}

@Composable
private fun HeadersCard(title: String, headers: List<Pair<String, String>>, query: String) {
    val scheme = MaterialTheme.colorScheme
    DetailCard(title, copy = headers.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.first}: ${it.second}" }) {
        if (headers.isEmpty()) {
            Text(stringResource(R.string.debug_console_no_headers), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant)
            return@DetailCard
        }
        val mark = SpanStyle(background = scheme.tertiaryContainer, color = scheme.onTertiaryContainer)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                headers.forEach { (name, value) ->
                    val line = "$name: $value"
                    Text(buildAnnotatedString {
                        append(line)
                        addStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Medium), 0, name.length)
                        markMatches(line, query).forEach { addStyle(mark, it, it + query.length) }
                    }, style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono)
                }
            }
        }
    }
}

private fun markMatches(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var index = text.indexOf(query, ignoreCase = true)
    while (index >= 0 && out.size < 2_000) {
        out += index
        index = text.indexOf(query, index + query.length, ignoreCase = true)
    }
    return out
}

private class RenderedBody(val text: AnnotatedString, val matches: List<Int>, val lines: Int, val json: Boolean)

/**
 * A request or response body: indented and coloured when it is JSON, collapsed
 * when it is long, and marked up with whatever the find bar is looking for.
 */
@Composable
private fun BodyCard(
    title: String,
    body: String?,
    empty: String,
    note: String?,
    query: String,
    onRendered: (List<Int>, TextLayoutResult?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var formatted by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = remember(scheme) {
        mapOf(
            DebugJson.Kind.KEY to SpanStyle(color = scheme.primary),
            DebugJson.Kind.STRING to SpanStyle(color = scheme.tertiary),
            DebugJson.Kind.NUMBER to SpanStyle(color = scheme.secondary, fontWeight = FontWeight.Medium),
            DebugJson.Kind.LITERAL to SpanStyle(color = scheme.secondary, fontWeight = FontWeight.Bold),
            DebugJson.Kind.PUNCTUATION to SpanStyle(color = scheme.onSurfaceVariant),
        )
    }
    val mark = remember(scheme) { SpanStyle(background = scheme.tertiaryContainer, color = scheme.onTertiaryContainer) }
    val rendered by produceState<RenderedBody?>(null, body, formatted, query, colors) {
        val raw = body
        value = if (raw == null) null else withContext(Dispatchers.Default) {
            val pretty = DebugJson.pretty(raw)
            val shown = if (formatted && pretty != null) pretty else raw
            val found = markMatches(shown, query)
            RenderedBody(
                buildAnnotatedString {
                    append(shown)
                    if (pretty != null && shown.length <= HIGHLIGHT_LIMIT) {
                        DebugJson.tokens(shown).forEach { token -> colors[token.kind]?.let { addStyle(it, token.start, token.end) } }
                    }
                    found.forEach { addStyle(mark, it, it + query.length) }
                },
                found, shown.count { it == '\n' } + 1, pretty != null,
            )
        }
    }
    // A match can sit anywhere, so searching opens the whole body.
    val open = expanded || query.isNotEmpty()

    DetailCard(title, copy = body, actions = {
        if (rendered?.json == true) TextButton(onClick = { formatted = !formatted }) {
            Text(stringResource(if (formatted) R.string.debug_console_raw else R.string.debug_console_formatted))
        }
    }) {
        val shown = rendered
        if (body == null) {
            Text(empty, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            LaunchedEffect(Unit) { onRendered(emptyList(), null) }
            return@DetailCard
        }
        if (shown == null) return@DetailCard
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        LaunchedEffect(shown, layout) { onRendered(shown.matches, layout) }
        SelectionContainer {
            Text(shown.text, style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono,
                maxLines = if (open) Int.MAX_VALUE else COLLAPSED_LINES, overflow = TextOverflow.Clip,
                onTextLayout = { layout = it })
        }
        if (shown.lines > COLLAPSED_LINES && query.isEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.debug_console_show_less)
                    else stringResource(R.string.debug_console_show_all, shown.lines))
            }
        }
    }
}

private enum class StreamKind { DATA, DONE, LABEL }

private class StreamLine(val number: Int, val kind: StreamKind, val text: String, val valid: Boolean)

private fun parseStream(body: String): List<StreamLine> {
    var number = 0
    return body.lineSequence().filter { it.isNotBlank() }.map { line ->
        if (line.startsWith("data:")) {
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") StreamLine(++number, StreamKind.DONE, payload, true)
            else StreamLine(++number, StreamKind.DATA, payload, DebugJson.isJson(payload))
        } else StreamLine(0, StreamKind.LABEL, line, true)
    }.toList()
}

/**
 * The raw events in arrival order. A chunk that is not JSON is the usual reason
 * a stream "just stops", so those are called out; so is a missing end marker.
 */
private fun LazyListScope.stream(
    trace: DebugTrace,
    lines: List<StreamLine>,
    query: String,
    opened: MutableMap<Int, Boolean>,
) {
    if (trace.streamBody == null) {
        item(key = "stream-missing") {
            val scheme = MaterialTheme.colorScheme
            NoteCard(stringResource(R.string.debug_console_stream_missing), scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
        }
        return
    }
    if (trace.truncated) item(key = "stream-truncated") {
        val scheme = MaterialTheme.colorScheme
        NoteCard(stringResource(R.string.debug_console_stream_truncated), scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
    } else if (lines.isNotEmpty() && lines.none { it.kind == StreamKind.DONE } && trace.protocol.contains("OPENAI", ignoreCase = true)) {
        item(key = "stream-no-done") {
            val scheme = MaterialTheme.colorScheme
            NoteCard(stringResource(R.string.debug_console_stream_no_done), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        }
    }
    val shown = if (query.isEmpty()) lines else lines.filter { it.text.contains(query, ignoreCase = true) }
    items(shown.size) { index -> StreamRow(shown[index], opened) }
}

@Composable
private fun StreamRow(line: StreamLine, opened: MutableMap<Int, Boolean>) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    when (line.kind) {
        StreamKind.LABEL -> Text(line.text, style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono,
            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp))
        StreamKind.DONE -> Row(Modifier.fillMaxWidth().background(scheme.primaryContainer, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(line.number.toString(), style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono,
                color = scheme.onPrimaryContainer)
            Text(stringResource(R.string.debug_console_stream_done), style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer)
        }
        StreamKind.DATA -> {
            val open = opened[line.number] == true
            Column(Modifier.fillMaxWidth().clip(shape)
                .background(if (line.valid) scheme.surfaceContainerLow else scheme.errorContainer)
                .clickable { opened[line.number] = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(line.number.toString(), style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono,
                        color = if (line.valid) scheme.primary else scheme.onErrorContainer)
                    if (!line.valid) Text(stringResource(R.string.debug_console_stream_malformed),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = scheme.onErrorContainer)
                }
                Text(if (open) DebugJson.pretty(line.text) ?: line.text else line.text,
                    style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono,
                    color = if (line.valid) scheme.onSurface else scheme.onErrorContainer,
                    maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ActionBar(trace: DebugTrace, context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            FilledTonalButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.debug_console_copy))
            }
            ExpressiveDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_copy_all)) }, onClick = {
                    menu = false
                    copyText(context, trace.asText())
                })
                ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_copy_curl)) }, onClick = {
                    menu = false
                    copyText(context, trace.asCurl())
                })
                trace.requestBody?.let { body ->
                    ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_copy_request)) }, onClick = {
                        menu = false
                        copyText(context, body)
                    })
                }
                (trace.errorBody ?: trace.responsePreview ?: trace.streamBody)?.let { body ->
                    ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_copy_response)) }, onClick = {
                        menu = false
                        copyText(context, body)
                    })
                }
            }
        }
        Button(onClick = { shareTraceText(context, scope, trace.asText()) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.debug_console_share))
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // A full body can pass the Binder limit, and the clipboard throws instead of trimming.
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Provider request", text.take(400_000))) }
        .onSuccess { Toast.makeText(context, R.string.debug_console_copied, Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(context, context.getString(R.string.ui_failed, it.javaClass.simpleName), Toast.LENGTH_LONG).show() }
}
