package com.androidharness.app.ui.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.R

/** Play builds only. Summarises everything since the last uploaded store version. */
@Composable
internal fun WhatsNewDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = 440.dp),
            shape = RoundedCornerShape(32.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 20.dp,
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)),
                    ).padding(horizontal = 24.dp, vertical = 26.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = CircleShape, color = scheme.surface, tonalElevation = 4.dp, shadowElevation = 8.dp) {
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(30.dp), tint = scheme.primary)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.whats_new_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.whats_new_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onPrimaryContainer.copy(alpha = .78f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 328.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    WhatsNewSection(R.string.whats_new_added, scheme.primary, ADDED)
                    WhatsNewSection(R.string.whats_new_improved, scheme.tertiary, IMPROVED)
                    WhatsNewSection(R.string.whats_new_fixed, scheme.secondary, FIXED)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)
                        .heightIn(min = 54.dp),
                    shape = RoundedCornerShape(27.dp),
                ) {
                    Text(stringResource(R.string.whats_new_done), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun WhatsNewSection(title: Int, color: Color, items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
        }
        items.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.padding(top = 7.dp).size(6.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = .7f), CircleShape),
                )
                Text(
                    stringResource(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val ADDED = listOf(
    R.string.wn_languages,
    R.string.wn_plugins,
    R.string.wn_live_mode,
    R.string.wn_autocompact,
    R.string.wn_editor_files,
    R.string.wn_memory_search,
    R.string.wn_workspace_learn,
    R.string.wn_visuals_tips,
    R.string.wn_local_models,
    R.string.wn_side_steer_settings,
)

private val IMPROVED = listOf(
    R.string.wn_readable,
    R.string.wn_focus,
    R.string.wn_speed,
)

private val FIXED = listOf(
    R.string.wn_fixed_tools,
    R.string.wn_fixed_chat,
    R.string.wn_fixed_github,
)
