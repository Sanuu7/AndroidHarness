package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.launch

/**
 * Bookmark control in an answer's action row. Saving pops the glyph in with a bouncy spring, fades
 * the tint to the tertiary accent and sends a ring outward; un-saving settles back quietly.
 *
 * Only a tap animates. The saved set loads a moment after the chat opens, and answers that were
 * already saved must appear in their final state rather than replay the pop.
 */
@Composable
internal fun SaveAnswerIconButton(saved: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val fillSpec = expressiveEffectsSpec<Float>()
    val settleSpec = expressiveSpatialSpec<Float>()
    var tapped by remember { mutableStateOf(false) }
    val fill = remember { Animatable(if (saved) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(saved) {
        val animate = tapped
        tapped = false
        val target = if (saved) 1f else 0f
        if (!animate) { fill.snapTo(target); return@LaunchedEffect }
        launch { fill.animateTo(target, fillSpec) }
        if (saved) {
            scale.snapTo(.5f)
            ring.snapTo(0f)
            // The ring runs on its own clock while the glyph springs.
            launch { ring.animateTo(1f, tween(520)) }
            scale.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMediumLow))
        } else {
            scale.snapTo(.78f)
            scale.animateTo(1f, settleSpec)
        }
    }
    val ringColor = scheme.tertiary
    IconToggleButton(
        checked = saved,
        onCheckedChange = {
            tapped = true
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onToggle()
        },
        modifier = Modifier.drawBehind {
            val t = ring.value
            if (t < 1f) drawCircle(
                color = ringColor.copy(alpha = (1f - t) * .45f),
                radius = size.minDimension / 2f * (.35f + .65f * t),
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 2.dp.toPx() * (1f - t * .6f)),
            )
        },
    ) {
        Icon(
            if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = context.getString(R.string.chat_save_answer),
            tint = lerp(scheme.onSurfaceVariant, scheme.tertiary, fill.value),
            modifier = Modifier.size(19.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
    }
}
