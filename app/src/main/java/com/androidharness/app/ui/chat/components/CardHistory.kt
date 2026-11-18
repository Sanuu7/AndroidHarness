package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key as compositionKey
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Small groups retain their natural layout; large histories only compose visible rows. */
@Composable
internal fun <T> CardHistory(
    entries: List<T>,
    key: (T) -> String,
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable (T) -> Unit,
) {
    if (entries.size <= 12) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            entries.forEach { entry -> compositionKey(key(entry)) { content(entry) } }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .65f).dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(entries, key = key) { content(it) }
        }
    }
}
