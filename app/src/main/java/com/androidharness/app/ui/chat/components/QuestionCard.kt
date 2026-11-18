@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.QuestionAutoContinue
import com.androidharness.app.agent.QuestionRequest
import kotlinx.coroutines.delay
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton

/**
 * A pending question stays in the conversation; selecting an option never submits it.
 * [autoContinueAt] is when the app answers with the recommended option, or null while it will not.
 */
@Composable
internal fun QuestionCard(question: QuestionRequest, onAnswer: (String) -> Unit, autoContinueAt: Long? = null) {
    // Some providers reuse call IDs. A changed question must not inherit the previous answer.
    key(question.callId, question.question, question.options, question.multiSelect) {
        QuestionCardBody(question, onAnswer, autoContinueAt)
    }
}

@Composable
private fun RecommendedBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
            .padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(
            stringResource(R.string.question_recommended),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** Selection mark: an empty ring that springs into a filled cookie (single) or rounded square (multi) with a check. */
@Composable
private fun ChoiceMark(checked: Boolean, multi: Boolean, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateFloatAsState(if (checked) 1f else 0f, bouncySpec(), label = "choice fill")
    val ring = if (enabled) scheme.outline else scheme.outline.copy(alpha = 0.4f)
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).border(2.dp, ring, if (multi) RoundedCornerShape(7.dp) else CircleShape))
        Box(
            Modifier
                .size(26.dp)
                .graphicsLayer { scaleX = fill; scaleY = fill; rotationZ = (1f - fill) * -90f }
                .background(scheme.primary, if (multi) MaterialShapes.Square.toShape() else MaterialShapes.Cookie6Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary)
        }
    }
}

@Composable
private fun QuestionCardBody(question: QuestionRequest, onAnswer: (String) -> Unit, autoContinueAt: Long?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var selected by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var writtenAnswer by rememberSaveable { mutableStateOf("") }
    var writing by rememberSaveable { mutableStateOf(question.options.isEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(autoContinueAt, submitted) {
        while (autoContinueAt != null && !submitted) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val answer = (selected.sorted().mapNotNull(question.options::getOrNull) +
        listOf(if (writing) writtenAnswer.trim() else "").filter(String::isNotBlank)).joinToString("; ")

    CodeCard(accent = WorkState.Waiting, collapsedRadius = 28.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MorphingStatusBadge(if (submitted) WorkState.Done else WorkState.Waiting,
                    Icons.Outlined.ChatBubbleOutline, size = 40.dp, showOutcome = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(uiContext.getString(R.string.ui_question_for_you), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, color = scheme.tertiary)
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
                Text(question.question, style = MaterialTheme.typography.titleLargeEmphasized,
                    modifier = Modifier.semantics { heading() })
            }

            if (question.options.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().let { if (question.multiSelect) it else it.selectableGroup() },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val last = question.options.lastIndex
                    question.options.forEachIndexed { index, option ->
                        val checked = index in selected
                        val choose = {
                            if (!question.multiSelect) writing = false
                            selected = if (!question.multiSelect) listOf(index)
                                else if (checked) selected - index else selected + index
                        }
                        // Connected list: outer corners round, inner corners tight. A chosen
                        // option springs to fully round corners and lifts to the accent tone.
                        val outer = 22.dp
                        val inner = 6.dp
                        val top by animateDpAsState(if (checked || index == 0) outer else inner, bouncySpec(), label = "option top")
                        val bottom by animateDpAsState(if (checked || index == last) outer else inner, bouncySpec(), label = "option bottom")
                        val container by animateColorAsState(
                            if (checked) scheme.secondaryContainer else scheme.surfaceContainerLowest, label = "option color",
                        )
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val scale by animateFloatAsState(if (pressed) 0.97f else 1f, bouncySpec(), label = "option press")
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                                .background(container)
                                .heightIn(min = 56.dp)
                                .let {
                                    if (question.multiSelect) it.toggleable(checked, interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                                        enabled = !submitted, role = Role.Checkbox, onValueChange = { choose() })
                                    else it.selectable(checked, interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                                        enabled = !submitted, role = Role.RadioButton, onClick = choose)
                                }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ChoiceMark(checked, question.multiSelect, !submitted)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(option, style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                                    color = if (checked) scheme.onSecondaryContainer else scheme.onSurface)
                                if (index in question.recommended) RecommendedBadge()
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
                        shape = RoundedCornerShape(20.dp),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            } else {
                FilledTonalButton(onClick = { if (!question.multiSelect) selected = emptyList(); writing = true }, enabled = !submitted,
                    modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (question.multiSelect && selected.isNotEmpty()) uiContext.getString(R.string.ui_add_a_note) else uiContext.getString(R.string.ui_write_your_own_answer))
                }
            }

            if (autoContinueAt != null && !submitted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.background(scheme.surfaceContainerHighest, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.question_auto_continue, QuestionAutoContinue.remaining(autoContinueAt, now)),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { if (answer.isNotBlank() && !submitted) { submitted = true; onAnswer(answer) } },
                enabled = answer.isNotBlank() && !submitted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(if (submitted) uiContext.getString(R.string.ui_answer_sent) else uiContext.getString(R.string.ui_send_answer),
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Icon(if (submitted) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
            }
        }
    }
}
