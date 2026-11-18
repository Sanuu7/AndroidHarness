package com.androidharness.app.ui.chat.components

import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.LinkPreview
import com.androidharness.app.ui.chat.RemoteContentLoader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The page an image came from, keyed by direct image URL. */
internal val LocalChatImageSources = compositionLocalOf<Map<String, String>> { emptyMap() }

/** Opens the full-screen viewer; null keeps the old tap-through behaviour. */
internal val LocalChatImageOpen = compositionLocalOf<((String) -> Unit)?> { null }

private sealed interface ImageState {
    data object Loading : ImageState
    data class Loaded(val bitmap: Bitmap) : ImageState
    data object Failed : ImageState
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val preview: LinkPreview) : PreviewState
    data object Failed : PreviewState
}

/** Inline image from Markdown, with a bounded decode and a tap-through to the viewer. */
@Composable
internal fun RemoteImageCard(url: String, alt: String, onOpen: ((String) -> Unit)?) {
    val context = LocalContext.current
    val state by produceState<ImageState>(ImageState.Loading, url) {
        value = RemoteContentLoader.loadImage(context, url)
            ?.let { ImageState.Loaded(it) } ?: ImageState.Failed
    }
    val description = alt.ifBlank { stringResource(R.string.ui_image) }
    val sourceUrl = LocalChatImageSources.current[url]
    val openImage = LocalChatImageOpen.current
    val sourceHost = (sourceUrl ?: url).toHttpUrlOrNull()?.host?.removePrefix("www.")
    // A picture that arrives straight from the cache just shows; one that had to
    // load fades in out of a soft blur while the frame eases to its real height.
    val startedAt = remember(url) { android.os.SystemClock.uptimeMillis() }
    val reveal = remember(url) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(state) {
        if (state !is ImageState.Loaded) return@LaunchedEffect
        if (android.os.SystemClock.uptimeMillis() - startedAt < 80) reveal.snapTo(1f)
        else reveal.animateTo(1f, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    Column(Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.spring(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))) {
        when (val current = state) {
            ImageState.Loading -> Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(shimmerBrush()),
            )

            ImageState.Failed -> Unit

            is ImageState.Loaded -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val ratio = current.bitmap.width.toFloat() / current.bitmap.height.coerceAtLeast(1)
                val height = (maxWidth / ratio).coerceIn(120.dp, 420.dp)
                val openModifier = when {
                    openImage != null -> Modifier.clickable { openImage(url) }
                    onOpen != null -> Modifier.clickable { onOpen(sourceUrl ?: url) }
                    else -> Modifier
                }
                Column(Modifier.fillMaxWidth().then(openModifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Image(
                        bitmap = current.bitmap.asImageBitmap(),
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(height)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .blur(((1f - reveal.value) * 16f).dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle)
                            .graphicsLayer {
                                alpha = reveal.value
                                val scale = 1.03f - 0.03f * reveal.value
                                scaleX = scale; scaleY = scale
                            },
                    )
                    if (!sourceHost.isNullOrBlank()) {
                        Text(
                            sourceHost,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "image skeleton")
    val shift by transition.animateFloat(
        initialValue = -500f,
        targetValue = 1_400f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "image skeleton shift",
    )
    val low = MaterialTheme.colorScheme.surfaceContainerLow
    val high = MaterialTheme.colorScheme.surfaceContainerHigh
    return Brush.linearGradient(
        colors = listOf(low, high, low),
        start = Offset(shift, 0f),
        end = Offset(shift + 500f, 500f),
    )
}

/** Open Graph card for a link that sits on its own line. */
@Composable
internal fun RemoteLinkCard(url: String, label: String, onOpen: ((String) -> Unit)?) {
    val state by produceState<PreviewState>(PreviewState.Loading, url) {
        value = RemoteContentLoader.loadPreview(url)?.let { PreviewState.Ready(it) } ?: PreviewState.Failed
    }
    val preview = (state as? PreviewState.Ready)?.preview
    val host = preview?.siteName
        ?: url.toHttpUrlOrNull()?.host?.removePrefix("www.")
        ?: url
    val title = preview?.title ?: label.ifBlank { host }
    val openUrl = preview?.embedUrl ?: preview?.url ?: url
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { onOpen?.invoke(openUrl) },
        enabled = onOpen != null,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            preview?.imageUrl?.let { imageUrl ->
                RemotePreviewImage(imageUrl, play = preview.embedUrl != null)
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                preview?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Language, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(host, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    if (preview?.embedUrl != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("YouTube", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemotePreviewImage(url: String, play: Boolean) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, url) {
        value = RemoteContentLoader.loadImage(context, url)
    }
    Box(
        Modifier.fillMaxWidth().height(160.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (play) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = .55f), modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.chat_play_video),
                    tint = Color.White,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}
