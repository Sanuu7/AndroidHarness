package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.rich.RichResponseContent

/**
 * An answer the model pulled back to research further. It stays where it
 * was, dimmed and capped in height with a soft fade at the bottom, under a
 * small "still checking" label, until the revised answer streams in.
 */
@Composable
internal fun WithdrawnDraft(text: String) {
    val scheme = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "Draft pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "Draft pulse alpha")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse }.background(scheme.primary, CircleShape))
            Text(stringResource(R.string.chat_draft_checking), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        Box(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).clipToBounds()
                .graphicsLayer { alpha = 0.5f; compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Fade the cut-off bottom edge instead of slicing through a line.
                    val fadeHeight = 64.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - fadeHeight, endY = size.height),
                        blendMode = BlendMode.DstOut,
                    )
                },
        ) {
            RichResponseContent(text, streaming = false, onOpenUrl = null, onFollowUp = null)
        }
    }
}
