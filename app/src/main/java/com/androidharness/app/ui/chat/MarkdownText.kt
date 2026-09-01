package com.androidharness.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Markdown-style renderer for model output, no external dependency. Supports
 * fenced code blocks (language header + one-tap copy), headings #–####,
 * bullet/numbered lists (one nesting level), blockquotes with a hairline rail,
 * horizontal rules, and inline bold / italic / strikethrough / code / links.
 *
 * While [streaming], only completed paragraphs (up to the last blank line, or
 * the whole text when it ends inside an open code fence) render as markdown;
 * the in-progress tail renders plain. This prevents flicker from half-formed
 * markers and keeps re-parse cost bounded while text streams in.
 *
 * A live open code fence is additionally capped to its last ~1500 chars:
 * the fence body re-lays-out every frame while streaming, so an unbounded
 * body would cost O(n) per frame (O(n^2) cumulative) on the main thread.
 * Full content renders once the message commits.
 */
@Composable
fun MarkdownText(
    text: String,
    streaming: Boolean = false,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    // Strip agent system directives (like ::web-preview{...}) so raw markup is never visible to the user
    val cleanText = remember(text) {
        if (text.contains("preview")) {
            com.androidharness.app.core.WebResourceExtractor.stripDirectives(text)
        } else {
            text
        }
    }

    // The fence tracker is a composable (remember), it must be invoked
    // unconditionally, so the `streaming` gate sits on the result.
    val openFence = rememberEndsInsideOpenFence(cleanText) && streaming
    val cut = if (!streaming || openFence) -1 else cleanText.lastIndexOf("\n\n")
    // Key the stable prefix on `cut` (not the ever-growing `text`): while the
    // in-progress tail grows, the completed-paragraph prefix string is reused
    // instead of being re-allocated on every typewriter tick.
    val safeText = remember(streaming, openFence, cut, if (!streaming || openFence) cleanText else null) {
        when {
            !streaming -> cleanText
            openFence -> if (cleanText.length > STREAM_FENCE_CAP) {
                "…" + cleanText.takeLast(STREAM_FENCE_CAP)
            } else cleanText
            cut > 0 -> cleanText.substring(0, cut)
            else -> ""
        }
    }
    // The fence path renders the (capped) whole text; only paragraph streaming
    // has a separate plain tail.
    val tail = remember(cleanText, safeText, openFence) {
        if (openFence) "" else cleanText.substring(safeText.length)
    }

    Column(Modifier.fillMaxWidth()) {
        // Capped fence text still parses to a real CodeBlock (language header,
        // copy button); the cap bounds the per-frame layout cost.
        if (safeText.isNotEmpty()) MarkdownBlocks(safeText, onOpenUrl = onOpenUrl)
        if (tail.isNotEmpty()) MarkdownBlocks(tail, plain = true, onOpenUrl = onOpenUrl)
    }
}

/** Live open-fence bodies longer than this render only their tail. */
private const val STREAM_FENCE_CAP = 1_500

/**
 * Incremental open-fence scan. Streaming text only appends within a message,
 * so fence parity is folded per COMPLETED line and only new lines are scanned
 * instead of rescanning the full accumulated text on every typewriter tick.
 */
private class FenceTracker {
    /** Offset such that [0, safePos) has been folded into [open]; always at a line boundary. */
    var safePos = 0
    var open = false
    /** Last text seen; used to detect a wholesale (non-append) replacement. */
    var prefixRef: String = ""
}

@Composable
private fun rememberEndsInsideOpenFence(text: String): Boolean {
    val tracker = remember { FenceTracker() }
    return remember(text) {
        if (!text.startsWith(tracker.prefixRef)) {
            tracker.safePos = 0
            tracker.open = false
        }
        var open = tracker.open
        var pos = tracker.safePos
        while (true) {
            val nl = text.indexOf('\n', pos)
            if (nl < 0) break
            if (text.substring(pos, nl).trimStart().startsWith("```")) open = !open
            pos = nl + 1
        }
        tracker.open = open
        tracker.safePos = pos
        tracker.prefixRef = text
        // The trailing partial line counts for the answer but is NOT folded:
        // it may still grow into (or out of) a fence marker.
        if (text.substring(pos).trimStart().startsWith("```")) !open else open
    }
}

// ---------------------------------------------------------------------------
// Block parsing

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val content: String) : Block
    data class Code(val text: String, val language: String) : Block
    data class Quote(val lines: List<String>) : Block
    data object Rule : Block
    data class Bullets(val items: List<ListItem>) : Block
    data class Table(
        val headers: List<String>,
        val alignments: List<TextAlign>,
        val rows: List<List<String>>,
    ) : Block
}

private data class ListItem(val marker: String, val content: String, val nested: List<ListItem>)

private fun isTableStart(lines: List<String>, idx: Int): Boolean {
    if (idx + 1 >= lines.size) return false
    val header = lines[idx].trim()
    val delimiter = lines[idx + 1].trim()
    if (!header.contains('|') || !delimiter.contains('|')) return false
    val delimiterCells = delimiter.trim('|').split('|')
    return delimiterCells.isNotEmpty() && delimiterCells.all { cell ->
        val trimmed = cell.trim()
        trimmed.isNotEmpty() && trimmed.all { it == '-' || it == ':' }
    }
}

private fun parseTableBlock(lines: List<String>, startIdx: Int): Pair<Block.Table, Int> {
    val headerLine = lines[startIdx].trim()
    val delimiterLine = lines[startIdx + 1].trim()

    val headers = splitTableRow(headerLine)
    val alignments = splitTableRow(delimiterLine).map { cell ->
        val trimmed = cell.trim()
        when {
            trimmed.startsWith(':') && trimmed.endsWith(':') -> TextAlign.Center
            trimmed.endsWith(':') -> TextAlign.End
            else -> TextAlign.Start
        }
    }

    val rows = mutableListOf<List<String>>()
    var i = startIdx + 2
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.isBlank() || !line.contains('|')) break
        rows += splitTableRow(line)
        i++
    }

    return Block.Table(headers, alignments, rows) to i
}

private fun splitTableRow(row: String): List<String> {
    val clean = row.trim()
    val trimmed = if (clean.startsWith('|') && clean.endsWith('|') && clean.length > 1) {
        clean.substring(1, clean.length - 1)
    } else {
        clean.removePrefix("|").removeSuffix("|")
    }
    return trimmed.split('|').map { it.trim() }
}

private val bulletRegex = Regex("^\\s*([-*+])\\s+(.*)$")
private val numberedRegex = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])\\s*(?:\\1\\s*){2,}$")

private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = text.split('\n')
    var i = 0
    val paraBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paraBuf.isNotEmpty()) {
            out += Block.Paragraph(paraBuf.joinToString("\n").trimEnd())
            paraBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip the closing fence
                out += Block.Code(body.toString(), lang)
            }

            headingRegex.matches(trimmed) -> {
                flushParagraph()
                val m = headingRegex.find(trimmed)!!
                out += Block.Heading(m.groupValues[1].length.coerceAtMost(4), m.groupValues[2])
                i++
            }

            ruleRegex.matches(trimmed) -> {
                flushParagraph()
                out += Block.Rule
                i++
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                out += Block.Quote(quoteLines)
            }

            isTableStart(lines, i) -> {
                flushParagraph()
                val (tableBlock, nextIdx) = parseTableBlock(lines, i)
                out += tableBlock
                i = nextIdx
            }

            bulletRegex.matches(line) || numberedRegex.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<ListItem>()
                while (i < lines.size && (bulletRegex.matches(lines[i]) || numberedRegex.matches(lines[i]))) {
                    val l = lines[i]
                    val m = bulletRegex.find(l) ?: numberedRegex.find(l)!!
                    val item = ListItem(m.groupValues[1], m.groupValues[2], emptyList())
                    val isNested = l.startsWith("  ") || l.startsWith("\t")
                    if (isNested && items.isNotEmpty()) {
                        items[items.lastIndex] = items.last().let { top ->
                            top.copy(nested = top.nested + item)
                        }
                    } else {
                        items += item
                    }
                    i++
                }
                out += Block.Bullets(items)
            }

            line.isBlank() -> {
                flushParagraph()
                i++
            }

            else -> {
                paraBuf += line
                i++
            }
        }
    }
    flushParagraph()
    return out
}

// ---------------------------------------------------------------------------
// Block rendering

@Composable
private fun MarkdownBlocks(
    text: String,
    plain: Boolean = false,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val blocks = remember(text) { parseBlocks(text) }
    blocks.forEachIndexed { index, block ->
        when (block) {
            is Block.Code -> CodeBlock(block.text.trimEnd('\n'), block.language)
            is Block.Heading -> Text(
                block.content,
                // Calmer than a type-scale ladder: mobile summaries read best
                // when headings stay close to body size and lean on weight.
                style = when (block.level) {
                    1 -> MaterialTheme.typography.titleMedium
                    2 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.labelLarge
                },
                modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
            )
            is Block.Quote -> QuoteBlock(block.lines)
            is Block.Rule -> HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            is Block.Bullets -> ListBlock(block.items, plain = plain)
            is Block.Table -> TableBlock(block, onOpenUrl = onOpenUrl)
            is Block.Paragraph -> if (plain) {
                Text(block.text, style = MaterialTheme.typography.bodyLarge)
            } else {
                ParagraphText(block.text, onOpenUrl = onOpenUrl)
            }
        }
        if (index != blocks.lastIndex) Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun ListBlock(items: List<ListItem>, plain: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        items.forEach { item ->
            Row {
                Text(
                    if (item.marker.length == 1 && item.marker[0].isDigit()) "${item.marker}." else "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(16.dp),
                )
                Column(Modifier.weight(1f)) {
                    if (plain) {
                        Text(item.content, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text(styledText(item.content), style = MaterialTheme.typography.bodyLarge)
                    }
                    item.nested.forEach { nested ->
                        Row {
                            Text(
                                "◦",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp),
                            )
                            if (plain) {
                                Text(
                                    nested.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    styledText(nested.content),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(lines: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(top = 2.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(
                    styledText(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun TableBlock(
    table: Block.Table,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                table.headers.forEachIndexed { colIdx, header ->
                    val align = table.alignments.getOrElse(colIdx) { TextAlign.Start }
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = align,
                        color = scheme.onSurface,
                        modifier = Modifier
                            .widthIn(min = 100.dp)
                            .padding(horizontal = 8.dp),
                    )
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

            // Body Rows (Zebra striped for clean mobile document legibility)
            table.rows.forEachIndexed { rowIdx, row ->
                val bg = if (rowIdx % 2 == 1) scheme.surfaceContainerLow.copy(alpha = 0.5f) else Color.Transparent
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(bg)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                ) {
                    table.headers.indices.forEach { colIdx ->
                        val cellText = row.getOrElse(colIdx) { "" }
                        val align = table.alignments.getOrElse(colIdx) { TextAlign.Start }
                        Box(
                            modifier = Modifier
                                .widthIn(min = 100.dp)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = styledText(cellText),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = align,
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
                if (rowIdx != table.rows.lastIndex) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

/** Paragraph text with tap-to-open links (annotations set by [styledText]). */
@Composable
private fun ParagraphText(text: String, onOpenUrl: ((String) -> Unit)? = null) {
    val styled = styledText(text)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val context = LocalContext.current
    val currentOpenUrl by androidx.compose.runtime.rememberUpdatedState(onOpenUrl)
    Text(
        text = styled,
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures { pos ->
                val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                styled.getStringAnnotations("url", offset, offset)
                    .firstOrNull()?.let { ann ->
                        val url = ann.item
                        if (currentOpenUrl != null && com.androidharness.app.core.LocalPortProbe.isLocalhostUrl(url)) {
                            currentOpenUrl?.invoke(url)
                        } else {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    }
            }
        },
    )
}

/** Committed code blocks render at most this many lines before an expand control. */
private const val CODE_BLOCK_COLLAPSED_LINES = 40

@Composable
private fun CodeBlock(code: String, language: String) {
    val scheme = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Layout cost scales with block size; giant pasted files render collapsed
    // (one Text of 40 lines) until the user asks for the rest.
    val lines = code.lines()
    val collapsed = !expanded && lines.size > CODE_BLOCK_COLLAPSED_LINES
    val shown = if (collapsed) {
        lines.take(CODE_BLOCK_COLLAPSED_LINES).joinToString("\n")
    } else code
    val hiddenCount = lines.size - CODE_BLOCK_COLLAPSED_LINES

    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp),
            ) {
                Text(
                    language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                        scope.launch {
                            delay(1500)
                            copied = false
                        }
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (copied) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                shown,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .horizontalScroll(rememberScrollState()),
            )
            if (collapsed) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 8.dp, bottom = 6.dp),
                ) {
                    Text("+ $hiddenCount more lines")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Inline styling

private val linkRegex = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")

/** Inline bold, italic, strikethrough, code, and links. */
@Composable
private fun styledText(text: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeColor, codeBackground, linkColor) {
        val links = linkRegex.findAll(text).associateBy { it.range.first }
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val link = links[i]
                when {
                    link != null -> {
                        pushStringAnnotation(tag = "url", annotation = link.groupValues[2])
                        pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        append(link.groupValues[1])
                        pop()
                        pop()
                        i = link.range.last + 1
                    }

                    text.startsWith("**", i) -> {
                        val end = text.indexOf("**", i + 2)
                        if (end > i + 2) {
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            append(text.substring(i + 2, end))
                            pop()
                            i = end + 2
                        } else {
                            append(text[i]); i++
                        }
                    }

                    text.startsWith("~~", i) -> {
                        val end = text.indexOf("~~", i + 2)
                        if (end > i + 2) {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                            append(text.substring(i + 2, end))
                            pop()
                            i = end + 2
                        } else {
                            append(text[i]); i++
                        }
                    }

                    text.startsWith("`", i) -> {
                        val end = text.indexOf('`', i + 1)
                        if (end > i + 1) {
                            pushStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = codeBackground,
                                    color = codeColor,
                                )
                            )
                            // Hair spaces pad the chip visually, spans can't
                            // take real padding inside a Text.
                            append(" " + text.substring(i + 1, end) + " ")
                            pop()
                            i = end + 1
                        } else {
                            append(text[i]); i++
                        }
                    }

                    text.startsWith("*", i) -> {
                        val end = text.indexOf('*', i + 1)
                        if (end > i + 1) {
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                            append(text.substring(i + 1, end))
                            pop()
                            i = end + 1
                        } else {
                            append(text[i]); i++
                        }
                    }

                    else -> {
                        append(text[i]); i++
                    }
                }
            }
        }
    }
}
