package com.androidharness.app.ui.chat.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.R
import com.androidharness.app.ui.chat.RemoteContentLoader
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** Minimal full-screen image viewer: black canvas, pinch to zoom, save and share. */
@Composable
internal fun ImageViewerDialog(
    imageUrl: String,
    sourceUrl: String?,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(imageUrl) { mutableStateOf(true) }
    var scale by remember(imageUrl) { mutableFloatStateOf(1f) }
    var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(imageUrl) {
        loading = true
        bitmap = RemoteContentLoader.loadImage(context, imageUrl)
        loading = false
    }
    val transform = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    val host = (sourceUrl ?: imageUrl).toHttpUrlOrNull()?.host?.removePrefix("www.")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 76.dp)
                    .transformable(transform),
                contentAlignment = Alignment.Center,
            ) {
                val loaded = bitmap
                when {
                    loading -> CircularProgressIndicator(color = Color.White)
                    loaded != null -> Image(
                        bitmap = loaded.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                    )
                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.chat_image_open_failed),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (sourceUrl != null) {
                            TextButton(onClick = { onOpenSource(sourceUrl) }) {
                                Text(stringResource(R.string.chat_image_open_source), color = Color.White)
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_image_close), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = {
                        scope.launch {
                            val ok = ChatImageActions.share(context, imageUrl)
                            Toast.makeText(
                                context,
                                context.getString(if (ok) R.string.chat_image_shared else R.string.chat_image_share_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = Color.White,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(16.dp), tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_image_share),
                            color = Color.Black,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                IconButton(onClick = {
                    scope.launch {
                        val ok = ChatImageActions.download(context, imageUrl)
                        Toast.makeText(
                            context,
                            context.getString(if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Icon(Icons.Outlined.FileDownload, stringResource(R.string.chat_image_download), tint = Color.White)
                }
                if (sourceUrl != null) {
                    IconButton(onClick = { onOpenSource(sourceUrl) }) {
                        Icon(Icons.Outlined.Language, stringResource(R.string.chat_image_open_source), tint = Color.White)
                    }
                }
            }

            if (!host.isNullOrBlank()) {
                Text(
                    host,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
