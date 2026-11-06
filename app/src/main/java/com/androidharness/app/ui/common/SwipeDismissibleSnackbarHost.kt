package com.androidharness.app.ui.common

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Keep Material's timeout, accessibility and action handling, with a swipe escape. */
@Composable
internal fun SwipeDismissibleSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { Snackbar(it) },
) {
    val dismissDistance = with(LocalDensity.current) { 48.dp.toPx() }
    SnackbarHost(hostState, modifier) { data ->
        // A queued message must start at rest even while the previous one fades out.
        key(data) {
            var drag by remember { mutableStateOf(Offset.Zero) }
            var dragging by remember { mutableStateOf(false) }
            val offset by animateOffsetAsState(
                targetValue = drag,
                animationSpec = if (dragging) snap() else spring(),
                label = "snackbarSwipe",
            )
            Box(Modifier
                .pointerInput(data, dismissDistance) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragCancel = { dragging = false; drag = Offset.Zero },
                        onDragEnd = {
                            dragging = false
                            if (abs(drag.x) >= dismissDistance || drag.y >= dismissDistance) {
                                data.dismiss()
                            } else {
                                drag = Offset.Zero
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        drag = Offset(drag.x + amount.x, (drag.y + amount.y).coerceAtLeast(0f))
                    }
                }
                // Keep pointer coordinates fixed while only the visual layer follows the finger.
                .graphicsLayer { translationX = offset.x; translationY = offset.y },
            ) {
                snackbar(data)
            }
        }
    }
}
