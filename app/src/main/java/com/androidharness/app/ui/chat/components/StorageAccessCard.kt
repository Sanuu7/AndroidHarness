package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.StorageAccessReceipt
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.rememberStorageAccess

/**
 * The agent's storage access card. It grants nothing: the buttons open Android's own pages,
 * and the card turns into a confirmation once access exists, however it was granted.
 */
@Composable
internal fun StorageAccessCard(receipt: StorageAccessReceipt, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val access = rememberStorageAccess()
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        color = if (access.granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        AnimatedContent(access.granted, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "storage-card") { granted ->
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.FolderOff, null, Modifier.size(24.dp),
                        tint = if (granted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
                    Text(stringResource(if (granted) R.string.storage_card_title_granted else R.string.storage_card_title),
                        style = MaterialTheme.typography.titleMedium)
                }
                Text(if (granted) stringResource(R.string.storage_card_granted_body) else receipt.reason, style = MaterialTheme.typography.bodyMedium)
                if (!granted) {
                    receipt.path?.let {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
                            Text(it, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = access.request) { Text(stringResource(R.string.storage_card_all_files)) }
                        FilledTonalButton(onClick = { SystemGrants.openAppDetails(context) }) {
                            Text(stringResource(R.string.storage_card_app_permissions))
                        }
                    }
                    Text(stringResource(R.string.storage_card_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
