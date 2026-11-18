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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    val actions = rememberSentImageActions(dir)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(scheme.surfaceContainerHigh)
            .combinedClickable(onClick = { onOpen(index) }, onLongClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                menuOpen = true
            }),
        contentAlignment = Alignment.Center,
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_image_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { menuOpen = false; actions.share(ref) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_image_save_to_gallery)) },
                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                onClick = { menuOpen = false; actions.save(ref) },
            )
            if (onLongPress != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_message_options)) },
                leadingIcon = { Icon(Icons.Outlined.MoreHoriz, null) },
                onClick = { menuOpen = false; onLongPress() },
            )
        }
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

/** Share and save-to-gallery for a sent photo, each confirmed with a short toast. */
internal class SentImageActions(val share: (ImageRef) -> Unit, val save: (ImageRef) -> Unit)

@Composable
internal fun rememberSentImageActions(dir: File): SentImageActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(dir, context, scope) {
        fun toast(id: Int) = android.widget.Toast.makeText(context, id, android.widget.Toast.LENGTH_SHORT).show()
        SentImageActions(
            share = { ref -> scope.launch { if (!ChatImageActions.shareLocal(context, dir, ref)) toast(R.string.chat_image_share_failed) } },
            save = { ref -> scope.launch {
                toast(if (ChatImageActions.saveLocal(context, dir, ref)) R.string.chat_image_saved else R.string.chat_image_save_failed)
            } },
        )
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
    val actions = rememberSentImageActions(dir)
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 0 = hidden, 1 = shown. The viewer grows in from slightly smaller and fades
    // the backdrop, then reverses on close so it never just pops away.
    val appear = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch { appear.animateTo(0f, tween(170)); onDismiss() }
        }
    }
    val dismissPx = with(density) { 140.dp.toPx() }
    val dragFade = (kotlin.math.abs(dragY) / (dismissPx * 3f)).coerceIn(0f, 1f)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = appear.value * (1f - dragFade * 0.8f)))) {
            HorizontalPager(
                state = pager,
                key = { refs[it].name },
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        val grow = (0.9f + 0.1f * appear.value) * (1f - dragFade * 0.2f)
                        scaleX = grow; scaleY = grow
                        alpha = appear.value
                        translationY = dragY
                    }
                    // Swipe down (or up) to put the photo away.
                    .draggable(
                        state = rememberDraggableState { delta -> dragY += delta },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (kotlin.math.abs(dragY) > dismissPx || kotlin.math.abs(velocity) > 2600f) close()
                            else animate(dragY, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> dragY = v }
                        },
                    ),
            ) { page ->
                ZoomableImage(refs[page], dir, active = pager.currentPage == page)
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .graphicsLayer { alpha = appear.value * (1f - dragFade) }
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = close) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.ui_close_image), tint = Color.White)
                }
                if (refs.size > 1) Text(
                    stringResource(R.string.ui_image_counter, pager.currentPage + 1, refs.size),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.share(refs[pager.currentPage]) }) {
                    Icon(Icons.Outlined.Share, stringResource(R.string.chat_image_share), tint = Color.White)
                }
                IconButton(onClick = { actions.save(refs[pager.currentPage]) }) {
                    Icon(Icons.Outlined.Download, stringResource(R.string.chat_image_save_to_gallery), tint = Color.White)
                }
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
    val zoomScope = rememberCoroutineScope()
    // Leaving a page resets its zoom so coming back starts from the full photo.
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    fun zoomTo(targetScale: Float, targetOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        zoomScope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                scale = fromScale + (targetScale - fromScale) * t
                offset = fromOffset + (targetOffset - fromOffset) * t
            }
        }
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.01f) zoomTo(1f, Offset.Zero)
                    else zoomTo(2.5f, (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f)
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
