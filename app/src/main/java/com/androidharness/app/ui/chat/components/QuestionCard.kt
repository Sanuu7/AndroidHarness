package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

/** A pending question stays in the conversation; selecting an option never submits it. */
@Composable
internal fun QuestionCard(question: QuestionRequest, onAnswer: (String) -> Unit) {
    // Some providers reuse call IDs. A changed question must not inherit the previous answer.
    key(question.callId, question.question, question.options, question.multiSelect) {
        QuestionCardBody(question, onAnswer)
    }
}

@Composable
private fun QuestionCardBody(question: QuestionRequest, onAnswer: (String) -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var selected by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var writtenAnswer by rememberSaveable { mutableStateOf("") }
    var writing by rememberSaveable { mutableStateOf(question.options.isEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val answer = (selected.sorted().mapNotNull(question.options::getOrNull) +
        listOf(if (writing) writtenAnswer.trim() else "").filter(String::isNotBlank)).joinToString("; ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = scheme.primary.copy(alpha = .08f)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.padding(8.dp).size(18.dp), tint = scheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(uiContext.getString(R.string.ui_question_for_you), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                    Text(
                        when {
                            question.options.isEmpty() -> uiContext.getString(R.string.ui_write_your_answer_below)
                            question.multiSelect && selected.isNotEmpty() -> uiContext.getString(R.string.ui_selected_select_all_that_apply, (selected.size).toString())
                            question.multiSelect -> uiContext.getString(R.string.ui_select_all_that_apply)
                            else -> uiContext.getString(R.string.ui_choose_one_or_write_your_own)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            SelectionContainer {
                Text(question.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() })
            }

            if (question.options.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().let { if (question.multiSelect) it else it.selectableGroup() },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    question.options.forEachIndexed { index, option ->
                        val checked = index in selected
                        val choose = {
                            if (!question.multiSelect) writing = false
                            selected = if (!question.multiSelect) listOf(index)
                                else if (checked) selected - index else selected + index
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (checked) scheme.primary.copy(alpha = .06f) else scheme.surface,
                            border = BorderStroke(1.dp, if (checked) scheme.primary.copy(alpha = .65f) else scheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .let {
                                        if (question.multiSelect) it.toggleable(checked, enabled = !submitted,
                                            role = Role.Checkbox, onValueChange = { choose() })
                                        else it.selectable(checked, enabled = !submitted, role = Role.RadioButton, onClick = choose)
                                    }.padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (question.multiSelect) Checkbox(checked, null, Modifier.size(24.dp), enabled = !submitted)
                                else RadioButton(checked, null, Modifier.size(24.dp), enabled = !submitted)
                                Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = if (checked) scheme.primary else scheme.onSurface)
                            }
                        }
                    }
                }
            }

            if (writing) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected.isEmpty()) uiContext.getString(R.string.ui_your_answer) else uiContext.getString(R.string.ui_additional_detail), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        if (question.options.isNotEmpty()) IconButton(
                            onClick = { writtenAnswer = ""; writing = false }, enabled = !submitted,
                            modifier = Modifier.size(48.dp),
                        ) { Icon(Icons.Filled.Close, uiContext.getString(R.string.ui_remove_written_answer), Modifier.size(18.dp)) }
                    }
                    OutlinedTextField(
                        value = writtenAnswer,
                        onValueChange = { writtenAnswer = it },
                        enabled = !submitted,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = uiContext.getString(R.string.ui_written_answer) },
                        placeholder = { Text(if (selected.isEmpty()) uiContext.getString(R.string.ui_write_your_answer) else uiContext.getString(R.string.ui_add_a_little_more_context)) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(12.dp),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            } else {
                TextButton(onClick = { if (!question.multiSelect) selected = emptyList(); writing = true }, enabled = !submitted,
                    contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (question.multiSelect && selected.isNotEmpty()) uiContext.getString(R.string.ui_add_a_note) else uiContext.getString(R.string.ui_write_your_own_answer))
                }
            }

            Button(
                onClick = { if (answer.isNotBlank() && !submitted) { submitted = true; onAnswer(answer) } },
                enabled = answer.isNotBlank() && !submitted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(if (submitted) uiContext.getString(R.string.ui_answer_sent) else uiContext.getString(R.string.ui_send_answer))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}
