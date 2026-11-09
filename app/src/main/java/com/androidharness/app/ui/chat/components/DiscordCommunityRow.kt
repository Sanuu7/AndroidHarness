package com.androidharness.app.ui.chat.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R

internal const val DISCORD_INVITE_URL = "https://discord.gg/NdC5K3XeWF"

// Discord's brand blurple, the background its own mark is drawn on.
private val DiscordBlurple = Color(0xFF5865F2)

/** Row that opens the AndroidHarness Discord invite in the user's browser or the Discord app. */
@Composable
internal fun DiscordCommunityRow() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val noBrowser = stringResource(R.string.discord_no_browser)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.discord_open_invite)) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, noBrowser, Toast.LENGTH_LONG).show()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(DiscordBlurple),
        ) {
            Icon(
                painterResource(R.drawable.ic_discord),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.discord_join_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.discord_join_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}
