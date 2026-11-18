package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.HarnessActivityLogo
import com.androidharness.app.ui.common.LogoShape
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val WORDMARK = "Android Harness"

/** The letters that survive the collapse: the A of Android and the H of Harness. */
private val INITIALS = setOf(0, WORDMARK.indexOf('H'))

/**
 * Plays once at the start of a Chat turn: "Android Harness" rises in, every
 * letter but the A and the H folds away so the two initials slide together,
 * the pair turns into the AH app icon, holds for a second, and [onFinished]
 * hands the row over to the live activity.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChatTurnIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val enter = remember { Animatable(0f) }
    val collapse = remember { Animatable(0f) }
    val icon = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        delay(280)
        collapse.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        icon.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 320f))
        delay(1_000)
        finished()
    }
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    Box(
        modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 4.dp)
            .semantics { contentDescription = WORDMARK },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.graphicsLayer {
            alpha = enter.value * (1f - icon.value).coerceIn(0f, 1f)
            translationY = (1f - enter.value) * 10.dp.toPx()
        }) {
            WORDMARK.forEachIndexed { index, letter ->
                val initial = index in INITIALS
                Text(
                    letter.toString(),
                    style = style,
                    color = if (initial) scheme.primary else scheme.onSurface,
                    modifier = if (initial) Modifier else Modifier.foldAway { collapse.value },
                )
            }
        }
        val cookie = MaterialShapes.Cookie9Sided.toShape()
        Box(
            Modifier
                .size(30.dp)
                .graphicsLayer {
                    val p = icon.value
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = 0.45f + 0.55f * p
                    scaleY = 0.45f + 0.55f * p
                    rotationZ = (1f - p) * -40f
                }
                .clip(cookie)
                .background(scheme.primaryContainer, cookie),
            contentAlignment = Alignment.Center,
        ) {
            HarnessActivityLogo(LogoShape.Monogram, scheme.onPrimaryContainer, Modifier.size(19.dp), animate = false)
        }
    }
}

/** Narrows a letter to nothing and fades it a little ahead of the narrowing. */
private fun Modifier.foldAway(progress: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val p = progress().coerceIn(0f, 1f)
    layout((placeable.width * (1f - p)).roundToInt(), placeable.height) {
        placeable.placeWithLayer(0, 0) { alpha = (1f - p * 1.6f).coerceIn(0f, 1f) }
    }
}
