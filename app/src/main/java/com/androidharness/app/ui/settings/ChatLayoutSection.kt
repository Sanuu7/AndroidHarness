package com.androidharness.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ChatDensity
import com.androidharness.app.data.ChatLayoutStyle
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.ui.chat.answerBodyStyle
import com.androidharness.app.ui.theme.LocalChatLayout
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Chat spacing, line spacing and side margins (#127). Sliders drive a local draft so the
 * preview follows the thumb; the value is saved when the drag ends.
 */
@Composable
internal fun ChatLayoutSection(saved: ChatLayoutStyle, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var draft by remember(saved) { mutableStateOf(saved) }
    fun save(style: ChatLayoutStyle) { draft = style; scope.launch { repository.setChatLayout(style) } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.chat_layout_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.chat_layout_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (draft != ChatLayoutStyle()) TextButton(onClick = { save(ChatLayoutStyle()) }) {
                Text(stringResource(R.string.chat_layout_reset))
            }
        }

        ChatLayoutPreview(draft)

        Text(stringResource(R.string.chat_layout_density), style = MaterialTheme.typography.titleSmall)
        SettingsChoiceGroup(
            options = listOf(
                ChatDensity.COMPACT to stringResource(R.string.chat_density_compact),
                ChatDensity.DEFAULT to stringResource(R.string.chat_density_default),
                ChatDensity.COMFORTABLE to stringResource(R.string.chat_density_comfortable),
            ),
            selected = { it == draft.density },
            onSelect = { save(draft.copy(density = it)) },
        )

        val lineLabel = stringResource(R.string.chat_layout_line_spacing)
        LabeledSlider(lineLabel, stringResource(R.string.chat_layout_line_spacing_value, draft.lineSpacing),
            value = draft.lineSpacing, range = ChatLayoutStyle.LINE_SPACING_RANGE, steps = 7,
            onChange = { draft = draft.copy(lineSpacing = (it * 10).roundToInt() / 10f) },
            onDone = { save(draft) })

        val marginLabel = stringResource(R.string.chat_layout_side_margin)
        val m = ChatLayoutStyle.SIDE_MARGIN_RANGE
        LabeledSlider(marginLabel, stringResource(R.string.chat_layout_side_margin_value, draft.sideMargin),
            value = draft.sideMargin.toFloat(), range = m.first.toFloat()..m.last.toFloat(), steps = (m.last - m.first) / 2 - 1,
            onChange = { draft = draft.copy(sideMargin = it.roundToInt()) },
            onDone = { save(draft) })
    }
}

@Composable
private fun LabeledSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone,
            valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}

/** A miniature conversation drawn with the same spacing rules the chat screen uses. */
@Composable
private fun ChatLayoutPreview(style: ChatLayoutStyle) {
    val scheme = MaterialTheme.colorScheme
    val frame = RoundedCornerShape(20.dp)
    CompositionLocalProvider(LocalChatLayout provides style) {
        Column(
            Modifier.fillMaxWidth()
                .background(scheme.surface, frame)
                .border(1.dp, scheme.outlineVariant.copy(alpha = .6f), frame)
                .padding(horizontal = style.sideMargin.dp, vertical = 16.dp * style.density.scale),
            verticalArrangement = Arrangement.spacedBy(12.dp * style.density.scale),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(R.string.chat_layout_preview_question),
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimaryContainer,
                    modifier = Modifier.widthIn(max = 260.dp)
                        .background(scheme.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp))
            }
            Text(stringResource(R.string.chat_layout_preview_answer), style = answerBodyStyle(), color = scheme.onSurface)
            Text(stringResource(R.string.chat_layout_preview_composer),
                style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                    .border(1.dp, scheme.outlineVariant.copy(alpha = .55f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp))
        }
    }
}
