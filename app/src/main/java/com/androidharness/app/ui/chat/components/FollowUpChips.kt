package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import kotlinx.coroutines.delay

/** Suggestion sets that already hopped in, so scrolling back does not replay them. */
private object ShownFollowUps {
    val keys = HashSet<String>()
}

private val ChipPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

/**
 * Next-question chips under the latest answer, as Expressive buttons that
 * morph when pressed. They hop in one after another the first time they
 * appear.
 */
@Composable
internal fun FollowUpChips(suggestions: List<String>, onDigDeeper: (() -> Unit)? = null, onSelect: (String) -> Unit) {
    if (suggestions.isEmpty() && onDigDeeper == null) return
    val context = LocalContext.current
    val setKey = (listOf(onDigDeeper != null) + suggestions).joinToString("\u0000")
    val animate = remember(setKey) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f && ShownFollowUps.keys.add(setKey)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var order = 0
        // Reruns the question with Deep search as a new answer version.
        if (onDigDeeper != null) {
            FilledTonalButton(onClick = onDigDeeper, contentPadding = ChipPadding,
                modifier = Modifier.height(36.dp).hopIn(order++, animate)) {
                Icon(Icons.Outlined.TravelExplore, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_dig_deeper), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        suggestions.forEach { suggestion ->
            OutlinedButton(
                onClick = { onSelect(suggestion) },
                contentPadding = ChipPadding,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.height(36.dp).hopIn(order++, animate),
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(suggestion, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A short springy rise, staggered by [order]. */
@Composable
private fun Modifier.hopIn(order: Int, animate: Boolean): Modifier {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(Unit) {
        delay(order * 90L)
        progress.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 480f))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 14.dp.toPx()
        scaleX = 0.85f + 0.15f * p
        scaleY = 0.85f + 0.15f * p
    }
}
