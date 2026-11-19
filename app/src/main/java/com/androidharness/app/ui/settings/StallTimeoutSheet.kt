package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.StreamRetrier
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

/** Choices offered before Custom, in seconds. */
internal val STALL_TIMEOUT_CHOICES = listOf(90, 180, 300, 600, 900, 1_200, StreamRetrier.STALL_TIMEOUT_NEVER)

/** "15 min", "90s" or "Never" for a stall window in seconds. */
@Composable
internal fun stallTimeoutLabel(seconds: Int): String =
    if (seconds == StreamRetrier.STALL_TIMEOUT_NEVER) stringResource(R.string.stall_timeout_never)
    else StreamRetrier.formatStall(seconds * 1_000L)

/**
 * Picks how long a stream may stay silent before the run pauses. Shared by
 * Settings and the paused task card, so both save the same app setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StallTimeoutSheet(
    currentSeconds: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** Label for the pick action; the paused card uses it to say the task resumes. */
    confirmLabel: String = stringResource(R.string.settings_action_save),
) {
    val presets = STALL_TIMEOUT_CHOICES
    var custom by rememberSaveable { mutableStateOf(currentSeconds !in presets) }
    var minutesText by rememberSaveable {
        mutableStateOf(if (currentSeconds in presets) "" else (currentSeconds / 60.0).let {
            if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)
        })
    }
    var selected by rememberSaveable { mutableStateOf(currentSeconds.takeIf { it in presets } ?: StreamRetrier.DEFAULT_STALL_TIMEOUT_SECONDS) }
    val customSeconds = minutesText.replace(',', '.').toDoubleOrNull()?.let { (it * 60).toInt() }
    val customValid = customSeconds != null &&
        customSeconds in StreamRetrier.MIN_STALL_TIMEOUT_SECONDS..StreamRetrier.MAX_STALL_TIMEOUT_SECONDS
    val pick = if (custom) customSeconds?.takeIf { customValid } else selected

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stall_timeout_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.stall_timeout_detail), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                presets.forEach { seconds ->
                    StallChoice(
                        title = stallTimeoutLabel(seconds) +
                            if (seconds == StreamRetrier.DEFAULT_STALL_TIMEOUT_SECONDS) " · " + stringResource(R.string.stall_timeout_default) else "",
                        selected = !custom && selected == seconds,
                        onClick = { custom = false; selected = seconds },
                    )
                }
                StallChoice(stringResource(R.string.stall_timeout_custom), custom, onClick = { custom = true })
            }
            if (custom) OutlinedTextField(
                value = minutesText,
                onValueChange = { minutesText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) },
                singleLine = true,
                label = { Text(stringResource(R.string.stall_timeout_minutes)) },
                isError = minutesText.isNotEmpty() && !customValid,
                supportingText = { Text(stringResource(R.string.stall_timeout_range)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
                TextButton(enabled = pick != null, onClick = { pick?.let(onPick) }) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
private fun StallChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}
