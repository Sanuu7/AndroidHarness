package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.CheckStatus
import com.androidharness.app.agent.VerificationCheck
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.theme.fastEffectsSpec

private fun CheckStatus.work(): WorkState = when (this) {
    CheckStatus.PASSED -> WorkState.Done
    CheckStatus.FAILED -> WorkState.Failed
    CheckStatus.UNKNOWN -> WorkState.Idle
    CheckStatus.STALE -> WorkState.Waiting
}

/** Build, test and lint receipt; each recorded command opens as its own terminal panel. */
@Composable
internal fun VerificationCard(checks: List<VerificationCheck>) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable(checks) { mutableStateOf(false) }
    val failed = checks.count { it.status == CheckStatus.FAILED }
    val passed = checks.isNotEmpty() && checks.all { it.status == CheckStatus.PASSED }
    val state = when {
        failed > 0 -> WorkState.Failed
        passed -> WorkState.Done
        else -> WorkState.Idle
    }
    val label = { status: CheckStatus ->
        when (status) {
            CheckStatus.PASSED -> uiContext.getString(R.string.ui_passed)
            CheckStatus.FAILED -> uiContext.getString(R.string.ui_failed_64)
            CheckStatus.UNKNOWN -> uiContext.getString(R.string.ui_unconfirmed)
            CheckStatus.STALE -> uiContext.getString(R.string.ui_recheck_needed)
        }
    }
    CodeCard(
        onClick = { expanded = !expanded },
        expanded = expanded,
        accent = state.takeIf { it == WorkState.Failed },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        CodeCardHeader(
            badge = {
                MorphingStatusBadge(
                    state,
                    when (state) {
                        WorkState.Failed -> Icons.Outlined.ErrorOutline
                        WorkState.Done -> Icons.Outlined.Verified
                        else -> Icons.Outlined.HelpOutline
                    },
                    showOutcome = false,
                )
            },
            title = if (checks.isEmpty()) uiContext.getString(R.string.ui_verification_checks_not_run)
                else uiContext.getString(R.string.ui_verification).trimEnd(' ', '·', ':'),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    checks.groupingBy { it.status }.eachCount().forEach { (status, count) ->
                        StatusPill("$count", status.work())
                    }
                    ExpandChevron(expanded, null)
                }
            },
        )
        AnimatedVisibility(
            expanded,
            enter = expandVertically(animationSpec = bouncySpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                if (checks.isEmpty()) Text(
                    uiContext.getString(R.string.ui_no_build_test_or_lint_result_recorded),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                checks.forEach { check ->
                    CodePanel(
                        check.command,
                        label = label(check.status),
                        prompt = "$",
                        tone = if (check.status == CheckStatus.FAILED) WorkState.Failed else null,
                    )
                }
            }
        }
    }
}
