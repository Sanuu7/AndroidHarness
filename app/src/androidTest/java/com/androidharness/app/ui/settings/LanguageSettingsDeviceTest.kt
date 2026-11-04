package com.androidharness.app.ui.settings

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val container get() = (target.applicationContext as HarnessApp).container

    private var previousOnboarding = false

    @Before fun openAnAlreadyConfiguredApp() = runBlocking {
        // First-launch notification prompts must not cover the language picker on Android 13+.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            target.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        previousOnboarding = container.settings.settings.first().onboardingDone
        container.settings.setOnboardingDone(true)
    }

    @After fun restoreSetupState() = runBlocking {
        container.settings.setOnboardingDone(previousOnboarding)
    }

    private fun label(id: Int, tags: String): String = target.createConfigurationContext(
        Configuration(target.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) },
    ).getString(id)

    @Test fun pickerSwitchesTheRealActivityPersistsAndClearsTheOverride() {
        var original = LocaleListCompat.getEmptyLocaleList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                original = AppCompatDelegate.getApplicationLocales()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
            try {
                openLanguage(scenario)
                scrollTo(label(R.string.settings_language_contribute_title, "en"))
                click("简体中文")
                scrollTo(label(R.string.settings_language_contribute_title, "zh-CN"))
                scenario.onActivity {
                    assertEquals("zh", it.resources.configuration.locales[0].language)
                    assertEquals("zh-CN", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                }
                screenshot("language-chinese-phone.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "zh-CN"))
                scenario.onActivity { assertEquals("zh", it.resources.configuration.locales[0].language) }

                click("Русский")
                scrollTo(label(R.string.settings_language_contribute_title, "ru"))
                scenario.onActivity {
                    assertEquals("ru", it.resources.configuration.locales[0].language)
                    assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                }
                screenshot("language-russian-phone.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "ru"))
                scenario.onActivity { assertEquals("ru", it.resources.configuration.locales[0].language) }

                click("العربية")
                scrollTo(label(R.string.settings_language_contribute_title, "ar"))
                scenario.onActivity {
                    assertEquals("ar", it.resources.configuration.locales[0].language)
                    assertEquals("ar", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.window.decorView.layoutDirection)
                }
                screenshot("language-arabic.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "ar"))
                scenario.onActivity { assertEquals("ar", it.resources.configuration.locales[0].language) }

                click("فارسی")
                scrollTo(label(R.string.settings_language_contribute_title, "fa"))
                scenario.onActivity {
                    assertEquals("fa", it.resources.configuration.locales[0].language)
                    assertEquals("fa", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.window.decorView.layoutDirection)
                }
                screenshot("language-persian-phone.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "fa"))
                scenario.onActivity { assertEquals("fa", it.resources.configuration.locales[0].language) }

                // Japanese must restore LTR layout after Persian, including on legacy Android.
                click("日本語")
                scrollTo(label(R.string.settings_language_contribute_title, "ja"))
                scenario.onActivity {
                    assertEquals("ja", it.resources.configuration.locales[0].language)
                    assertEquals("ja", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.window.decorView.layoutDirection)
                }
                screenshot("language-japanese-phone.png")
                scenario.recreate()
                scrollTo("日本語")
                scenario.onActivity { assertEquals("ja", it.resources.configuration.locales[0].language) }
                var japanese = find("日本語")
                while (japanese != null && !japanese.isCheckable) japanese = japanese.parent
                assertTrue("Japanese remains selected after recreation", japanese?.isChecked == true)

                click("Español")
                scrollTo(label(R.string.settings_language_contribute_title, "es"))
                scenario.onActivity {
                    assertEquals("es", it.resources.configuration.locales[0].language)
                    assertEquals("es", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.window.decorView.layoutDirection)
                }
                screenshot("language-spanish-phone.png")
                scenario.recreate()
                scrollTo("Español")
                scenario.onActivity { assertEquals("es", it.resources.configuration.locales[0].language) }
                var spanish = find("Español")
                while (spanish != null && !spanish.isCheckable) spanish = spanish.parent
                assertTrue("Spanish remains selected after recreation", spanish?.isChecked == true)

                click("Deutsch")
                scrollTo(label(R.string.settings_language_contribute_title, "de"))
                scenario.onActivity {
                    assertEquals("de", it.resources.configuration.locales[0].language)
                    assertEquals("de", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.window.decorView.layoutDirection)
                }
                screenshot("language-german-phone.png")
                scenario.recreate()
                scrollTo("Deutsch")
                scenario.onActivity { assertEquals("de", it.resources.configuration.locales[0].language) }
                var german = find("Deutsch")
                while (german != null && !german.isCheckable) german = german.parent
                assertTrue("German remains selected after recreation", german?.isChecked == true)

                scrollTo("Português (Brasil)")
                click("Português (Brasil)")
                scrollTo(label(R.string.settings_language_contribute_title, "pt-BR"))
                scenario.onActivity {
                    assertEquals("pt-BR", it.resources.configuration.locales[0].toLanguageTag())
                    assertEquals("pt-BR", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.window.decorView.layoutDirection)
                }
                screenshot("language-portuguese-brazil-phone.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "pt-BR"))
                scenario.onActivity { assertEquals("pt-BR", it.resources.configuration.locales[0].toLanguageTag()) }
                var portuguese = find("Português (Brasil)")
                while (portuguese != null && !portuguese.isCheckable) portuguese = portuguese.parent
                assertTrue("Brazilian Portuguese remains selected after recreation", portuguese?.isChecked == true)

                scrollTo("Bahasa Indonesia")
                click("Bahasa Indonesia")
                scrollTo(label(R.string.settings_language_contribute_title, "id"))
                scenario.onActivity {
                    assertEquals("id", it.resources.configuration.locales[0].toLanguageTag().substringBefore('-'))
                    assertEquals("id", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.window.decorView.layoutDirection)
                }
                screenshot("language-indonesian.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "id"))
                scenario.onActivity {
                    assertEquals("id", it.resources.configuration.locales[0].toLanguageTag().substringBefore('-'))
                    // Older Android uses the legacy language code internally.
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("in"))
                }
                scrollTo(label(R.string.settings_language_contribute_title, "id"))
                var chosen = find("Bahasa Indonesia")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Indonesian remains selected with the legacy locale code", chosen?.isChecked == true)

                // Explicit English must work even after every translated locale override.
                click("English")
                scrollTo(label(R.string.settings_language_contribute_title, "en"))
                scenario.onActivity { assertEquals("en", it.resources.configuration.locales[0].language) }
                click("Bahasa Indonesia")
                scrollTo(label(R.string.settings_language_system, "id"))
                click(label(R.string.settings_language_system, "id"))
                val systemTags = if (android.os.Build.VERSION.SDK_INT >= 33)
                    target.getSystemService(android.app.LocaleManager::class.java).systemLocales.toLanguageTags()
                else Resources.getSystem().configuration.locales.toLanguageTags()
                // The top bar stays visible when clearing the override resets the scroll position.
                awaitText(label(R.string.settings_page_language_title, systemTags))
                scenario.onActivity {
                    assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
                    assertEquals(label(R.string.settings_page_language_title, systemTags),
                        it.getString(R.string.settings_page_language_title))
                }
                screenshot("language-system-default-phone.png")
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun sinhalaSelectionSurvivesRecreationAndReturnsToSystemDefault() {
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                openLanguage(scenario)
                scrollTo("සිංහල")
                click("සිංහල")
                awaitText(label(R.string.settings_page_language_title, "si"))
                scenario.onActivity {
                    assertEquals("si", it.resources.configuration.locales[0].language)
                    assertEquals("si", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals("යෙදුමේ භාෂාව", it.getString(R.string.settings_page_language_title))
                    assertEquals("මාදිලි සහ සැපයුම්කරුවන්", it.getString(R.string.settings_page_models_title))
                }
                screenshot("language-sinhala-phone.png")
                scenario.recreate()
                scrollTo("සිංහල")
                scenario.onActivity { assertEquals("si", it.resources.configuration.locales[0].language) }
                var chosen = find("සිංහල")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Sinhala remains selected after recreation", chosen?.isChecked == true)
                scrollTo(label(R.string.settings_language_system, "si"))
                click(label(R.string.settings_language_system, "si"))
                scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun vietnameseSelectionSurvivesRecreationAndReturnsToSystemDefault() {
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                openLanguage(scenario)
                scrollTo("Tiếng Việt")
                click("Tiếng Việt")
                awaitText(label(R.string.settings_page_language_title, "vi"))
                scenario.onActivity {
                    assertEquals("vi", it.resources.configuration.locales[0].language)
                    assertEquals("vi", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals("Ngôn ngữ ứng dụng", it.getString(R.string.settings_page_language_title))
                    assertEquals("Mô hình và nhà cung cấp", it.getString(R.string.settings_page_models_title))
                }
                screenshot("language-vietnamese-phone.png")
                scenario.recreate()
                scrollTo("Tiếng Việt")
                scenario.onActivity { assertEquals("vi", it.resources.configuration.locales[0].language) }
                var chosen = find("Tiếng Việt")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Vietnamese remains selected after recreation", chosen?.isChecked == true)
                scrollTo(label(R.string.settings_language_system, "vi"))
                click(label(R.string.settings_language_system, "vi"))
                scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun turkishSelectionSurvivesRecreationAndReturnsToSystemDefault() {
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                openLanguage(scenario)
                scrollTo("Türkçe")
                click("Türkçe")
                awaitText(label(R.string.settings_page_language_title, "tr"))
                scenario.onActivity {
                    assertEquals("tr", it.resources.configuration.locales[0].language)
                    assertEquals("tr", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals("Uygulama dili", it.getString(R.string.settings_page_language_title))
                    assertEquals("Modeller ve sağlayıcılar", it.getString(R.string.settings_page_models_title))
                    assertEquals("1 kullanılabilir model", it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
                    assertEquals("2 kullanılabilir model", it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
                    assertEquals("Groq · 2 anahtar kaydedildi", it.resources.getQuantityString(R.plurals.search_keys_saved, 2, 2, "Groq"))
                    assertEquals(it.getString(R.string.ui_installed), it.getString(R.string.pm_installed))
                }
                screenshot("language-turkish-phone.png")
                scenario.recreate()
                scrollTo("Türkçe")
                scenario.onActivity { assertEquals("tr", it.resources.configuration.locales[0].language) }
                var chosen = find("Türkçe")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Turkish remains selected after recreation", chosen?.isChecked == true)
                scrollTo(label(R.string.settings_language_system, "tr"))
                click(label(R.string.settings_language_system, "tr"))
                scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun malaySelectionSurvivesRecreationAndReturnsToSystemDefault() {
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                openLanguage(scenario)
                scrollTo("Bahasa Melayu")
                click("Bahasa Melayu")
                awaitText(label(R.string.settings_page_language_title, "ms"))
                scenario.onActivity {
                    assertEquals("ms", it.resources.configuration.locales[0].language)
                    assertEquals("ms", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals("Bahasa apl", it.getString(R.string.settings_page_language_title))
                    assertEquals("Model dan penyedia", it.getString(R.string.settings_page_models_title))
                    assertEquals("1 model tersedia", it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
                    assertEquals("2 model tersedia", it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
                    assertEquals("Groq · 2 kunci disimpan", it.resources.getQuantityString(R.plurals.search_keys_saved, 2, 2, "Groq"))
                    assertEquals(it.getString(R.string.ui_installed), it.getString(R.string.pm_installed))
                }
                screenshot("language-malay-phone.png")
                scenario.recreate()
                scrollTo("Bahasa Melayu")
                scenario.onActivity { assertEquals("ms", it.resources.configuration.locales[0].language) }
                var chosen = find("Bahasa Melayu")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Malay remains selected after recreation", chosen?.isChecked == true)
                scrollTo(label(R.string.settings_language_system, "ms"))
                click(label(R.string.settings_language_system, "ms"))
                scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun thaiSelectionSurvivesRecreationAndReturnsToSystemDefault() {
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                openLanguage(scenario)
                scrollTo("ไทย")
                click("ไทย")
                awaitText(label(R.string.settings_page_language_title, "th"))
                scenario.onActivity {
                    assertEquals("th", it.resources.configuration.locales[0].language)
                    assertEquals("th", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals("ภาษาของแอป", it.getString(R.string.settings_page_language_title))
                    assertEquals("โมเดลและผู้ให้บริการ", it.getString(R.string.settings_page_models_title))
                    assertEquals("มีโมเดลที่พร้อมใช้งาน 1 โมเดล", it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
                    assertEquals("มีโมเดลที่พร้อมใช้งาน 2 โมเดล", it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
                    assertEquals("Groq · บันทึกคีย์แล้ว 2 รายการ", it.resources.getQuantityString(R.plurals.search_keys_saved, 2, 2, "Groq"))
                    assertEquals(it.getString(R.string.ui_installed), it.getString(R.string.pm_installed))
                }
                screenshot("language-thai-phone.png")
                scenario.recreate()
                scrollTo("ไทย")
                scenario.onActivity { assertEquals("th", it.resources.configuration.locales[0].language) }
                var chosen = find("ไทย")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Thai remains selected after recreation", chosen?.isChecked == true)
                scrollTo(label(R.string.settings_language_system, "th"))
                click(label(R.string.settings_language_system, "th"))
                scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun frenchSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("fr", "Français", "Langue de l’application", "Modèles et fournisseurs", "%1\$d modèle disponible", "%1\$d modèles disponibles", "%2\$s · %1\$d clés enregistrées",
            listOf("fr-CA"))

    @Test fun hindiSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("hi", "हिन्दी", "ऐप की भाषा", "मॉडल और प्रदाता", "%1\$d मॉडल उपलब्ध", "%1\$d मॉडल उपलब्ध", "%2\$s · %1\$d कुंजियाँ सहेजी गईं",
            listOf("hi-IN"))

    @Test fun koreanSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("ko", "한국어", "앱 언어", "모델 및 제공업체", "사용 가능한 모델 %1\$d개", "사용 가능한 모델 %1\$d개", "%2\$s · 키 %1\$d개 저장됨",
            listOf("ko-KR"))

    @Test fun tamilSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("ta", "தமிழ்", "பயன்பாட்டு மொழி", "மாதிரிகள் மற்றும் வழங்குநர்கள்", "%1\$d மாதிரி உள்ளது", "%1\$d மாதிரிகள் உள்ளன", "%2\$s · %1\$d விசைகள் சேமிக்கப்பட்டன",
            listOf("ta-LK"))

    @Test fun bengaliSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("bn", "বাংলা", "অ্যাপের ভাষা", "মডেল ও প্রদানকারী", "%1\$dটি মডেল উপলব্ধ", "%1\$dটি মডেল উপলব্ধ", "%2\$s · %1\$dটি কী সংরক্ষিত",
            listOf("bn-BD"))

    @Test fun filipinoSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("fil", "Filipino", "Wika ng app", "Mga modelo at provider", "May %1\$d magagamit na modelo", "May %1\$d magagamit na modelo", "%2\$s · %1\$d key ang na-save",
            listOf("fil-PH", "tl-PH"))

    @Test fun traditionalChineseSelectionSurvivesRecreationAndReturnsToSystemDefault() =
        checkAdditionalLanguage("zh-Hant", "繁體中文", "應用程式語言", "模型與供應商", "有 %1\$d 個可用模型", "有 %1\$d 個可用模型", "%2\$s · 已儲存 %1\$d 組金鑰",
            listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant-HK"))

    private fun checkAdditionalLanguage(
        tag: String, nativeName: String, title: String, models: String,
        one: String, other: String, keys: String, aliases: List<String>,
    ) {
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                openLanguage(scenario)
                click(nativeName)
                awaitText(title)
                scenario.onActivity {
                    assertEquals(tag, AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(java.util.Locale.forLanguageTag(tag).language, it.resources.configuration.locales[0].language)
                    assertEquals(title, it.getString(R.string.settings_page_language_title))
                    assertEquals(models, it.getString(R.string.settings_page_models_title))
                    val locale = java.util.Locale.forLanguageTag(tag)
                    assertEquals(String.format(locale, one, 1), it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
                    assertEquals(String.format(locale, other, 2), it.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
                    assertEquals(String.format(locale, keys, 4, "Groq"), it.resources.getQuantityString(R.plurals.search_keys_saved, 4, 4, "Groq"))
                    assertEquals(it.getString(R.string.ui_installed), it.getString(R.string.pm_installed))
                }
                screenshot("language-$tag-phone.png")
                scenario.recreate()
                scrollTo(nativeName)
                var chosen = find(nativeName)
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("$nativeName remains selected after recreation", chosen?.isChecked == true)
                if (tag == "zh-Hant") {
                    click("简体中文")
                    awaitText(label(R.string.settings_page_language_title, "zh-CN"))
                    scenario.onActivity {
                        assertEquals("zh-CN", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                        assertNotEquals(title, it.getString(R.string.settings_page_language_title))
                    }
                    click(nativeName)
                    awaitText(title)
                }
                aliases.forEach { alias ->
                    scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(alias)) }
                    try {
                        awaitText(title)
                    } catch (error: IllegalStateException) {
                        var details = ""
                        scenario.onActivity {
                            details = "override=${AppCompatDelegate.getApplicationLocales().toLanguageTags()}, " +
                                "resources=${it.resources.configuration.locales.toLanguageTags()}, " +
                                "title=${it.getString(R.string.settings_page_language_title)}"
                        }
                        throw AssertionError("Locale alias $alias: $details", error)
                    }
                    scrollTo(nativeName)
                    var selected = find(nativeName)
                    while (selected != null && !selected.isCheckable) selected = selected.parent
                    assertTrue("$alias selects $nativeName", selected?.isChecked == true)
                }
                scrollTo(label(R.string.settings_language_system, tag))
                click(label(R.string.settings_language_system, tag))
                scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun contributionButtonOpensThePublicProjectAtLargeFont() {
        var opened: String? = null
        var action = ""
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                action = activity.getString(R.string.settings_language_contribute_action)
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(
                        LocalDensity provides Density(density, 1.5f),
                        LocalUriHandler provides object : UriHandler {
                            override fun openUri(uri: String) { opened = uri }
                        },
                    ) {
                        HarnessTheme(dynamicColor = false) {
                            Surface {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    LanguageSection()
                                }
                            }
                        }
                    }
                }
            }
            scrollTo(action)
            screenshot("language-contribute-150-percent.png")
            click(action)
            assertEquals("https://poeditor.com/join/project/ZPbHNa9WWc", opened)
        }
    }

    private fun openLanguage(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { container.pendingSettingsScroll.value = "language" }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any {
                it == text || it.lineSequence().any { line -> line == text }
            }) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        val visible = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { visible += it.toString() }
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        screenshot("language-failure-${text.hashCode()}.png")
        error("Missing language control: $text; UI: $visible")
    }

    private fun scrollTo(text: String) {
        for (forward in listOf(true, false)) {
            repeat(8) {
                if (find(text) != null) return
                // Real swipes also exercise the scroll container when another locale adds rows.
                val metrics = target.resources.displayMetrics
                val x = metrics.widthPixels * .5f
                val startY = metrics.heightPixels * if (forward) .78f else .25f
                val endY = metrics.heightPixels * if (forward) .25f else .78f
                val down = SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) {
                        0 -> android.view.MotionEvent.ACTION_DOWN
                        12 -> android.view.MotionEvent.ACTION_UP
                        else -> android.view.MotionEvent.ACTION_MOVE
                    }
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        x, startY + (endY - startY) * step / 12, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    val injected = try { instrumentation.uiAutomation.injectInputEvent(event, true) }
                    finally { event.recycle() }
                    // A locale change can replace the focused window during a swipe. Retry navigation.
                    if (!injected) break
                    SystemClock.sleep(20)
                }
                SystemClock.sleep(200)
            }
        }
        awaitText(text)
    }

    private fun click(text: String) {
        scrollTo(text)
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return
            }
            node = node.parent
        }
        error("Language control is not clickable: $text")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(target.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
