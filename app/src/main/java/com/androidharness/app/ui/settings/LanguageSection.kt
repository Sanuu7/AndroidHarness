package com.androidharness.app.ui.settings

import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.androidharness.app.R
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.RadioButton

internal const val TRANSLATION_PROJECT_URL = "https://poeditor.com/join/project/ZPbHNa9WWc"

@Composable
internal fun LanguageSection() {
    val locales = AppCompatDelegate.getApplicationLocales()
    val selected = when {
        locales.isEmpty -> ""
        locales[0]?.language == "zh" -> "zh-CN"
        locales[0]?.language == "ru" -> "ru"
        locales[0]?.language == "ar" -> "ar"
        locales[0]?.language == "fa" -> "fa"
        locales[0]?.language == "pt" -> "pt-BR"
        locales[0]?.language == "de" -> "de"
        locales[0]?.language == "es" -> "es"
        locales[0]?.language == "ja" -> "ja"
        locales[0]?.language == "id" || locales[0]?.language == "in" -> "id"
        locales[0]?.language == "si" -> "si"
        else -> "en"
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val languages = listOf(
        "" to R.string.settings_language_system,
        "en" to R.string.settings_language_english,
        "zh-CN" to R.string.settings_language_chinese,
        "ru" to R.string.settings_language_russian,
        "ar" to R.string.settings_language_arabic,
        "fa" to R.string.settings_language_persian,
        "pt-BR" to R.string.settings_language_portuguese,
        "de" to R.string.settings_language_german,
        "es" to R.string.settings_language_spanish,
        "ja" to R.string.settings_language_japanese,
        "id" to R.string.settings_language_indonesian,
        "si" to R.string.settings_language_sinhala,
    )

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.selectableGroup()) {
            languages.forEachIndexed { index, (tag, title) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .selectable(
                            selected = tag == selected,
                            role = Role.RadioButton,
                            onClick = {
                                if (tag != selected) {
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                                }
                            },
                        ).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                        if (tag.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_language_system_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    RadioButton(selected = tag == selected, onClick = null)
                }
                if (index < languages.lastIndex) HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }

    SettingsAnchor(R.string.settings_language_contribute_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_language_contribute_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_language_contribute_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = {
                    runCatching { uriHandler.openUri(TRANSLATION_PROJECT_URL) }.onFailure {
                        Toast.makeText(context, R.string.settings_language_link_failed, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_language_contribute_action))
                }
                Text(
                    stringResource(R.string.settings_language_contribute_release_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
