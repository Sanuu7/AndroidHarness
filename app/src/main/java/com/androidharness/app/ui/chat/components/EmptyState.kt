package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.theme.*

import androidx.compose.material3.OutlinedButton

import androidx.compose.foundation.layout.Arrangement

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import kotlinx.coroutines.delay
import com.androidharness.app.ui.common.Button

/**
 * First-run / new-conversation screen. With a provider it hands off to
 * [ChatEmptyState] for both Chat and Code; without one it shows a single
 * call-to-action button instead of instructions to hunt through menus.
 */
@Composable
internal fun EmptyState(
    hasProvider: Boolean,
    onSuggestion: (String) -> Unit,
    onAddProvider: () -> Unit,
    chatOnly: Boolean = false,
    agent: com.androidharness.app.data.ChatAgentProfile? = null,
    agentSetup: (@Composable () -> Unit)? = null,
    /** Fill the chat list's viewport (fillParentMaxHeight) so content can center. */
    modifier: Modifier = Modifier,
    /** Chat starters that need the user's words go to the composer instead of sending. */
    onStarter: (String) -> Unit = onSuggestion,
    /** Chat hides its starters once the composer has text. */
    startersVisible: Boolean = true,
) {
    if (hasProvider) {
        ChatEmptyState(agent, agentSetup, startersVisible, onSend = onSuggestion, onPrefill = onStarter,
            modifier = modifier, code = !chatOnly)
        return
    }
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.fillMaxWidth().fillMaxHeight()) {
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(top = 24.dp, start = 8.dp, end = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (chatOnly && agent != null) {
                    AgentAvatar(agent.avatar)
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    uiContext.getString(R.string.ui_connect_a_provider),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    uiContext.getString(R.string.ui_add_an_api_key_for_openai_anthropic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(26.dp))
                Button(onClick = onAddProvider) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiContext.getString(R.string.settings_providers_add))
                }
            }
        }
    }
}

// Verified creator profile: Sanuu, creator of AndroidHarness.
internal const val DONATION_URL = "https://buymeacoffee.com/sanuj7"
