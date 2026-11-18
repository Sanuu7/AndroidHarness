package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.CodeActivityGroups
import com.androidharness.app.agent.toolPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * One disclosure for a run of read-only steps. While it works the title follows the
 * live step ("Reading app/Main.kt…") with a shimmer and each new step slides in from
 * below; once done it settles into a summary such as "Read 4 files, searched 2 times".
 * Expanding shows every call as its usual card, with any reasoning in between.
 */
@Composable
internal fun ExploreGroupCard(
    group: CodeActivityGroups.Group,
    results: Map<String, ChatMessage?>,
    runningIds: Set<String>,
    live: Boolean,
    onOpenFile: (String, Int?) -> Unit,
    awaitingApprovalId: String? = null,
) {
    val calls = group.calls
    if (calls.isEmpty()) return
    val context = LocalContext.current
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val activeIds = activeToolIds(calls, results, runningIds)
    val waiting = awaitingApprovalId != null && calls.any { it.id == awaitingApprovalId }
    val active = calls.lastOrNull { it.id in activeIds }
    val failed = calls.count { results[it.id]?.isError == true }
    val state = when {
        waiting -> WorkState.Waiting
        active != null -> WorkState.Running
        live && calls.any { results[it.id] == null } -> WorkState.Running
        failed > 0 && failed == calls.size -> WorkState.Failed
        else -> WorkState.Done
    }
    val working = state == WorkState.Running || state == WorkState.Waiting
    val summary = exploreSummary(calls)
    val focus = active ?: calls.last()
    val title = if (working) remember(focus, results[focus.id]) { toolPresentation(focus, results[focus.id]).action } else summary
    val subtitle = buildList {
        if (working) add(summary)
        if (failed > 0) add(pluralStringResource(R.plurals.code_explore_failed, failed, failed))
    }.joinToString(" · ")
    val outcomes = remember(calls, results, activeIds) {
        calls.map { call ->
            when {
                call.id in activeIds -> WorkState.Running
                results[call.id]?.isError == true -> WorkState.Failed
                results[call.id] != null -> WorkState.Done
                else -> WorkState.Idle
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    CodeCard(onClick = { expanded = !expanded }, expanded = expanded,
        accent = state.takeIf { it == WorkState.Failed || it == WorkState.Waiting },
        modifier = Modifier.animateContentSize(expressiveSpatialSpec())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            if (working) MorphingStatusBadge(state, toolIcon(focus.name))
            else MorphingStatusBadge(state, Icons.Outlined.TravelExplore)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // Each step rises in from below and the previous one drifts up and away.
                AnimatedContent(title, transitionSpec = {
                    (slideInVertically(tween(260)) { it } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(200)) { -it } + fadeOut(tween(140))) using SizeTransform(clip = true)
                }, label = "Explore step") { text ->
                    if (working) ChatShimmerText(text, running = true,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    else Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (subtitle.isNotBlank()) AnimatedContent(subtitle, transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                }, label = "Explore summary") { text ->
                    Text(text, style = MaterialTheme.typography.bodySmall,
                        color = if (failed > 0) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedContent(calls.size, transitionSpec = {
                    (slideInVertically(tween(220)) { it } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(180)) { -it } + fadeOut(tween(120)))
                }, label = "Explore count") { count -> StatusPill("$count", state) }
                ExpandChevron(expanded, context.getString(if (expanded) R.string.ui_collapse else R.string.ui_expand))
            }
        }
        OutcomeStrip(outcomes, Modifier.padding(start = 60.dp, end = 16.dp, bottom = 14.dp))
        AnimatedVisibility(
            expanded,
            enter = expandVertically(animationSpec = bouncySpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                CardHistory(group.entries, key = { entry ->
                    when (entry) {
                        is CodeActivityGroups.Entry.Call -> "call-" + entry.call.id
                        is CodeActivityGroups.Entry.Thought -> "thought-" + (entry.message.id ?: entry.message.createdAt.toString())
                    }
                }) { entry ->
                    when (entry) {
                        is CodeActivityGroups.Entry.Thought -> ThinkingBlock(entry.message.thinking,
                            thinkingMs = entry.message.thinkingMs, reasoningTokens = entry.message.generation.reasoningTokens)
                        is CodeActivityGroups.Entry.Call -> ToolCallCard(
                            call = entry.call,
                            result = results[entry.call.id],
                            running = entry.call.id in activeIds,
                            onOpenFile = onOpenFile,
                            awaitingApproval = entry.call.id == awaitingApprovalId,
                        )
                    }
                }
            }
        }
    }
}

/** "Read 3 files, searched 2 times" in a fixed verb order. */
@Composable
private fun exploreSummary(calls: List<com.androidharness.app.core.ToolCallData>): String {
    val parts = CodeActivityGroups.summary(calls).map { (verb, count) ->
        pluralStringResource(when (verb) {
            CodeActivityGroups.Verb.READ -> R.plurals.code_explore_read
            CodeActivityGroups.Verb.SEARCH -> R.plurals.code_explore_search
            CodeActivityGroups.Verb.LIST -> R.plurals.code_explore_list
            CodeActivityGroups.Verb.WEB -> R.plurals.code_explore_web
            CodeActivityGroups.Verb.MEMORY -> R.plurals.code_explore_memory
            CodeActivityGroups.Verb.OTHER -> R.plurals.code_explore_other
        }, count, count)
    }
    return parts.joinToString(", ").replaceFirstChar { it.titlecase() }.ifBlank { stringResource(R.string.code_explore_exploring) }
}
