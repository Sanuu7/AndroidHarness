package com.androidharness.app.ui.settings

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {
    private fun strings(locale: String): Map<String, String> = buildMap {
        File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as Element
                if (node.getAttribute("translatable") == "false") continue
                val name = node.getAttribute("name")
                assertFalse("Duplicate resource $name", containsKey(name))
                put(name, node.textContent)
            }
        }
    }

    @Test fun chineseCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-zh-rCN").keys)
    }

    @Test fun translatedTemplatesKeepValidArgumentTypesAndFormatSuccessfully() {
        val english = strings("values")
        val chinese = strings("values-zh-rCN")
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            val translated = chinese.getValue(name)
            argument.findAll(translated).forEach {
                assertEquals("Unexpected format argument in $name", expected[it.groupValues[1].toInt()], it.groupValues[2].last())
            }
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { 'd' -> 2; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.ENGLISH, format, *args)
                String.format(Locale.SIMPLIFIED_CHINESE, translated, *args)
            }
        }
    }
    @Test fun russianCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-ru").keys)
    }

    @Test fun russianTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val russian = strings("values-ru")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Russian supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            val translated = russian.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { 'd' -> 21; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("ru"), translated, *args)
            }
        }
    }

    @Test fun russianPluralsCoverEveryCountResourceAndRussianForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val russian = plurals("values-ru")
        assertEquals(english.keys, russian.keys)
        russian.forEach { (name, forms) ->
            assertEquals(name, setOf("one", "few", "many", "other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("ru"), form, 21)
            }
        }
    }

    @Test fun indonesianCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-in").keys)
    }

    @Test fun indonesianTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val indonesian = strings("values-in")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Indonesian supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            val translated = indonesian.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { 'd' -> 21; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("id"), translated, *args)
            }
        }
    }

    @Test fun indonesianPluralsCoverEveryCountResourceAndIndonesianForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val indonesian = plurals("values-in")
        assertEquals(english.keys, indonesian.keys)
        indonesian.forEach { (name, forms) ->
            assertEquals(name, setOf("other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("id"), form, 21)
            }
        }
    }

    @Test fun arabicCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-ar").keys)
    }

    @Test fun arabicTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val arabic = strings("values-ar")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Arabic supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            val translated = arabic.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { 'd' -> 21; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("ar"), translated, *args)
            }
        }
    }

    @Test fun arabicPluralsCoverEveryCountResourceAndArabicForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val arabic = plurals("values-ar")
        assertEquals(english.keys, arabic.keys)
        arabic.forEach { (name, forms) ->
            assertEquals(name, setOf("zero", "one", "two", "few", "many", "other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("ar"), form, 21)
            }
        }
    }

    @Test fun persianCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-fa").keys)
    }

    @Test fun persianTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val persian = strings("values-fa")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Persian supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            val translated = persian.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { 'd' -> 21; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("fa"), translated, *args)
            }
        }
    }

    @Test fun persianPluralsCoverEveryCountResourceAndPersianForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val persian = plurals("values-fa")
        assertEquals(english.keys, persian.keys)
        persian.forEach { (name, forms) ->
            assertEquals(name, setOf("one", "other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("fa"), form, 21)
            }
        }
    }

    @Test fun androidLanguageListDeclaresAllSupportedLanguages() {
        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml")).getElementsByTagName("locale")
        val tags = (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") }
        assertTrue(tags.containsAll(listOf("en", "zh-CN", "ru", "ar", "fa", "pt-BR", "de", "es", "ja", "id", "si", "vi")))
    }

    @Test fun brazilianPortugueseCoversEveryUiResourceInItsOriginalGroup() = assertUiResourceCoverage("values-pt-rBR")

    @Test fun germanCoversEveryUiResourceInItsOriginalGroup() = assertUiResourceCoverage("values-de")

    @Test fun spanishCoversEveryUiResourceInItsOriginalGroup() = assertUiResourceCoverage("values-es")

    @Test fun japaneseCoversEveryUiResourceInItsOriginalGroup() = assertUiResourceCoverage("values-ja")

    @Test fun sinhalaCoversEveryUiResourceInItsOriginalGroup() = assertUiResourceCoverage("values-si")

    @Test fun vietnameseCoversEveryUiResourceInItsOriginalGroup() {
        assertUiResourceCoverage("values-vi")
        val vietnamese = strings("values-vi")
        strings("values").filterValues { it.startsWith("@string/") }.forEach { (name, reference) ->
            assertEquals("Resource reference $name", reference, vietnamese.getValue(name))
        }
    }

    private fun assertUiResourceCoverage(resourceFolder: String) {
        assertEquals(strings("values").keys, strings(resourceFolder).keys)
        for (group in listOf("strings.xml", "ui_strings.xml", "ui_extra_strings.xml", "rich_strings.xml")) {
            fun names(locale: String): Set<String> {
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(File("src/main/res/$locale/$group")).documentElement.childNodes
                return (0 until root.length).mapNotNull { root.item(it) as? Element }
                    .filter { it.tagName in setOf("string", "plurals") && it.getAttribute("translatable") != "false" }
                    .map { it.getAttribute("name") }.toSet()
            }
            assertEquals("Resource group $group", names("values"), names(resourceFolder))
        }
    }

    @Test fun brazilianPortuguesePreservesFormattingAndTechnicalValues() = assertFormattingAndTechnicalValues("values-pt-rBR", "pt-BR")

    @Test fun germanPreservesFormattingAndTechnicalValues() = assertFormattingAndTechnicalValues("values-de", "de")

    @Test fun spanishPreservesFormattingAndTechnicalValues() = assertFormattingAndTechnicalValues("values-es", "es")

    @Test fun japanesePreservesFormattingAndTechnicalValues() = assertFormattingAndTechnicalValues("values-ja", "ja")

    @Test fun sinhalaPreservesFormattingAndTechnicalValues() = assertFormattingAndTechnicalValues("values-si", "si")

    @Test fun vietnamesePreservesFormattingAndTechnicalValues() = assertFormattingAndTechnicalValues("values-vi", "vi")

    private fun assertFormattingAndTechnicalValues(resourceFolder: String, languageTag: String) {
        val english = strings("values")
        val translations = strings(resourceFolder)
        // These arguments supply English grammar, rather than counts or user values.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        val url = Regex("https?://[^\\s<>\"\\\\]+")
        english.forEach { (name, format) ->
            val translated = translations.getValue(name)
            val expected = argument.findAll(format).filter {
                it.groupValues[1].toInt() !in grammarOnly[name].orEmpty()
            }.map { it.value }.sorted().toList()
            assertEquals("Format arguments in $name", expected,
                argument.findAll(translated).map { it.value }.sorted().toList())
            assertEquals("URLs in $name", url.findAll(format).map { it.value }.toList(),
                url.findAll(translated).map { it.value }.toList())
            val types = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
            if (types.isNotEmpty()) {
                val args = Array<Any>(types.keys.max()) { index ->
                    when (types[index + 1]) { 'd' -> 21; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag(languageTag), translated, *args)
            }
            for (token in listOf("Shizuku", "AndroidHarness", "GitHub", "CodeGraph", "localhost", "mcpServers")) {
                assertEquals("Technical value $token in $name", format.split(token).size, translated.split(token).size)
            }
        }
        for (name in english.keys.filter { it.startsWith("settings_language_") &&
            it.substringAfter("settings_language_") in setOf("english", "chinese", "russian", "arabic", "persian", "indonesian", "portuguese", "german", "spanish", "japanese", "sinhala", "vietnamese") }) {
            assertEquals("Native language name $name", english.getValue(name).removeSurrounding("\""),
                translations.getValue(name).removeSurrounding("\""))
        }
    }

    @Test fun brazilianPortuguesePluralsPreserveCountsAndCoverOneAndOther() = assertPluralCoverage("values-pt-rBR", "pt-BR")

    @Test fun germanPluralsPreserveCountsAndCoverOneAndOther() = assertPluralCoverage("values-de", "de")

    @Test fun spanishPluralsPreserveCountsAndCoverOneAndOther() = assertPluralCoverage("values-es", "es")

    @Test fun japanesePluralsPreserveCountsAndUseOther() = assertPluralCoverage("values-ja", "ja", setOf("other"))

    @Test fun sinhalaPluralsPreserveCountsAndCoverOneAndOther() = assertPluralCoverage("values-si", "si")

    @Test fun vietnamesePluralsPreserveCountsAndUseOther() =
        assertPluralCoverage("values-vi", "vi", setOf("other"), preserveAllArguments = true)

    private fun assertPluralCoverage(resourceFolder: String, languageTag: String, quantities: Set<String> = setOf("one", "other"), preserveAllArguments: Boolean = false) {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val translated = plurals(resourceFolder)
        assertEquals(english.keys, translated.keys)
        val argument = Regex("%(\\d+)\\$([-+ #0,(]*\\d*(?:\\.\\d+)?[sdf])")
        translated.forEach { (name, forms) ->
            assertEquals(name, quantities, forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                if (preserveAllArguments) {
                    assertEquals("Plural arguments in $name",
                        argument.findAll(english.getValue(name).getValue("other")).map { it.value }.sorted().toList(),
                        argument.findAll(form).map { it.value }.sorted().toList())
                }
                val types = argument.findAll(form).associate { it.groupValues[1].toInt() to it.groupValues[2].last() }
                val args = Array<Any>(types.keys.max()) { index ->
                    when (types[index + 1]) { 'd' -> 21; 'f' -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag(languageTag), form, *args)
            }
        }
    }

}
