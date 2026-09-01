package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import com.androidharness.app.agent.ApprovalRequest
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.common.VisualDiffViewer
import com.androidharness.app.ui.theme.LocalStatusColors

/**
 * One shared skeleton for every "agent needs you" card (approvals, plans,
 * questions, environment installs): flat hairline card, quiet title row,
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
    ActionCard(
        title = "Approve this action?",
        icon = Icons.Outlined.Shield,
        actions = {
            TextButton(onClick = onDeny) { Text("Deny") }
            FilledTonalButton(onClick = { onApprove(true) }) { Text("Always") }
            Button(onClick = { onApprove(false) }) { Text("Allow") }
        },
    ) {
        Text(
            describeToolCall(approval.call),
            style = MaterialTheme.typography.bodyMedium,
        )
        approval.diffPreview?.let { diff ->
            if (diff.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                DiffView(diff)
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
    ActionCard(
        title = "Plan ready to run",
        actions = {
            TextButton(onClick = onDiscard) { Text("Discard") }
            Button(onClick = onApprove) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Approve & execute")
            }
        },
    ) {
        Text(
            plan.take(2_000),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * Interactive question card. Options render as full-width selectable rows,
 * single-choice answers fire instantly on tap (radio indicator), multi-select
 * toggles checkboxes and answers through a submit button that shows the pick
 * count. "Something else…" reveals a free-text field in every mode.
 */
@Composable
internal fun QuestionCard(
    question: QuestionRequest,
    onAnswer: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var selected by remember(question.callId) { mutableStateOf(setOf<Int>()) }
    var noteOpen by remember(question.callId) { mutableStateOf(question.options.isEmpty()) }
    var note by remember(question.callId) { mutableStateOf("") }
    val multi = question.multiSelect

    val answerText: () -> String = {
        val picks = selected.sorted().mapNotNull { question.options.getOrNull(it) }
        (picks + listOf(note.trim()).filter { it.isNotBlank() }).joinToString("; ")
    }
    val ready = selected.isNotEmpty() || note.isNotBlank()

    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (multi) "PICK ANY THAT APPLY" else if (question.options.isEmpty()) "FREE ANSWER" else "PICK ONE",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                question.question,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )

            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                question.options.forEachIndexed { idx, option ->
                    val isSel = idx in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                if (isSel) scheme.primary.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .clickable {
                                when {
                                    multi -> selected = if (isSel) selected - idx else selected + idx
                                    !noteOpen -> onAnswer(option)
                                    else -> selected = if (isSel) selected - idx else selected + idx
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                    ) {
                        if (multi) {
                            MultiCheck(checked = isSel)
                        } else {
                            RadioDot(checked = isSel)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSel) scheme.primary else scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (idx != question.options.lastIndex) {
                        HorizontalDivider(
                            color = scheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(start = 40.dp),
                        )
                    }
                }
            }

            if (noteOpen) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Type your answer…", style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { noteOpen = true }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Something else…",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            if (multi || noteOpen) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (ready) onAnswer(answerText()) },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selected.isNotEmpty()) "Send answer · ${selected.size} selected"
                        else "Send answer",
                    )
                }
            }
        }
    }
}

/** Radio indicator for single-choice rows. */
@Composable
private fun RadioDot(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .border(
                width = 2.dp,
                color = if (checked) scheme.primary else scheme.outlineVariant,
                shape = CircleShape,
            ),
    ) {
        if (checked) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(scheme.primary, CircleShape),
            )
        }
    }
}

/** Checkbox indicator for multi-select rows. */
@Composable
private fun MultiCheck(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .background(
                if (checked) scheme.primary else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .border(
                width = 2.dp,
                color = if (checked) scheme.primary else scheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            ),
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun EnvironmentInstallCard(
    request: com.androidharness.app.agent.EnvironmentRequest,
    envState: EnvState,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val repair = request.repair
    ActionCard(
        title = if (repair) {
            "Linux environment needs repair"
        } else {
            "Linux environment needed"
        },
        icon = Icons.Outlined.Terminal,
        actions = {
            if (!repair || envState is EnvState.Ready || envState is EnvState.Failed) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Button(onClick = onInstall) { Text(if (repair) "Repair" else "Install now") }
            }
        },
    ) {
        if (repair) {
            Text(
                "\"${request.missingTool}\" is missing from the installed Linux environment " +
                    "(the command failed with \"not found\").",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "Wanted to run: ${request.command.take(80)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Repair reinstalls the toolchain, then the agent re-runs the command.",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
        } else {
            Text(
                "The agent wants to run: ${request.command.take(80)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Required: ${request.hints.joinToString(" · ")}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
        }
        when (envState) {
            is EnvState.Downloading -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Downloading ${envState.pkg} (${envState.index + 1}/${envState.total})…",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            is EnvState.Installing -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Installing ${envState.pkg} (${envState.index + 1}/${envState.total})…",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            is EnvState.Preparing -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Resolving packages…",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
            }
            is EnvState.Failed -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Install failed: ${envState.message}",
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
