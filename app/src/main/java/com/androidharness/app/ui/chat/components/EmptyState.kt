package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.HarnessMark
import kotlinx.coroutines.delay

/**
 * First-run / new-chat screen.
 *
 * One mark, one headline, one supporting line, and a single quiet list of
 * suggestions with hairline separators — no hero squircles, color pills, or
 * status badges. Without a provider there is a real call-to-action button
 * instead of instructions to hunt through menus.
 */
@Composable
internal fun EmptyState(
    hasProvider: Boolean,
    onSuggestion: (String) -> Unit,
    onAddProvider: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val suggestions = listOf(
        Icons.Outlined.Terminal to "Create a Python hello world and run it",
        Icons.Outlined.Folder to "Explore my workspace and summarize it",
        Icons.Outlined.Code to "Build a simple HTML landing page",
        Icons.Outlined.CreateNewFolder to "Set up a new project with a README",
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(40)
        entered = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .padding(top = 72.dp, start = 8.dp, end = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HarnessMark(size = 52.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            if (hasProvider) "What do you want to build?" else "Connect a provider",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (hasProvider) "Your agent can read, write, search and run code in this workspace."
            else "Add an API key for OpenAI, Anthropic or Gemini to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hasProvider) {
            Spacer(Modifier.height(28.dp))
            Surface(
                color = scheme.surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Column {
                    suggestions.forEachIndexed { index, (icon, suggestion) ->
                        AnimatedVisibility(
                            visible = entered,
                            enter = fadeIn(tween(200, delayMillis = 90 * index)) +
                                slideInVertically(tween(240, delayMillis = 90 * index)) { it / 6 },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSuggestion(suggestion) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(scheme.surfaceContainerHigh),
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = scheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    suggestion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        if (index != suggestions.lastIndex) {
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 56.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(26.dp))
            Button(onClick = onAddProvider) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add provider")
            }
        }
    }
}
