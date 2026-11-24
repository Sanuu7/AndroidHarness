package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ImageRef
import com.androidharness.app.ui.chat.AttachmentImport
import com.androidharness.app.ui.chat.AttachmentImportStatus
import com.androidharness.app.ui.chat.FileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val TileHeight = 64.dp
private val TileShape = RoundedCornerShape(14.dp)

private sealed interface TrayItem {
    val key: String

    data class Photo(val index: Int, val name: String) : TrayItem {
        override val key get() = "img:$name"
    }

    data class Doc(val index: Int, val file: FileAttachment) : TrayItem {
        override val key get() = "file:$index:${file.name}"
    }

    data class Pending(val import: AttachmentImport) : TrayItem {
        override val key get() = "import:${import.id}"
    }
}

/**
 * One horizontal tray for everything waiting to go out with the next message.
 * It has a fixed height and scrolls sideways, so adding a tenth photo never
 * pushes the text field around the way stacked rows did.
 *
 * [imageSupport] is the model's image capability (null when unknown). It only
 * matters in Chat, where a model that cannot see images blocks the send.
 */
@Composable
internal fun ComposerAttachmentTray(
    images: List<String>,
    files: List<FileAttachment>,
    imports: List<AttachmentImport>,
    imageDir: File,
    imageSupport: Boolean?,
    /** A model that cannot see images gets the text read from them instead of being blocked. */
    imagesAsText: Boolean = false,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
    onRemoveImport: (String) -> Unit,
    onOpenImage: (List<ImageRef>, Int) -> Unit,
    /** Side inset of the tiles and hint, to line up with the surface the tray sits in. */
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val items = remember(images, files, imports) {
        buildList {
            images.forEachIndexed { i, name -> add(TrayItem.Photo(i, name)) }
            files.forEachIndexed { i, file -> add(TrayItem.Doc(i, file)) }
            imports.forEach { add(TrayItem.Pending(it)) }
        }
    }
    if (items.isEmpty()) return
    val refs = remember(images) { images.map { ImageRef(it, "image/*") } }
    val listState = rememberLazyListState()
    // Keep the newest item in view as more arrive.
    LaunchedEffect(items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex) }

    val scheme = MaterialTheme.colorScheme
    val photoBlocked = images.isNotEmpty() && imageSupport == false && !imagesAsText
    val hint = when {
        photoBlocked -> stringResource(R.string.chat_attachment_model_unsupported)
        images.isNotEmpty() && imageSupport == false -> stringResource(R.string.chat_attachment_read_as_text)
        images.isNotEmpty() && imageSupport == null -> stringResource(R.string.chat_attachment_image_unconfirmed_hint)
        else -> null
    }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(TileHeight + 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(items, key = { it.key }) { item ->
                Box(Modifier.animateItem()) {
                    when (item) {
                        is TrayItem.Photo -> PhotoTile(
                            file = File(imageDir, item.name),
                            blocked = photoBlocked,
                            onOpen = { onOpenImage(refs, item.index) },
                            onRemove = { onRemoveImage(item.index) },
                        )
                        is TrayItem.Doc -> DocTile(
                            name = item.file.name,
                            detail = item.file.sizeLabel,
                            onRemove = { onRemoveFile(item.index) },
                        )
                        is TrayItem.Pending -> PendingTile(item.import) { onRemoveImport(item.import.id) }
                    }
                }
            }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (photoBlocked) scheme.error else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = horizontalPadding + 4.dp, end = horizontalPadding + 4.dp, top = 4.dp),
            )
        }
    }
}

/** The small round remove badge that sits on the corner of every tile. */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.settings_action_remove)
    // A 28dp hit area around a 20dp visual keeps the badge easy to tap.
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun PhotoTile(file: File, blocked: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { loadSampledImage(file, 256) }
    }
    Box(Modifier.size(TileHeight + 8.dp), contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier.size(TileHeight)
                .clip(TileShape)
                .background(scheme.surfaceContainerHigh)
                .border(
                    if (blocked) 2.dp else 1.dp,
                    if (blocked) scheme.error else scheme.outlineVariant.copy(alpha = 0.5f),
                    TileShape,
                )
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    image,
                    contentDescription = stringResource(R.string.ui_open_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Image, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun DocTile(name: String, detail: String, onRemove: () -> Unit) {
    WideTile(
        name = name,
        detail = detail,
        error = false,
        leading = {
            Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        },
        onRemove = onRemove,
    )
}

@Composable
private fun PendingTile(pending: AttachmentImport, onRemove: () -> Unit) {
    val reading = pending.status == AttachmentImportStatus.READING
    val status = stringResource(when (pending.status) {
        AttachmentImportStatus.READING -> R.string.chat_attachment_reading
        AttachmentImportStatus.TOO_LARGE -> R.string.chat_attachment_too_large_title
        AttachmentImportStatus.UNSUPPORTED -> R.string.chat_attachment_unsupported
        AttachmentImportStatus.FAILED -> R.string.chat_attachment_failed
    })
    WideTile(
        name = pending.name.ifBlank { stringResource(R.string.ui_attachment) },
        detail = if (reading) status else listOfNotNull(status, pending.detail).joinToString(" · "),
        error = !reading,
        leading = {
            if (reading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        },
        onRemove = onRemove,
    )
}

@Composable
private fun WideTile(
    name: String,
    detail: String,
    error: Boolean,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.width(196.dp).height(TileHeight + 8.dp), contentAlignment = Alignment.BottomStart) {
        Surface(
            shape = TileShape,
            color = if (error) scheme.errorContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, if (error) scheme.error.copy(alpha = 0.6f) else scheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.width(188.dp).height(TileHeight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 14.dp),
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (error) scheme.error.copy(alpha = 0.12f) else scheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { leading() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd))
    }
}
