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
 * One tool call = one compact, quiet row.
 *
 * Flat hairline card, neutral icon tile, monospace tool name, one-line summary.
 * A Material expressive progress bar indicates active calls. The right-hand status
 * slot shows a check on success or a cross on failure. The whole row toggles details.
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
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = expressiveSpatialSpec(),
        label = "tool card chevron",
    )
    val presentation = remember(call, result) { toolPresentation(call, result) }
    // Prepare large argument/patch payloads away from the animation's UI thread.
    // Keep raw arguments immediately readable while formatting is in flight.
    val details by produceState<ToolCardDetails?>(null, call.name, call.argumentsJson) {
        value = null
        value = withContext(Dispatchers.Default) {
            ToolCardDetails(
                name = call.name,
                raw = call.argumentsJson,
                arguments = prettyArgs(call.argumentsJson),
                patch = if (call.name == "apply_patch") runCatching {
                    Json.parseToJsonElement(call.argumentsJson).jsonObject["patch"]?.jsonPrimitive?.content
                }.getOrNull() else null,
            )
        }
    }
    val currentDetails = details?.takeIf { it.name == call.name && it.raw == call.argumentsJson }
    val pretty = currentDetails?.arguments ?: call.argumentsJson.take(3000)
    val patchDiff = currentDetails?.patch
    val active = running && result == null
    val ok = result?.let { !it.isError }
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .background(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
                ) {
                    Icon(
                        toolIcon(call.name),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        presentation.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    presentation.detail?.let { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    result?.generation?.execution?.distinctBy { it.label }?.forEach { receipt ->
                        androidx.compose.material3.Surface(
                            color = if (receipt.backend == com.androidharness.app.data.env.DeviceBackend.ROOT) scheme.tertiaryContainer else scheme.secondaryContainer,
                            contentColor = if (receipt.backend == com.androidharness.app.data.env.DeviceBackend.ROOT) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
                            shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(receipt.label + if (receipt.fallbackReason != null) " · fallback" else "",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                val statusKey = when {
                    active -> 0
                    ok == true -> 1
                    ok == false -> 2
                    else -> 3
                }
                Crossfade(
                    targetState = statusKey,
                    animationSpec = fastEffectsSpec(),
                    label = "tool status",
                ) { key ->
                    when (key) {
                        1 -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = uiContext.getString(R.string.settings_action_done),
                            tint = success,
                            modifier = Modifier.size(18.dp),
                        )
                        2 -> Icon(
                            Icons.Filled.Close,
                            contentDescription = uiContext.getString(R.string.ui_failed_64),
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        else -> Spacer(Modifier.size(18.dp))
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) uiContext.getString(R.string.ui_collapse) else uiContext.getString(R.string.ui_expand),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation.value },
                )
            }
            if (active) {
                ToolCallProgress(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
            result?.images?.takeIf { call.name != "read_image" && it.isNotEmpty() }?.let { imgs ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    imgs.forEach { ref -> ToolResultImage(ref) }
                }
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(animationSpec = expressiveSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    Text(
                        call.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = HarnessMono,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (patchDiff != null) {
                        VisualDiffViewer(
                            diffText = patchDiff,
                            maxHeight = 280.dp,
                            showFileHeader = false,
                            onOpenFile = { path -> onOpenFile(path, null) },
                        )
                    } else {
                        MonoBlock(pretty)
                    }
                    result?.let {
                        Spacer(Modifier.height(8.dp))
                        if (call.name in listOf("grep", "search_files", "web_search")) {
                            ClickableOutput(it.text.take(4000), onOpenFile)
                        } else {
                            MonoBlock(it.text.take(4000))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collapses a run of consecutive tool calls behind one quiet header row.
 * A batch made entirely of task calls is a subagent spawn: sparkle icon,
 * "Spawned N subagents" wording, and each child renders as its own
 * [SubagentCard] section with live steps instead of a bare tool row.
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
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = expressiveSpatialSpec(),
        label = "tool group chevron",
    )
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val activeIds = activeToolIds(calls, results, runningIds)
    val anyRunning = activeIds.isNotEmpty()
    val failedCount = calls.count { results[it.id]?.isError == true }
    val doneCount = calls.count { results[it.id]?.isError == false }
    val allSubagents = calls.isNotEmpty() && calls.all { it.name == "task" }

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .background(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
                ) {
                    Icon(
                        if (allSubagents) Icons.Filled.AutoAwesome else Icons.Outlined.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            allSubagents && anyRunning ->
                                uiContext.getString(R.string.ui_running_of_subagents, (activeIds.size).toString(), (calls.size).toString())
                            allSubagents -> uiContext.getString(R.string.ui_spawned_subagents, (calls.size).toString())
                            anyRunning -> uiContext.getString(R.string.ui_running_tools)
                            else -> uiContext.getString(R.string.ui_ran_tools, (calls.size).toString())
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
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
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failedCount > 0) scheme.error else scheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Crossfade(
                    targetState = anyRunning,
                    animationSpec = fastEffectsSpec(),
                    label = "tool group status",
                ) { running ->
                    when {
                        running -> Spacer(Modifier.size(18.dp))
                        failedCount > 0 -> Icon(
                            Icons.Filled.Close,
                            contentDescription = uiContext.getString(R.string.ui_failed_64),
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        else -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = uiContext.getString(R.string.settings_action_done),
                            tint = success,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) uiContext.getString(R.string.ui_collapse) else uiContext.getString(R.string.ui_expand),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation.value },
                )
            }
            if (anyRunning) {
                ToolCallProgress(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
            calls.filter { it.name != "read_image" && !com.androidharness.app.browser.BrowserToolActivity.isBrowserTool(it.name) }
                .flatMap { results[it.id]?.images.orEmpty() }
                .takeIf { it.isNotEmpty() }?.let { imgs ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    ) {
                        imgs.forEach { ref -> ToolResultImage(ref) }
                    }
                }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(animationSpec = expressiveSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
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
}

/** Finished-turn receipt: one compact row by default, full semantic tool history on tap. */
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
        buildList {
            activity.forEachIndexed { index, message ->
                val messageKey = message.id ?: index.toString()
                if (message.thinking.isNotBlank() || (message.generation.reasoningTokens ?: 0) > 0)
                    add(ActivityRow.Thinking("$messageKey-thinking", message))
                if (message.text.isNotBlank()) add(ActivityRow.Text("$messageKey-text", message.text))
                message.toolCalls.forEach { add(ActivityRow.Tool("$messageKey-tool-${it.id}", it)) }
            }
        }
    }
    var expanded by rememberSaveable(calls.joinToString(",") { it.id }) { mutableStateOf(false) }
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "turn activity chevron",
    )
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val failedCount = calls.count { results[it.id]?.isError == true }
    val completedCount = calls.count { results[it.id] != null }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(13.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_chat_check_check),
                    contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = if (failedCount > 0) scheme.error else if (completedCount == calls.size && calls.isNotEmpty()) success else scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (failedCount > 0) uiContext.getString(R.string.ui_work_summary_failed, (failedCount).toString()) else uiContext.getString(R.string.ui_work_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failedCount > 0) scheme.error else scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (calls.isNotEmpty()) Text("$completedCount / ${calls.size}",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Icon(
                    painterResource(R.drawable.ic_chat_chevron_right),
                    contentDescription = if (expanded) uiContext.getString(R.string.ui_collapse_activity) else uiContext.getString(R.string.ui_expand_activity),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp).graphicsLayer { rotationZ = rotation.value / 2 },
                )
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(animationSpec = fastSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = fastSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    if (workedLabel.isNotBlank()) Text(uiContext.getString(R.string.ui_worked_for, (workedLabel).toString()), style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant)
                    CardHistory(rows, key = { it.key }) { row ->
                        when (row) {
                            is ActivityRow.Thinking -> ThinkingBlock(row.message.thinking,
                                thinkingMs = row.message.thinkingMs, reasoningTokens = row.message.generation.reasoningTokens)
                            is ActivityRow.Text -> AssistantText(row.text)
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
}

private data class ToolCardDetails(val name: String, val raw: String, val arguments: String, val patch: String?)
