package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.CodeActivityGroups
import com.androidharness.app.agent.toolPresentation
import com.androidharness.app.browser.BrowserToolActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.common.TextButton

/**
 * One inline line for a run of steps: read-only exploring ("Read 4 files, searched 2
 * times") or browser work ("Opened 2 pages, clicked 5 times"). While it works the title
 * follows the live step with a shimmer and each new step slides in from below. Opening
 * it lists every step as its own inline line, with any reasoning in between.
 */
@Composable
internal fun ExploreGroupCard(
    group: CodeActivityGroups.Group,
    results: Map<String, ChatMessage?>,
    runningIds: Set<String>,
    live: Boolean,
    onOpenFile: (String, Int?) -> Unit,
    awaitingApprovalId: String? = null,
    onWatchBrowser: ((String?) -> Unit)? = null,
    browserTargets: Map<String, String> = emptyMap(),
) {
    val calls = group.calls
    if (calls.isEmpty()) return
    val context = LocalContext.current
    val browser = group.kind == CodeActivityGroups.Kind.BROWSER
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
    val summary = if (browser) browserSummary(calls) else exploreSummary(calls)
    val focus = active ?: calls.last()
    val liveStep = remember(focus, results[focus.id], browserTargets[focus.id], working) {
        if (browser) BrowserToolActivity.from(focus, results[focus.id], working, browserTargets[focus.id], waiting).action
        else toolPresentation(focus, results[focus.id]).action
    }
    val site = if (browser) remember(focus, results[focus.id], browserTargets[focus.id]) {
        BrowserToolActivity.from(focus, results[focus.id], false, browserTargets[focus.id]).site
    } else null
    val detail = buildList {
        if (working) add(summary) else site?.let(::add)
        if (failed > 0) add(pluralStringResource(R.plurals.code_explore_failed, failed, failed))
    }.joinToString(" · ")
    InlineActivityRow(
        icon = when {
            browser -> Icons.Outlined.Language
            working -> toolIcon(focus.name)
            else -> Icons.Outlined.TravelExplore
        },
        title = if (working) liveStep else summary,
        detail = detail,
        state = state,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        count = calls.size,
        animateTitle = working,
        trailing = if (browser && working && onWatchBrowser != null) ({
            TextButton(onClick = { onWatchBrowser(browserTargets[focus.id]) }, modifier = Modifier.heightIn(min = 32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text(context.getString(R.string.ui_watch), style = MaterialTheme.typography.labelMedium)
            }
        }) else null,
    ) {
        CardHistory(group.entries, spacing = 0.dp, key = { entry ->
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
                    onWatchBrowser = onWatchBrowser,
                    browserTarget = browserTargets[entry.call.id],
                )
            }
        }
    }
}

/** "Read 3 files, searched 2 times" in a fixed verb order. */
@Composable
private fun exploreSummary(calls: List<ToolCallData>): String {
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

/** "Opened 2 pages, clicked 5 times, took 1 screenshot" in a fixed order. */
@Composable
private fun browserSummary(calls: List<ToolCallData>): String {
    val counts = calls.groupingBy {
        when (it.name) {
            "browser_navigate", "browser_back", "browser_forward", "browser_refresh" -> 0
            "browser_click" -> 1
            "browser_type" -> 2
            "browser_screenshot" -> 3
            "browser_read", "browser_get_dom", "browser_get_logs", "browser_get_url" -> 4
            else -> 5
        }
    }.eachCount().toSortedMap()
    val parts = counts.map { (kind, count) ->
        pluralStringResource(when (kind) {
            0 -> R.plurals.browser_steps_open
            1 -> R.plurals.browser_steps_click
            2 -> R.plurals.browser_steps_type
            3 -> R.plurals.browser_steps_screenshot
            4 -> R.plurals.browser_steps_read
            else -> R.plurals.browser_steps_other
        }, count, count)
    }
    return parts.joinToString(", ").replaceFirstChar { it.titlecase() }.ifBlank { stringResource(R.string.browser_steps_using) }
}
