package com.androidharness.app.ui.chat.rich

import com.androidharness.app.ui.theme.*
import androidx.compose.foundation.shape.RoundedCornerShape

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.core.response.ResponseDiff
import com.androidharness.app.ui.chat.LocalChatImagesEnabled
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.chat.RemoteContentLoader
import com.androidharness.app.ui.chat.components.ImageViewerDialog
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ResponseCopyShare(title: String, text: String, mime: String = "text/plain") {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
            Text(stringResource(if (copied) R.string.rich_copied else R.string.rich_copy))
        }
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
            runCatching { context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(context, context.getString(R.string.rich_share_unavailable), Toast.LENGTH_SHORT).show() }
        }) { Text(stringResource(R.string.rich_share)) }
    }
}

@Composable
internal fun ResponseGallery(gallery: RichResponse.Node.Gallery, onOpenUrl: ((String) -> Unit)?, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    if (!LocalChatImagesEnabled.current) { Text(stringResource(R.string.rich_images_hidden)); return }
    val pager = rememberPagerState { gallery.images.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var expanded by rememberSaveable(gallery) { mutableStateOf(false) }
    val contextChange = LocalResponseContext.current
    // Only user scrolling changes the next-message context; initial composition does not attach anything.
    LaunchedEffect(pager, gallery) {
        snapshotFlow { pager.settledPage }.drop(1).collect { index ->
            val image = gallery.images[index.coerceIn(gallery.images.indices)]
            contextChange?.invoke(gallery.title, "Selected image ${index + 1}: ${image.caption}\n${image.url}\nSource: ${image.source}")
        }
    }
    val currentIndex = pager.currentPage.coerceIn(gallery.images.indices)
    val current = gallery.images[currentIndex]
    if (expanded) ImageViewerDialog(current.url, current.source.ifBlank { null }, { expanded = false }, { url -> expanded = false; onOpenUrl?.invoke(url) })
    ResponsePanel {
        Text(gallery.title, style = MaterialTheme.typography.titleSmall)
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth()) { index ->
            val image = gallery.images[index]
            val state by produceState<Pair<Boolean, Bitmap?>>(true to null, image.url) {
                value = false to RemoteContentLoader.loadImage(context, image.url)
            }
            Box(Modifier.fillMaxWidth().height(240.dp).harnessGlass(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                .clickable { expanded = true }, contentAlignment = Alignment.Center) {
                val bitmap = state.second
                when {
                    state.first -> CircularProgressIndicator(Modifier.size(24.dp))
                    bitmap != null -> Image(bitmap.asImageBitmap(), image.caption.ifBlank { gallery.title }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else -> Text(stringResource(R.string.chat_image_open_failed), Modifier.padding(16.dp))
                }
            }
        }
        if (current.caption.isNotBlank()) Text(current.caption, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launchPage(pager, pager.currentPage - 1) }, enabled = pager.currentPage > 0) { Text(stringResource(R.string.rich_previous)) }
            Text("${pager.currentPage + 1} / ${gallery.images.size}", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { scope.launchPage(pager, pager.currentPage + 1) }, enabled = pager.currentPage < gallery.images.lastIndex) { Text(stringResource(R.string.rich_next)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.rich_expand_image)) }
            if (current.source.isNotBlank() && onOpenUrl != null) TextButton(onClick = { onOpenUrl(current.source) }) { Text(stringResource(R.string.rich_open_source)) }
        }
        onFollowUp?.let { follow -> TextButton(onClick = {
            follow(RichResponse.followUp(context.getString(R.string.rich_discuss_image_prompt), gallery.title,
                "Selected image ${pager.currentPage + 1}: ${current.caption}\n${current.url}\nSource: ${current.source}"))
        }) { Text(stringResource(R.string.rich_discuss_image)) } }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchPage(pager: androidx.compose.foundation.pager.PagerState, page: Int) = launch {
    pager.animateScrollToPage(page.coerceIn(0, pager.pageCount - 1))
}

@Composable
internal fun ResponseEvidence(evidence: RichResponse.Node.Evidence, onOpenUrl: ((String) -> Unit)?) {
    val openFile = LocalResponseFileOpen.current
    ResponsePanel {
        Text(evidence.title, style = MaterialTheme.typography.titleSmall)
        evidence.items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider()
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            if (item.status.isNotBlank()) Text(stringResource(R.string.rich_reported_status, stringResource(when (item.status) {
                "passed" -> R.string.rich_passed; "failed" -> R.string.rich_failed; else -> R.string.rich_unverified
            })), style = MaterialTheme.typography.labelMedium)
            if (item.text.isNotBlank()) SelectionContainer { MarkdownText(item.text, onOpenUrl = onOpenUrl) }
            if (item.path.isNotBlank()) {
                Text(item.path + (item.line?.let { ":$it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                if (openFile != null) TextButton(onClick = { openFile(item.path, item.line) }) { Text(stringResource(R.string.rich_open_file)) }
            }
            if (item.url.isNotBlank() && onOpenUrl != null) TextButton(onClick = { onOpenUrl(item.url) }) { Text(stringResource(R.string.rich_open_source)) }
        }
    }
}

@Composable
internal fun ResponseWriting(writing: RichResponse.Node.Writing) {
    var expanded by rememberSaveable(writing) { mutableStateOf(false) }
    var overflowed by remember(writing) { mutableStateOf(false) }
    ResponsePanel {
        Text(writing.title, style = MaterialTheme.typography.titleSmall)
        if (writing.language.isNotBlank()) Text(writing.language, style = MaterialTheme.typography.labelSmall)
        SelectionContainer {
            Text(writing.text, maxLines = if (expanded) Int.MAX_VALUE else 10, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontFamily = if (writing.language.isBlank()) null else HarnessMono, style = MaterialTheme.typography.bodyMedium,
                onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow })
        }
        if (overflowed || writing.text.lines().size > 10 || writing.text.length > 800) TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.rich_show_less else R.string.rich_show_all))
        }
        ResponseCopyShare(writing.title, writing.text)
    }
}

@Composable
internal fun ResponseDiffView(diff: RichResponse.Node.Diff, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    var mode by rememberSaveable(diff) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(diff) { mutableStateOf(false) }
    val lines = remember(diff) { ResponseDiff.lines(diff.before, diff.after) }
    val colors = MaterialTheme.colorScheme
    val text = remember(lines, mode, expanded, colors) {
        val shown = if (expanded) lines else lines.take(24)
        if (mode == 0) buildAnnotatedString {
            shown.forEach { line -> withStyle(SpanStyle(background = when (line.marker) {
                "+" -> colors.primary.copy(alpha = .12f); "-" -> colors.error.copy(alpha = .12f); else -> colors.surface
            })) { append(line.marker); append(' '); append(line.text); append('\n') } }
        } else AnnotatedString((if (mode == 1) diff.before else diff.after).lines().let { if (expanded) it else it.take(24) }.joinToString("\n"))
    }
    val openFile = LocalResponseFileOpen.current
    val contextChange = LocalResponseContext.current
    val ui = LocalContext.current
    ResponsePanel {
        Text(diff.title, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.rich_change_count, lines.count { it.marker == "+" }, lines.count { it.marker == "-" }), style = MaterialTheme.typography.labelMedium)
        if (diff.path.isNotBlank()) {
            Text(diff.path, style = MaterialTheme.typography.labelSmall)
            openFile?.let { open -> TextButton(onClick = { open(diff.path, diff.line) }) { Text(stringResource(R.string.rich_open_file)) } }
        }
        TabRow(mode, modifier = Modifier.harnessGlassControl(),
            containerColor = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) androidx.compose.ui.graphics.Color.Transparent else colors.surface) {
            listOf(R.string.rich_changes, R.string.rich_before, R.string.rich_after).forEachIndexed { index, label ->
                Tab(selected = mode == index, onClick = { mode = index; contextChange?.invoke(diff.title, "View: ${ui.getString(label)}\n" + if (index == 0) RichResponse.describe(diff) else if (index == 1) diff.before else diff.after) }, text = { Text(stringResource(label)) })
            }
        }
        SelectionContainer { Text(text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono) }
        if (lines.size > 24) TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.rich_show_less else R.string.rich_show_all)) }
        ResponseCopyShare(diff.title, if (mode == 0) lines.joinToString("\n") { it.marker + " " + it.text } else if (mode == 1) diff.before else diff.after)
        onFollowUp?.let { follow -> TextButton(onClick = { follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_diff_prompt), diff.title, RichResponse.describe(diff))) }) {
            Text(stringResource(R.string.rich_discuss_diff))
        } }
    }
}
