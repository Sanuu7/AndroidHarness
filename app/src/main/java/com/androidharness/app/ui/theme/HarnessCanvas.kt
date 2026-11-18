package com.androidharness.app.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.data.HarnessCanvasSettings
import com.androidharness.app.data.HarnessCanvasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop

internal val LocalHarnessCanvasVisible = staticCompositionLocalOf { false }

@Composable
internal fun HarnessCanvasBackdrop(
    settings: HarnessCanvasSettings,
    isChat: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val file = remember(context, settings.imageId) { HarnessCanvasStore.image(context, settings.imageId) }
    val visible = file != null && settings.enabled && (if (isChat) settings.chat else settings.code)
    SideEffect { HarnessCanvasMemory.settings = settings }
    // A screen opened by navigation finds the decoded image already in memory, so its first
    // frame has the canvas instead of a bare surface that the image then fades over.
    val cacheKey = file?.let { "${it.path}:${it.lastModified()}" }
    val cached = if (visible) HarnessCanvasMemory.bitmapFor(cacheKey) else null
    val bitmap by produceState(cached, cacheKey, visible) {
        value = if (!visible) null else HarnessCanvasMemory.bitmapFor(cacheKey) ?: withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file!!.path)?.asImageBitmap() }.getOrNull()
        }?.also { HarnessCanvasMemory.remember(cacheKey, it) }
    }
    val parentBackdrop = LocalHarnessGlassBackdrop.current
    val wallpaperBackdrop = rememberHarnessGlassScene(backgroundOnly = true)
    val combinedBackdrop = if (parentBackdrop != null && wallpaperBackdrop != null) rememberCombinedBackdrop(parentBackdrop, wallpaperBackdrop) else null
    val screen = LocalHarnessGlassScene.current
    DisposableEffect(screen, combinedBackdrop) {
        screen?.background = combinedBackdrop
        onDispose { if (screen?.background === combinedBackdrop) screen?.background = null }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.matchParentSize().recordHarnessGlassScene(wallpaperBackdrop)) {
        Crossfade(bitmap, modifier = Modifier.fillMaxSize(), animationSpec = defaultEffectsSpec(), label = "Harness Canvas") { image ->
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alpha = 1f - settings.dim.takeIf { it.isFinite() }.let { (it ?: .65f).coerceIn(0f, 1f) })
        }
        }
        CompositionLocalProvider(LocalHarnessCanvasVisible provides (visible && bitmap != null),
            LocalHarnessGlassBackdrop provides (combinedBackdrop ?: parentBackdrop)) { content() }
    }
}

/** Last canvas settings and decoded image, shared by every chat screen in this process. */
internal object HarnessCanvasMemory {
    @Volatile var settings: HarnessCanvasSettings? = null
    private var key: String? = null
    private var image: ImageBitmap? = null
    fun bitmapFor(cacheKey: String?): ImageBitmap? = synchronized(this) { image.takeIf { cacheKey != null && key == cacheKey } }
    fun remember(cacheKey: String?, bitmap: ImageBitmap) = synchronized(this) { key = cacheKey; image = bitmap }
}
