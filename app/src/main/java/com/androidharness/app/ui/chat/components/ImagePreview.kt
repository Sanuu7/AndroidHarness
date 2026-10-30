package com.androidharness.app.ui.chat.components

import android.graphics.BitmapFactory
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

/** A tappable preview of a sent image. Falls back to the filename chip if the file is gone. */
@Composable
internal fun ImageThumbnail(ref: ImageRef, dir: File, onClick: () -> Unit) {
    val uiContext = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref.name, dir) {
        value = withContext(Dispatchers.IO) { loadSampledImage(File(dir, ref.name), 900) }
    }
    val image = bitmap
    if (image == null) {
        AttachmentChip(ref.name, uiContext.getString(R.string.ui_image), Icons.Outlined.Image)
        return
    }
    Image(
        bitmap = image,
        contentDescription = uiContext.getString(R.string.ui_open_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}

/** Full-screen viewer with pinch zoom and pan. */
@Composable
internal fun ImagePreviewDialog(ref: ImageRef, dir: File, onDismiss: () -> Unit) {
    val uiContext = LocalContext.current
    val file = remember(ref.name, dir) { File(dir, ref.name) }
    val missing = remember(file) { !file.exists() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref.name, dir) {
        value = withContext(Dispatchers.IO) { loadSampledImage(file, 2400) }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = uiContext.getString(R.string.ui_open_image),
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
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.ui_close_image), tint = Color.White)
            }
        }
    }
}
