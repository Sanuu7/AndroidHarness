package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.foundation.text.contextmenu.data.*
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.androidharness.app.R
import com.androidharness.app.ui.common.TextButton

/** Captures the selection's copy payload directly, without reading an older system clipboard value. */
private class QuoteClipboard(private val clipboard: Clipboard, val onQuote: (String) -> Unit) : Clipboard by clipboard {
    var quoting = false
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (quoting) {
            quoting = false
            clipEntry?.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
                    .joinToString("\n").takeIf(String::isNotBlank)?.let(onQuote)
            }
        } else clipboard.setClipEntry(clipEntry)
    }
}

private class QuoteMenu(val dataProvider: TextContextMenuDataProvider, val finish: () -> Unit) : TextContextMenuSession {
    override fun close() = finish()
}

private class QuoteMenuProvider : TextContextMenuProvider {
    var menu by mutableStateOf<QuoteMenu?>(null)
    var coordinates: LayoutCoordinates? = null
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider): Unit = suspendCancellableCoroutine { continuation ->
        menu?.close()
        lateinit var session: QuoteMenu
        session = QuoteMenu(dataProvider) {
            if (menu === session) menu = null
            if (continuation.isActive) continuation.resume(Unit)
        }
        menu = session
        continuation.invokeOnCancellation { session.close() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuoteReplySelection(onQuote: (String) -> Unit, content: @Composable () -> Unit) {
    val currentQuote by rememberUpdatedState(onQuote)
    val clipboard = LocalClipboard.current
    val selectionClipboard = remember(clipboard) { QuoteClipboard(clipboard) { currentQuote(it) } }
    val provider = remember { QuoteMenuProvider() }
    DisposableEffect(provider) { onDispose { provider.menu?.close() } }
    CompositionLocalProvider(LocalClipboard provides selectionClipboard,
        LocalTextContextMenuToolbarProvider provides provider, LocalTextContextMenuDropdownProvider provides provider) {
        Box(Modifier.onGloballyPositioned { provider.coordinates = it }, propagateMinConstraints = true) { content() }
        provider.menu?.let { session ->
            val dataProvider = session.dataProvider
            val data = dataProvider.data()
            val copy = data.components.filterIsInstance<TextContextMenuItem>().firstOrNull { it.key == TextContextMenuKeys.CopyKey }
            val selectAll = data.components.filterIsInstance<TextContextMenuItem>().firstOrNull { it.key == TextContextMenuKeys.SelectAllKey }
            val layout = provider.coordinates?.takeIf { it.isAttached } ?: return@let
            val bounds = dataProvider.contentBounds(layout)
            val rect = Rect(layout.localToWindow(bounds.topLeft), bounds.size)
            val position = remember(rect) { object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                    popupContentSize: IntSize): IntOffset = IntOffset(
                    (rect.center.x.toInt() - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    (rect.top.toInt() - popupContentSize.height - 12).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            } }
            Popup(popupPositionProvider = position, onDismissRequest = session::close, properties = PopupProperties(focusable = false)) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Row {
                        copy?.let { item ->
                            TextButton(onClick = {
                                // SelectionContainer starts its copy handler undispatched, so the
                                // selection reaches our clipboard before this callback returns.
                                selectionClipboard.quoting = true
                                try { item.onClick(session) } finally { selectionClipboard.quoting = false }
                                session.close()
                            }) { Text(stringResource(R.string.chat_quote_reply)) }
                            TextButton(onClick = { item.onClick(session); session.close() }) { Text(item.label) }
                        }
                        selectAll?.let { item -> TextButton(onClick = { item.onClick(session) }) { Text(item.label) } }
                    }
                }
            }
        }
    }
}
