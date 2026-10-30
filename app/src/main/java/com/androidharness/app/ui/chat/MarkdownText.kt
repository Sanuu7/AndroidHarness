package com.androidharness.app.ui.chat

import com.androidharness.app.core.WorkspaceFileReference
import com.androidharness.app.ui.chat.rich.LocalResponseFileOpen
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink

import com.androidharness.app.R
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import com.androidharness.app.ui.common.ExpressiveIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidharness.app.ui.chat.components.MathBlock
import com.androidharness.app.ui.chat.components.MathInlineView
import com.androidharness.app.ui.chat.components.MermaidBlock
import com.androidharness.app.ui.chat.components.RemoteImageCard
import com.androidharness.app.ui.chat.components.RemoteLinkCard
import com.androidharness.app.ui.files.CodeTokenColors
import com.androidharness.app.ui.files.CodeTokenizer
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** False when this chat is set to "No image"; stray image blocks become links. */
internal val LocalChatImagesEnabled = compositionLocalOf { true }

/**
 * Markdown-style renderer for model output, no external dependency. Supports
 * fenced code blocks (language header + one-tap copy), headings #–####,
 * bullet/numbered lists (one nesting level), blockquotes with a hairline rail,
 * horizontal rules, and inline bold / italic / strikethrough / code / links.
 *
 * While [streaming], completed blocks are cached and the short live tail keeps
 * inline formatting. Both share one composition path so completion preserves
 * renderer identity and scroll position. Very long live paragraphs use a plain
 * fast path until their block completes.
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
    /** False for external documents such as release notes; no remote fetches. */
    media: Boolean = true,
) {
    // Strip agent system directives (follow-up chips, web preview) so raw
    // markup is never visible to the user, including mid-stream.
    val cleanText = remember(text) {
        val withoutFollowUps = com.androidharness.app.core.FollowUpSuggestions.stripForDisplay(text)
        if (withoutFollowUps.contains("preview")) {
            com.androidharness.app.core.WebResourceExtractor.stripDirectives(withoutFollowUps)
        } else {
            withoutFollowUps
        }
    }

    val tracker = remember { MarkdownStreamTracker() }
    val window = remember(cleanText) { tracker.update(cleanText) }
    val cut = if (streaming) window.stableEnd else cleanText.length
    val stableText = remember(cut, streaming, window.revision, if (!streaming) cleanText else null) { cleanText.substring(0, cut) }
    val stableBlocks = remember(stableText) { parseBlocks(stableText) }
    val cappedFence = streaming && window.fenceBodyStart != null && cleanText.length - window.fenceBodyStart > STREAM_FENCE_CAP
    val tail = remember(cleanText, cut, cappedFence) {
        if (!streaming) "" else if (cappedFence) {
            // Keep preceding prose and the language header; cap only the live code body.
            cleanText.substring(cut, window.fenceBodyStart!!) + "…\n" + cleanText.takeLast(STREAM_FENCE_CAP)
        } else cleanText.substring(cut)
    }
    val tailBlocks = remember(tail) { parseBlocks(tail) }
    Column(Modifier.fillMaxWidth()) {
        // One composition path keeps table scrolling and completed math stable
        // when the live tail becomes a completed block or the message commits.
        MarkdownBlocks(stableBlocks + tailBlocks, liveFrom = if (streaming) stableBlocks.size else Int.MAX_VALUE,
            onOpenUrl = onOpenUrl, media = media,
            codeCopy = if (cappedFence) ({ cleanText.substring(window.fenceBodyStart!!).trimEnd('\n') }) else null)
    }
}

/** Live code is bounded for layout; Copy still uses its full source. */
private const val STREAM_FENCE_CAP = 1_500
private const val STREAM_INLINE_CAP = 4_000

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
    data class Image(val url: String, val alt: String) : Block
    data class LinkPreview(val url: String, val label: String) : Block
    data class Math(val tex: String, val display: Boolean) : Block
    data class Mermaid(val code: String) : Block
}

private data class ListItem(val marker: String, val content: String, val nested: List<ListItem>)

internal fun isTableStart(lines: List<String>, idx: Int): Boolean {
    if (idx + 1 >= lines.size) return false
    val header = lines[idx].trim()
    val delimiter = lines[idx + 1].trim()
    if (!header.contains('|') || !delimiter.contains('|')) return false
    val delimiterCells = splitTableRow(delimiter)
    return delimiterCells.size == splitTableRow(header).size &&
        delimiterCells.all { it.matches(Regex(":?-+:?")) }
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

private val bulletRegex = Regex("^\\s*([-*+])\\s+(.*)$")
private val numberedRegex = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])\\s*(?:\\1\\s*){2,}$")
private val standaloneImageRegex = Regex("^!\\[([^\\]]*)]\\(((?:https?://|harness-image:)[^)\\s]+)\\)\\s*$")
private val standaloneLinkRegex = Regex("^\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)\\s*$")
private val bareUrlRegex = Regex("^https?://\\S+$")
private val bareImageUrlRegex = Regex(
    "^https?://\\S+\\.(?:png|jpe?g|webp|gif)(?:\\?\\S*)?$",
    RegexOption.IGNORE_CASE,
)
private val inlineImageRegex = Regex("!\\[([^\\]]*)]\\(((?:https?://|harness-image:)[^)\\s]+)\\)")

private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = text.split('\n')
    var i = 0
    val paraBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paraBuf.isEmpty()) return
        val joined = paraBuf.joinToString("\n").trimEnd()
        paraBuf.clear()
        val trimmed = joined.trim()
        standaloneImageRegex.matchEntire(trimmed)?.let {
            out += Block.Image(it.groupValues[2], it.groupValues[1])
            return
        }
        standaloneLinkRegex.matchEntire(trimmed)?.let {
            out += Block.LinkPreview(it.groupValues[2], it.groupValues[1])
            return
        }
        if (bareImageUrlRegex.matches(trimmed)) {
            out += Block.Image(trimmed, "")
            return
        }
        if (bareUrlRegex.matches(trimmed)) {
            out += Block.LinkPreview(trimmed, "")
            return
        }
        // An image inside a normal paragraph renders as a picture, not as a
        // link. Split the paragraph around it so the surrounding prose keeps
        // its order.
        val images = inlineImageRegex.findAll(joined).toList()
        if (images.isNotEmpty()) {
            var cursor = 0
            for (match in images) {
                val before = joined.substring(cursor, match.range.first).trim()
                if (before.isNotEmpty()) out += Block.Paragraph(before)
                out += Block.Image(match.groupValues[2], match.groupValues[1])
                cursor = match.range.last + 1
            }
            val after = joined.substring(cursor).trim()
            if (after.isNotEmpty()) out += Block.Paragraph(after)
            return
        }
        out += Block.Paragraph(joined)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val fenceSize = trimmed.takeWhile { it == '`' }.length
                val lang = trimmed.drop(fenceSize).trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !(lines[i].trim().let { closing ->
                    val ticks = closing.takeWhile { it == '`' }.length
                    ticks >= fenceSize && closing.drop(ticks).isBlank()
                })) {
                    body.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip the closing fence
                if (lang.equals("mermaid", ignoreCase = true)) {
                    out += Block.Mermaid(body.toString().trim())
                } else {
                    out += Block.Code(body.toString(), lang)
                }
            }

            trimmed.startsWith("$$") -> {
                flushParagraph()
                val after = trimmed.removePrefix("$$")
                val close = after.indexOf("$$")
                if (close >= 0) {
                    val tex = after.substring(0, close).trim()
                    if (tex.isNotEmpty()) out += Block.Math(tex, true)
                    i++
                } else {
                    val body = StringBuilder(after)
                    i++
                    while (i < lines.size) {
                        val current = lines[i]
                        val end = current.indexOf("$$")
                        if (end >= 0) {
                            if (end > 0) body.append('\n').append(current.substring(0, end))
                            i++
                            break
                        }
                        body.append('\n').append(current)
                        i++
                    }
                    val tex = body.toString().trim()
                    if (tex.isNotEmpty()) out += Block.Math(tex, true)
                }
            }

            trimmed.startsWith("\\[") -> {
                flushParagraph()
                val after = trimmed.removePrefix("\\[")
                val close = after.indexOf("\\]")
                if (close >= 0) {
                    val tex = after.substring(0, close).trim()
                    if (tex.isNotEmpty()) out += Block.Math(tex, true)
                    i++
                } else {
                    val body = StringBuilder(after)
                    i++
                    while (i < lines.size) {
                        val current = lines[i]
                        val end = current.indexOf("\\]")
                        if (end >= 0) {
                            if (end > 0) body.append('\n').append(current.substring(0, end))
                            i++
                            break
                        }
                        body.append('\n').append(current)
                        i++
                    }
                    val tex = body.toString().trim()
                    if (tex.isNotEmpty()) out += Block.Math(tex, true)
                }
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
    blocks: List<Block>,
    liveFrom: Int = Int.MAX_VALUE,
    onOpenUrl: ((String) -> Unit)? = null,
    media: Boolean = true,
    codeCopy: (() -> String)? = null,
) {
    val imagesEnabled = LocalChatImagesEnabled.current
    var linkCards = 0
    blocks.forEachIndexed { index, block -> key(index) {
        val live = index >= liveFrom
        val plain = live && block is Block.Paragraph && block.text.length > STREAM_INLINE_CAP
        when (block) {
            is Block.Image -> when {
                com.androidharness.app.core.ChatResponseImages.imageId(block.url) != null -> {
                    if (media && imagesEnabled) Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        com.androidharness.app.ui.chat.components.ChatResponseImage(block.url, block.alt)
                    } else if (block.alt.isNotBlank()) Text(block.alt, style = MaterialTheme.typography.bodySmall)
                }
                !media || live -> Text(
                    if (block.alt.isNotBlank()) "${block.alt}: ${block.url}" else block.url,
                    style = MaterialTheme.typography.bodyLarge,
                )
                !imagesEnabled -> ParagraphText(
                    "[${block.alt.ifBlank { "image" }}](${block.url})",
                    onOpenUrl,
                    linkIcons = false,
                )
                else -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    RemoteImageCard(block.url, block.alt, onOpenUrl)
                }
            }

            is Block.LinkPreview -> if (!media || live) {
                Text(
                    if (block.label.isNotBlank()) "${block.label}: ${block.url}" else block.url,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (linkCards < 3 && onOpenUrl != null) {
                linkCards++
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    RemoteLinkCard(block.url, block.label, onOpenUrl)
                }
            } else {
                // Beyond the preview cards a standalone link stays visible as a link.
                ParagraphText("[${block.label.ifBlank { "link" }}](${block.url})", onOpenUrl, linkIcons = false)
            }
            is Block.Code -> CodeBlock(block.text.trimEnd('\n'), block.language, copySource = if (index == blocks.lastIndex) codeCopy else null)
            is Block.Math -> if (!media || live) {
                Text(
                    if (block.display) "\$\$${block.tex}\$\$" else "\$${block.tex}\$",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                MathBlock(block.tex)
            }
            is Block.Mermaid -> if (!media || live) {
                CodeBlock(block.code, "mermaid", copySource = if (index == blocks.lastIndex) codeCopy else null)
            } else {
                MermaidBlock(block.code)
            }
            is Block.Heading -> {
                // Calmer than a type-scale ladder: mobile summaries read best
                // when headings stay close to body size and lean on weight.
                val headingStyle = when (block.level) {
                    1 -> MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp)
                    2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp)
                    else -> MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp)
                }
                val styled = styledParagraph(block.content, onOpenUrl, math = !live, linkIcons = !live)
                LinkedText(
                    styled.text,
                    headingStyle,
                    onOpenUrl = onOpenUrl,
                    inlineContent = styled.inlineContent,
                    modifier = Modifier,

                )
            }
            is Block.Quote -> QuoteBlock(block.lines, onOpenUrl = onOpenUrl)
            is Block.Rule -> HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            is Block.Bullets -> ListBlock(block.items, onOpenUrl = onOpenUrl)
            is Block.Table -> TableBlock(block, onOpenUrl = onOpenUrl)
            is Block.Paragraph -> if (plain) {
                Text(block.text, style = answerBodyStyle())
            } else {
                ParagraphText(block.text, onOpenUrl = onOpenUrl, linkIcons = !live, math = !live)
            }
        }
        if (index != blocks.lastIndex) Spacer(Modifier.height(when {
            blocks[index + 1] is Block.Heading -> 20.dp
            block is Block.Heading -> 8.dp
            else -> 14.dp
        }))
    } }
}

@Composable
private fun ListBlock(items: List<ListItem>, onOpenUrl: ((String) -> Unit)? = null) {
    val style = answerBodyStyle()
    val fontScale = LocalDensity.current.fontScale
    val markerWidth = (maxOf(22f, (items.filter { item -> item.marker.all { it.isDigit() } }.maxOfOrNull { it.marker.length + 1 } ?: 1) * 9f) * fontScale).dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val numbered = item.marker.all { it.isDigit() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (numbered) "${item.marker}." else "•", style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (numbered) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(markerWidth))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val styled = styledLine(item.content, onOpenUrl)
                    LinkedText(styled.text, style, onOpenUrl = onOpenUrl, inlineContent = styled.inlineContent)
                    item.nested.forEach { nested ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("◦", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
                            val nestedText = styledLine(nested.content, onOpenUrl)
                            LinkedText(nestedText.text, style, onOpenUrl = onOpenUrl, inlineContent = nestedText.inlineContent,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(lines: List<String>, onOpenUrl: ((String) -> Unit)? = null) {
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
                val styled = styledLine(line, onOpenUrl)
                Text(
                    styled.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                    inlineContent = styled.inlineContent,
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.SemiBold)
    val rows = listOf(table.headers) + table.rows
    val styledRows = rows.map { row ->
        table.headers.indices.map { styledText(row.getOrElse(it) { "" }) }
    }
    if (expanded) {
        com.androidharness.app.ui.chat.rich.ExpandedResponseTable(
            com.androidharness.app.core.response.RichResponse.Node.Table(
                uiContext.getString(R.string.answer_table_title), styledRows.first().map { it.text },
                styledRows.drop(1).map { row -> row.map { it.text } }, ""),
            onDismiss = { expanded = false },
        )
    }

    AnswerTableSurface(Modifier.padding(vertical = 2.dp).testTag("answer-table")) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val columns = pluralStringResource(R.plurals.answer_table_columns, table.headers.size, table.headers.size)
                val count = pluralStringResource(R.plurals.answer_table_rows, table.rows.size, table.rows.size)
                Text("$columns · $count", style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                ExpressiveIconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant)) {
                    Icon(Icons.Outlined.OpenInFull, uiContext.getString(R.string.answer_table_expand), modifier = Modifier.size(18.dp))
                }
                ExpressiveIconButton(onClick = {
                    clipboard.setText(AnnotatedString(tableAsTsv(styledRows.map { row -> row.map { it.text } })))
                    copied = true
                    scope.launch { delay(1500); copied = false }
                }, modifier = Modifier.size(44.dp).semantics {
                    contentDescription = uiContext.getString(if (copied) R.string.settings_shizuku_copied else R.string.ui_copy_table)
                }, colors = IconButtonDefaults.iconButtonColors(contentColor = if (copied) scheme.primary else scheme.onSurfaceVariant)) {
                    Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val viewport = with(density) { maxWidth.roundToPx() }
                val padding = with(density) { 24.dp.roundToPx() }
                val minWidth = with(density) { (88.dp * fontScale.coerceAtMost(1.6f)).roundToPx() }
                val maxColumnWidth = with(density) { 280.dp.roundToPx() }
                val widths = remember(styledRows, bodyStyle, headerStyle, density, viewport, measurer) {
                    val natural = table.headers.indices.map { column ->
                        styledRows.indices.maxOf { row ->
                            measurer.measure(
                                text = styledRows[row][column],
                                style = if (row == 0) headerStyle else bodyStyle,
                                softWrap = false,
                            ).size.width + padding
                        }
                    }
                    markdownColumnWidths(natural, viewport, minWidth, maxColumnWidth)
                }
                val tableWidth = with(density) { widths.sum().toDp() }
                Column(Modifier.horizontalScroll(scrollState).testTag("answer-table-scroll")) {
                    Column(Modifier.width(tableWidth).semantics { collectionInfo = CollectionInfo(styledRows.size, table.headers.size) }) {
                        styledRows.forEachIndexed { rowIndex, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                row.forEachIndexed { column, cell ->
                                    LinkedText(
                                        styled = cell,
                                        style = if (rowIndex == 0) headerStyle else bodyStyle,
                                        textAlign = table.alignments.getOrElse(column) { TextAlign.Start },
                                        onOpenUrl = onOpenUrl,
                                        modifier = Modifier
                                            .semantics {
                                                if (rowIndex == 0) heading()
                                                collectionItemInfo = CollectionItemInfo(rowIndex, 1, column, 1)
                                            }
                                            .width(with(density) { widths[column].toDp() })
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                    )
                                }
                            }
                            if (rowIndex < styledRows.lastIndex) {
                                HorizontalDivider(
                                    color = scheme.outlineVariant.copy(alpha = if (rowIndex == 0) 0.7f else 0.3f),
                                )
                            }
                        }
                    }
                }
            }
            AnswerTableScrollHint(scrollState)
        }
    }
}

@Composable
private fun answerBodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.let {
    it.copy(lineHeight = it.fontSize * 1.6f)
}

/** Paragraph text with tap-to-open links (annotations set by [styledText]). */
@Composable
private fun ParagraphText(
    text: String,
    onOpenUrl: ((String) -> Unit)? = null,
    linkIcons: Boolean = true,
    math: Boolean = true,
) {
    val styled = styledParagraph(text, onOpenUrl, linkIcons, math)
    LinkedText(
        styled.text,
        answerBodyStyle(),
        onOpenUrl = onOpenUrl,
        inlineContent = styled.inlineContent,
    )
}

@Composable
private fun LinkedText(
    styled: AnnotatedString,
    style: TextStyle,
    textAlign: TextAlign = TextAlign.Start,
    onOpenUrl: ((String) -> Unit)? = null,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val context = LocalContext.current
    val currentOpenUrl by androidx.compose.runtime.rememberUpdatedState(onOpenUrl)
    Text(
        text = styled,
        style = style,
        textAlign = textAlign,
        color = MaterialTheme.colorScheme.onSurface,
        inlineContent = inlineContent,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(styled) {
            detectTapGestures { pos ->
                val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                styled.getStringAnnotations("url", offset, offset)
                    .firstOrNull()?.let { ann ->
                        openMarkdownLink(context, ann.item, currentOpenUrl)
                    }
            }
        },
    )
}

/** Committed code blocks render at most this many lines before an expand control. */
private const val CODE_BLOCK_COLLAPSED_LINES = 40

/** Past this many lines a block renders plain instead of tokenizing on the main thread. */
private const val CODE_BLOCK_HIGHLIGHT_LIMIT = 400

/**
 * Tokenizes [code] with the same tokenizer the file editor uses, so a fenced block
 * in an answer is coloured like the same file opened in the editor. Returns null
 * when the fence names no language we know, which renders as plain monospace.
 */
private fun highlightedCode(
    code: String,
    language: String,
    dark: Boolean,
    plain: androidx.compose.ui.graphics.Color,
): AnnotatedString? {
    val lang = CodeTokenizer.langForName(language) ?: return null
    return buildAnnotatedString {
        var lineStart = 0
        while (lineStart <= code.length) {
            val newline = code.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) code.length else newline
            val line = code.substring(lineStart, lineEnd)
            val tokens = CodeTokenizer.tokenizeLine(lang, line)
            // Tokens cover only part of a line, so walk them in order and copy the
            // gaps between them verbatim.
            var cursor = 0
            tokens.sortedBy { it.start }.forEach { token ->
                val start = token.start.coerceIn(0, line.length)
                val end = token.end.coerceIn(start, line.length)
                if (start > cursor) append(line.substring(cursor, start))
                if (end > start) {
                    withStyle(androidx.compose.ui.text.SpanStyle(CodeTokenColors.of(token.type, dark, plain))) {
                        append(line.substring(start, end))
                    }
                }
                cursor = maxOf(cursor, end)
            }
            if (cursor < line.length) append(line.substring(cursor))
            if (newline < 0) {
                lineStart = code.length + 1
            } else {
                append('\n')
                lineStart = newline + 1
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String, copySource: (() -> String)? = null) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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

    // Highlighting runs on the main thread, so it stays off past a few hundred
    // lines: an expanded multi-thousand-line dump renders plain rather than
    // tokenizing every line to draw it once.
    val highlighted = remember(shown, language, scheme.surface) {
        if (lines.size > CODE_BLOCK_HIGHLIGHT_LIMIT) {
            null
        } else {
            highlightedCode(
                code = shown,
                language = language,
                dark = CodeTokenColors.isDarkSurface(scheme.surface),
                plain = scheme.onSurface,
            )
        }
    }

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
                        clipboard.setText(AnnotatedString(copySource?.invoke() ?: code))
                        copied = true
                        scope.launch {
                            delay(1500)
                            copied = false
                        }
                    },
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = uiContext.getString(R.string.settings_telegram_copy_code),
                        tint = if (copied) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            com.androidharness.app.ui.common.LeftToRightContent {
                Text(
                    highlighted ?: AnnotatedString(shown),
                    softWrap = false,
                    fontFamily = HarnessMono,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
            if (collapsed) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 8.dp, bottom = 6.dp),
                ) {
                    Text(uiContext.getString(R.string.ui_more_lines_512, (hiddenCount).toString()))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Inline styling

private val linkRegex = Regex("""\[([^\]]+)]\((?:<([^>\n]+)>|([^\s)]+))\)""")

/**
 * Inline links are citations by default, so they collapse to a link icon. A
 * link whose label is the address itself is an explicit URL the reader should
 * see, so it stays as text.
 */
private fun isCitationLink(label: String, url: String): Boolean {
    val text = label.trim()
    if (text.isEmpty()) return true
    if (text.equals(url.trim(), ignoreCase = true)) return false
    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) return false
    return true
}

/** External links go to the system browser; chats route localhost to their preview. */
private fun openMarkdownLink(context: android.content.Context, url: String, onOpenUrl: ((String) -> Unit)?) {
    if (WorkspaceFileReference.fromLink(url) != null || url.startsWith("file:", ignoreCase = true)) return
    if (onOpenUrl != null && com.androidharness.app.core.LocalPortProbe.isLocalhostUrl(url)) onOpenUrl(url)
    else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private data class StyledResult(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/** Plain text with inline styling and underlined links, used where inline content is not laid out. */
@Composable
private fun styledText(text: String): AnnotatedString = styled(text, math = false, linkIcons = false).text

/** Paragraph styling plus inline KaTeX placeholders and compact citation icons. */
@Composable
private fun styledParagraph(
    text: String,
    onOpenUrl: ((String) -> Unit)? = null,
    linkIcons: Boolean = true,
    math: Boolean = true,
): StyledResult = styled(text, math = math, linkIcons = linkIcons, onOpenUrl = onOpenUrl)

/** One list item or quote line with citation icons and no math. */
@Composable
private fun styledLine(text: String, onOpenUrl: ((String) -> Unit)? = null): StyledResult =
    styled(text, math = false, onOpenUrl = onOpenUrl)

@Composable
private fun styled(
    text: String,
    math: Boolean,
    linkIcons: Boolean = true,
    onOpenUrl: ((String) -> Unit)? = null,
): StyledResult {
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkColor = MaterialTheme.colorScheme.primary
    val mathColor = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val context = LocalContext.current
    val currentOpenUrl by androidx.compose.runtime.rememberUpdatedState(onOpenUrl)
    val fileOpen = LocalResponseFileOpen.current
    val currentFileOpen by androidx.compose.runtime.rememberUpdatedState(fileOpen)
    val fileLinks = fileOpen != null
    val openLinkLabel = stringResource(R.string.chat_open_link)
    // Measurements belong to this paragraph and font scale. A global revision made
    // every paragraph rebuild whenever any equation reported its dimensions.
    val mathSizes = remember(density) {
        androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.unit.IntSize>()
    }
    val measuredMath = if (math) mathSizes.toMap() else emptyMap()
    // A paragraph with an inline image keeps its text flow; render the image
    // as a labelled link instead of leaking the leading "!".
    val normalized = remember(text) {
        inlineImageRegex.replace(text) { match ->
            val label = match.groupValues[1].ifBlank { "image" }
            "[$label](${match.groupValues[2]})"
        }
    }
    return remember(normalized, codeColor, codeBackground, linkColor, mathColor, density, measuredMath, math, linkIcons, openLinkLabel, fileLinks) {
        val links = linkRegex.findAll(normalized).associateBy { it.range.first }
        val files = if (fileLinks) WorkspaceFileReference.findInText(normalized).associateBy { it.range.first } else emptyMap()
        val inlineContent = LinkedHashMap<String, InlineTextContent>()
        var mathIndex = 0
        var linkIndex = 0
        val result = buildAnnotatedString {
            fun appendFile(label: String, reference: WorkspaceFileReference) {
                withLink(LinkAnnotation.Clickable(
                    tag = "workspace-file:${reference.path}:${reference.line ?: 0}",
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { currentFileOpen?.invoke(reference.path, reference.line) },
                )) { append(label) }
            }
            fun appendMath(tex: String) {
                val id = "inline-math-${mathIndex++}"
                val size = measuredMath[tex]
                // A couple of pixels of slack keep superscripts from clipping
                // against the inline WebView edge.
                val width = size?.width?.plus(6)?.dp ?: (tex.length * 8).coerceIn(24, 220).dp
                val height = size?.height?.plus(2)?.dp ?: 24.dp
                appendInlineContent(id, tex)
                inlineContent[id] = InlineTextContent(
                    Placeholder(
                        width = with(density) { width.toSp() },
                        height = with(density) { height.toSp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    ),
                ) {
                    MathInlineView(tex, mathColor) { w, h ->
                        if (w > 0 && h > 0) {
                            val next = androidx.compose.ui.unit.IntSize(w, h)
                            if (mathSizes[tex] != next) mathSizes[tex] = next
                        }
                    }
                }
            }
            // Nested spans recurse with the outer style pushed, so a link inside
            // bold/italic/strikethrough still resolves instead of leaking as
            // literal "[label](url)". Code spans stay literal on purpose.
            fun walk(from: Int, to: Int) {
                var i = from
                while (i < to) {
                    val link = links[i]
                    when {
                        // A link that starts inside but ends outside this span is
                        // broken nesting; let it fall through as plain text.
                        link != null && link.range.last < to -> {
                            val label = link.groupValues[1]
                            val url = link.groupValues[2].ifEmpty { link.groupValues[3] }
                            val file = if (fileLinks) WorkspaceFileReference.fromLink(url) else null
                            if (file != null) {
                                appendFile(label, file)
                            } else if (linkIcons && isCitationLink(label, url)) {
                                // A cited site becomes a small chain icon; the label
                                // stays as alternate text for copying and selection.
                                val id = "inline-link-${linkIndex++}"
                                appendInlineContent(id, label.ifBlank { url })
                                inlineContent[id] = InlineTextContent(
                                    Placeholder(
                                        width = 18.sp,
                                        height = 18.sp,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                    ),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().clickable {
                                            openMarkdownLink(context, url, currentOpenUrl)
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Link, contentDescription = openLinkLabel,
                                            modifier = Modifier.size(13.dp), tint = linkColor,
                                        )
                                    }
                                }
                            } else {
                                pushStringAnnotation(tag = "url", annotation = url)
                                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                                append(label)
                                pop()
                                pop()
                            }
                            i = link.range.last + 1
                        }

                        normalized.startsWith("**", i) -> {
                            val end = normalized.indexOf("**", i + 2)
                            if (end > i + 2 && end + 2 <= to) {
                                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                                walk(i + 2, end)
                                pop()
                                i = end + 2
                            } else {
                                append(normalized[i]); i++
                            }
                        }

                        normalized.startsWith("~~", i) -> {
                            val end = normalized.indexOf("~~", i + 2)
                            if (end > i + 2 && end + 2 <= to) {
                                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                                walk(i + 2, end)
                                pop()
                                i = end + 2
                            } else {
                                append(normalized[i]); i++
                            }
                        }

                        normalized.startsWith("`", i) -> {
                            val end = normalized.indexOf('`', i + 1)
                            if (end > i + 1 && end < to) {
                                pushStyle(
                                    SpanStyle(
                                        fontFamily = HarnessMono,
                                        background = codeBackground,
                                        color = codeColor,
                                    )
                                )
                                // Hair spaces pad the chip visually, spans can't
                                // take real padding inside a Text.
                                val code = normalized.substring(i + 1, end)
                                val file = if (fileLinks) WorkspaceFileReference.fromText(code) else null
                                append(" ")
                                if (file != null) appendFile(code, file) else append(code)
                                append(" ")
                                pop()
                                i = end + 1
                            } else {
                                append(normalized[i]); i++
                            }
                        }

                        files[i]?.let { it.range.last < to } == true -> {
                            val file = files.getValue(i)
                            appendFile(normalized.substring(file.range), file.reference)
                            i = file.range.last + 1
                        }

                        math && normalized.startsWith("\\(", i) -> {
                            val end = normalized.indexOf("\\)", i + 2)
                            val tex = if (end > i + 2 && end + 2 <= to) normalized.substring(i + 2, end) else null
                            if (tex != null && isValidInlineMath(tex)) {
                                appendMath(tex)
                                i = end + 2
                            } else {
                                append(normalized[i]); i++
                            }
                        }

                        math && normalized[i] == '$' && !normalized.startsWith("$$", i) &&
                            (i == 0 || normalized[i - 1] != '\\') -> {
                            val end = findDollarClose(normalized, i + 1)
                            val tex = if (end in (i + 1) until to) normalized.substring(i + 1, end) else null
                            if (tex != null && isValidInlineMath(tex)) {
                                appendMath(tex)
                                i = end + 1
                            } else {
                                append(normalized[i]); i++
                            }
                        }

                        normalized.startsWith("*", i) -> {
                            val end = normalized.indexOf('*', i + 1)
                            if (end > i + 1 && end < to) {
                                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                                walk(i + 1, end)
                                pop()
                                i = end + 1
                            } else {
                                append(normalized[i]); i++
                            }
                        }

                        else -> {
                            append(normalized[i]); i++
                        }
                    }
                }
            }
            walk(0, normalized.length)
        }
        StyledResult(result, inlineContent)
    }
}

private fun findDollarClose(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        when {
            text[i] == '\\' -> i += 2
            text[i] == '$' -> return i
            text[i] == '\n' -> return -1
            else -> i++
        }
    }
    return -1
}

private fun isValidInlineMath(tex: String): Boolean =
    tex.isNotBlank() && !tex.first().isWhitespace() && !tex.last().isWhitespace() && tex.length <= 2_000
