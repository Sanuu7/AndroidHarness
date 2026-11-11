package com.androidharness.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pure parsing and grouping for the Edge catalogue. The network and the disk
 * cache are not exercised here; these cover the parts that decide what the
 * picker shows.
 */
class EdgeVoiceCatalogTest {

    private val sample = """
        [
          {"Name":"Microsoft Server Speech Text to Speech Voice (pt-BR, FranciscaNeural)",
           "ShortName":"pt-BR-FranciscaNeural","Gender":"Female","Locale":"pt-BR",
           "LocaleName":"Portuguese (Brazil)","Status":"GA"},
          {"Name":"Microsoft Server Speech Text to Speech Voice (pt-BR, AntonioNeural)",
           "ShortName":"pt-BR-AntonioNeural","Gender":"Male","Locale":"pt-BR",
           "LocaleName":"Portuguese (Brazil)","Status":"GA"},
          {"Name":"Microsoft Server Speech Text to Speech Voice (en-US, AvaMultilingualNeural)",
           "ShortName":"en-US-AvaMultilingualNeural","Gender":"Female","Locale":"en-US",
           "LocaleName":"English (United States)","Status":"GA"}
        ]
    """.trimIndent()

    private fun catalog() = EdgeVoiceCatalog(
        // Parsing and grouping never touch the Context; the temp directory keeps
        // the cache path valid without pulling Robolectric into a JVM test.
        context = null,
    )

    @Test
    fun parsesShortNameLocaleGenderAndDisplayName() {
        val voices = catalog().parse(sample)
        assertNotNull(voices)
        assertEquals(3, voices!!.size)
        val francisca = voices.first { it.shortName == "pt-BR-FranciscaNeural" }
        assertEquals("pt-BR", francisca.locale)
        assertEquals("Francisca", francisca.displayName)
        assertTrue(francisca.isFemale)
        assertEquals("Portuguese (Brazil)", francisca.localeName)
    }

    @Test
    fun multilingualIsFlaggedAndKeptInTheDisplayName() {
        val ava = catalog().parse(sample)!!.first { it.shortName.startsWith("en-US-Ava") }
        assertTrue(ava.isMultilingual)
        assertEquals("AvaMultilingual", ava.displayName)
    }

    @Test
    fun unparseableBodiesReturnNullInsteadOfThrowing() {
        assertNull(catalog().parse("<html>nope</html>"))
        assertNull(catalog().parse("[]"))
        assertNull(catalog().parse("{}"))
    }

    @Test
    fun groupsPutTheAppLanguageFirstThenAlphabetically() {
        val voices = catalog().parse(sample)!!
        val groups = catalog().group(voices, Locale.forLanguageTag("pt-BR"))
        assertEquals("pt-BR", groups.first().locale)
        // Women first inside a group, then alphabetical.
        assertEquals("pt-BR-FranciscaNeural", groups.first().voices.first().shortName)
    }

    @Test
    fun groupingOrdersTheMatchingLanguageBeforeOthers() {
        val voices = catalog().parse(sample)!!
        val groups = catalog().group(voices, Locale.forLanguageTag("en-US"))
        assertEquals("en-US", groups.first().locale)
        assertEquals("pt-BR", groups.last().locale)
    }

    @Test
    fun localeAndDisplayNameComeFromTheShortName() {
        assertEquals("pt-BR", EdgeVoiceCatalog.localeOf("pt-BR-FranciscaNeural"))
        assertEquals("zh-CN", EdgeVoiceCatalog.localeOf("zh-CN-XiaoxiaoNeural"))
        assertEquals("Francisca", EdgeVoiceCatalog.displayNameFor("pt-BR-FranciscaNeural"))
        assertEquals("Xiaoxiao", EdgeVoiceCatalog.displayNameFor("zh-CN-XiaoxiaoNeural"))
        // Unexpected shapes are passed through rather than mangled.
        assertEquals("weird", EdgeVoiceCatalog.localeOf("weird"))
        assertEquals("weird", EdgeVoiceCatalog.displayNameFor("weird"))
    }

    @Test
    fun fallbackAlwaysProducesUsableVoices() {
        val fallback = catalog().fallback()
        assertTrue(fallback.isNotEmpty())
        assertTrue(fallback.all { it.shortName.isNotBlank() && it.locale.isNotBlank() })
        assertTrue(fallback.any { it.locale == "pt-BR" })
    }
}
