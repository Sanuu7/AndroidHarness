package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ProgressBarStyle
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import com.androidharness.app.ui.theme.LocalProgressBarStyle
import com.androidharness.app.ui.common.RadioButton

@Composable
internal fun ProgressBarStyleSection(selectedStyle: ProgressBarStyle, onSelect: (ProgressBarStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_anchor_progress_bar_style), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_progress_bar_subtitle),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            if (maxWidth >= 300.dp * LocalDensity.current.fontScale) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProgressBarStyle.entries.forEach { style ->
                        ProgressStyleChoice(style, selectedStyle == style, { onSelect(style) }, Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressBarStyle.entries.forEach { style ->
                        ProgressStyleChoice(style, selectedStyle == style, { onSelect(style) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressStyleChoice(style: ProgressBarStyle, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp),
        color = if (isSelected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        contentColor = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface,
        border = BorderStroke(1.dp, if (isSelected) scheme.primary else scheme.outlineVariant),
        modifier = modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)) {
        Column(Modifier.heightIn(min = 80.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (style == ProgressBarStyle.FLAT) uiContext.getString(R.string.ui_flat) else uiContext.getString(R.string.ui_squiggly), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                RadioButton(selected = isSelected, onClick = null)
            }
            CompositionLocalProvider(LocalProgressBarStyle provides style) {
                AppLinearProgressIndicator(Modifier.fillMaxWidth().clearAndSetSemantics {}, trackColor = scheme.outlineVariant)
            }
        }
    }
}
