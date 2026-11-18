package com.androidharness.app.ui.chat.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.R
import com.androidharness.app.core.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.androidharness.app.ui.common.IconButton

/** Decodes a stored attachment at a bounded size; never loads the full photo for a chip. */
internal fun loadSampledImage(file: File, maxEdge: Int): ImageBitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private val GalleryGap = 3.dp

/**
 * The photos a user sent, laid out as one rounded media block:
 * one photo keeps its own shape, two sit side by side, three get a large
 * lead with two stacked beside it, and four or more become a 2 by 2 grid
 * whose last tile counts the rest.
 */
@Composable
internal fun UserImageGallery(
    images: List<ImageRef>,
    dir: File,
    maxWidth: Dp,
    onOpen: (Int) -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val shape = RoundedCornerShape(18.dp)
    val modifier = Modifier.width(maxWidth).clip(shape)
    when (images.size) {
        1 -> {
            val ref = images[0]
            val known = ref.width?.takeIf { it > 0 }?.let { w -> ref.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h } }
            var aspect by remember(ref.name) { mutableFloatStateOf(known ?: 1f) }
            GalleryTile(ref, dir, 0, onOpen, onLongPress,
                onLoaded = { if (known == null) aspect = it.width.toFloat() / it.height.coerceAtLeast(1) },
                modifier = Modifier.clip(shape)
                    .width(maxWidth)
                    .heightIn(max = 340.dp)
                    .aspectRatio(aspect.coerceIn(0.56f, 1.9f)))
        }
        2 -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
            images.forEachIndexed { i, ref ->
                GalleryTile(ref, dir, i, onOpen, onLongPress, modifier = Modifier.weight(1f).aspectRatio(0.8f))
            }
        }
        3 -> Row(modifier.height(maxWidth * 0.78f), horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
            GalleryTile(images[0], dir, 0, onOpen, onLongPress, modifier = Modifier.weight(1.5f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GalleryGap)) {
                GalleryTile(images[1], dir, 1, onOpen, onLongPress, modifier = Modifier.weight(1f).fillMaxWidth())
                GalleryTile(images[2], dir, 2, onOpen, onLongPress, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(GalleryGap)) {
            val extra = images.size - 4
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
                    for (col in 0 until 2) {
                        val i = row * 2 + col
                        GalleryTile(images[i], dir, i, onOpen, onLongPress,
                            overlay = if (i == 3 && extra > 0) stringResource(R.string.ui_more_images, extra) else null,
                            modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryTile(
    ref: ImageRef,
    dir: File,
    index: Int,
    onOpen: (Int) -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    overlay: String? = null,
    onLoaded: (ImageBitmap) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref.name, dir) {
        value = withContext(Dispatchers.IO) { loadSampledImage(File(dir, ref.name), 900) }
    }
    val missing = remember(ref.name, dir) { !File(dir, ref.name).exists() }
    bitmap?.let { LaunchedEffect(it) { onLoaded(it) } }
    Box(
        modifier
            .background(scheme.surfaceContainerHigh)
            .combinedClickable(onClick = { onOpen(index) }, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = ref.caption?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_open_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            missing -> Icon(Icons.Outlined.BrokenImage, stringResource(R.string.ui_image_not_available),
                tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            else -> Icon(Icons.Outlined.Image, null, tint = scheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
        }
        if (overlay != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
                Text(overlay, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

/** Full-screen viewer for one image (tool output and similar single shots). */
@Composable
internal fun ImagePreviewDialog(ref: ImageRef, dir: File, onDismiss: () -> Unit) =
    ImagePreviewDialog(listOf(ref), 0, dir, onDismiss)

/**
 * Full-screen viewer that swipes through every photo in a message.
 * Pinch or double tap to zoom; one finger pans only while zoomed, so a
 * plain swipe always moves to the next photo.
 */
@Composable
internal fun ImagePreviewDialog(refs: List<ImageRef>, startIndex: Int, dir: File, onDismiss: () -> Unit) {
    if (refs.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, refs.lastIndex)) { refs.size }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { refs[it].name }) { page ->
                ZoomableImage(refs[page], dir, active = pager.currentPage == page)
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.ui_close_image), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (refs.size > 1) Text(
                    stringResource(R.string.ui_image_counter, pager.currentPage + 1, refs.size),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(ref: ImageRef, dir: File, active: Boolean) {
    val uiContext = LocalContext.current
    val file = remember(ref.name, dir) { File(dir, ref.name) }
    val missing = remember(file) { !file.exists() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref.name, dir) {
        value = withContext(Dispatchers.IO) { loadSampledImage(file, 2400) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Leaving a page resets its zoom so coming back starts from the full photo.
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                    else {
                        scale = 2.5f
                        offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } > 1
                        if (pinching || scale > 1.01f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale <= 1.01f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = ref.caption ?: uiContext.getString(R.string.ui_open_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            missing -> Text(
                uiContext.getString(R.string.ui_image_not_available),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
