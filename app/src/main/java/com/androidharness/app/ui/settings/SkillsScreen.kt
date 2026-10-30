package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.skills.SkillMeta
import com.androidharness.app.skills.SkillParseException
import com.androidharness.app.skills.SkillParser
import com.androidharness.app.skills.SkillSource
import com.androidharness.app.tools.skillTemplate
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
fun SkillsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val skills = remember(settings.disabledSkills, settings.cavemanInstalled) { container.skills.list() }
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SkillMeta?>(null) }

    val bundled = skills.filter { it.source == SkillSource.BUNDLED }
    val user = skills.filter { it.source == SkillSource.USER }
    val project = skills.filter { it.source == SkillSource.PROJECT }
    val enabledCount = skills.count { it.enabled }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppHeader(
                title = stringResource(R.string.settings_page_skills_title),
                subtitle = stringResource(R.string.settings_skills_enabled_count, enabledCount.toString(), bundled.size.toString()),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_skills_add))
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                uiContext.getString(R.string.ui_the_model_sees_these_names_every_turn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SkillGroup(uiContext.getString(R.string.ui_bundled), bundled, container, scope, onEdit = null)
            if (user.isNotEmpty()) {
                SkillGroup(uiContext.getString(R.string.ui_your_skills), user, container, scope, onEdit = { editing = it })
            }
            if (project.isNotEmpty()) {
                SkillGroup(uiContext.getString(R.string.ui_this_workspace), project, container, scope, onEdit = null)
            }
            Spacer(Modifier.height(72.dp))
        }
    }

    if (showAdd) {
        SkillEditorDialog(
            title = stringResource(R.string.settings_skills_add),
            initial = skillTemplate(),
            onDismiss = { showAdd = false },
            onSave = { content ->
                container.skills.saveUser(content)
                showAdd = false
            },
        )
    }
    editing?.let { skill ->
        val current = container.skills.view(skill.name).getOrNull()?.content ?: skillTemplate(skill.name, skill.description)
        SkillEditorDialog(
            title = stringResource(R.string.settings_skills_edit_title, skill.name),
            initial = current,
            onDismiss = { editing = null },
            onSave = { content ->
                container.skills.saveUser(content)
                editing = null
            },
        )
    }
}

@Composable
private fun SkillGroup(
    title: String,
    skills: List<SkillMeta>,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onEdit: ((SkillMeta) -> Unit)?,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            skills.forEachIndexed { index, skill ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            skill.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = HarnessMono,
                        )
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onEdit != null) {
                        IconButton(onClick = { onEdit(skill) }) {
                            Text(stringResource(R.string.settings_action_edit), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { container.skills.deleteUser(skill.name) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_action_delete), modifier = Modifier.padding(0.dp))
                        }
                    }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { on ->
                            scope.launch { container.settings.setSkillEnabled(skill.name, on) }
                        },
                    )
                }
                if (index != skills.lastIndex) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = runCatching { SkillParser.parse(text) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = runCatching { SkillParser.validateUserContent(it); null }.exceptionOrNull()?.message
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp, max = 420.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                    label = { Text(stringResource(R.string.settings_skills_file)) },
                )
                Text(
                    uiContext.getString(R.string.ui_catalog_line, (parsed?.name ?: "?").toString(), (parsed?.catalogDescription ?: "fix frontmatter").toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        SkillParser.validateUserContent(text)
                        onSave(text)
                    } catch (e: SkillParseException) {
                        error = e.message
                    }
                },
                enabled = error == null,
            ) { Text(stringResource(R.string.settings_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}
