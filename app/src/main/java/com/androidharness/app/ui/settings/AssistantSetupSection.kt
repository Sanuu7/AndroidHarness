package com.androidharness.app.ui.settings

import com.androidharness.app.agent.ThinkingLevel
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidharness.app.R
import com.androidharness.app.assistant.AssistantAvailability
import com.androidharness.app.assistant.AssistantCheck
import com.androidharness.app.assistant.AssistantFacts
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

/** Whether Android currently uses AndroidHarness as the digital assistant app. */
internal fun isHarnessAssistant(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    } else runCatching {
        Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            ?.startsWith(context.packageName + "/") == true
    }.getOrDefault(false)

/** Puts the assistant check on the clipboard for a bug report. */
private fun copyAssistantReport(context: Context, facts: AssistantFacts) {
    val report = AssistantCheck.report(facts, com.androidharness.app.BuildConfig.VERSION_NAME)
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.assistant_check_copy_label), report))
    // Android 13 and later confirm copies on their own.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        Toast.makeText(context, R.string.assistant_check_copied, Toast.LENGTH_SHORT).show()
}

/** Opens the system screen where the assistant app is picked. */
internal fun openAssistantPicker(context: Context) {
    val intents = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
    Toast.makeText(context, R.string.assistant_settings_open_failed, Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AssistantSetupSection(
    voiceFirst: Boolean,
    onVoiceFirst: (Boolean) -> Unit,
    thinking: ThinkingLevel,
    onThinking: (ThinkingLevel) -> Unit,
    speakReplies: Boolean,
    onSpeakReplies: (Boolean) -> Unit,
    modelPicker: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var facts by remember { mutableStateOf(AssistantCheck.facts(context, isHarnessAssistant(context))) }
    val active = facts.active
    val availability = AssistantCheck.availability(facts)
    // The choice is made in Android settings, so look again whenever the page comes back.
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) facts = AssistantCheck.facts(context, isHarnessAssistant(context))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val scheme = MaterialTheme.colorScheme
    val badge by animateColorAsState(if (active) scheme.primary else scheme.secondaryContainer, label = "assistantBadge")
    val glyph by animateColorAsState(if (active) scheme.onPrimary else scheme.onSecondaryContainer, label = "assistantGlyph")

    SettingsAnchor(R.string.assistant_settings_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).background(badge, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                        Icon(if (active) Icons.Rounded.CheckCircle else Icons.Rounded.TouchApp, null, Modifier.size(22.dp), tint = glyph)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.assistant_settings_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (active) R.string.assistant_settings_on else R.string.assistant_settings_off),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.assistant_settings_desc), style = MaterialTheme.typography.bodyMedium)
                val canPick = availability == AssistantAvailability.ACTIVE || availability == AssistantAvailability.PICK ||
                    availability == AssistantAvailability.PICK_SAMSUNG
                if (canPick) Text(stringResource(R.string.assistant_settings_steps), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                when (availability) {
                    AssistantAvailability.ACTIVE -> null
                    AssistantAvailability.UNSUPPORTED -> R.string.assistant_check_unsupported
                    AssistantAvailability.OTHER_PROFILE -> R.string.assistant_check_profile
                    AssistantAvailability.PICK_SAMSUNG -> R.string.assistant_check_samsung
                    AssistantAvailability.PICK -> R.string.assistant_check_generic
                }?.let { hint ->
                    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                        color = if (canPick) scheme.onSurfaceVariant else scheme.error)
                }
                Text(stringResource(R.string.assistant_settings_privacy), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                // The anchor fills the row, so the copy button sits on its own line below it.
                if (canPick) SettingsAnchor(R.string.assistant_settings_open) {
                    FilledTonalButton(onClick = { openAssistantPicker(context) }) {
                        Text(stringResource(R.string.assistant_settings_open))
                    }
                }
                if (!active) TextButton(onClick = { copyAssistantReport(context, facts) }) {
                    Text(stringResource(R.string.assistant_check_copy))
                }
            }
        }
    }
    SettingsAnchor(R.string.assistant_settings_voice_first) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(voiceFirst, role = Role.Switch, onValueChange = onVoiceFirst).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_settings_voice_first), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_settings_voice_first_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                Switch(voiceFirst, onCheckedChange = null)
            }
        }
    }
    modelPicker()
    SettingsAnchor(R.string.assistant_settings_thinking) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_settings_thinking), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_settings_thinking_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                val shown = assistantThinkingChoice(thinking)
                SettingsChoiceGroup(
                    options = AssistantThinkingChoices.map { it to it.label },
                    selected = { it == shown },
                    onSelect = onThinking,
                )
            }
        }
    }
    SettingsAnchor(R.string.assistant_settings_speak) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(speakReplies, role = Role.Switch, onValueChange = onSpeakReplies).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_settings_speak), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_settings_speak_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                Switch(speakReplies, onCheckedChange = null)
            }
        }
    }
}

/** The levels offered in settings; the panel can also pick any level the current model has. */
private val AssistantThinkingChoices = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.MAX)

/** A level picked in the panel that settings does not list shows as its nearest neighbour. */
private fun assistantThinkingChoice(level: ThinkingLevel): ThinkingLevel = when (level) {
    ThinkingLevel.OFF -> ThinkingLevel.OFF
    ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> ThinkingLevel.LOW
    ThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
    ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> ThinkingLevel.HIGH
    ThinkingLevel.MAX, ThinkingLevel.ULTRA -> ThinkingLevel.MAX
}
