@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.audio.TtsPhase
import com.androidharness.app.data.audio.VoiceGateway
import com.androidharness.app.data.audio.VoicePresets
import com.androidharness.app.data.audio.VoiceProtocol
import com.androidharness.app.data.audio.VoiceProviderConfig
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Everything the Voice & speech page opens on top of itself: the provider list
 * ("Voice providers", with Add first), one provider's sheet, the add and edit
 * sheet, and the shared choice sheet behind the model and voice rows. They are
 * bottom sheets built from the same segmented rows as the page.
 */

// ---------------------------------------------------------------------------
// API key
// ---------------------------------------------------------------------------

/**
 * The one text field of the voice sheets: a filled, rounded well that rings in the
 * primary tone while focused (error tone when it is wrong). The supporting line sits
 * under the field instead of inside the ring.
 */
@Composable
internal fun VoiceField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val ring by animateColorAsState(
        when {
            isError -> scheme.error
            focused -> scheme.primary
            else -> Color.Transparent
        },
        motion.fastEffectsSpec(), label = "fieldRing",
    )
    val well = scheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().border(2.dp, ring, shape),
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            interactionSource = interaction,
            shape = shape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = well,
                unfocusedContainerColor = well,
                errorContainerColor = well,
                disabledContainerColor = well.copy(alpha = 0.5f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        if (supportingText != null) {
            Text(
                supportingText,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) scheme.error else scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Key block of one provider. Without a saved key it is a prompt with the field
 * open; with one it collapses to a saved state with Replace and Remove. The key is
 * checked against the provider before it is stored.
 */
@Composable
internal fun VoiceKeyPanel(
    container: AppContainer,
    provider: VoiceProviderConfig,
    hasKey: Boolean,
    scope: CoroutineScope,
    onKeyChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val success = LocalStatusColors.current.success
    var replacing by remember(provider.id) { mutableStateOf(false) }
    var draft by remember(provider.id) { mutableStateOf("") }
    var reveal by remember(provider.id) { mutableStateOf(false) }
    var checking by remember(provider.id) { mutableStateOf(false) }
    var error by remember(provider.id) { mutableStateOf<String?>(null) }
    val open = replacing || !hasKey

    Column(
        Modifier.fillMaxWidth().animateContentSize(motion.defaultSpatialSpec()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val badge by animateColorAsState(
                if (hasKey) success.copy(alpha = 0.18f) else scheme.primaryContainer,
                motion.defaultEffectsSpec(), label = "keyBadge",
            )
            val glyph by animateColorAsState(
                if (hasKey) success else scheme.onPrimaryContainer,
                motion.defaultEffectsSpec(), label = "keyGlyph",
            )
            Box(
                Modifier.size(48.dp).background(badge, MaterialShapes.Cookie6Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = hasKey,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.5f)) togetherWith
                            fadeOut(motion.fastEffectsSpec())
                    },
                    label = "keyGlyphSwap",
                ) { saved ->
                    Icon(if (saved) Icons.Rounded.Check else Icons.Rounded.Key, null, Modifier.size(24.dp), tint = glyph)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (hasKey) stringResource(R.string.settings_voice_key_saved_for, provider.name)
                    else stringResource(R.string.settings_voice_key_title_add, provider.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (hasKey) stringResource(R.string.settings_voice_key_saved_body) else stringResource(R.string.settings_voice_key_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.78f),
                )
            }
        }

        if (open) {
            VoiceField(
                value = draft,
                onValueChange = { draft = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_voice_provider_key, provider.name)) },
                isError = error != null,
                supportingText = error,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            stringResource(if (reveal) R.string.settings_voice_hide_key else R.string.settings_voice_show_key),
                        )
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    enabled = draft.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        error = null
                        scope.launch {
                            val key = draft.trim()
                            val result = VoiceGateway.validateKey(provider, key)
                            if (result.isSuccess) {
                                container.voiceProviders.saveApiKey(provider.id, key)
                                draft = ""
                                reveal = false
                                replacing = false
                                onKeyChanged()
                            } else {
                                error = result.exceptionOrNull()?.message ?: context.getString(R.string.ui_verification_failed)
                            }
                            checking = false
                        }
                    },
                ) {
                    AnimatedContent(
                        targetState = checking,
                        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                        label = "keyCheck",
                    ) { busy ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy) LoadingIndicator(Modifier.size(22.dp), color = LocalContentColor.current)
                            Text(stringResource(if (busy) R.string.ui_checking else R.string.ui_save_and_check))
                        }
                    }
                }
                if (provider.keyUrl.isNotBlank()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(provider.keyUrl)),
                            )
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.settings_voice_get_key))
                    }
                }
                if (hasKey) {
                    TextButton(onClick = { replacing = false; draft = ""; error = null }) {
                        Text(stringResource(R.string.settings_voice_key_cancel))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { replacing = true }) { Text(stringResource(R.string.settings_voice_replace_key)) }
                TextButton(onClick = {
                    container.voiceProviders.removeApiKey(provider.id)
                    onKeyChanged()
                }) { Text(stringResource(R.string.settings_action_remove), color = scheme.error) }
            }
        }
    }
}

/** Rows that set one provider up: its key, or a note that it needs none. */
internal fun SettingsGroupScope.providerSetupItems(
    container: AppContainer,
    provider: VoiceProviderConfig,
    saved: Boolean,
    scope: CoroutineScope,
    onKeyChanged: () -> Unit,
) {
    if (!provider.requiresKey) {
        item {
            VoiceNote(
                Icons.Rounded.Check,
                stringResource(R.string.settings_voice_free_body, provider.name),
                title = stringResource(R.string.settings_voice_free_title),
                good = true,
            )
        }
        return
    }
    item {
        VoiceKeyPanel(container, provider, hasKey = saved, scope = scope, onKeyChanged = onKeyChanged)
    }
}

// ---------------------------------------------------------------------------
// Provider list
// ---------------------------------------------------------------------------

/** "Voice providers": Add first, then every provider with its key state. A row opens that provider's sheet. */
@Composable
internal fun VoiceProvidersHub(
    providers: List<VoiceProviderConfig>,
    keyed: Set<String>,
    settings: AppSettings,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val listening = stringResource(R.string.settings_voice_cap_listen)
    val speaking = stringResource(R.string.settings_voice_cap_speak)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.settings_voice_providers_title),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = scheme.primary,
            )
            Text(
                stringResource(R.string.settings_voice_providers_desc),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        SettingsGroup {
            item(onClick = onAdd, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_voice_add_provider),
                    subtitle = stringResource(R.string.settings_voice_add_provider_desc),
                    leading = {
                        Box(Modifier.size(44.dp).background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(24.dp), tint = scheme.onPrimary)
                        }
                    },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                )
            }
            providers.forEach { provider ->
                val use = listOfNotNull(
                    listening.takeIf { settings.voiceEngine == provider.id },
                    speaking.takeIf { settings.ttsEngine == provider.id },
                ).joinToString(" · ")
                item(onClick = { onOpen(provider.id) }) {
                    VoiceProviderRow(
                        provider = provider,
                        saved = provider.id in keyed,
                        inUse = use.ifBlank { null },
                        modifier = if (provider.id == VoicePresets.GROQ) Modifier.settingsAnchor(R.string.settings_tts_groq_key) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceProviderRow(provider: VoiceProviderConfig, saved: Boolean, inUse: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val abilities = listOfNotNull(
        stringResource(R.string.settings_voice_cap_listen).takeIf { provider.supportsStt },
        stringResource(R.string.settings_voice_cap_speak).takeIf { provider.supportsTts },
    ).joinToString(" · ")
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        VoiceMonogram(provider, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                provider.protocol.label + " · " + abilities,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            if (inUse != null) {
                Text(inUse, style = MaterialTheme.typography.labelMediumEmphasized, color = scheme.primary)
            }
        }
        when {
            !provider.requiresKey -> SettingsStatusPill(stringResource(R.string.settings_voice_pill_free), ok = true)
            saved -> SettingsStatusPill(stringResource(R.string.settings_voice_pill_ready), ok = true)
            else -> SettingsStatusPill(stringResource(R.string.ui_no_key), ok = false)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
    }
}

/** The provider's initial on a soft Material shape. Each provider keeps its own shape and tone. */
@Composable
private fun VoiceMonogram(provider: VoiceProviderConfig, size: androidx.compose.ui.unit.Dp) {
    val seed = provider.id.hashCode()
    val tone = SettingsTone.entries[Math.floorMod(seed, 3)]
    SettingsIconBadge(tone, size, settingsHeroShape(seed)) { tint ->
        Text(
            provider.name.trim().firstOrNull()?.uppercase() ?: "?",
            color = tint,
            style = if (size > 52.dp) MaterialTheme.typography.headlineSmallEmphasized else MaterialTheme.typography.titleMedium,
        )
    }
}

// ---------------------------------------------------------------------------
// One provider
// ---------------------------------------------------------------------------

@Composable
internal fun VoiceProviderSheet(
    container: AppContainer,
    provider: VoiceProviderConfig,
    settings: AppSettings,
    saved: Boolean,
    scope: CoroutineScope,
    onKeyChanged: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember { mutableStateOf(false) }
    val host = remember(provider.baseUrl) { runCatching { android.net.Uri.parse(provider.baseUrl).host }.getOrNull() ?: provider.baseUrl }
    val listening = settings.voiceEngine == provider.id
    val speaking = settings.ttsEngine == provider.id

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VoiceMonogram(provider, 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        provider.protocol.label + " · " + host,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        if (provider.supportsStt) SettingsStatusPill(stringResource(R.string.settings_voice_cap_listen), ok = false)
                        if (provider.supportsTts) SettingsStatusPill(stringResource(R.string.settings_voice_cap_speak), ok = false)
                    }
                }
            }

            SettingsGroup {
                providerSetupItems(container, provider, saved, scope, onKeyChanged)
            }

            if (provider.supportsStt || provider.supportsTts) {
                SettingsGroup(Modifier.selectableGroup()) {
                    if (provider.supportsStt) item(
                        selected = listening,
                        onClick = { if (!listening) scope.launch { container.settings.setVoiceEngine(provider.id) } },
                    ) {
                        UseRow(Icons.Rounded.Mic, stringResource(R.string.settings_voice_use_listening), listening, provider.name)
                    }
                    if (provider.supportsTts) item(
                        selected = speaking,
                        onClick = { if (!speaking) scope.launch { container.settings.setTtsEngine(provider.id) } },
                    ) {
                        UseRow(Icons.Rounded.VolumeUp, stringResource(R.string.settings_voice_use_speaking), speaking, provider.name)
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_voice_edit_details))
                }
                if (provider.custom) {
                    TextButton(onClick = { confirmRemove = true }) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = scheme.error)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_voice_remove_provider), color = scheme.error)
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.settings_voice_remove_title, provider.name)) },
            text = { Text(stringResource(R.string.settings_voice_remove_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text(stringResource(R.string.settings_action_remove), color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

@Composable
private fun UseRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, active: Boolean, name: String) {
    SettingsItemRow(
        title = title,
        subtitle = if (active) stringResource(R.string.settings_voice_in_use) else stringResource(R.string.settings_voice_switch_to, name),
        leading = { SettingsIconBadge(icon, if (active) SettingsTone.PRIMARY else SettingsTone.SECONDARY, 40.dp) },
        trailing = { RadioButton(selected = active, onClick = null) },
    )
}

// ---------------------------------------------------------------------------
// Add or edit
// ---------------------------------------------------------------------------

/**
 * Add a provider or edit one. A new provider only needs a name and a URL; the
 * protocol, abilities and default models sit below. Saving an untouched preset is
 * harmless, its identity is kept so later updates still reach it.
 */
@Composable
internal fun VoiceProviderEditorSheet(
    initial: VoiceProviderConfig,
    isNew: Boolean,
    onSave: (VoiceProviderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var defaults by remember { mutableStateOf(false) }
    val urlOk = baseUrl.trim().let { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    val canSave = name.isNotBlank() && urlOk && (draft.supportsStt || draft.supportsTts)
    val turn by animateFloatAsState(if (defaults) 180f else 0f, motion.defaultSpatialSpec(), label = "defaultsTurn")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isNew) stringResource(R.string.settings_voice_new_provider)
                    else stringResource(R.string.settings_voice_editor_edit_title, initial.name),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                if (isNew) Text(
                    stringResource(R.string.settings_voice_editor_new_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            VoiceField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_voice_provider_name)) },
                placeholder = { Text(stringResource(R.string.settings_voice_name_hint)) },
                singleLine = true,
            )
            VoiceField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_voice_base_url)) },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                isError = baseUrl.isNotBlank() && !urlOk,
                supportingText = if (baseUrl.isNotBlank() && !urlOk) stringResource(R.string.settings_voice_url_invalid) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_voice_protocol),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = scheme.primary,
                )
                if (initial.custom) {
                    FlowRow(
                        Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VoiceProtocol.entries.filter { it != VoiceProtocol.EDGE }.forEach { protocol ->
                            VoiceChip(
                                checked = draft.protocol == protocol,
                                label = protocol.label,
                                onClick = {
                                    draft = draft.copy(
                                        protocol = protocol,
                                        supportsStt = if (protocol == VoiceProtocol.CARTESIA) false else draft.supportsStt,
                                        supportsTts = if (protocol == VoiceProtocol.ASSEMBLYAI) false else draft.supportsTts,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    Text(
                        draft.protocol.label + ". " + stringResource(R.string.settings_voice_protocol_locked),
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_voice_can_do),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = scheme.primary,
                )
                SettingsGroup {
                    item(onClick = { draft = draft.copy(supportsStt = !draft.supportsStt) }) {
                        SettingsItemRow(
                            title = stringResource(R.string.settings_voice_can_listen),
                            subtitle = stringResource(R.string.settings_voice_can_listen_desc),
                            leading = { SettingsIconBadge(Icons.Rounded.Mic, SettingsTone.SECONDARY, 40.dp) },
                            trailing = { Switch(checked = draft.supportsStt, onCheckedChange = { draft = draft.copy(supportsStt = it) }) },
                        )
                    }
                    item(onClick = { draft = draft.copy(supportsTts = !draft.supportsTts) }) {
                        SettingsItemRow(
                            title = stringResource(R.string.settings_voice_can_speak),
                            subtitle = stringResource(R.string.settings_voice_can_speak_desc),
                            leading = { SettingsIconBadge(Icons.Rounded.VolumeUp, SettingsTone.SECONDARY, 40.dp) },
                            trailing = { Switch(checked = draft.supportsTts, onCheckedChange = { draft = draft.copy(supportsTts = it) }) },
                        )
                    }
                    item(onClick = { draft = draft.copy(requiresKey = !draft.requiresKey) }) {
                        SettingsItemRow(
                            title = stringResource(R.string.settings_voice_requires_key),
                            subtitle = stringResource(R.string.settings_voice_requires_key_desc),
                            leading = { SettingsIconBadge(Icons.Rounded.Key, SettingsTone.TERTIARY, 40.dp) },
                            trailing = { Switch(checked = draft.requiresKey, onCheckedChange = { draft = draft.copy(requiresKey = it) }) },
                        )
                    }
                }
            }

            SettingsGroup(Modifier.animateContentSize(motion.defaultSpatialSpec())) {
                item(onClick = { defaults = !defaults }) {
                    SettingsItemRow(
                        title = stringResource(R.string.settings_voice_defaults),
                        subtitle = stringResource(R.string.settings_voice_defaults_desc),
                        trailing = {
                            Icon(
                                Icons.Rounded.ExpandMore, null,
                                Modifier.graphicsLayer { rotationZ = turn },
                                tint = scheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                if (defaults) item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceField(
                            value = draft.sttModel,
                            onValueChange = { draft = draft.copy(sttModel = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_stt_model)) },
                            singleLine = true,
                            enabled = draft.supportsStt,
                        )
                        VoiceField(
                            value = draft.ttsModel,
                            onValueChange = { draft = draft.copy(ttsModel = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_tts_model)) },
                            singleLine = true,
                            enabled = draft.supportsTts,
                        )
                        VoiceField(
                            value = draft.ttsVoice,
                            onValueChange = { draft = draft.copy(ttsVoice = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_tts_voice)) },
                            singleLine = true,
                            enabled = draft.supportsTts,
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
                Button(
                    enabled = canSave,
                    onClick = { onSave(draft.copy(name = name.trim(), baseUrl = baseUrl.trim().trimEnd('/'))) },
                ) { Text(stringResource(R.string.settings_tts_save)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Model and voice choices
// ---------------------------------------------------------------------------

/**
 * What a voice list needs to play samples: which voice is sounding right now, how far
 * along it is, and what a tap on a speaker does (start that voice, or stop it).
 */
internal class VoiceSample(
    private val playingId: String?,
    private val phase: TtsPhase,
    val onToggle: (String) -> Unit,
) {
    fun phaseOf(voiceId: String): TtsPhase = if (playingId == voiceId) phase else TtsPhase.IDLE
}

/**
 * The speaker beside a voice. Idle it is a quiet disc with a speaker, while the clip
 * is being made it shows the loading shape, and while it plays it turns into a
 * rounded square with a stop glyph.
 */
@Composable
internal fun VoiceListenButton(
    label: String,
    phase: TtsPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val active = phase != TtsPhase.IDLE
    val container by animateColorAsState(if (active) scheme.primary else scheme.surfaceContainerLowest, motion.defaultEffectsSpec(), label = "listenFill")
    val content by animateColorAsState(if (active) scheme.onPrimary else scheme.primary, motion.defaultEffectsSpec(), label = "listenGlyph")
    val corner by animateDpAsState(if (active) 14.dp else 20.dp, motion.fastSpatialSpec(), label = "listenCorner")
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f)) togetherWith
                    fadeOut(motion.fastEffectsSpec())
            },
            label = "listenState",
        ) { current ->
            when (current) {
                TtsPhase.IDLE -> Icon(Icons.Rounded.VolumeUp, stringResource(R.string.settings_voice_preview_play, label), Modifier.size(22.dp))
                TtsPhase.PREPARING -> LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                TtsPhase.SPEAKING -> Icon(Icons.Rounded.Stop, stringResource(R.string.settings_voice_preview_stop), Modifier.size(22.dp))
            }
        }
    }
}

internal data class VoiceChoice(val id: String, val label: String, val detail: String? = null)

/**
 * One list for models and voices: the options as selectable rows, a search box
 * when the list is long, and a field for an id that is not listed. [options] is
 * null while the provider is still being asked.
 */
@Composable
internal fun VoiceChoiceSheet(
    title: String,
    options: List<VoiceChoice>?,
    selectedId: String,
    customLabel: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    sample: VoiceSample? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    val shown = remember(options, query) {
        options?.filter { query.isBlank() || it.label.contains(query.trim(), true) || it.id.contains(query.trim(), true) }
    }
    val motion = MaterialTheme.motionScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                .navigationBarsPadding().imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLargeEmphasized)
                if (onRefresh != null) {
                    androidx.compose.material3.IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.settings_tts_refresh))
                    }
                }
            }
            if (sample != null) {
                Text(
                    stringResource(R.string.settings_voice_preview_hint),
                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = (options?.size ?: 0) > 8,
                enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
            ) {
                VoiceField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { Text(stringResource(R.string.settings_voice_choice_search)) },
                    singleLine = true,
                )
            }
            LazyColumn(
                Modifier.weight(1f, fill = false).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsSegmentGap),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (shown == null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingIndicator(Modifier.size(40.dp))
                            Text(stringResource(R.string.settings_voice_choice_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    itemsIndexed(shown, key = { _, option -> option.id }) { index, option ->
                        val active = option.id == selectedId
                        SettingsSegment(index, shown.size, onClick = { onSelect(option.id) }, selected = active) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                RadioButton(selected = active, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                    option.detail?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (sample != null) {
                                    VoiceListenButton(
                                        label = option.label,
                                        phase = sample.phaseOf(option.id),
                                        onClick = { sample.onToggle(option.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (customLabel != null) item {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.settings_voice_choice_custom),
                            Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VoiceField(
                                value = custom,
                                onValueChange = { custom = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(customLabel) },
                                singleLine = true,
                            )
                            if (sample != null) {
                                VoiceListenButton(
                                    label = custom.trim(),
                                    phase = sample.phaseOf(custom.trim()),
                                    onClick = { sample.onToggle(custom.trim()) },
                                    enabled = custom.isNotBlank(),
                                )
                            }
                            Button(enabled = custom.isNotBlank(), onClick = { onSelect(custom.trim()) }) {
                                Text(stringResource(R.string.settings_voice_choice_use))
                            }
                        }
                    }
                }
            }
        }
    }
}
