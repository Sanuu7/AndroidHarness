package com.androidharness.app.ui.chat.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.RunTip
import com.androidharness.app.ui.chat.RunTips
import kotlinx.coroutines.delay

/** Process-wide, so switching chats does not reset the gap between tips. */
private var lastTipShownAt = Long.MIN_VALUE / 2

/**
 * Shows one tip a little after a run starts, at most every few minutes,
 * and lets it linger briefly after the run so it can be finished.
 */
@Composable
internal fun RunTipHost(
    busy: Boolean,
    isChat: Boolean,
    settings: AppSettings,
    onShown: (cursor: Int) -> Unit,
    onOpen: (RunTip) -> Unit,
    onTurnOff: () -> Unit,
) {
    var tipId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Kept after dismissal so the exit animation still has content.
    var shown by remember { mutableStateOf<RunTip?>(null) }
    val latest by rememberUpdatedState(settings)
    val latestShown by rememberUpdatedState(onShown)
    LaunchedEffect(tipId) { tipId?.let { id -> RunTips.all.firstOrNull { it.id == id }?.let { shown = it } } }
    LaunchedEffect(busy, isChat, expanded) {
        if (busy) {
            if (tipId != null) return@LaunchedEffect
            delay(RunTips.DELAY_MS)
            val now = SystemClock.elapsedRealtime()
            if (now - lastTipShownAt < RunTips.COOLDOWN_MS) return@LaunchedEffect
            RunTips.next(latest, isChat)?.let { (tip, cursor) ->
                lastTipShownAt = now
                expanded = false
                tipId = tip.id
                latestShown(cursor)
            }
        } else if (tipId != null && !expanded) {
            delay(RunTips.LINGER_MS)
            tipId = null
        }
    }
    AnimatedVisibility(
        visible = tipId != null && settings.runTipsEnabled,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        shown?.let { tip ->
            RunTipCard(
                tip = tip,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onDismiss = { tipId = null },
                onOpen = { tipId = null; onOpen(tip) },
                onTurnOff = { tipId = null; onTurnOff() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RunTipCard(
    tip: RunTip,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("run-tip"),
        ) {
            Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = MaterialShapes.Cookie6Sided.toShape(), color = scheme.tertiaryContainer, modifier = Modifier.size(28.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Lightbulb, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
                        }
                    }
                    Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(R.string.tips_label), style = MaterialTheme.typography.labelSmall, color = scheme.tertiary)
                        Text(
                            stringResource(tip.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.tips_dismiss), modifier = Modifier.size(16.dp))
                    }
                }
                if (expanded) Row(
                    Modifier.fillMaxWidth().padding(start = 50.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tip.page != null) FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.tips_open_setting)) }
                    TextButton(onClick = onTurnOff) { Text(stringResource(R.string.tips_turn_off)) }
                }
            }
        }
    }
}
