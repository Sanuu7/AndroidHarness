package com.androidharness.app.ui.chat.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import kotlin.math.hypot
import kotlin.math.max

/**
 * Where on screen the last source chip was pressed. The web preview lives in
 * its own sheet window, so a true shared element is not possible; instead the
 * sheet reads this point and opens its content as a circle growing from it.
 */
internal object PreviewOrigin {
    private var point: Offset? = null
    private var at = 0L

    fun mark(screenPoint: Offset) { point = screenPoint; at = SystemClock.uptimeMillis() }

    /** The pressed point, if a chip was pressed just now; cleared once read. */
    fun take(): Offset? = point.takeIf { SystemClock.uptimeMillis() - at < 1_500 }.also { point = null }
}

/** Records this element's centre as the preview origin whenever it is pressed. */
internal fun Modifier.markPreviewOrigin(): Modifier = composed {
    val bounds = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onGloballyPositioned { bounds[0] = it }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            bounds[0]?.takeIf { it.isAttached }?.let { coordinates ->
                val topLeft = coordinates.positionOnScreen()
                PreviewOrigin.mark(Offset(topLeft.x + coordinates.size.width / 2f, topLeft.y + coordinates.size.height / 2f))
            }
        }
    }
}

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Opens content as a circle growing from [origin] (screen coordinates). A
 * point above the content, such as a chip the sheet slides over, is pulled to
 * the content's top edge so the circle still starts where the eye is.
 */
@Composable
internal fun Modifier.revealFrom(origin: Offset?): Modifier {
    if (origin == null) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(560, easing = Emphasized)) }
    val topLeft = remember { arrayOf(Offset.Zero) }
    return onGloballyPositioned { topLeft[0] = it.positionOnScreen() }.drawWithContent {
        val p = progress.value
        if (p >= 1f) { drawContent(); return@drawWithContent }
        val center = Offset(
            (origin.x - topLeft[0].x).coerceIn(0f, size.width),
            (origin.y - topLeft[0].y).coerceIn(0f, size.height),
        )
        val far = max(
            max(hypot(center.x, center.y), hypot(size.width - center.x, center.y)),
            max(hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y)),
        )
        val radius = far * p
        val circle = Path().apply { addOval(Rect(center, radius)) }
        clipPath(circle) { this@drawWithContent.drawContent() }
    }
}
