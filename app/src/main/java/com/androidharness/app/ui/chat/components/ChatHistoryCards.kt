package com.androidharness.app.ui.chat.components

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ChatHistoryMatch
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.ui.settings.SettingsMorphBadge
import com.androidharness.app.ui.settings.SettingsSegment
import com.androidharness.app.ui.settings.SettingsSegmentGap
import com.androidharness.app.ui.settings.SettingsTone
import kotlinx.coroutines.delay

/** How many past conversations show before the rest fold behind "Show more". */
private const val VISIBLE_MATCHES = 3

/**
 * Lets a chats block inside an answer find the conversations the agent really looked
 * up, so the model can place cards but never invent one.
 */
internal class ChatHistoryLinks(val found: List<ChatHistoryMatch>, val onOpen: (ChatHistoryMatch) -> Unit) {
    fun resolve(ids: List<String>): List<ChatHistoryMatch> {
        val bySession = found.associateBy { it.sessionId }
        return ids.mapNotNull { bySession[it] }
    }
}

internal val LocalChatHistoryLinks = staticCompositionLocalOf<ChatHistoryLinks?> { null }

/** Past conversations as one segmented group: rows a hair apart that round out under the finger. */
@Composable
internal fun ChatHistoryCards(
    matches: List<ChatHistoryMatch>,
    onOpen: (ChatHistoryMatch) -> Unit,
    title: String = "",
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty()) return
    val ids = matches.map { it.sessionId }
    var expanded by rememberSaveable(ids) { mutableStateOf(false) }
    // The cascade plays the first time the group appears, not every time it scrolls back into view.
    var arrived by rememberSaveable(ids) { mutableStateOf(false) }
    val played = remember { arrived }
    LaunchedEffect(Unit) { arrived = true }
    val motion = MaterialTheme.motionScheme
    val visible = matches.take(VISIBLE_MATCHES)
    val extra = matches.drop(VISIBLE_MATCHES)
    val count = if (extra.isEmpty()) matches.size else (if (expanded) matches.size else visible.size) + 1

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp).historyEntrance(0, played),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                title.ifBlank { stringResource(R.string.chat_history_matches) },
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (matches.size > 1) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    matches.size.toString(),
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SettingsSegmentGap)) {
            visible.forEachIndexed { index, hit ->
                ChatHistoryRow(hit, index, count, onOpen, Modifier.historyEntrance(index + 1, played))
            }
            if (extra.isNotEmpty()) {
                AnimatedVisibility(
                    expanded,
                    enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(SettingsSegmentGap)) {
                        extra.forEachIndexed { index, hit -> ChatHistoryRow(hit, VISIBLE_MATCHES + index, count, onOpen) }
                    }
                }
                val turn by animateFloatAsState(if (expanded) 180f else 0f, motion.defaultSpatialSpec(), label = "historyChevron")
                SettingsSegment(
                    count - 1, count,
                    Modifier.historyEntrance(visible.size + 1, played),
                    onClick = { expanded = !expanded },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (expanded) stringResource(R.string.chat_history_show_less)
                            else pluralStringResource(R.plurals.chat_history_show_more, extra.size, extra.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryRow(hit: ChatHistoryMatch, index: Int, count: Int, onOpen: (ChatHistoryMatch) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val chat = hit.kind == ConversationKind.CHAT
    val kind = stringResource(if (chat) R.string.chat_history_kind_chat else R.string.chat_history_kind_code)
    val now = remember { System.currentTimeMillis() }
    val ago = remember(hit.updatedAt, now) {
        DateUtils.getRelativeTimeSpanString(hit.updatedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    SettingsSegment(index, count, modifier, onClick = { onOpen(hit) }, interactionSource = interaction) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsMorphBadge(
                seed = hit.sessionId.hashCode(),
                pressed = pressed,
                tone = if (chat) SettingsTone.PRIMARY else SettingsTone.TERTIARY,
                size = 42.dp,
            ) { tint ->
                Icon(if (chat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Code, null, Modifier.size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (hit.excerpt.isNotBlank()) Text(
                    hit.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$kind · $ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                stringResource(if (hit.messageId != null) R.string.chat_history_open_message else R.string.chat_history_open_chat),
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Fades and lifts each row in, a beat after the one above it; a group seen before just appears. */
private fun Modifier.historyEntrance(order: Int, played: Boolean): Modifier = composed {
    val progress = remember { Animatable(if (played) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(55L * order.coerceAtMost(6))
            progress.animateTo(1f, spec)
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 20.dp.toPx()
        val scale = 0.96f + 0.04f * p
        scaleX = scale
        scaleY = scale
    }
}
