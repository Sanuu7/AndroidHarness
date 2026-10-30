package com.androidharness.app.ui.github

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.VerificationEvidence
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.github.PullRequestInfo
import com.androidharness.app.github.PullRequestPlan
import com.androidharness.app.github.buildPullRequestBody
import com.androidharness.app.github.suggestPullRequestTitle
import com.androidharness.app.tools.parsePullRequestResult
import com.androidharness.app.ui.chat.components.MonoBlock
import com.androidharness.app.ui.common.CatalogLoadingIndicator
import com.androidharness.app.ui.common.ToolCallProgress
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.Switch
import com.androidharness.app.ui.common.TextButton

internal fun copyPullRequestLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pull request", url))
    Toast.makeText(context, context.getString(R.string.pr_link_copied), Toast.LENGTH_SHORT).show()
}

internal fun openPullRequestLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pull request", url))
        Toast.makeText(context, context.getString(R.string.pr_open_failed), Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PullRequestLinkActions(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { openPullRequestLink(context, url) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pr_open_on_github), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { copyPullRequestLink(context, url) }) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pr_copy_link), maxLines = 1)
        }
    }
}

/** A rounded pill for branch names and states; long names ellipsize instead of wrapping the row. */
@Composable
private fun PullRequestPill(text: String, container: Color, content: Color, mono: Boolean = false, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontFamily = if (mono) HarnessMono else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchFlow(head: String, base: String, draft: Boolean) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        PullRequestPill(head, scheme.secondaryContainer, scheme.onSecondaryContainer, mono = true, modifier = Modifier.widthIn(max = 180.dp))
        // FlowRow mirrors in RTL, so the arrow has to flip to keep pointing at the base branch.
        Text(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "←" else "→",
            style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        PullRequestPill(base, scheme.surfaceContainerHighest, scheme.onSurface, mono = true, modifier = Modifier.widthIn(max = 180.dp))
        if (draft) PullRequestPill(stringResource(R.string.pr_draft), scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
}

private fun callArgs(call: ToolCallData) = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()

/** Chat card for github_create_pull_request: progress, then the PR number with open and copy actions. */
@Composable
internal fun PullRequestToolCard(call: ToolCallData, result: ChatMessage?, running: Boolean, awaitingApproval: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val args = remember(call.argumentsJson) { callArgs(call) }
    val parsed = remember(result?.text) { result?.takeIf { !it.isError }?.text?.let(::parsePullRequestResult) }
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val requestedTitle = args?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
    val failed = result?.isError == true
    val title = when {
        parsed != null && parsed.existing -> stringResource(R.string.pr_tool_existing, parsed.number)
        parsed != null -> stringResource(R.string.pr_tool_opened, parsed.number)
        failed -> stringResource(R.string.pr_tool_failed)
        awaitingApproval -> stringResource(R.string.pr_tool_waiting)
        else -> stringResource(R.string.pr_tool_opening)
    }
    Surface(onClick = { expanded = !expanded }, shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (parsed != null) success.copy(alpha = .45f) else scheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).background(if (parsed != null) success.copy(alpha = .14f) else scheme.surfaceContainerHigh,
                    RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Merge, null, Modifier.size(18.dp),
                        tint = if (parsed != null) success else if (failed) scheme.error else scheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    val subtitle = parsed?.title?.ifBlank { null } ?: requestedTitle
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    stringResource(if (expanded) R.string.ui_collapse else R.string.ui_expand), Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
            }
            if (running && result == null && !awaitingApproval) ToolCallProgress(Modifier.fillMaxWidth())
            parsed?.let { pr ->
                BranchFlow(pr.head, pr.base, pr.draft)
                if (pr.commits > 0 || pr.files > 0) Text(stringResource(R.string.pr_counts, pr.commits, pr.files),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                PullRequestLinkActions(pr.url, Modifier.fillMaxWidth())
            }
            if (failed && !expanded) result?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .5f))
                    Text(call.name, style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono, color = scheme.onSurfaceVariant)
                    args?.get("summary")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    result?.let { MonoBlock(it.text.take(4000)) }
                }
            }
        }
    }
}

/** Replaces raw JSON in the approval card with what will actually happen on GitHub. */
@Composable
internal fun PullRequestApprovalSummary(call: ToolCallData) {
    val scheme = MaterialTheme.colorScheme
    val args = remember(call.argumentsJson) { callArgs(call) }
    val title = args?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
    val base = args?.get("base")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val draft = args?.get("draft")?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull.equals("true", true) } == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pr_approval_body), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Surface(color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (base.isNotEmpty()) stringResource(R.string.pr_approval_base, base) else stringResource(R.string.pr_approval_default_base),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    if (draft) PullRequestPill(stringResource(R.string.pr_draft), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                }
                args?.get("summary")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private enum class Tone { Warning, Error, Info }

@Composable
private fun PullRequestBanner(icon: ImageVector, title: String, body: String?, tone: Tone,
    action: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val (container, accent) = when (tone) {
        Tone.Warning -> scheme.tertiaryContainer.copy(alpha = .55f) to scheme.tertiary
        Tone.Error -> scheme.errorContainer.copy(alpha = .55f) to scheme.error
        Tone.Info -> scheme.secondaryContainer.copy(alpha = .55f) to scheme.secondary
    }
    Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
            action?.let { Box(Modifier.fillMaxWidth().padding(start = 30.dp)) { it() } }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseBranchPicker(value: String, options: List<String>, enabled: Boolean, onOpen: () -> Unit, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) { expanded = !expanded; if (expanded) onOpen() } }) {
        OutlinedTextField(value, {}, readOnly = true, enabled = enabled, singleLine = true, shape = MaterialTheme.shapes.medium,
            label = { Text(stringResource(R.string.pr_base_label)) }, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = HarnessMono),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { CatalogLoadingIndicator(Modifier.size(24.dp)) }, onClick = {}, enabled = false)
            options.forEach { name ->
                DropdownMenuItem(text = { Text(name, fontFamily = HarnessMono, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; if (name != value) onSelect(name) })
            }
        }
    }
}

/**
 * Create pull request sheet. Everything is read from git and GitHub first; the only writes are an
 * optional push of the reviewed branch and the pull request itself.
 */
@Composable
fun GitHubPullRequestDialog(container: AppContainer, fs: WorkspaceFs, projectName: String, sessionId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val publisher = container.githubRepositories
    var plan by remember { mutableStateOf<PullRequestPlan?>(null) }
    var base by remember { mutableStateOf<String?>(null) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var bodyEdited by rememberSaveable { mutableStateOf(false) }
    var titleEdited by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(false) }
    var includeChecks by rememberSaveable { mutableStateOf(sessionId != null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<PullRequestInfo?>(null) }
    var showCommits by rememberSaveable { mutableStateOf(false) }

    suspend fun description(current: PullRequestPlan): String {
        val checks = if (includeChecks && sessionId != null)
            runCatching { VerificationEvidence.collect(container.sessions.messages(sessionId)) }.getOrNull() else null
        return buildPullRequestBody(null, current.branch, checks)
    }
    fun act(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = container.github.safeMessage(e) }
            finally { busy = false }
        }
    }
    suspend fun load() {
        val next = publisher.preparePullRequest(fs, base)
        plan = next
        base = next.branch.base
        if (!titleEdited) title = suggestPullRequestTitle(next.branch)
        if (!bodyEdited) body = description(next)
    }
    LaunchedEffect(Unit) { act { load() } }

    GitHubDialog(stringResource(R.string.pr_create), busy, onDismiss, footer = {
        val current = plan
        if (created == null && current != null && current.existing == null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Button(onClick = { act { created = publisher.openPullRequest(current, title, body, draft) } },
                enabled = !busy && title.isNotBlank() && current.branch.pushed && current.branch.ahead > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Merge, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (draft) R.string.pr_create_draft else R.string.pr_create))
            }
        }
    }) {
        val current = plan
        val opened = created
        if (opened != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(56.dp), tint = LocalStatusColors.current.success)
                Text(stringResource(if (opened.draft) R.string.pr_created_draft_title else R.string.pr_created_title, opened.number),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(opened.title, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                BranchFlow(opened.head.ifBlank { current?.branch?.branch.orEmpty() }, opened.base.ifBlank { current?.branch?.base.orEmpty() }, opened.draft)
            }
            PullRequestLinkActions(opened.url, Modifier.fillMaxWidth())
            return@GitHubDialog
        }
        if (current == null) {
            if (busy) Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CatalogLoadingIndicator(Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.pr_loading), style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { message ->
                PullRequestBanner(Icons.Outlined.ErrorOutline, stringResource(R.string.pr_tool_failed), message, Tone.Error) {
                    OutlinedButton(onClick = { act { load() } }, enabled = !busy) { Text(stringResource(R.string.pr_retry)) }
                }
            }
            return@GitHubDialog
        }
        val branch = current.branch

        // Header: where the change goes, and how big it is.
        Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(branch.repository, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(projectName, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                BranchFlow(branch.branch, branch.base, draft)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pr_counts, branch.ahead, branch.files.size),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    if (branch.files.isNotEmpty()) {
                        Text("+${branch.additions}", style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.success)
                        Text("−${branch.deletions}", style = MaterialTheme.typography.labelMedium, color = scheme.error)
                    }
                }
            }
        }

        current.existing?.let { existing ->
            PullRequestBanner(Icons.Outlined.Merge, stringResource(R.string.pr_tool_existing, existing.number),
                stringResource(R.string.pr_existing_body, branch.branch), Tone.Info)
            Text(existing.title, style = MaterialTheme.typography.bodyLarge)
            PullRequestLinkActions(existing.url, Modifier.fillMaxWidth())
            return@GitHubDialog
        }

        error?.let { PullRequestBanner(Icons.Outlined.ErrorOutline, stringResource(R.string.pr_tool_failed), it, Tone.Error) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        if (branch.ahead == 0) {
            PullRequestBanner(Icons.Outlined.Commit, stringResource(R.string.pr_no_commits_title),
                stringResource(R.string.pr_no_commits_body, branch.branch, branch.base), Tone.Warning)
        } else if (!branch.pushed) {
            PullRequestBanner(Icons.Outlined.CloudUpload,
                stringResource(if (branch.remoteHead == null) R.string.pr_not_pushed_title else R.string.pr_out_of_date_title),
                stringResource(R.string.pr_push_body, branch.branch), Tone.Warning) {
                FilledTonalButton(onClick = { act {
                    val state = container.githubRepositories.inspect(fs)
                    container.githubRepositories.publish(fs, state, emptySet(), "", false)
                    notice = context.getString(R.string.pr_pushed)
                    load()
                } }, enabled = !busy) {
                    Icon(Icons.Outlined.CloudUpload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pr_push_branch))
                }
            }
        }
        if (branch.uncommitted > 0) PullRequestBanner(Icons.Outlined.WarningAmber,
            stringResource(R.string.pr_uncommitted_title, branch.uncommitted), stringResource(R.string.pr_uncommitted_body), Tone.Warning)
        if (branch.behind > 0) PullRequestBanner(Icons.Outlined.WarningAmber,
            stringResource(R.string.pr_behind_title, branch.base, branch.behind), stringResource(R.string.pr_behind_body), Tone.Info)

        BaseBranchPicker(base ?: branch.base, branches.ifEmpty { listOf(current.defaultBranch) }.let { list ->
            (listOf(current.defaultBranch) + list).distinct().filter { it != branch.branch }
        }, enabled = !busy, onOpen = {
            if (branches.isEmpty()) scope.launch {
                branches = runCatching { publisher.baseBranches(branch.repository) }.getOrDefault(listOf(current.defaultBranch))
            }
        }) { picked -> base = picked; act { load() } }
        OutlinedTextField(title, onValueChange = { title = it; titleEdited = true }, label = { Text(stringResource(R.string.pr_title_label)) },
            enabled = !busy, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, onValueChange = { body = it; bodyEdited = true }, label = { Text(stringResource(R.string.pr_description_label)) },
            enabled = !busy, minLines = 6, maxLines = 14, shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono), modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { act { body = description(current); bodyEdited = false } }, enabled = !busy) {
            Text(stringResource(R.string.pr_rewrite_description))
        }
        if (sessionId != null) SwitchRow(stringResource(R.string.pr_include_checks), stringResource(R.string.pr_include_checks_body),
            includeChecks, !busy) { checked -> includeChecks = checked; if (!bodyEdited) act { body = description(current) } }
        SwitchRow(stringResource(R.string.pr_draft_label), stringResource(R.string.pr_draft_body), draft, !busy) { draft = it }

        if (branch.commits.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { showCommits = !showCommits }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pr_commits_header) + " · ${branch.ahead}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (showCommits) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    stringResource(if (showCommits) R.string.ui_collapse else R.string.ui_expand), Modifier.size(20.dp))
            }
            AnimatedVisibility(showCommits) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    branch.commits.forEach { subject ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Commit, null, Modifier.size(16.dp).padding(top = 2.dp), tint = scheme.onSurfaceVariant)
                            Text(subject, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val more = branch.ahead - branch.commits.size
                    if (more > 0) Text(stringResource(R.string.pr_more, more), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
