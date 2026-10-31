package com.androidharness.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.CacheUsageFooter
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.chat.SideQuestionUi
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec

/**
 * The /btw sheet: a quick question answered on the running task's context.
 * Everything here is ephemeral; dismissing the sheet discards the exchange.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SideQuestionSheet(
    state: SideQuestionUi,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxBody = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val scroll = rememberScrollState()
    // Follow a streaming answer unless the reader has scrolled up to reread.
    LaunchedEffect(scroll.maxValue) {
        if (state.running && scroll.maxValue - scroll.value < FOLLOW_SLACK_PX) scroll.scrollTo(scroll.maxValue)
    }
    HarnessGlassModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        MaterialTheme(motionScheme = MotionScheme.expressive()) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .padding(horizontal = 20.dp).padding(bottom = 12.dp)
                    .testTag("side-question-sheet"),
            ) {
                SideQuestionHeader(state, onStop, onDismiss)
                Spacer(Modifier.size(12.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = maxBody).verticalScroll(scroll)
                        .animateContentSize(expressiveSpatialSpec()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.asked) SideQuestionBody(state, onOpenUrl)
                    else Text(
                        stringResource(R.string.btw_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                SideQuestionInput(state, onAsk)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SideQuestionHeader(state: SideQuestionUi, onStop: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        SideQuestionBadge(state.running)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.btw_title), style = MaterialTheme.typography.titleMedium)
            val source = if (state.warm) stringResource(R.string.btw_source_live) else stringResource(R.string.btw_source_saved)
            Text(
                listOf(source, state.model, stringResource(R.string.btw_private)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.running) {
            val stop = stringResource(R.string.btw_stop)
            ExpressiveIconButton(
                onClick = onStop,
                modifier = Modifier.size(40.dp).semantics { contentDescription = stop },
                colors = IconButtonDefaults.iconButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
            ) { StopGlyph(color = scheme.onErrorContainer, size = 18.dp) }
        } else {
            val close = stringResource(R.string.settings_action_close)
            ExpressiveIconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp).semantics { contentDescription = close },
                colors = IconButtonDefaults.iconButtonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurfaceVariant),
            ) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp)) }
        }
    }
}

/** A cookie that turns while the side answer streams and settles when it lands. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SideQuestionBadge(running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val spin = rememberInfiniteTransition(label = "btw badge")
    val angle by spin.animateFloat(0f, 360f,
        infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart), label = "btw badge angle")
    val settle by animateFloatAsState(if (running) 1f else 0f, expressiveEffectsSpec(), label = "btw badge settle")
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(44.dp).graphicsLayer { rotationZ = angle * settle }
                .clip(shape).background(scheme.tertiaryContainer),
        )
        Icon(painterResource(R.drawable.ic_chat_btw), null, Modifier.size(20.dp), tint = scheme.onTertiaryContainer)
    }
}

@Composable
private fun ColumnScope.SideQuestionBody(state: SideQuestionUi, onOpenUrl: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Surface(
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
        modifier = Modifier.padding(start = 40.dp).align(Alignment.End),
    ) {
        Text(state.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
    if (!state.warm) Text(
        stringResource(R.string.btw_cold_note),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onSurfaceVariant,
    )
    ChatActivity(
        messages = state.messages,
        live = state.running,
        streamingThinking = if (state.running) state.streamingThinking else "",
        writing = state.running && state.streamingText.isNotBlank(),
        runningCalls = state.runningCalls,
        streamId = "btw-${state.startedAt}",
        autoExpand = false,
        showThinkingMetrics = true,
        thinkingTiming = state.thinkingTiming.takeIf { state.running },
        turnStartedAt = state.startedAt.takeIf { state.running },
        onOpenUrl = onOpenUrl,
    )
    state.retrying?.let {
        Text(stringResource(R.string.btw_retrying, it), style = MaterialTheme.typography.labelMedium, color = scheme.tertiary)
    }
    val answer = state.answer
    if (answer.isNotBlank()) MarkdownText(answer, streaming = state.running, onOpenUrl = onOpenUrl)
    else if (state.running && state.runningCalls.isEmpty() && state.streamingThinking.isBlank()) DotLoading(Modifier.padding(vertical = 8.dp))
    state.error?.let { error ->
        Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = MaterialTheme.shapes.large) {
            Text(error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
    }
    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
    if (state.stopped) Text(stringResource(R.string.btw_stopped), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    AnimatedVisibility(state.usage.isNotEmpty() || (!state.running && answer.isNotBlank()), enter = fadeIn(), exit = fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Same footer as a main turn: hit rate, cached tokens and per-request detail.
            if (state.usage.isNotEmpty()) CacheUsageFooter(state.usage, running = state.running)
            if (!state.running && answer.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.toolCount > 0) Text(
                    pluralStringResource(R.plurals.btw_tool_count, state.toolCount, state.toolCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) else Spacer(Modifier.weight(1f))
                val copied = stringResource(R.string.btw_copied)
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.btw_title), answer))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(painterResource(R.drawable.ic_chat_copy), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btw_copy))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SideQuestionInput(state: SideQuestionUi, onAsk: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var draft by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.asked) { if (!state.asked) runCatching { focus.requestFocus() } }
    val canAsk = draft.isNotBlank() && !state.running
    fun ask() {
        if (!canAsk) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onAsk(draft.trim())
        draft = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("side-question-input"),
            placeholder = {
                Text(stringResource(if (state.asked) R.string.btw_input_again else R.string.btw_input_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            shape = RoundedCornerShape(28.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { ask() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = scheme.outlineVariant,
                unfocusedContainerColor = scheme.surfaceContainerHigh,
                focusedContainerColor = scheme.surfaceContainerHigh,
            ),
        )
        Spacer(Modifier.width(8.dp))
        val ask = stringResource(R.string.btw_ask)
        ExpressiveIconButton(
            onClick = ::ask,
            enabled = canAsk,
            preserveFill = true,
            modifier = Modifier.size(48.dp).semantics { contentDescription = ask },
            shape = RoundedCornerShape(if (canAsk) 24.dp else 16.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = scheme.tertiary, contentColor = scheme.onTertiary,
                disabledContainerColor = scheme.surfaceContainerHighest, disabledContentColor = scheme.onSurfaceVariant,
            ),
        ) { Icon(painterResource(R.drawable.ic_chat_arrow_up), null, Modifier.size(20.dp)) }
    }
}

private const val FOLLOW_SLACK_PX = 480
