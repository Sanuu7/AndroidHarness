package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidharness.app.browser.BrowserActionTrack
import kotlin.math.roundToInt

/**
 * Floating, draggable bubble showing live browser automation.
 *
 * Rotates border gradient 360 degrees on every agent action.
 * Tapping opens the live preview of the browser.
 */
@Composable
fun FloatingBrowserBubble(
    visible: Boolean,
    latestAction: BrowserActionTrack?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
        exit = scaleOut(tween(250, easing = FastOutSlowInEasing)) + fadeOut(tween(180)),
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val parentWidthPx = constraints.maxWidth.toFloat()
            val parentHeightPx = constraints.maxHeight.toFloat()

            val bubbleWidthPx = with(density) { 210.dp.toPx() }
            val bubbleHeightPx = with(density) { 56.dp.toPx() }

            // Default position: Top-right
            var offsetX by remember { mutableFloatStateOf((parentWidthPx - bubbleWidthPx - 32f).coerceAtLeast(16f)) }
            var offsetY by remember { mutableFloatStateOf(60f) }

            // 360-degree spin animation triggered whenever agent performs an action
            val rotationAnim = remember { Animatable(0f) }
            LaunchedEffect(latestAction) {
                if (latestAction != null) {
                    val target = rotationAnim.value + 360f
                    rotationAnim.animateTo(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                    )
                }
            }

            val scheme = MaterialTheme.colorScheme

            val borderBrush = remember(scheme, rotationAnim.value) {
                val rot = (rotationAnim.value % 360f)
                Brush.sweepGradient(
                    0.0f to scheme.primary,
                    0.3f to scheme.tertiary,
                    0.6f to scheme.secondary,
                    1.0f to scheme.primary,
                    center = androidx.compose.ui.geometry.Offset.Zero,
                )
            }

            // Subtle live radar dot on the icon
            val infiniteTransition = rememberInfiniteTransition(label = "radar")
            val radarAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "radar alpha",
            )

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            offsetX.roundToInt().coerceIn(8, (parentWidthPx - bubbleWidthPx - 8).roundToInt().coerceAtLeast(8)),
                            offsetY.roundToInt().coerceIn(8, (parentHeightPx - bubbleHeightPx - 8).roundToInt().coerceAtLeast(8)),
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = scheme.primary, spotColor = scheme.primary)
                    .clip(RoundedCornerShape(28.dp))
                    .background(scheme.surfaceContainerHighest.copy(alpha = 0.96f))
                    .border(2.dp, borderBrush, RoundedCornerShape(28.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Globe icon with subtle live ring
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(scheme.primaryContainer)
                            .drawBehind {
                                drawCircle(
                                    color = scheme.primary.copy(alpha = radarAlpha * 0.35f),
                                    radius = size.minDimension / 1.6f,
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Language,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Text details
                    Column(
                        modifier = Modifier.width(115.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(scheme.primary.copy(alpha = radarAlpha)),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Agent Browser",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary,
                                maxLines = 1,
                            )
                        }

                        val actionText = latestAction?.let {
                            "${it.action.uppercase()}: ${it.detail}"
                        } ?: "Browsing live…"

                        Text(
                            text = actionText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Icon(
                        Icons.Outlined.OpenInFull,
                        contentDescription = "Open Web Preview",
                        tint = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
