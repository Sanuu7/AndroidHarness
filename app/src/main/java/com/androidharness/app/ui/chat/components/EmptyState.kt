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
 * First-run / new-chat screen.
 *
 * One mark, one headline, one supporting line, and a single quiet list of
 * suggestions with hairline separators, no hero squircles, color pills, or
 * status badges. Without a provider there is a real call-to-action button
 * instead of instructions to hunt through menus.
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
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val suggestions = if (chatOnly && agent != null && agent.starters.isNotEmpty()) agent.starters.map { agentIcon(agent.avatar) to it } else if (chatOnly) listOf(
        Icons.Outlined.School to uiContext.getString(R.string.chat_suggestion_explain),
        Icons.Outlined.Lightbulb to uiContext.getString(R.string.chat_suggestion_ideas),
        Icons.Outlined.EditNote to uiContext.getString(R.string.chat_suggestion_draft),
    ) else listOf(
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

    Box(modifier.fillMaxWidth().fillMaxHeight()) {
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(
                        top = 24.dp,
                        start = 8.dp, end = 8.dp, bottom = 24.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (chatOnly && agent != null) {
                    AgentAvatar(agent.avatar)
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    if (hasProvider && chatOnly && agent != null) agent.name else if (hasProvider) uiContext.getString(if (chatOnly) R.string.chat_empty_title else R.string.ui_what_do_you_want_to_build) else uiContext.getString(R.string.ui_connect_a_provider),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (hasProvider && chatOnly && agent != null && agent.description.isNotBlank()) agent.description else if (hasProvider) uiContext.getString(if (chatOnly) R.string.chat_empty_description else R.string.ui_start_with_an_idea_or_create_a)
                    else uiContext.getString(R.string.ui_add_an_api_key_for_openai_anthropic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (hasProvider) {
                    Spacer(Modifier.height(28.dp))
                    Surface(
                        color = scheme.surface,
                        shape = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) RoundedCornerShape(28.dp) else MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                    ) {
                        Column {
                            if (chatOnly && agent == null && agentSetup != null) {
                                agentSetup()
                                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(start = 56.dp))
                            }
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
                                                .harnessGlass(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
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
                            HorizontalDivider(color = scheme.outlineVariant)
                            DonationFundingNotice()
                            DonationRow()
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 56.dp),
                            )
                            UpiDonationRow()
                        }
                    }
                } else {
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
}

// Verified creator profile: Sanuu, creator of AndroidHarness.
private const val DONATION_URL = "https://buymeacoffee.com/sanuj7"

@Composable
private fun DonationRow() {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = uiContext.getString(R.string.ui_open_buy_me_a_coffee)) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        uiContext.getString(R.string.ui_no_browser_available_visit_buymeacoffee_com_sanuj7),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFFFFDD00)),
        ) {
            // Official asset: https://cdn.buymeacoffee.com/assets/logos/icon-black.png
            Image(
                painterResource(R.drawable.ic_buy_me_a_coffee),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(uiContext.getString(R.string.ui_donate), style = MaterialTheme.typography.bodyMedium)
            Text(
                uiContext.getString(R.string.ui_help_us_keep_building),
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
