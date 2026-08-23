package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec

/**
 * Collapsible reasoning transcript. Deliberately quiet: a label, a chevron, and
 * a hairline rail next to the body text. No brain icons, no purple — reasoning
 * is supporting evidence, not the main character.
 *
 * While live the block stays collapsed to a one-line pill (fast streams would
 * otherwise fight the layout); expanding mid-stream shows a height-capped,
 * auto-following view that stops following if the user scrolls up.
 */
@Composable
internal fun ThinkingBlock(thinking: String, live: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "reasoning chevron",
    )
    val scheme = MaterialTheme.colorScheme

    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    DotLoading(color = scheme.onSurfaceVariant, dotSize = 4.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (live) "Reasoning…" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (live && !expanded) {
                    Text(
                        formatCharCount(thinking.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation),
                    tint = scheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(defaultEffectsSpec()) + expandVertically(animationSpec = fastSpatialSpec()),
                exit = fadeOut(defaultEffectsSpec()) + shrinkVertically(animationSpec = fastSpatialSpec()),
            ) {
                val scroll = rememberScrollState()
                var lastMax by remember { mutableIntStateOf(0) }
                if (live) {
                    LaunchedEffect(thinking) {
                        val wasAtBottom = scroll.value >= lastMax - 48
                        lastMax = scroll.maxValue
                        if (wasAtBottom && scroll.value < scroll.maxValue) {
                            scroll.scrollTo(scroll.maxValue)
                        }
                    }
                }
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(2.dp)
                            .background(
                                scheme.outlineVariant.copy(alpha = 0.7f),
                                RoundedCornerShape(1.dp),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        thinking,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 260.dp)
                            .verticalScroll(scroll),
                    )
                }
            }
        }
    }
}

private fun formatCharCount(count: Int): String = when {
    count >= 100_000 -> "${count / 1000}k"
    count >= 10_000 -> "${"%.1f".format(count / 1000f)}k"
    count >= 1_000 -> "${count / 100}.${(count % 1000) / 100}k"
    else -> count.toString()
}
