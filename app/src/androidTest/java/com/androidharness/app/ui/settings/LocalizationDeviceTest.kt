package com.androidharness.app.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.automation.AutomationScreen
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.localizedOptionLabel
import com.androidharness.app.ui.files.FilesScreen
import com.androidharness.app.ui.setup.SetupScreen
import com.androidharness.app.ui.stats.StatsScreen
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    private var originalAccessibilityFlags = 0

    @Before fun inspectAllTestWindows() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        originalAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    private val originalResources = mutableListOf<Pair<Resources, Configuration>>()

    @Suppress("DEPRECATION")
    @After fun restoreActivityResources() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
        instrumentation.runOnMainSync {
            originalResources.asReversed().forEach { (resources, configuration) ->
                resources.updateConfiguration(configuration, resources.displayMetrics)
            }
        }
    }

    private fun context(locale: String): Context = target.createConfigurationContext(
        Configuration(target.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(locale)) },
    )

    @Suppress("DEPRECATION")
    private fun show(activity: ChatListTestActivity, scale: Float = 1f, locale: String = "zh-CN", content: @Composable () -> Unit) {
        // Native dialog windows use the activity context, outside our composition locals.
        val resources = activity.resources
        if (originalResources.none { it.first === resources })
            originalResources += resources to Configuration(resources.configuration)
        val configuration = Configuration(resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale))
            fontScale = scale
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        activity.window.decorView.layoutDirection = configuration.layoutDirection
        val localized = activity.createConfigurationContext(configuration)
        activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalConfiguration provides localized.resources.configuration,
                LocalLayoutDirection provides if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density, scale),
                LocalDesktopLayout provides false,
            ) { HarnessTheme(dynamicColor = false, content = content) }
        }
    }

    @Test fun englishFallbackAndChineseResourcesKeepFormatsAndDomainLabels() {
        val en = context("en")
        val zh = context("zh-CN")
        val fallback = context("fr")
        assertEquals("Models & providers", en.getString(R.string.settings_page_models_title))
        assertEquals(en.getString(R.string.settings_page_models_title), fallback.getString(R.string.settings_page_models_title))
        assertNotEquals(en.getString(R.string.settings_page_models_title), zh.getString(R.string.settings_page_models_title))
        assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
        assertNotEquals(PermissionMode.CONFIRM_ALL.label, zh.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        assertEquals("1 available model", en.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
        assertEquals("2 available models", en.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
        assertEquals("已从 5 个文件中移除 10 MB", zh.getString(R.string.ui_removed_from_files, "10 MB", "5"))
        assertEquals(" · full shell", en.getString(R.string.ui_full_shell))
    }

    @Test fun chineseSearchOpensTheSameAnchorAndPermissionChoicesWorkAtLargeFont() {
        val zh = context("zh-CN")
        val query = mutableStateOf(zh.getString(R.string.settings_search_max_context_title))
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings(maxContextTokens = 200_000, maxIterations = 75))
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity) {
                SettingsHome(query.value, { query.value = it }, { page, anchor -> selected = page to anchor },
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            // The query field contains the same title; click the result's description.
            click(zh.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("chinese-settings-search.png")
            scenario.onActivity { activity -> show(activity, 1.5f) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {},
                        onLiveUpdates = { state.value = state.value.copy(liveUpdatesEnabled = it) })
                }
            } }
            click(zh.getString(R.string.settings_anchor_default_permission))
            click(zh.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(zh.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("chinese-agent-150-percent.png")
        }
    }

    @Test fun primaryScreensRenderChineseWithoutChangingStoredPreferences() {
        val container = (target.applicationContext as HarnessApp).container
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity) { SetupScreen(container, onFinish = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_welcome_to_androidharness))
            screenshot("chinese-setup.png")
            scenario.onActivity { activity -> show(activity) { StatsScreen(container, onBack = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_model_activity))
            screenshot("chinese-usage.png")
            scenario.onActivity { activity -> show(activity) { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_automation))
            screenshot("chinese-automation.png")
            scenario.onActivity { activity -> show(activity) { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_files))
            screenshot("chinese-files.png")
        }
    }

    @Test fun chineseModelPickerPreservesProviderAndModelIdentifiers() {
        val preference = "localization-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("localization-test-provider", "Provider unchanged",
            ProviderType.OPENAI_COMPAT, "https://unused.invalid/v1", "first-model")
        var selected: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity) {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"), ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> selected = id to model },
                        onRefreshCatalog = { null }, onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(context("zh-CN").getString(R.string.ui_all_models))
                click("Second model")
                screenshot("chinese-model-picker.png")
                click(context("zh-CN").getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", selected)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    @Test fun russianResourcesUseRussianPluralRulesAndKeepDomainValues() {
        val ru = context("ru")
        assertEquals("Модели и провайдеры", ru.getString(R.string.settings_page_models_title))
        assertEquals(context("en").getString(R.string.app_name), ru.getString(R.string.app_name))
        assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
        assertEquals("Подтверждать всё", ru.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        for ((count, word) in listOf(1 to "модель", 2 to "модели", 5 to "моделей", 11 to "моделей",
            21 to "модель", 22 to "модели", 25 to "моделей", 101 to "модель")) {
            assertEquals("$count $word", ru.resources.getQuantityString(R.plurals.ui_model_count, count, count))
            val prefix = when (word) { "модель" -> "Доступна"; "модели" -> "Доступны"; else -> "Доступно" }
            assertEquals("$prefix $count $word", ru.resources.getQuantityString(R.plurals.settings_accounts_models_available, count, count))
        }
        assertEquals("Из 5 файлов удалено 10 MB", ru.getString(R.string.ui_removed_from_files, "10 MB", "5"))
        assertEquals("Обновлено файлов: 2", ru.getString(R.string.ui_updated, "2", "files"))
        assertEquals("Использований: 3 · Файлов: 2", ru.getString(R.string.ui_in, "3", "references", "2", "files"))
    }

    @Test fun russianSearchAndPermissionControlsWorkWithLargeText() {
        val ru = context("ru")
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings())
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "ru") {
                SettingsHome(ru.getString(R.string.settings_search_max_context_title), {},
                    { page, anchor -> selected = page to anchor }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            click(ru.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("russian-settings-search.png")
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "ru") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {}, onLiveUpdates = {})
                }
            } }
            click(ru.getString(R.string.settings_anchor_default_permission))
            click(ru.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(ru.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("russian-agent-150-percent.png")
        }
    }

    @Test fun russianPrimaryScreensAndModelPickerPreserveModelIds() {
        val container = (target.applicationContext as HarnessApp).container
        val ru = context("ru")
        val preference = "russian-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("russian-provider", "Provider unchanged", ProviderType.OPENAI_COMPAT,
            "https://unused.invalid/v1", "first-model")
        var chosen: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity, locale = "ru") { SetupScreen(container, onFinish = {}) } }
                awaitText(ru.getString(R.string.ui_welcome_to_androidharness)); screenshot("russian-setup.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") { StatsScreen(container, onBack = {}) } }
                awaitText(ru.getString(R.string.ui_model_activity)); screenshot("russian-usage.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
                awaitText(ru.getString(R.string.ui_automation)); screenshot("russian-automation.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
                awaitText(ru.getString(R.string.ui_files)); screenshot("russian-files.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"),
                            ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> chosen = id to model }, onRefreshCatalog = { null },
                        onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(ru.getString(R.string.ui_all_models))
                awaitText(ru.getString(R.string.ui_search_models_or_providers))
                awaitText("${provider.name} · ${ru.getString(R.string.ui_current)}")
                click("Second model")
                screenshot("russian-model-picker.png"); click(ru.getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", chosen)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    @Test fun indonesianSearchAndPermissionControlsWorkWithLargeText() {
        val indo = context("id")
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings())
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "id") {
                SettingsHome(indo.getString(R.string.settings_search_max_context_title), {},
                    { page, anchor -> selected = page to anchor }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            click(indo.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("indonesian-settings-search.png")
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "id") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {}, onLiveUpdates = {})
                }
            } }
            click(indo.getString(R.string.settings_anchor_default_permission))
            click(indo.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(indo.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("indonesian-agent-150-percent.png")
        }
    }

    @Test fun indonesianPrimaryScreensAndModelPickerPreserveModelIds() {
        val container = (target.applicationContext as HarnessApp).container
        val indo = context("id")
        val preference = "indonesian-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("indonesian-provider", "Provider unchanged", ProviderType.OPENAI_COMPAT,
            "https://unused.invalid/v1", "first-model")
        var chosen: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity, locale = "id") { SetupScreen(container, onFinish = {}) } }
                awaitText(indo.getString(R.string.ui_welcome_to_androidharness)); screenshot("indonesian-setup.png")
                scenario.onActivity { activity -> show(activity, locale = "id") { StatsScreen(container, onBack = {}) } }
                awaitText(indo.getString(R.string.ui_model_activity)); screenshot("indonesian-usage.png")
                scenario.onActivity { activity -> show(activity, locale = "id") { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
                awaitText(indo.getString(R.string.ui_automation)); screenshot("indonesian-automation.png")
                scenario.onActivity { activity -> show(activity, locale = "id") { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
                awaitText(indo.getString(R.string.ui_files)); screenshot("indonesian-files.png")
                scenario.onActivity { activity -> show(activity, locale = "id") {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"),
                            ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> chosen = id to model }, onRefreshCatalog = { null },
                        onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(indo.getString(R.string.ui_all_models))
                awaitText(indo.getString(R.string.ui_search_models_or_providers))
                awaitText("${provider.name} · ${indo.getString(R.string.ui_current)}")
                click("Second model")
                screenshot("indonesian-model-picker.png"); click(indo.getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", chosen)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    @Test fun arabicSearchAndPermissionControlsWorkWithLargeText() {
        val ar = context("ar")
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings())
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "ar") {
                SettingsHome(ar.getString(R.string.settings_search_max_context_title), {},
                    { page, anchor -> selected = page to anchor }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            click(ar.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("arabic-settings-search.png")
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "ar") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {}, onLiveUpdates = {})
                }
            } }
            click(ar.getString(R.string.settings_anchor_default_permission))
            click(ar.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(ar.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("arabic-agent-150-percent.png")
        }
    }

    @Test fun arabicPrimaryScreensAndModelPickerPreserveModelIds() {
        val container = (target.applicationContext as HarnessApp).container
        val ar = context("ar")
        val preference = "arabic-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("arabic-provider", "Provider unchanged", ProviderType.OPENAI_COMPAT,
            "https://unused.invalid/v1", "first-model")
        var chosen: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity, locale = "ar") { SetupScreen(container, onFinish = {}) } }
                awaitText(ar.getString(R.string.ui_welcome_to_androidharness)); screenshot("arabic-setup.png")
                scenario.onActivity { activity -> show(activity, locale = "ar") { StatsScreen(container, onBack = {}) } }
                awaitText(ar.getString(R.string.ui_model_activity)); screenshot("arabic-usage.png")
                scenario.onActivity { activity -> show(activity, locale = "ar") { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
                awaitText(ar.getString(R.string.ui_automation)); screenshot("arabic-automation.png")
                scenario.onActivity { activity -> show(activity, locale = "ar") { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
                awaitText(ar.getString(R.string.ui_files)); screenshot("arabic-files.png")
                scenario.onActivity { activity -> show(activity, locale = "ar") {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"),
                            ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> chosen = id to model }, onRefreshCatalog = { null },
                        onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(ar.getString(R.string.ui_all_models))
                awaitText(ar.getString(R.string.ui_search_models_or_providers))
                awaitText("${provider.name} · ${ar.getString(R.string.ui_current)}")
                click("Second model")
                screenshot("arabic-model-picker.png"); click(ar.getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", chosen)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    @Test fun japaneseResourcesKeepNativeNamesAndFormatCounts() {
        for (tag in listOf("ja", "ja-JP")) {
            val ja = context(tag)
            assertEquals("モデルとプロバイダー", ja.getString(R.string.settings_page_models_title))
            assertEquals("すべて確認", ja.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
            assertEquals("全自動", ja.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals("フルアクセス", ja.localizedOptionLabel(PermissionMode.FULL_ACCESS.label))
            assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, ja.resources.configuration.layoutDirection)
            for (count in listOf(0, 1, 2, 10, 100, 1_000_000)) {
                assertEquals("利用可能なモデル：$count", ja.resources.getQuantityString(R.plurals.settings_accounts_models_available, count, count))
                assertEquals("${count}行", ja.resources.getQuantityString(R.plurals.answer_table_rows, count, count))
                assertEquals("${count}列", ja.resources.getQuantityString(R.plurals.answer_table_columns, count, count))
            }
            assertEquals(context("en").getString(R.string.app_name), ja.getString(R.string.app_name))
            assertEquals("日本語", ja.getString(R.string.settings_language_japanese))
            assertEquals("Español", ja.getString(R.string.settings_language_spanish))
            assertEquals("Deutsch", ja.getString(R.string.settings_language_german))
            assertEquals("English", ja.getString(R.string.settings_language_english))
            assertEquals("Português (Brasil)", ja.getString(R.string.settings_language_portuguese))
            assertTrue(ja.getString(R.string.settings_root_shizuku_available).contains("Shizuku"))
            assertTrue(ja.getString(R.string.settings_root_shizuku_available).contains("Root"))
            assertEquals("参照：3件 · ファイル：2件", ja.getString(R.string.ui_in, "3", "references", "2", "files"))
        }
    }

    @Test fun spanishResourcesKeepNativeNamesAndFormatCounts() {
        val es = context("es")
        assertEquals("Modelos y proveedores", es.getString(R.string.settings_page_models_title))
        assertEquals("Confirmar todo", es.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        assertEquals("Automático", es.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
        assertEquals("Acceso total", es.localizedOptionLabel(PermissionMode.FULL_ACCESS.label))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, es.resources.configuration.layoutDirection)
        assertEquals("1 modelo disponible", es.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
        assertEquals("2 modelos disponibles", es.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
        for (count in listOf(0, 1, 2, 10, 100, 1_000_000)) {
            val value = es.resources.getQuantityString(R.plurals.answer_table_rows, count, count)
            assertTrue(value, value.contains(count.toString()))
            assertFalse(value, value.contains("%1"))
        }
        assertEquals("0 filas", es.resources.getQuantityString(R.plurals.answer_table_rows, 0, 0))
        assertEquals("1 fila", es.resources.getQuantityString(R.plurals.answer_table_rows, 1, 1))
        assertEquals(context("en").getString(R.string.app_name), es.getString(R.string.app_name))
        assertEquals("Español", es.getString(R.string.settings_language_spanish))
        assertEquals("Deutsch", es.getString(R.string.settings_language_german))
        assertEquals("English", es.getString(R.string.settings_language_english))
        assertEquals("Português (Brasil)", es.getString(R.string.settings_language_portuguese))
        assertEquals("Bahasa Indonesia", es.getString(R.string.settings_language_indonesian))
        assertTrue(es.getString(R.string.settings_root_shizuku_available).contains("Shizuku"))
        assertTrue(es.getString(R.string.settings_root_shizuku_available).contains("Root"))
    }

    @Test fun germanResourcesKeepNativeNamesAndFormatCounts() {
        val de = context("de")
        assertEquals("Modelle und Anbieter", de.getString(R.string.settings_page_models_title))
        assertEquals("Alles bestätigen", de.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        assertEquals("Vollautomatisch", de.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
        assertEquals("Vollzugriff", de.localizedOptionLabel(PermissionMode.FULL_ACCESS.label))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, de.resources.configuration.layoutDirection)
        assertEquals("1 verfügbares Modell", de.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
        assertEquals("2 verfügbare Modelle", de.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
        for (count in listOf(0, 1, 2, 10, 100, 1_000_000)) {
            val value = de.resources.getQuantityString(R.plurals.answer_table_rows, count, count)
            assertTrue(value, value.contains(count.toString()))
            assertFalse(value, value.contains("%1"))
        }
        assertEquals("0 Zeilen", de.resources.getQuantityString(R.plurals.answer_table_rows, 0, 0))
        assertEquals("1 Zeile", de.resources.getQuantityString(R.plurals.answer_table_rows, 1, 1))
        assertEquals(context("en").getString(R.string.app_name), de.getString(R.string.app_name))
        assertEquals("Deutsch", de.getString(R.string.settings_language_german))
        assertEquals("English", de.getString(R.string.settings_language_english))
        assertEquals("Português (Brasil)", de.getString(R.string.settings_language_portuguese))
        assertEquals("Bahasa Indonesia", de.getString(R.string.settings_language_indonesian))
        assertTrue(de.getString(R.string.settings_root_shizuku_available).contains("Shizuku"))
        assertTrue(de.getString(R.string.settings_root_shizuku_available).contains("Root"))
    }

    @Test fun brazilianPortugueseResourcesKeepNativeNamesAndFormatCounts() {
        val pt = context("pt-BR")
        assertEquals("Modelos e provedores", pt.getString(R.string.settings_page_models_title))
        assertEquals("Confirmar tudo", pt.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        assertEquals("Totalmente automático", pt.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
        assertEquals("Acesso total", pt.localizedOptionLabel(PermissionMode.FULL_ACCESS.label))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, pt.resources.configuration.layoutDirection)
        assertEquals("1 modelo disponível", pt.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
        assertEquals("2 modelos disponíveis", pt.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
        for (count in listOf(0, 1, 2, 10, 100, 1_000_000)) {
            val value = pt.resources.getQuantityString(R.plurals.answer_table_rows, count, count)
            assertTrue(value, value.contains(count.toString()))
            assertFalse(value, value.contains("%1"))
        }
        assertEquals(context("en").getString(R.string.app_name), pt.getString(R.string.app_name))
        assertEquals("Português (Brasil)", pt.getString(R.string.settings_language_portuguese))
        assertEquals("English", pt.getString(R.string.settings_language_english))
        assertEquals("Bahasa Indonesia", pt.getString(R.string.settings_language_indonesian))
        assertEquals("Remover \"Example\" da lista? Os arquivos no disco não são excluídos.",
            pt.getString(R.string.settings_workspace_delete_message, "Example"))
        assertTrue(pt.getString(R.string.settings_root_shizuku_available).contains("Shizuku"))
        assertTrue(pt.getString(R.string.settings_root_shizuku_available).contains("Root"))
    }

    @Test fun brazilianPortugueseSearchAndPermissionControlsWorkWithLargeText() = checkSearchAndPermissionControls("pt-BR", "portuguese-brazil")

    @Test fun germanSearchAndPermissionControlsWorkWithLargeText() = checkSearchAndPermissionControls("de", "german")

    @Test fun spanishSearchAndPermissionControlsWorkWithLargeText() = checkSearchAndPermissionControls("es", "spanish")

    @Test fun japaneseSearchAndPermissionControlsWorkWithLargeText() = checkSearchAndPermissionControls("ja", "japanese")

    private fun checkSearchAndPermissionControls(locale: String, screenshotPrefix: String) {
        val localized = context(locale)
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings())
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = locale) {
                SettingsHome(localized.getString(R.string.settings_search_max_context_title), {},
                    { page, anchor -> selected = page to anchor }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            click(localized.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("$screenshotPrefix-settings-search.png")
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = locale) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {}, onLiveUpdates = {})
                }
            } }
            click(localized.getString(R.string.settings_anchor_default_permission))
            click(localized.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(localized.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("$screenshotPrefix-agent-150-percent.png")
        }
    }

    @Test fun brazilianPortuguesePrimaryScreensAndModelPickerPreserveModelIds() = checkPrimaryScreensAndModelPicker("pt-BR", "portuguese-brazil")

    @Test fun germanPrimaryScreensAndModelPickerPreserveModelIds() = checkPrimaryScreensAndModelPicker("de", "german")

    @Test fun spanishPrimaryScreensAndModelPickerPreserveModelIds() = checkPrimaryScreensAndModelPicker("es", "spanish")

    @Test fun japanesePrimaryScreensAndModelPickerPreserveModelIds() = checkPrimaryScreensAndModelPicker("ja", "japanese")

    private fun checkPrimaryScreensAndModelPicker(locale: String, screenshotPrefix: String) {
        val container = (target.applicationContext as HarnessApp).container
        val localized = context(locale)
        val preference = "$screenshotPrefix-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("$screenshotPrefix-provider", "Provider unchanged", ProviderType.OPENAI_COMPAT,
            "https://unused.invalid/v1", "first-model")
        var chosen: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity, locale = locale) { SetupScreen(container, onFinish = {}) } }
                awaitText(localized.getString(R.string.ui_welcome_to_androidharness)); screenshot("$screenshotPrefix-setup.png")
                scenario.onActivity { activity -> show(activity, locale = locale) { StatsScreen(container, onBack = {}) } }
                awaitText(localized.getString(R.string.ui_model_activity)); screenshot("$screenshotPrefix-usage.png")
                scenario.onActivity { activity -> show(activity, locale = locale) { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
                awaitText(localized.getString(R.string.ui_automation)); screenshot("$screenshotPrefix-automation.png")
                scenario.onActivity { activity -> show(activity, locale = locale) { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
                awaitText(localized.getString(R.string.ui_files)); screenshot("$screenshotPrefix-files.png")
                scenario.onActivity { activity -> show(activity, locale = locale) {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"),
                            ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> chosen = id to model }, onRefreshCatalog = { null },
                        onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(localized.getString(R.string.ui_all_models))
                awaitText(localized.getString(R.string.ui_search_models_or_providers))
                awaitText("${provider.name} · ${localized.getString(R.string.ui_current)}")
                click(localized.getString(R.string.ui_choose_from, "Second model", provider.name))
                screenshot("$screenshotPrefix-model-picker.png"); click(localized.getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", chosen)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    @Test fun persianSearchAndPermissionControlsWorkWithLargeText() {
        val fa = context("fa")
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings())
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "fa") {
                SettingsHome(fa.getString(R.string.settings_search_max_context_title), {},
                    { page, anchor -> selected = page to anchor }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            click(fa.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("persian-settings-search.png")
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "fa") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {}, onLiveUpdates = {})
                }
            } }
            click(fa.getString(R.string.settings_anchor_default_permission))
            click(fa.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(fa.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("persian-agent-150-percent.png")
        }
    }

    @Test fun persianPrimaryScreensAndModelPickerPreserveModelIds() {
        val container = (target.applicationContext as HarnessApp).container
        val fa = context("fa")
        val preference = "persian-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("persian-provider", "Provider unchanged", ProviderType.OPENAI_COMPAT,
            "https://unused.invalid/v1", "first-model")
        var chosen: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity, locale = "fa") { SetupScreen(container, onFinish = {}) } }
                awaitText(fa.getString(R.string.ui_welcome_to_androidharness)); screenshot("persian-setup.png")
                scenario.onActivity { activity -> show(activity, locale = "fa") { StatsScreen(container, onBack = {}) } }
                awaitText(fa.getString(R.string.ui_model_activity)); screenshot("persian-usage.png")
                scenario.onActivity { activity -> show(activity, locale = "fa") { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
                awaitText(fa.getString(R.string.ui_automation)); screenshot("persian-automation.png")
                scenario.onActivity { activity -> show(activity, locale = "fa") { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
                awaitText(fa.getString(R.string.ui_files)); screenshot("persian-files.png")
                scenario.onActivity { activity -> show(activity, locale = "fa") {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"),
                            ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> chosen = id to model }, onRefreshCatalog = { null },
                        onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(fa.getString(R.string.ui_all_models))
                awaitText(fa.getString(R.string.ui_search_models_or_providers))
                awaitText("${provider.name} · ${fa.getString(R.string.ui_current)}")
                click("Second model")
                screenshot("persian-model-picker.png"); click(fa.getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", chosen)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    @Test fun persianResourcesKeepDomainValuesAndFormatEveryCount() {
        val fa = context("fa")
        assertEquals(context("en").getString(R.string.app_name), fa.getString(R.string.app_name))
        for (id in listOf(R.string.settings_language_english, R.string.settings_language_chinese,
            R.string.settings_language_russian, R.string.settings_language_arabic,
            R.string.settings_language_indonesian, R.string.settings_language_persian, R.string.settings_language_portuguese, R.string.settings_language_german, R.string.settings_language_spanish, R.string.settings_language_japanese)) {
            assertEquals("Language autonyms stay recognizable", context("en").getString(id), fa.getString(id))
        }
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, fa.resources.configuration.layoutDirection)
        assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
        assertTrue(fa.getString(R.string.settings_root_shizuku_available).contains("Shizuku"))
        assertTrue(fa.getString(R.string.settings_root_shizuku_available).contains("Root"))
        for (count in listOf(0, 1, 2, 3, 10, 11, 99, 100, 101)) {
            val number = String.format(java.util.Locale.forLanguageTag("fa"), "%d", count)
            val value = fa.resources.getQuantityString(R.plurals.settings_accounts_models_available, count, count)
            assertTrue(value, value.contains(number))
            assertFalse(value, value.contains("%1"))
        }
        val summary = fa.getString(R.string.sync_preview_counts, 2, 3, 4)
        for (count in 2..4) assertTrue(summary, summary.contains(String.format(java.util.Locale.forLanguageTag("fa"), "%d", count)))
        val fileSummary = fa.getString(R.string.ui_in, "3", "references", "4", "files")
        assertTrue(fileSummary, fileSummary.contains("3") && fileSummary.contains("4"))
        assertFalse(fileSummary, fileSummary.contains("references") || fileSummary.contains("files"))
    }

    @Test fun arabicResourcesSelectAllPluralFormsAndKeepDomainValues() {
        val ar = context("ar")
        assertEquals("النماذج والمزوّدون", ar.getString(R.string.settings_page_models_title))
        assertEquals(context("en").getString(R.string.app_name), ar.getString(R.string.app_name))
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, ar.resources.configuration.layoutDirection)
        assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
        assertEquals("تأكيد كل إجراء", ar.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        for (count in listOf(0, 1, 2, 3, 10, 11, 99, 100, 101, 102, 103)) {
            val number = String.format(java.util.Locale.forLanguageTag("ar"), "%d", count)
            val expected = when {
                count == 0 -> "لا نماذج ($number)"
                count == 1 -> "نموذج واحد ($number)"
                count == 2 -> "نموذجان ($number)"
                count % 100 in 3..10 -> "$number نماذج"
                count % 100 in 11..99 -> "$number نموذجاً"
                else -> "$number نموذج"
            }
            assertEquals("Count $count", expected, ar.resources.getQuantityString(R.plurals.ui_model_count, count, count))
            val available = when {
                count == 0 -> "لا توجد نماذج متاحة ($number)"
                count == 1 -> "نموذج واحد متاح ($number)"
                count == 2 -> "نموذجان متاحان ($number)"
                count % 100 in 3..10 -> "$number نماذج متاحة"
                count % 100 in 11..99 -> "$number نموذجاً متاحاً"
                else -> "$number نموذج متاح"
            }
            assertEquals(available, ar.resources.getQuantityString(R.plurals.settings_accounts_models_available, count, count))
        }
        assertEquals("عدد الملفات المحدّثة: 2", ar.getString(R.string.ui_updated, "2", "files"))
        assertEquals("المراجع: 3 · الملفات: 2", ar.getString(R.string.ui_in, "3", "references", "2", "files"))
    }

    @Test fun arabicRichRepliesKeepMixedContentAndCopyOriginalCodeAndTableValues() {
        val ar = context("ar")
        val code = "const path = \"/src/ملف.kt\";\nconsole.log(path);"
        val answer = "# شرح بالعربية\n\nالنموذج GPT-5 · المسار /src/Main.kt · [المصدر](https://example.com/a?x=1&y=2)\n\n```javascript\n$code\n```\n\nرياضيات: ${'$'}x^2 = 4${'$'}"
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "ar") {
                com.androidharness.app.ui.chat.components.ChatAppearance {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        com.androidharness.app.ui.chat.components.AssistantText(answer)
                    }
                }
            } }
            awaitText("شرح بالعربية")
            val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
            val ready = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                fun inspect(view: android.view.View) {
                    if (view is android.webkit.WebView) view.evaluateJavascript("document.querySelector('.katex') !== null") {
                        rendered.set(it == "true"); ready.countDown()
                    }
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                }
                // Equations render asynchronously from bundled assets.
                activity.window.decorView.postDelayed({ inspect(activity.window.decorView) }, 2500)
            }
            assertTrue("Arabic reply must contain rendered math", ready.await(5, java.util.concurrent.TimeUnit.SECONDS) && rendered.get())
            screenshot("arabic-markdown-code-math.png")
            click(ar.getString(R.string.settings_telegram_copy_code))
            instrumentation.runOnMainSync {
                val clipboard = target.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            val table = """```harness-ui
                {"type":"table","title":"مقارنة النماذج","columns":["النموذج","المسار"],"rows":[["GPT-5","/src/ملف.kt"],["Claude","https://example.com/a?x=1&y=2"]]}
                ```""".trimIndent()
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "ar") {
                com.androidharness.app.ui.chat.components.ChatAppearance {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        com.androidharness.app.ui.chat.components.AssistantText(table)
                    }
                }
            } }
            awaitText(ar.getString(R.string.rich_filter_rows))
            screenshot("arabic-rich-table-150-percent.png")
            click(ar.getString(R.string.rich_copy))
            instrumentation.runOnMainSync {
                val copied = target.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied.contains("GPT-5"))
                assertTrue(copied.contains("/src/ملف.kt"))
                assertTrue(copied.contains("https://example.com/a?x=1&y=2"))
            }
        }
    }

    @Test fun indonesianRichRepliesKeepMixedContentAndCopyOriginalCodeAndTableValues() {
        val indo = context("id")
        val code = "const path = \"/src/ملف.kt\";\nconsole.log(path);"
        val answer = "# Penjelasan dalam bahasa Indonesia\n\nModel GPT-5 · jalur /src/Main.kt · [Sumber](https://example.com/a?x=1&y=2)\n\n```javascript\n$code\n```\n\nMatematika: ${'$'}x^2 = 4${'$'}"
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "id") {
                com.androidharness.app.ui.chat.components.ChatAppearance {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        com.androidharness.app.ui.chat.components.AssistantText(answer)
                    }
                }
            } }
            awaitText("Penjelasan dalam bahasa Indonesia")
            val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
            val ready = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                fun inspect(view: android.view.View) {
                    if (view is android.webkit.WebView) view.evaluateJavascript("document.querySelector('.katex') !== null") {
                        rendered.set(it == "true"); ready.countDown()
                    }
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                }
                // Equations render asynchronously from bundled assets.
                activity.window.decorView.postDelayed({ inspect(activity.window.decorView) }, 2500)
            }
            assertTrue("Indonesian reply must contain rendered math", ready.await(5, java.util.concurrent.TimeUnit.SECONDS) && rendered.get())
            screenshot("indonesian-markdown-code-math.png")
            click(indo.getString(R.string.settings_telegram_copy_code))
            instrumentation.runOnMainSync {
                val clipboard = target.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            val table = """```harness-ui
                {"type":"table","title":"Perbandingan model","columns":["Model","Jalur"],"rows":[["GPT-5","/src/ملف.kt"],["Claude","https://example.com/a?x=1&y=2"]]}
                ```""".trimIndent()
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "id") {
                com.androidharness.app.ui.chat.components.ChatAppearance {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        com.androidharness.app.ui.chat.components.AssistantText(table)
                    }
                }
            } }
            awaitText(indo.getString(R.string.rich_filter_rows))
            screenshot("indonesian-rich-table-150-percent.png")
            click(indo.getString(R.string.rich_copy))
            instrumentation.runOnMainSync {
                val copied = target.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied.contains("GPT-5"))
                assertTrue(copied.contains("/src/ملف.kt"))
                assertTrue(copied.contains("https://example.com/a?x=1&y=2"))
            }
        }
    }

    @Test fun indonesianResourcesUseInvariantPluralsAndPreserveDomainValues() {
        for (tag in listOf("id", "in", "id-ID")) {
            val indo = context(tag)
            assertEquals("Model & penyedia", indo.getString(R.string.settings_page_models_title))
            assertEquals(context("en").getString(R.string.app_name), indo.getString(R.string.app_name))
            assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, indo.resources.configuration.layoutDirection)
            assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
            assertEquals("Konfirmasi semua tindakan", indo.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
            for (count in listOf(0, 1, 2, 5, 11, 21, 100, 101)) {
                assertEquals("$count model", indo.resources.getQuantityString(R.plurals.ui_model_count, count, count))
                assertEquals("$count model tersedia", indo.resources.getQuantityString(R.plurals.settings_accounts_models_available, count, count))
            }
            assertEquals("2 berkas diperbarui", indo.getString(R.string.ui_updated, "2", "files"))
            assertEquals("3 referensi di 2 berkas", indo.getString(R.string.ui_in, "3", "references", "2", "files"))
        }
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            // Older Android versions merge a button's title, value and description into one node.
            if (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any {
                it == text || it.lineSequence().any { line -> line == text }
            }) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        // Android 9 can report no active root immediately after a dialog is dismissed.
        return search(automation.rootInActiveWindow)
            ?: automation.windows.firstNotNullOfOrNull { search(it.root) }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            node(text)?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("localization-failure.png")
        val visible = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            visible += "${node.text} | ${node.contentDescription}"
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        error("Missing localized control: $text; visible: $visible")
    }

    private fun click(text: String) {
        var current: AccessibilityNodeInfo? = awaitText(text)
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(150)
                return
            }
            current = current.parent
        }
        error("Localized control is not clickable: $text")
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(target.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
