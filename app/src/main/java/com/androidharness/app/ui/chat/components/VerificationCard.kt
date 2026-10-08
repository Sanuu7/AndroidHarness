package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.CheckStatus
import com.androidharness.app.agent.VerificationCheck
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors

@Composable
internal fun VerificationCard(checks: List<VerificationCheck>) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalStatusColors.current
    Surface(modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Verification", style = MaterialTheme.typography.labelLarge)
            if (checks.isEmpty()) Text("Not run: no build, test, or lint result recorded for this turn.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            checks.forEach { check ->
                val label = when (check.status) {
                    CheckStatus.PASSED -> "Passed"
                    CheckStatus.FAILED -> "Failed"
                    CheckStatus.UNKNOWN -> "Unconfirmed"
                    CheckStatus.STALE -> "Recheck needed"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = when (check.status) {
                    CheckStatus.PASSED -> colors.success
                    CheckStatus.FAILED -> scheme.error
                    else -> scheme.onSurfaceVariant
                })
                Text(check.command, fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall)
            }
            if (checks.isNotEmpty()) Text("Recorded command outcomes, not a guarantee of correctness.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
