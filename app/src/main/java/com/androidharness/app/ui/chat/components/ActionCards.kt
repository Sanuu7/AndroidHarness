package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import com.androidharness.app.agent.isPhoneControl
import com.androidharness.app.agent.isRiskyPhoneControl
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.ApprovalRequest
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.ui.common.PackageInstallProgress
import com.androidharness.app.ui.common.packageProgress
import com.androidharness.app.ui.common.VisualDiffViewer
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

/**
 * One shared skeleton for every "agent needs you" card (approvals, plans,
 * environment installs). A clover badge slowly turns while the agent waits on
 * you, the card itself lifts to a brighter tonal surface with roomy corners,
 * and the actions sit as pill buttons on the right.
 */
@Composable
private fun ActionCard(
    title: String,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    state: WorkState = WorkState.Waiting,
    content: @Composable ColumnScope.() -> Unit,
) {
    CodeCard(modifier = modifier, accent = WorkState.Waiting, collapsedRadius = 28.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorphingStatusBadge(state, icon ?: Icons.Outlined.Shield, size = 40.dp, showOutcome = false)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                actions()
            }
        }
    }
}

private const val PHONE_FIRST_COMMAND = "First command:"
private const val PHONE_ONE_COMMAND = "\n\nCommand:"

@Composable
internal fun ApprovalCard(
    approval: ApprovalRequest,
    onApprove: (Boolean) -> Unit,
    onDeny: () -> Unit,
) {
    if (approval.call.name == com.androidharness.app.tools.AndroidSdkInstallTool.NAME) {
        AndroidBuildToolsApprovalCard(approval, onApprove = { onApprove(false) }, onDeny = onDeny)
        return
    }
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val isPkg = approval.call.name == "pkg_install" || approval.call.name == com.androidharness.app.tools.AndroidSdkInstallTool.NAME
    val isPullRequest = approval.call.name == "github_create_pull_request"
    // A schedule is approved one at a time, so there is no "Always" for it.
    val isReminder = approval.call.name in com.androidharness.app.tools.ReminderTools.confirmed
    val isSchedule = approval.call.name == com.androidharness.app.tools.AutomationCreateTool.NAME || isReminder
    // Phone control is allowed for the whole chat at once, so it has one Allow button.
    val isPhone = approval.isPhoneControl
    val isRiskyPhone = approval.isRiskyPhoneControl
    val scheme = MaterialTheme.colorScheme
    ActionCard(
        title = when {
            isPkg -> uiContext.getString(R.string.ui_install_linux_package)
            isPullRequest -> uiContext.getString(R.string.pr_approval_title)
            isReminder -> uiContext.getString(when (approval.call.name) {
                com.androidharness.app.tools.ReminderTools.ALARM -> R.string.alarm_approval_title
                com.androidharness.app.tools.ReminderTools.TIMER -> R.string.timer_approval_title
                else -> R.string.reminders_approval_title
            })
            isSchedule -> uiContext.getString(R.string.automation_approval_title)
            isRiskyPhone -> uiContext.getString(R.string.phone_control_risky_title)
            isPhone -> uiContext.getString(R.string.phone_control_approval_title)
            else -> uiContext.getString(R.string.ui_approve_this_action)
        },
        icon = when {
            isPkg -> Icons.Outlined.Inventory2
            isPullRequest -> Icons.Outlined.Merge
            isReminder -> Icons.Outlined.Alarm
            isSchedule -> Icons.Outlined.Schedule
            isRiskyPhone -> Icons.Outlined.Shield
            isPhone -> Icons.Outlined.PhoneAndroid
            else -> Icons.Outlined.Shield
        },
        iconTint = if (isRiskyPhone) scheme.error else if (isPkg) scheme.tertiary else scheme.primary,
        actions = {
            TextButton(onClick = onDeny) { Text(if (isPkg) uiContext.getString(R.string.ui_decline) else uiContext.getString(R.string.ui_deny)) }
            if (!isPkg && !isSchedule && !isPhone) {
                // Only approvals that are safe to keep are saved, anything else lasts for this run.
                val saved = com.androidharness.app.agent.GrantPolicy.canSave(approval.grantKey)
                FilledTonalButton(onClick = { onApprove(true) }) {
                    Text(uiContext.getString(if (saved) R.string.ui_always else R.string.approval_for_this_run))
                }
            }
            Button(onClick = { onApprove(false) }) {
                Text(uiContext.getString(when {
                    isRiskyPhone -> R.string.phone_control_allow_once
                    isPhone -> R.string.phone_control_allow
                    approval.call.name == com.androidharness.app.tools.ReminderTools.ALARM -> R.string.alarm_set
                    approval.call.name == com.androidharness.app.tools.ReminderTools.TIMER -> R.string.timer_start
                    isReminder -> R.string.reminders_set
                    else -> R.string.ui_allow
                }))
            }
        },
    ) {
        if (isPhone) {
            val preview = approval.diffPreview.orEmpty()
            val marker = if (PHONE_FIRST_COMMAND in preview) PHONE_FIRST_COMMAND else PHONE_ONE_COMMAND
            val command = preview.substringAfter(marker, "").trim()
            Text(
                preview.substringBefore(marker).trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            if (command.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                CodePanel(command, label = approval.call.name)
            }
            return@ActionCard
        }
        if (isPkg) {
            Surface(
                color = scheme.errorContainer.copy(alpha = 0.6f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        uiContext.getString(R.string.ui_warning_the_agent_is_about_to_download),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onErrorContainer,
                    )
                }
            }
        }
        if (isPullRequest) {
            com.androidharness.app.ui.github.PullRequestApprovalSummary(approval.call)
            return@ActionCard
        }
        Text(
            describeToolCall(approval.call),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        approval.diffPreview?.let { preview ->
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                if (isPkg) {
                    CodePanel(preview)
                } else {
                    DiffView(preview)
                }
            }
        }
        if (approval.diffPreview == null) {
            Spacer(Modifier.height(10.dp))
            CodePanel(prettyArgs(approval.call.argumentsJson), label = approval.call.name)
        }
    }
}

@Composable
internal fun PlanApprovalCard(
    plan: String,
    onApprove: () -> Unit,
    onDiscard: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember(plan) { mutableStateOf(false) }
    val longPlan = plan.length > 2_000
    ActionCard(
        title = uiContext.getString(R.string.ui_plan_ready_to_run),
        icon = Icons.Outlined.Checklist,
        actions = {
            TextButton(onClick = onDiscard) { Text(uiContext.getString(R.string.settings_action_discard)) }
            Button(onClick = onApprove) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(uiContext.getString(R.string.ui_approve_execute))
            }
        },
    ) {
        if (longPlan && !expanded) {
            Text(plan.take(2_000), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 8, overflow = TextOverflow.Ellipsis)
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(14.dp)) {
                        com.androidharness.app.ui.chat.MarkdownText(plan)
                    }
                }
            }
        }
        if (longPlan) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) uiContext.getString(R.string.ui_show_less) else uiContext.getString(R.string.ui_show_full_plan))
        }
    }
}

@Composable
internal fun EnvironmentInstallCard(
    request: com.androidharness.app.agent.EnvironmentRequest,
    envState: EnvState,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    installStarted: Boolean = false,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val repair = request.repair
    val installing = installStarted || envState.packageProgress() != null
    val progressState = if (installStarted && envState.packageProgress() == null && envState !is EnvState.Failed) EnvState.Preparing else envState
    ActionCard(
        title = if (installing) {
            if (repair) uiContext.getString(R.string.ui_repairing_linux_environment) else uiContext.getString(R.string.ui_installing_linux_environment)
        } else if (repair) {
            uiContext.getString(R.string.ui_linux_environment_needs_repair)
        } else {
            uiContext.getString(R.string.ui_linux_environment_needed)
        },
        icon = Icons.Outlined.Terminal,
        state = if (envState is EnvState.Failed) WorkState.Failed else if (installing) WorkState.Running else WorkState.Waiting,
        actions = {
            if (!installing) {
                TextButton(onClick = onSkip) { Text(uiContext.getString(R.string.ui_skip)) }
                Button(onClick = onInstall) { Text(if (repair) uiContext.getString(R.string.ui_repair) else uiContext.getString(R.string.ui_install_now)) }
            }
        },
    ) {
        if (repair) {
            Text(
                uiContext.getString(R.string.ui_is_missing_from_the_installed_linux_environment, (request.missingTool).toString()) +
                    uiContext.getString(R.string.ui_the_command_failed_with_not_found),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                uiContext.getString(R.string.ui_wanted_to_run, (request.command.take(80)).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                uiContext.getString(R.string.ui_repair_reinstalls_the_toolchain_then_the_agent),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
        } else {
            Text(
                uiContext.getString(R.string.ui_wanted_to_run, (request.command.take(80)).toString()) +
                    uiContext.getString(R.string.ui_the_command_failed_with_not_found),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                uiContext.getString(R.string.ui_required, (request.hints.joinToString(" · ")).toString()),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
        }
        when (progressState) {
            is EnvState.Downloading, is EnvState.Installing, is EnvState.Preparing -> {
                Spacer(Modifier.height(10.dp))
                PackageInstallProgress(progressState)
            }
            is EnvState.Failed -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    uiContext.getString(R.string.ui_install_failed, (progressState.message).toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
                )
            }
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// Unified diff rendering

@Composable
internal fun DiffView(diff: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(6.dp)) {
            VisualDiffViewer(
                diffText = diff,
                maxHeight = 260.dp,
                showFileHeader = false,
            )
        }
    }
}

/**
 * The agent asking to set up on-device Android builds. Shows what lands on the phone in plain
 * terms instead of a raw package list, plus where the SDK file comes from and its license.
 */
@Composable
private fun AndroidBuildToolsApprovalCard(approval: ApprovalRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val args = remember(approval.call.argumentsJson) {
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(approval.call.argumentsJson) as kotlinx.serialization.json.JsonObject }.getOrNull()
    }
    val plan = remember(args) { args?.let { runCatching { com.androidharness.app.tools.AndroidSdkInstallTool.plan(it) }.getOrNull() } }
    val reason = (args?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    ActionCard(
        title = androidx.compose.ui.res.stringResource(R.string.build_tools_card_title),
        icon = Icons.Outlined.Android,
        iconTint = scheme.tertiary,
        actions = {
            TextButton(onClick = onDeny) { Text(androidx.compose.ui.res.stringResource(R.string.build_tools_not_now)) }
            Button(onClick = onApprove) { Text(androidx.compose.ui.res.stringResource(R.string.build_tools_install)) }
        },
    ) {
        Text(reason ?: androidx.compose.ui.res.stringResource(R.string.build_tools_card_body),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (plan == null) {
            CodePanel(prettyArgs(approval.call.argumentsJson), label = approval.call.name)
            return@ActionCard
        }
        androidx.compose.material3.Surface(color = scheme.surfaceContainerHighest.copy(alpha = .6f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidBuildToolsComponents(plan.gradle, plan.api)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.build_tools_download_note),
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

/** One row per thing the setup installs; reused by the Terminal settings panel. */
@Composable
internal fun AndroidBuildToolsComponents(gradle: Boolean, api: Int, installed: (String) -> Boolean? = { null }) {
    val rows = buildList {
        add(Triple("openjdk-21", R.string.build_tools_java, R.string.build_tools_java_desc))
        add(Triple("d8", R.string.build_tools_d8, R.string.build_tools_d8_desc))
        add(Triple("aapt2", R.string.build_tools_aapt2, R.string.build_tools_aapt2_desc))
        add(Triple("apksigner", R.string.build_tools_apksigner, R.string.build_tools_apksigner_desc))
        if (gradle) add(Triple("gradle", R.string.build_tools_gradle, R.string.build_tools_gradle_desc))
    }
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    @Composable fun Line(title: String, desc: String, done: Boolean?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (done == true) Icons.Outlined.CheckCircle else Icons.Outlined.Inventory2, null, Modifier.size(18.dp),
                tint = if (done == true) success else scheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
    rows.forEach { (pkg, title, desc) ->
        Line(androidx.compose.ui.res.stringResource(title), androidx.compose.ui.res.stringResource(desc), installed(pkg))
    }
    Line(androidx.compose.ui.res.stringResource(R.string.build_tools_platform, api),
        androidx.compose.ui.res.stringResource(R.string.build_tools_platform_desc), installed("platform"))
}
