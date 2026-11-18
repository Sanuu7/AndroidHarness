package com.androidharness.app.ui.chat.components

import androidx.compose.ui.res.painterResource
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.toolPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.common.ToolCallProgress
import com.androidharness.app.ui.common.VisualDiffViewer
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One tool call = one tonal card.
 *
 * The leading badge carries the state: it spins as a cookie while the tool runs
 * and springs into a circle (done) or squared tile (failed) when it returns.
 * Tapping opens the input and output as terminal panels.
 */
@Composable
internal fun ToolCallCard(
    call: ToolCallData,
    result: ChatMessage?,
    running: Boolean,
    onOpenFile: (String, Int?) -> Unit,
    showLiveCode: Boolean = true,
    codeDraft: com.androidharness.app.agent.LiveCodeDraft? = null,
    awaitingApproval: Boolean = false,
    onWatchBrowser: ((String?) -> Unit)? = null,
    browserTarget: String? = null,
    packageProgress: com.androidharness.app.data.env.PackageInstallStatus? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (call.name == "pkg_install" || call.name == com.androidharness.app.tools.AndroidSdkInstallTool.NAME) {
        PackageInstallCard(call, result, running, awaitingApproval, packageProgress)
        return
    }
    if (call.name == "github_create_pull_request") {
        com.androidharness.app.ui.github.PullRequestToolCard(call, result, running, awaitingApproval)
        return
    }
    if (com.androidharness.app.browser.BrowserToolActivity.isBrowserTool(call.name)) {
        BrowserToolCard(call, result, running, browserTarget, awaitingApproval, onWatchBrowser)
        return
    }
    if (showLiveCode && call.name in com.androidharness.app.agent.LiveCodePreview.tools) {
        if (codeDraft != null) LiveCodeCard(codeDraft, awaitingApproval, onOpenFile)
        else {
            val savedPreview by produceState<com.androidharness.app.agent.LiveCodeDraft?>(null, call, result, running) {
                value = withContext(Dispatchers.Default) {
                    com.androidharness.app.agent.LiveCodePreview.fromCall(call, result, running)
                }
            }
            savedPreview?.let { LiveCodeCard(it, awaitingApproval, onOpenFile) }
        }
        return
    }
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val presentation = remember(call, result) { toolPresentation(call, result) }
    // Prepare large argument/patch payloads away from the animation's UI thread.
    // Keep raw arguments immediately readable while formatting is in flight.
    val details by produceState<ToolCardDetails?>(null, call.name, call.argumentsJson) {
        value = null
        value = withContext(Dispatchers.Default) {
            val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
            ToolCardDetails(
                name = call.name,
                raw = call.argumentsJson,
                arguments = prettyArgs(call.argumentsJson),
                patch = if (call.name == "apply_patch") runCatching { args?.get("patch")?.jsonPrimitive?.content }.getOrNull() else null,
                command = if (call.name in shellTools) runCatching { args?.get("command")?.jsonPrimitive?.content }.getOrNull() else null,
            )
        }
    }
    val currentDetails = details?.takeIf { it.name == call.name && it.raw == call.argumentsJson }
    val pretty = currentDetails?.arguments ?: call.argumentsJson.take(3000)
    val patchDiff = currentDetails?.patch
    val command = currentDetails?.command
    val active = running && result == null
    val state = when {
        awaitingApproval -> WorkState.Waiting
        active -> WorkState.Running
        result?.isError == false -> WorkState.Done
        result?.isError == true -> WorkState.Failed
        else -> WorkState.Idle
    }
    val scheme = MaterialTheme.colorScheme

    val receipts = result?.generation?.execution?.distinctBy { it.label }.orEmpty()
    val images = result?.images?.takeIf { call.name != "read_image" && it.isNotEmpty() }
    InlineActivityRow(
        icon = toolIcon(call.name),
        title = presentation.title,
        detail = presentation.detail,
        detailMono = call.name in shellTools || call.name in pathTools,
        state = state,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        always = if (receipts.isEmpty() && images == null) null else ({
            if (receipts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                receipts.forEach { receipt ->
                    StatusPill(
                        receipt.label + if (receipt.fallbackReason != null) " · fallback" else "",
                        if (receipt.backend == com.androidharness.app.data.env.DeviceBackend.ROOT) WorkState.Waiting else WorkState.Done,
                    )
                }
            }
            images?.forEach { ref -> ToolResultImage(ref) }
        }),
    ) {
        when {
            patchDiff != null -> Surface(color = scheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
                Box(Modifier.padding(6.dp)) {
                    VisualDiffViewer(
                        diffText = patchDiff,
                        maxHeight = 280.dp,
                        showFileHeader = false,
                        onOpenFile = { path -> onOpenFile(path, null) },
                    )
                }
            }
            command != null -> CodePanel(command, label = call.name, prompt = "$")
            else -> CodePanel(pretty, label = call.name)
        }
        result?.let {
            if (call.name in listOf("grep", "search_files", "web_search")) {
                Surface(color = scheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        ClickableOutput(it.text.take(4000), onOpenFile)
                    }
                }
            } else {
                CodePanel(
                    it.text.take(4000).ifBlank { "∅" },
                    label = uiContext.getString(if (it.isError) R.string.ui_failed_64 else R.string.ui_output_1407),
                    tone = if (it.isError) WorkState.Failed else null,
                )
            }
        }
    }
}

private val shellTools = setOf("shell", "shell_background")
private val pathTools = setOf("read_file", "file_info", "write_file", "edit_file", "multi_edit", "list_dir", "create_dir", "delete_file", "move_file")

private fun toolState(id: String, results: Map<String, ChatMessage?>, activeIds: Set<String>): WorkState = when {
    id in activeIds -> WorkState.Running
    results[id]?.isError == true -> WorkState.Failed
    results[id] != null -> WorkState.Done
    else -> WorkState.Idle
}

/**
 * Collapses a run of consecutive tool calls behind one header with an outcome
 * strip, one segment per call. A batch made entirely of task calls is a subagent
 * spawn: sparkle badge, "Spawned N subagents" wording, and each child renders as
 * its own [SubagentCard] section with live steps instead of a bare tool row.
 */
@Composable
internal fun ToolGroupCard(
    calls: List<ToolCallData>,
    results: Map<String, ChatMessage?>,
    runningIds: Set<String>,
    onOpenFile: (String, Int?) -> Unit,
    subagentSteps: Map<String, List<String>> = emptyMap(),
    showLiveCode: Boolean = true,
    onWatchBrowser: ((String?) -> Unit)? = null,
    browserTargets: Map<String, String> = emptyMap(),
    packageProgress: Map<String, com.androidharness.app.data.env.PackageInstallStatus> = emptyMap(),
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable(calls.joinToString(",") { it.id }) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val activeIds = activeToolIds(calls, results, runningIds)
    val anyRunning = activeIds.isNotEmpty()
    val failedCount = calls.count { results[it.id]?.isError == true }
    val doneCount = calls.count { results[it.id]?.isError == false }
    val allSubagents = calls.isNotEmpty() && calls.all { it.name == "task" }
    val groupState = when {
        anyRunning -> WorkState.Running
        failedCount > 0 -> WorkState.Failed
        doneCount > 0 -> WorkState.Done
        else -> WorkState.Idle
    }
    val outcomes = calls.map { toolState(it.id, results, activeIds) }

    CodeCard(onClick = { expanded = !expanded }, expanded = expanded,
        accent = groupState.takeIf { it == WorkState.Failed }) {
        val summary = buildString {
            if (doneCount > 0) append("$doneCount done")
            if (failedCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("$failedCount failed")
            }
            if (anyRunning) {
                if (isNotEmpty()) append(" · ")
                append("${activeIds.size} running")
            }
        }
        CodeCardHeader(
            badge = {
                MorphingStatusBadge(groupState, if (allSubagents) Icons.Filled.AutoAwesome else Icons.Outlined.Build)
            },
            title = when {
                allSubagents && anyRunning ->
                    uiContext.getString(R.string.ui_running_of_subagents, (activeIds.size).toString(), (calls.size).toString())
                allSubagents -> uiContext.getString(R.string.ui_spawned_subagents, (calls.size).toString())
                anyRunning -> uiContext.getString(R.string.ui_running_tools)
                else -> uiContext.getString(R.string.ui_ran_tools, (calls.size).toString())
            },
            subtitle = summary,
            subtitleColor = if (failedCount > 0) scheme.error else scheme.onSurfaceVariant,
            trailing = {
                ExpandChevron(expanded, if (expanded) uiContext.getString(R.string.ui_collapse) else uiContext.getString(R.string.ui_expand))
            },
        )
        OutcomeStrip(outcomes, Modifier.padding(start = 60.dp, end = 16.dp, bottom = 14.dp))
        calls.filter { it.name != "read_image" && !com.androidharness.app.browser.BrowserToolActivity.isBrowserTool(it.name) }
            .flatMap { results[it.id]?.images.orEmpty() }
            .takeIf { it.isNotEmpty() }?.let { imgs ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    imgs.forEach { ref -> ToolResultImage(ref) }
                }
            }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(animationSpec = bouncySpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                CardHistory(calls, key = { it.id }) { call ->
                    if (call.name == "task") {
                        SubagentCard(
                            call = call,
                            steps = subagentSteps[call.id].orEmpty(),
                            result = results[call.id],
                            running = call.id in activeIds,
                            onOpenFile = onOpenFile,
                        )
                    } else {
                        ToolCallCard(
                            call = call,
                            result = results[call.id],
                            running = call.id in activeIds,
                            onOpenFile = onOpenFile,
                            showLiveCode = showLiveCode,
                            onWatchBrowser = onWatchBrowser,
                            browserTarget = browserTargets[call.id],
                            packageProgress = packageProgress[call.id],
                        )
                    }
                }
            }
        }
    }
}

/**
 * Finished-turn receipt. Collapsed it is a single summary with an outcome strip
 * (one segment per tool, colored by result); tapping opens the full history of
 * reasoning, notes and tool calls for the turn.
 */
@Composable
internal fun TurnActivityCard(
    calls: List<ToolCallData>,
    results: Map<String, ChatMessage?>,
    fileEdits: List<com.androidharness.app.data.db.FileEditEntity>,
    workedLabel: String,
    activity: List<ChatMessage>,
    onOpenFile: (String, Int?) -> Unit,
    showLiveCode: Boolean = true,
    onWatchBrowser: ((String?) -> Unit)? = null,
    browserTargets: Map<String, String> = emptyMap(),
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (activity.isEmpty()) return
    val rows = remember(activity) {
        val plan = com.androidharness.app.agent.CodeActivityGroups.plan(activity)
        buildList {
            activity.forEachIndexed { index, message ->
                val messageKey = message.id ?: index.toString()
                if (message.id !in plan.foldedThinking && (message.thinking.isNotBlank() || (message.generation.reasoningTokens ?: 0) > 0))
                    add(ActivityRow.Thinking("$messageKey-thinking", message))
                if (message.text.isNotBlank()) add(ActivityRow.Text("$messageKey-text", message.text))
                val slots = message.id?.let { plan.slots[it] }
                if (slots == null) message.toolCalls.forEach { add(ActivityRow.Tool("$messageKey-tool-${it.id}", it)) }
                else {
                    message.toolCalls.filter { it.name == "task" }.forEach { add(ActivityRow.Tool("$messageKey-tool-${it.id}", it)) }
                    slots.forEach { slot ->
                        when (slot) {
                            is com.androidharness.app.agent.CodeActivityGroups.Slot.Explore -> add(ActivityRow.Explore(slot.group.key, slot.group))
                            is com.androidharness.app.agent.CodeActivityGroups.Slot.Single -> add(ActivityRow.Tool("$messageKey-tool-${slot.call.id}", slot.call))
                        }
                    }
                }
            }
        }
    }
    var expanded by rememberSaveable(calls.joinToString(",") { it.id }) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val failedCount = calls.count { results[it.id]?.isError == true }
    val completedCount = calls.count { results[it.id] != null }
    val state = if (failedCount > 0) WorkState.Failed else WorkState.Done
    val outcomes = remember(calls, results) { calls.map { toolState(it.id, results, emptySet()) } }
    CodeCard(onClick = { expanded = !expanded }, expanded = expanded) {
        CodeCardHeader(
            badge = {
                MorphingStatusBadge(state, painterResource(R.drawable.ic_chat_check_check), showOutcome = false)
            },
            title = if (failedCount > 0) uiContext.getString(R.string.ui_work_summary_failed, (failedCount).toString())
                else uiContext.getString(R.string.ui_work_summary),
            subtitle = workedLabel.takeIf { it.isNotBlank() }?.let { uiContext.getString(R.string.ui_worked_for, it) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (calls.isNotEmpty()) StatusPill("$completedCount / ${calls.size}", state)
                    ExpandChevron(expanded,
                        if (expanded) uiContext.getString(R.string.ui_collapse_activity) else uiContext.getString(R.string.ui_expand_activity))
                }
            },
        )
        if (outcomes.isNotEmpty()) OutcomeStrip(outcomes, Modifier.padding(start = 60.dp, end = 16.dp, bottom = 14.dp))
        AnimatedVisibility(
            expanded,
            enter = expandVertically(animationSpec = bouncySpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                CardHistory(rows, key = { it.key }) { row ->
                    when (row) {
                        is ActivityRow.Thinking -> ThinkingBlock(row.message.thinking,
                            thinkingMs = row.message.thinkingMs, reasoningTokens = row.message.generation.reasoningTokens)
                        is ActivityRow.Text -> Box(Modifier.padding(horizontal = 8.dp)) { AssistantText(row.text) }
                        is ActivityRow.Explore -> ExploreGroupCard(row.group, results, emptySet(), live = false, onOpenFile = onOpenFile,
                            onWatchBrowser = onWatchBrowser, browserTargets = browserTargets)
                        is ActivityRow.Tool -> ToolCallCard(
                            call = row.call,
                            result = results[row.call.id],
                            running = false,
                            onOpenFile = onOpenFile,
                            showLiveCode = showLiveCode,
                            onWatchBrowser = onWatchBrowser,
                            browserTarget = browserTargets[row.call.id],
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline tool-result image (browser screenshots). Decoded off the main thread
 * with a coarse sample size; these are for viewing in the transcript, not
 * pixel work. Provider payloads never include them.
 */
@Composable
internal fun ToolResultImage(ref: ImageRef, previewHeight: androidx.compose.ui.unit.Dp? = null, fillPreviewWidth: Boolean = false) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var fullScreen by rememberSaveable(ref.name) { mutableStateOf(false) }
    if (fullScreen) {
        val dir = File(context.filesDir, "images")
        val fullDir = File(dir, "originals").takeIf { File(it, ref.name).isFile } ?: dir
        ImagePreviewDialog(ref, fullDir) { fullScreen = false }
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, ref.name) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, "images/${ref.name}")
                if (!file.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }
    bitmap?.let { bmp ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = ref.caption ?: ref.name,
            contentScale = if (previewHeight == null) ContentScale.FillWidth else ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillPreviewWidth) Modifier.height((maxWidth * bmp.height.toFloat() / bmp.width.coerceAtLeast(1)).coerceIn(120.dp, previewHeight ?: 420.dp))
                    else if (previewHeight != null) Modifier.heightIn(max = previewHeight) else Modifier)
                .clip(RoundedCornerShape(10.dp))
                .then(if (previewHeight != null) Modifier.clickable(onClickLabel = context.getString(R.string.ui_open_image)) { fullScreen = true } else Modifier)
                .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        )
        }
    }
}

private sealed interface ActivityRow {
    val key: String
    data class Thinking(override val key: String, val message: ChatMessage) : ActivityRow
    data class Text(override val key: String, val text: String) : ActivityRow
    data class Tool(override val key: String, val call: ToolCallData) : ActivityRow
    data class Explore(override val key: String, val group: com.androidharness.app.agent.CodeActivityGroups.Group) : ActivityRow
}

private data class ToolCardDetails(val name: String, val raw: String, val arguments: String, val patch: String?, val command: String?)
