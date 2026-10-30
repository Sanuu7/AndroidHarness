package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.common.IconButton

@Composable
internal fun FindInChatBar(
    query: String,
    onQueryChange: (String) -> Unit,
    position: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = scheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(
                            stringResource(R.string.ui_find_in_this_chat),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        inner()
                    }
                },
            )
            if (query.isNotBlank()) {
                Text(
                    if (total > 0) stringResource(R.string.ui_find_position, (position + 1).coerceAtLeast(1).toString(), total.toString())
                    else stringResource(R.string.ui_no_matches),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (total > 0) scheme.onSurfaceVariant else scheme.error,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onPrevious, enabled = total > 0) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.ui_previous_match), Modifier.size(20.dp))
            }
            IconButton(onClick = onNext, enabled = total > 0) {
                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ui_next_match), Modifier.size(20.dp))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, stringResource(R.string.settings_action_close), Modifier.size(20.dp))
            }
        }
    }
}
