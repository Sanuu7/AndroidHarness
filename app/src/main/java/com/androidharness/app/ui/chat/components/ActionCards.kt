package com.androidharness.app.ui.chat.components

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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors

/**
 * One shared skeleton for every "agent needs you" card (approvals, plans,
 * questions, environment installs): flat hairline card, quiet title row,
 * content, and a right-aligned action row with a single filled accent button.
 * The old design gave each card its own colors, icons and elevation — the
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(
    question: QuestionRequest,
    onAnswer: (String) -> Unit,
) {
    var freeText by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    ActionCard(
        title = question.question,
        actions = {
            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Answer…") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = { if (freeText.isNotBlank()) onAnswer(freeText) },
                enabled = freeText.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send answer",
                    tint = if (freeText.isNotBlank()) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (question.options.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                question.options.forEach { option ->
                    Surface(
                        color = scheme.surface,
                        contentColor = scheme.onSurface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                        onClick = { onAnswer(option) },
                    ) {
                        Text(
                            option,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
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
    ActionCard(
        title = "Linux environment needed",
        icon = Icons.Outlined.Terminal,
        actions = {
            if (envState is EnvState.NotInstalled || envState is EnvState.Failed) {
                TextButton(onClick = onSkip) { Text("Use toybox") }
                Button(onClick = onInstall) { Text("Install now") }
            }
        },
    ) {
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
    val lines = remember(diff) { diff.lines() }
    val scheme = MaterialTheme.colorScheme
    val addBg = LocalStatusColors.current.success.copy(alpha = 0.14f)
    val delBg = scheme.error.copy(alpha = 0.14f)
    val hunkColor = scheme.primary
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildDiffAnnotated(lines, addBg, delBg, hunkColor),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(10.dp)
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

private fun buildDiffAnnotated(
    lines: List<String>,
    addBg: Color,
    delBg: Color,
    hunkColor: Color,
): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append('\n')
        when (line.firstOrNull()) {
            '+' -> withStyle(SpanStyle(background = addBg)) { append(line) }
            '-' -> withStyle(SpanStyle(background = delBg)) { append(line) }
            '@' -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hunkColor)) { append(line) }
            else -> append(line)
        }
    }
}
