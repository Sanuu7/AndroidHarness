package com.androidharness.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.androidharness.app.ui.theme.defaultEffectsSpec

/**
 * The label of the setting a search result or deep link is pointing at, if any.
 *
 * A settings search result used to open its page at the very top with no
 * indication of which control the user asked for, which made searching for
 * something like "Whisper model" a dead end on a long page. The page sets this
 * to the target label and every [SettingsAnchor] with a matching title scrolls
 * itself into view and flashes a tint.
 */
internal val LocalSettingsHighlight = staticCompositionLocalOf<String?> { null }

/** Provides [highlight] to everything below, see [LocalSettingsHighlight]. */
@Composable
internal fun SettingsHighlightScope(
    highlight: String?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSettingsHighlight provides highlight) { content() }
}

/**
 * Marks the control a deep link can land on. The anchor title must match the
 * visible label of the control it wraps, which is also the title the search
 * entry carries, so the two stay greppable as a pair.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(
    title: String,
    content: @Composable () -> Unit,
) {
    val highlight = LocalSettingsHighlight.current
    val isTarget = highlight != null && title.equals(highlight.trim(), ignoreCase = true)
    val requester = remember { BringIntoViewRequester() }
    val tint by animateColorAsState(
        targetValue = if (isTarget) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else Color.Transparent,
        animationSpec = defaultEffectsSpec(),
        label = "settingsAnchorTint",
    )

    LaunchedEffect(isTarget) {
        if (isTarget) {
            // One frame of slack so the page is laid out before asking it to
            // scroll, otherwise the request races the first measurement.
            kotlinx.coroutines.delay(120)
            runCatching { requester.bringIntoView() }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .background(tint, MaterialTheme.shapes.medium),
    ) {
        content()
    }
}
