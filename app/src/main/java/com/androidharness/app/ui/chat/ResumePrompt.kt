package com.androidharness.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import com.androidharness.app.R
import com.androidharness.app.agent.RunRecovery
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

/**
 * Edits the text sent when a task resumes. Used for the app-wide default (reset returns to the
 * built-in prompt) and for one chat (reset returns to whatever the app default is).
 *
 * The field is pre-filled with the prompt that would be sent now, so the user edits real text
 * instead of a blank box. Saving text identical to [fallback] stores nothing, which keeps a chat
 * following the app setting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ResumePromptSheet(
    description: String,
    current: String,
    fallback: String,
    isCustom: Boolean,
    resetLabel: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
    onSaveAndResume: ((String?) -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val scheme = MaterialTheme.colorScheme
    val max = RunRecovery.MAX_PROMPT_CHARS
    val value = RunRecovery.customPrompt(text, fallback)
    // A pending reset is worth offering while the stored value is custom or the field has drifted.
    val canReset = isCustom || text.trim() != fallback.trim()
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = scheme.tertiaryContainer, modifier = Modifier.size(52.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.EditNote, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(26.dp))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.resume_prompt_title), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(max) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.resume_prompt_field_label)) },
                    minLines = 4,
                    maxLines = 10,
                    shape = MaterialTheme.shapes.large,
                    supportingText = {
                        Text(stringResource(R.string.resume_prompt_counter, text.length, max), Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    },
                )
                Text(stringResource(R.string.resume_prompt_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { text = fallback }, enabled = canReset) {
                        Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                        Text(resetLabel, Modifier.padding(start = 8.dp))
                    }
                    if (onSaveAndResume == null) {
                        Button(onClick = { onSave(value); onDismiss() }) { Text(stringResource(R.string.settings_action_save)) }
                    } else {
                        FilledTonalButton(onClick = { onSave(value); onDismiss() }) { Text(stringResource(R.string.settings_action_save)) }
                        Button(onClick = { onSaveAndResume(value); onDismiss() }) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.resume_prompt_save_resume), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card's resume action. With [onEditPrompt] it becomes an expressive split button whose menu
 * edits the prompt that will be sent; without it, it stays the plain tonal button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ResumeSplitButton(
    label: String,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    onEditPrompt: (() -> Unit)? = null,
    onResetPrompt: (() -> Unit)? = null,
) {
    if (onEditPrompt == null) {
        FilledTonalButton(onClick = onResume, modifier = modifier) { Text(label) }
        return
    }
    var menu by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (menu) 180f else 0f, label = "resume-menu-chevron")
    val moreDescription = stringResource(R.string.resume_prompt_more)
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Box(modifier) {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TonalTrailingButton(
                        checked = menu,
                        onCheckedChange = { menu = it },
                        modifier = Modifier.semantics { contentDescription = moreDescription },
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(SplitButtonDefaults.TrailingIconSize).rotate(rotation))
                    }
                },
            )
            ExpressiveDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ExpressiveDropdownMenuItem(
                    text = { Text(stringResource(R.string.resume_prompt_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { menu = false; onEditPrompt() },
                )
                onResetPrompt?.let { reset ->
                    ExpressiveDropdownMenuItem(
                        text = { Text(stringResource(R.string.resume_prompt_reset_chat)) },
                        leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                        onClick = { menu = false; reset() },
                    )
                }
            }
        }
    }
}

/** Shown on a recovery card while a custom prompt is in effect; tapping it opens the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomPromptChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(stringResource(R.string.resume_prompt_active_badge)) },
        leadingIcon = { Icon(Icons.Outlined.EditNote, null, Modifier.size(AssistChipDefaults.IconSize)) },
        shape = MaterialTheme.shapes.large,
    )
}
