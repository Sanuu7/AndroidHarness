package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * Message composer: a flat hairline-outlined pill.
 *
 * The circular action button is the one strong accent on the screen —
 * accent when there's text to send, red while the agent runs (tap = stop),
 * neutral when empty. Send ↔ stop morphs with a fast crossfade+scale.
 */
@Composable
internal fun MessageComposer(
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val fxSpec = defaultEffectsSpec<Float>()
    val actionColor by animateColorAsState(
        targetValue = when {
            busy -> scheme.error
            text.isNotBlank() -> scheme.primary
            else -> scheme.surfaceContainerHighest
        },
        animationSpec = fastEffectsSpec(),
        label = "composer action color",
    )
    val actionContentColor = when {
        busy -> scheme.onError
        text.isNotBlank() -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val actionScale by animateFloatAsState(
        targetValue = if (busy || text.isNotBlank()) 1f else 0.94f,
        animationSpec = fastEffectsSpec(),
        label = "composer action scale",
    )

    Surface(color = scheme.surface) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 2.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            ) {
                IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = "Attach image",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onTextChange(it)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    "Message your agent…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    maxLines = 5,
                )
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = actionColor,
                    contentColor = actionContentColor,
                    modifier = Modifier
                        .size(40.dp)
                        .scale(actionScale),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        IconButton(
                            enabled = busy || text.isNotBlank(),
                            onClick = {
                                if (busy) onStop()
                                else if (text.isNotBlank()) {
                                    onSend(text)
                                    text = ""
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            AnimatedContent(
                                targetState = busy,
                                transitionSpec = {
                                    (fadeIn(fxSpec) + scaleIn(fxSpec, initialScale = 0.7f))
                                        .togetherWith(fadeOut(fxSpec) + scaleOut(fxSpec, targetScale = 0.7f))
                                },
                                label = "composer action",
                            ) { running ->
                                Icon(
                                    if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = if (running) "Stop" else "Send",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
