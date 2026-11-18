package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.ChatActivityPresentation
import kotlinx.coroutines.delay

/**
 * The sites a live research turn has found, as small chips under its activity
 * row. Each new chip springs in a beat after the one before it, and a page
 * that was actually read gets a check, so the wait reads as progress.
 */
@Composable
internal fun LiveSourceChips(sources: List<ChatActivityPresentation.Source>, onOpenUrl: (String) -> Unit) {
    if (sources.isEmpty()) return
    // Read pages first: they are what the answer will lean on.
    val ordered = remember(sources) { sources.sortedByDescending { it.read }.distinctBy { it.host }.take(10) }
    val arrivals = remember { mutableIntStateOf(0) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ordered.forEach { source ->
            key(source.host) {
                // Staggered by arrival order, so a batch of results lands one chip at a time.
                val order = remember { arrivals.intValue++ }
                SourceChip(source, order, onOpenUrl)
            }
        }
    }
}

@Composable
private fun SourceChip(source: ChatActivityPresentation.Source, order: Int, onOpenUrl: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((order % 10) * 70L)
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f))
    }
    Surface(
        onClick = { onOpenUrl(source.url) },
        shape = CircleShape,
        color = if (source.read) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        modifier = Modifier.height(28.dp).graphicsLayer {
            val p = appear.value
            alpha = p.coerceIn(0f, 1f)
            scaleX = 0.7f + 0.3f * p
            scaleY = 0.7f + 0.3f * p
            translationY = (1f - p) * 6.dp.toPx()
        },
    ) {
        Row(Modifier.padding(PaddingValues(start = 4.dp, end = 10.dp)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(siteColor(source.host)), contentAlignment = Alignment.Center) {
                if (source.read) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = scheme.onPrimary)
                else Text(source.host.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
            }
            Text(source.host, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (source.read) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        }
    }
}

/** A stable, theme-friendly dot colour per site, so the same site always looks the same. */
@Composable
private fun siteColor(host: String): androidx.compose.ui.graphics.Color {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    return palette[(host.hashCode() and Int.MAX_VALUE) % palette.size]
}
