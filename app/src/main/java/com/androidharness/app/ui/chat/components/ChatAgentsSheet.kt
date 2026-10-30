package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.FileAttachments
import com.androidharness.app.ui.chat.PdfTextExtractor
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.FilledTonalIconButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

internal fun agentIcon(key: String): ImageVector = when (key) {
    "study" -> Icons.Outlined.School
    "write" -> Icons.Outlined.EditNote
    "research" -> Icons.Outlined.TravelExplore
    "idea" -> Icons.Outlined.Lightbulb
    "travel" -> Icons.Outlined.Explore
    else -> Icons.Outlined.AutoAwesome
}

@Composable
internal fun agentIconLabel(key: String): String = stringResource(when (key) {
    "study" -> R.string.chat_agents_icon_study
    "write" -> R.string.chat_agents_icon_write
    "research" -> R.string.chat_agents_icon_research
    "idea" -> R.string.chat_agents_icon_idea
    "travel" -> R.string.chat_agents_icon_travel
    else -> R.string.chat_agents_icon_spark
})

@Composable
internal fun AgentAvatar(key: String, modifier: Modifier = Modifier) {
    Surface(modifier.size(48.dp), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(targetState = key, animationSpec = expressiveEffectsSpec(), label = "agent avatar") { iconKey ->
                Icon(agentIcon(iconKey), null, Modifier.size(26.dp))
            }
        }
    }
}

/** Same library and editor from Settings and Chat; selection always creates a new conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAgentsSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onStart: (ChatAgentProfile) -> Unit,
    onRegularChat: (() -> Unit)? = null,
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(stateSaver = Saver<ChatAgentProfile?, String>(
        save = { it?.let { agent -> kotlinx.serialization.json.Json.encodeToString(ChatAgentProfile.serializer(), agent) } ?: "" },
        restore = { it.takeIf(String::isNotEmpty)?.let { raw -> kotlinx.serialization.json.Json.decodeFromString(ChatAgentProfile.serializer(), raw) } },
    )) { mutableStateOf<ChatAgentProfile?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatAgentProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).fillMaxHeight(.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_agents_title), style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(stringResource(R.string.chat_agents_count, settings.chatAgents.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { editing = ChatAgentProfile() }, enabled = settings.chatAgents.size < ChatAgentProfile.MAX_AGENTS) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.chat_agents_new))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp)) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRegularChat != null) item(key = "regular") {
                    Surface(onClick = onRegularChat, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.chat_agents_regular), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.chat_agents_regular_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (settings.chatAgents.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AgentAvatar("spark", Modifier.size(72.dp))
                        Text(stringResource(R.string.chat_agents_empty), style = MaterialTheme.typography.titleLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(stringResource(R.string.chat_agents_empty_desc), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(onClick = { editing = ChatAgentProfile() }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_agents_new))
                        }
                    }
                }
                items(settings.chatAgents.sortedByDescending { it.updatedAt }, key = { it.id }) { agent ->
                    var menu by remember { mutableStateOf(false) }
                    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().animateItem()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AgentAvatar(agent.avatar)
                                Column(Modifier.weight(1f)) {
                                    Text(agent.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (agent.description.isNotBlank()) Text(agent.description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.chat_agents_edit)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_agents_edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                            onClick = { menu = false; editing = agent })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_agents_duplicate)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                            enabled = settings.chatAgents.size < ChatAgentProfile.MAX_AGENTS,
                                            onClick = { menu = false; editing = agent.copy(id = java.util.UUID.randomUUID().toString(),
                                                name = container.appContext.getString(R.string.chat_agents_copy, agent.name).take(60)) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_agents_delete_action)) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                            onClick = { menu = false; pendingDelete = agent })
                                    }
                                }
                            }
                            FilledTonalButton(onClick = { onStart(agent) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.chat_agents_start))
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.chat_agents_snapshot), modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    editing?.let { profile -> ChatAgentEditor(container, profile, onDismiss = { editing = null }, onSaved = { editing = null }) }
    pendingDelete?.let { profile -> AlertDialog(onDismissRequest = { if (!deleting) pendingDelete = null },
        title = { Text(stringResource(R.string.chat_agents_delete)) },
        text = { Text(stringResource(R.string.chat_agents_delete_desc, profile.name)) },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            deleting = true
            scope.launch {
                try { container.settings.deleteChatAgent(profile.id); pendingDelete = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { deleting = false }
            }
        }) { Text(stringResource(R.string.chat_agents_delete_action)) } },
        dismissButton = { TextButton(enabled = !deleting, onClick = { pendingDelete = null }) { Text(stringResource(R.string.settings_action_cancel)) } }) }
}

private val profileSaver = Saver<ChatAgentProfile, String>(
    save = { kotlinx.serialization.json.Json.encodeToString(ChatAgentProfile.serializer(), it) },
    restore = { kotlinx.serialization.json.Json.decodeFromString(ChatAgentProfile.serializer(), it) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatAgentEditor(container: AppContainer, initial: ChatAgentProfile, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var profile by rememberSaveable(initial.id, stateSaver = profileSaver) { mutableStateOf(initial) }
    var starters by rememberSaveable(initial.id) { mutableStateOf(initial.starters.joinToString("\n")) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var pickModel by rememberSaveable { mutableStateOf(false) }
    var pickSkills by rememberSaveable { mutableStateOf(false) }
    var manageProviders by rememberSaveable { mutableStateOf(false) }
    var addProvider by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val currentChatModel by remember(appSettings.lastActiveSessionId) { container.chatModels.observe(appSettings.lastActiveSessionId) }
        .collectAsStateWithLifecycle(initialValue = ChatModelState(AppSettings(), emptyList(), ChatModelSelection()))
    var inheritThinking by rememberSaveable(initial.id) { mutableStateOf(initial.name.isBlank()) }
    val thinkingLevel = if (inheritThinking) currentChatModel.selection.thinking else profile.thinking
    val modelChoice = profile.model ?: currentChatModel.selection.active
    val thinkingProvider = providers.firstOrNull { it.id == modelChoice?.providerId }?.copy(model = modelChoice?.model.orEmpty())
    val reasoningRevision by com.androidharness.app.llm.ModelsDev.revision.collectAsStateWithLifecycle()
    val resolvedThinking = remember(thinkingProvider, thinkingLevel, appSettings.reasoningConfigs, appSettings.maxOutputTokens,
        currentChatModel.selection.customThinkingLevels, profile.customThinkingLevels, catalogs, reasoningRevision) {
        thinkingProvider?.let { config -> com.androidharness.app.llm.RequestOptions(thinking = thinkingLevel,
            maxOutputTokens = appSettings.maxOutputTokens, customReasoning = appSettings.reasoningConfigs,
            customThinkingLevels = currentChatModel.selection.customThinkingLevels + profile.customThinkingLevels)
            .resolveThinking(config, catalogs[config.id].orEmpty().firstOrNull { it.id == config.model }) }
    }
    var thinkingMenu by remember { mutableStateOf(false) }
    fun close() { if (saving || importing) return; if (profile != initial || starters != initial.starters.joinToString("\n")) discard = true else onDismiss() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true; error = null
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }?.take(180) ?: "knowledge.txt"
                        val mime = resolver.getType(uri)
                        require(FileAttachments.isPdf(name, mime) || FileAttachments.isTextLike(name, mime)) { context.getString(R.string.chat_agents_file_type) }
                        val bytes = resolver.openInputStream(uri)?.use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            while (out.size() <= 4 * 1024 * 1024) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 + 1 - out.size()))
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                            ?: error(context.getString(R.string.chat_agents_file_error))
                        require(bytes.size <= 4 * 1024 * 1024) { context.getString(R.string.chat_agents_file_large) }
                        val text = if (FileAttachments.isPdf(name, mime)) {
                            val result = PdfTextExtractor.extract(bytes) ?: error(context.getString(R.string.chat_agents_file_empty))
                            require(result.pageCount <= PdfTextExtractor.MAX_PAGES && result.text.length < PdfTextExtractor.MAX_CHARS) { context.getString(R.string.chat_agents_file_large) }
                            result.text
                        } else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                        require(text.isNotBlank()) { context.getString(R.string.chat_agents_file_empty) }
                        require(text.length <= ChatAgentProfile.MAX_FILE_CHARS) { context.getString(R.string.chat_agents_file_large) }
                        AgentKnowledge(name = name, text = text)
                    }
                    require(profile.knowledge.size < ChatAgentProfile.MAX_FILES && profile.knowledge.sumOf { it.text.length } + file.text.length <= ChatAgentProfile.MAX_KNOWLEDGE_CHARS) {
                        context.getString(R.string.chat_agents_file_large)
                    }
                    profile = profile.copy(knowledge = profile.knowledge + file)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: context.getString(R.string.chat_agents_file_error) }
                finally { importing = false }
            }
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > .5f
        DisposableEffect(dialogWindow, lightBars) {
            dialogWindow?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            onDispose { }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { AppHeader(stringResource(if (initial.name.isBlank()) R.string.chat_agents_new else R.string.chat_agents_edit), onBack = ::close) },
                bottomBar = {
                    Surface(shadowElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).widthIn(max = 840.dp)) {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                            Button(enabled = !saving && !importing && profile.name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                                saving = true; error = null
                                scope.launch {
                                    try {
                                        val chosenLevels = if (thinkingProvider != null && resolvedThinking?.customLevel != null)
                                            mapOf(com.androidharness.app.agent.CustomReasoning.key(thinkingProvider) to resolvedThinking.customLevel) else emptyMap()
                                        val next = profile.copy(name = profile.name.trim(), thinking = thinkingLevel,
                                            customThinkingLevels = chosenLevels,
                                            starters = starters.lines().map(String::trim).filter(String::isNotBlank))
                                        container.settings.saveChatAgent(next); onSaved()
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { saving = false }
                                }
                            }) { Text(stringResource(if (saving) R.string.chat_agents_saving else R.string.chat_agents_save)) }
                        }
                    }
                }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth().animateContentSize(expressiveSpatialSpec())) {
                            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                AgentAvatar(profile.avatar)
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name.ifBlank { stringResource(R.string.chat_agents_untitled) }, style = MaterialTheme.typography.headlineSmallEmphasized)
                                    Text(profile.description.ifBlank { stringResource(R.string.chat_agents_description) }, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        AgentEditorGroup(stringResource(R.string.chat_agents_basics), Icons.Outlined.Badge) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChatAgentProfile.AVATARS.forEach { key ->
                                    val selected = profile.avatar == key
                                    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        animationSpec = expressiveEffectsSpec(), label = "agent icon")
                                    FilledTonalIconToggleButton(checked = selected, onCheckedChange = { profile = profile.copy(avatar = key) }, enabled = !saving,
                                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(containerColor = color, checkedContainerColor = color,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant, checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                                        AnimatedAgentIcon(key, selected, stringResource(R.string.chat_agents_icon, agentIconLabel(key)))
                                    }
                                }
                            }
                            AgentField(profile.name, { profile = profile.copy(name = it.take(60)) }, R.string.chat_agents_name, saving)
                            AgentField(profile.description, { profile = profile.copy(description = it.take(240)) }, R.string.chat_agents_description_label, saving, lines = 2)
                            AgentField(profile.instructions, { profile = profile.copy(instructions = it.take(8_000)) }, R.string.chat_agents_instructions, saving,
                                lines = 4, hint = R.string.chat_agents_instructions_hint)
                            AgentField(profile.persona, { profile = profile.copy(persona = it.take(2_000)) }, R.string.chat_agents_persona, saving,
                                lines = 2, hint = R.string.chat_agents_persona_hint)
                            AgentField(starters, { starters = it.take(804) }, R.string.chat_agents_starters, saving, lines = 3, hint = R.string.chat_agents_starters_desc)
                        }
                        AgentEditorGroup(stringResource(R.string.chat_agents_model), Icons.Outlined.Tune) {
                            Text(stringResource(R.string.chat_agents_model_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val provider = providers.firstOrNull { it.id == profile.model?.providerId }
                            OutlinedButton(onClick = { pickModel = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (profile.model == null) stringResource(R.string.chat_agents_inherit_model) else
                                    if (provider == null) stringResource(R.string.chat_agents_missing_model) else "${provider.name} · ${profile.model?.model}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (profile.model != null) TextButton(onClick = { profile = profile.copy(model = null) }, enabled = !saving) { Text(stringResource(R.string.chat_agents_inherit_model)) }
                            if (profile.model == null && modelChoice != null) Text(modelChoice.model,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val thinking = resolvedThinking
                            if (thinking != null && (thinking.customConfig != null || thinking.spec.levels.isNotEmpty() || thinking.spec.reasoningSupported)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.chat_agents_thinking), style = MaterialTheme.typography.titleSmall)
                                    HeaderThinkingControl(thinking.level, thinking.spec.levels, thinkingMenu, { thinkingMenu = it },
                                        onSetThinking = { level ->
                                            inheritThinking = false
                                            profile = profile.copy(thinking = level)
                                        }, onPickModel = { pickModel = true }, resolved = thinking,
                                        onCustomLevel = { name -> thinkingProvider?.let { config ->
                                            inheritThinking = false
                                            profile = profile.copy(thinking = thinking.level, customThinkingLevels = profile.customThinkingLevels +
                                                (com.androidharness.app.agent.CustomReasoning.key(config) to com.androidharness.app.agent.CustomReasoning.wire(name)))
                                        } })
                                }
                            }
                        }
                        AgentEditorGroup(stringResource(R.string.chat_agents_knowledge), Icons.Outlined.MenuBook) {
                            Text(stringResource(R.string.chat_agents_knowledge_desc), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.chat_agents_knowledge_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            profile.knowledge.forEach { file ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Description, null, Modifier.padding(end = 12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(R.string.chat_agents_file_size, file.text.length), style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { profile = profile.copy(knowledge = profile.knowledge.filterNot { it.id == file.id }) }, enabled = !saving && !importing) {
                                        Icon(Icons.Outlined.Close, stringResource(R.string.chat_agents_remove_file, file.name))
                                    }
                                }
                            }
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "application/pdf", "application/json")) },
                                enabled = !saving && !importing && profile.knowledge.size < ChatAgentProfile.MAX_FILES) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(stringResource(if (importing) R.string.chat_agents_file_reading else R.string.chat_agents_add_file))
                            }
                            Text(stringResource(R.string.chat_agents_knowledge_usage, profile.knowledge.size, profile.knowledge.sumOf { it.text.length }), style = MaterialTheme.typography.labelSmall)
                        }
                        AgentEditorGroup(stringResource(R.string.chat_agents_tools), Icons.Outlined.Extension) {
                            Text(stringResource(R.string.chat_agents_tools_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(Triple(AgentCapability.WEB, R.string.chat_agents_web, R.string.chat_agents_web_desc),
                                Triple(AgentCapability.IMAGES, R.string.chat_agents_images, R.string.chat_agents_images_desc),
                                Triple(AgentCapability.GITHUB, R.string.chat_agents_github, R.string.chat_agents_github_desc)).forEach { (capability, title, description) ->
                                AgentSwitch(stringResource(title), stringResource(description), capability in profile.capabilities, !saving) { enabled ->
                                    profile = profile.copy(capabilities = if (enabled) profile.capabilities + capability else profile.capabilities - capability)
                                }
                            }
                            HorizontalDivider()
                            Text(stringResource(R.string.chat_agents_memory), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup()) {
                                AgentMemory.entries.forEach { mode ->
                                    val title = when(mode) { AgentMemory.PRIVATE -> R.string.chat_agents_memory_private; AgentMemory.SHARED -> R.string.chat_agents_memory_shared; AgentMemory.OFF -> R.string.chat_agents_memory_off }
                                    val description = when(mode) { AgentMemory.PRIVATE -> R.string.chat_agents_memory_private_desc; AgentMemory.SHARED -> R.string.chat_agents_memory_shared_desc; AgentMemory.OFF -> R.string.chat_agents_memory_off_desc }
                                    Row(Modifier.fillMaxWidth().selectable(profile.memory == mode, enabled = !saving, role = Role.RadioButton,
                                        onClick = { profile = profile.copy(memory = mode) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(profile.memory == mode, null, enabled = !saving)
                                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                                            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            Text(stringResource(R.string.chat_agents_memory_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AgentSwitch(stringResource(R.string.chat_agents_history), stringResource(R.string.chat_agents_history_desc), profile.sharedHistory, !saving) {
                                profile = profile.copy(sharedHistory = it)
                            }
                        }
                        AgentEditorGroup(stringResource(R.string.chat_agents_skills), Icons.Outlined.AutoStories) {
                            Text(stringResource(R.string.chat_agents_skills_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                profile.skills.forEach { skill -> InputChip(selected = true, onClick = { if (!saving) profile = profile.copy(skills = profile.skills - skill) },
                                    label = { Text(skill.name) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.chat_agents_remove_file, skill.name), Modifier.size(16.dp)) }) }
                            }
                            OutlinedButton(onClick = { pickSkills = true }, enabled = !saving) { Text(stringResource(R.string.chat_agents_skills_choose)) }
                        }
                        Text(stringResource(R.string.chat_agents_snapshot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
    if (pickModel) ModelPickerSheet(providers, profile.model?.providerId, profile.model?.model, catalogs,
        onDismiss = { pickModel = false }, onSelect = { id, model ->
            val chosen = model ?: providers.firstOrNull { it.id == id }?.model
            if (chosen != null) profile = profile.copy(model = ChatModelChoice(id, chosen))
            pickModel = false
        }, onRefreshCatalog = { container.providers.refreshCatalog(it) }, onManageProviders = {
            addProvider = false; manageProviders = true; pickModel = false
        }, onAddProvider = { addProvider = true; manageProviders = true; pickModel = false },
        onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } }, onAddCustomModel = { id, model, reasoning -> scope.launch { container.providers.addCustomModel(id, model, reasoning) } },
        onDeleteCustomModel = { id, model -> scope.launch { container.providers.removeCustomModel(id, model) } })
    if (manageProviders) com.androidharness.app.ui.settings.ProviderManagerSheet(
        providers = providers, activeProviderId = profile.model?.providerId, apiKeys = container.providers::apiKeys,
        onDismiss = { manageProviders = false; pickModel = true },
        onSetActive = { id -> providers.firstOrNull { it.id == id }?.let { profile = profile.copy(model = ChatModelChoice(id, it.model)) } },
        onDelete = { container.providers.delete(it) }, onEnabledChange = { id, enabled -> container.providers.setEnabled(id, enabled) },
        onMove = { id, direction, visible -> scope.launch { container.providers.move(id, direction, visible) } },
        onSave = { existing, name, type, url, model, keys, headers ->
            scope.launch {
                try {
                    if (existing == null) {
                        val created = container.providers.add(name, type, url, model, keys, headers)
                        profile = profile.copy(model = ChatModelChoice(created.id, created.model))
                    } else container.providers.update(existing.copy(name = name, type = type, baseUrl = url, model = model, headers = headers), keys)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = context.getString(R.string.provider_save_failed) }
            }
        }, catalogs = catalogs, onRefreshCatalog = { container.providers.refreshCatalog(it) }, addProviderInitially = addProvider,
    )
    if (pickSkills) {
        val store = remember { container.skills.forProject(null) }
        val available = remember { store.enabled() }
        var query by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pickSkills = false }, title = { Text(stringResource(R.string.chat_agents_skills_choose)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.chat_agents_search_skills)) }, singleLine = true)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (available.isEmpty()) Text(stringResource(R.string.chat_agents_skills_empty))
                    available.filter { it.name.contains(query, true) || it.description.contains(query, true) }.forEach { meta ->
                        val selected = profile.skills.any { it.name == meta.name }
                        Row(Modifier.fillMaxWidth().clickable {
                            if (selected) profile = profile.copy(skills = profile.skills.filterNot { it.name == meta.name })
                            else {
                                val content = store.view(meta.name).getOrNull()?.content
                                if (content == null || content.length > 8_000 || profile.skills.size >= 6 || profile.skills.sumOf { it.content.length } + content.length > 24_000) {
                                    error = context.getString(R.string.chat_agents_skill_large)
                                } else profile = profile.copy(skills = profile.skills + AgentSkill(meta.name, content))
                            }
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected, null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(meta.name, style = MaterialTheme.typography.titleSmall)
                                Text(meta.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { pickSkills = false }) { Text(stringResource(R.string.chat_agents_done)) } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.chat_agents_discard)) },
        text = { Text(stringResource(R.string.chat_agents_discard_desc)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_agents_discard_action)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.chat_agents_keep_editing)) } })
}

@Composable
private fun AgentEditorGroup(title: String, icon: ImageVector, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyOpen) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().animateContentSize(expressiveSpatialSpec())) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            AnimatedVisibility(expanded, enter = expandVertically(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                exit = shrinkVertically(expressiveSpatialSpec()) + fadeOut(expressiveEffectsSpec())) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
private fun AgentField(value: String, onChange: (String) -> Unit, label: Int, saving: Boolean, lines: Int = 1, hint: Int? = null) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, enabled = !saving, modifier = Modifier.fillMaxWidth(),
        minLines = lines, maxLines = maxOf(lines, 8), singleLine = lines == 1,
        placeholder = hint?.let { { Text(stringResource(it)) } })
}

@Composable
private fun AgentSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}
