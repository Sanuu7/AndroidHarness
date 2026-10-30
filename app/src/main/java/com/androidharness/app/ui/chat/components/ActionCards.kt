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
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * One shared skeleton for every "agent needs you" card (approvals, plans,
 * environment installs): flat hairline card, quiet title row,
 * content, and a right-aligned action row with a single filled accent button.
 * The old design gave each card its own colors, icons and elevation, the
 *sameness here is what makes the feed readable.
 */
@Composable
private fun ActionCard(
    title: String,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = iconTint)
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
            }
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(14.dp))
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

@Composable
internal fun ApprovalCard(
    approval: ApprovalRequest,
    onApprove: (Boolean) -> Unit,
    onDeny: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val isPkg = approval.call.name == "pkg_install" || approval.call.name == com.androidharness.app.tools.AndroidSdkInstallTool.NAME
    val isPullRequest = approval.call.name == "github_create_pull_request"
    val scheme = MaterialTheme.colorScheme
    ActionCard(
        title = when {
            isPkg -> uiContext.getString(R.string.ui_install_linux_package)
            isPullRequest -> uiContext.getString(R.string.pr_approval_title)
            else -> uiContext.getString(R.string.ui_approve_this_action)
        },
        icon = when {
            isPkg -> Icons.Outlined.Inventory2
            isPullRequest -> Icons.Outlined.Merge
            else -> Icons.Outlined.Shield
        },
        iconTint = if (isPkg) scheme.tertiary else scheme.primary,
        actions = {
            TextButton(onClick = onDeny) { Text(if (isPkg) uiContext.getString(R.string.ui_decline) else uiContext.getString(R.string.ui_deny)) }
            if (!isPkg) {
                FilledTonalButton(onClick = { onApprove(true) }) { Text(uiContext.getString(R.string.ui_always)) }
            }
            Button(onClick = { onApprove(false) }) { Text(uiContext.getString(R.string.ui_allow)) }
        },
    ) {
        if (isPkg) {
            Surface(
                color = scheme.errorContainer.copy(alpha = 0.25f),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
        )
        approval.diffPreview?.let { preview ->
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                if (isPkg) {
                    MonoBlock(preview)
                } else {
                    DiffView(preview)
                }
            }
        }
        if (approval.diffPreview == null) {
            Spacer(Modifier.height(10.dp))
            MonoBlock(prettyArgs(approval.call.argumentsJson))
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
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    com.androidharness.app.ui.chat.MarkdownText(plan)
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
    VisualDiffViewer(
        diffText = diff,
        maxHeight = 260.dp,
        showFileHeader = false,
    )
}
