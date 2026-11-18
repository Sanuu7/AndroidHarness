package com.androidharness.app.core.response

import com.androidharness.app.core.ChatExperience
import org.junit.Assert.*
import org.junit.Test

class ResponseProfileTest {
    private val profile = """{"type":"profile","name":"Example scientist","subtitle":"Researcher","description":"An introduction supported by the biography.","image":"https://example.com/portrait.png","image_source":"https://example.com/biography","facts":[{"label":"Known for","value":"Astronomy","source":"https://example.com/biography"}],"sources":[{"title":"Biography","url":"https://example.com/biography"}]}"""

    @Test fun profileIsNativeSourcedAndSurvivesChatNormalizationAndPlainTextCopy() {
        val node = RichResponse.decode(profile) as RichResponse.Node.Profile
        assertEquals("Example scientist", node.name)
        assertEquals("Astronomy", node.facts.single().value)
        val normalized = ChatRichResponse.normalize(profile)
        assertTrue(RichResponse.parse(normalized).single() is RichResponse.Part.Content)
        val copied = RichResponse.plainText(normalized)
        assertTrue(copied.contains("Known for: Astronomy"))
        assertTrue(copied.contains("https://example.com/biography"))
        assertFalse(copied.contains("harness-ui"))
        assertEquals("https://example.com/portrait.png", RichResponse.images(normalized).single().url)
        val inventory = com.androidharness.app.tools.ChatImageInventory.from(listOf(com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.ASSISTANT, normalized)))
        assertEquals("https://example.com/portrait.png", (inventory.single() as com.androidharness.app.tools.ChatImageSource.Remote).url)
        assertTrue(ChatExperience.SYSTEM_PROMPT.contains("native profile card"))
        val example = RichResponse.INSTRUCTIONS.lineSequence().first { it.startsWith("{\"type\":\"profile\"") }.substringBefore(" (")
        assertNotNull(RichResponse.decode(example))
    }

    @Test fun profilesCanOmitPicturesAndFactsButMustHaveSources() {
        val simple = """{"type":"profile","name":"A place","description":"A sourced overview.","sources":[{"title":"Official page","url":"https://example.com/place"}]}"""
        val node = RichResponse.decode(simple) as RichResponse.Node.Profile
        assertTrue(node.image.isEmpty()); assertTrue(node.facts.isEmpty())
        assertNull(RichResponse.decode(simple.replace("[{\"title\":\"Official page\",\"url\":\"https://example.com/place\"}]", "[]")))
        assertNull(RichResponse.decode(simple.replace("\"sources\"", "\"not_sources\"")))
    }

    @Test fun unsafeOrUnsourcedPartsAreDroppedWithoutLosingTheCard() {
        fun card(json: String) = RichResponse.decode(json) as RichResponse.Node.Profile
        // A picture without its page borrows the first source; a fact without a source is dropped.
        assertEquals("https://example.com/biography", card(profile.replace("\"image_source\":\"https://example.com/biography\",", "")).imageSource)
        assertTrue(card(profile.replace("\"source\":\"https://example.com/biography\"", "\"source\":\"\"")).facts.isEmpty())
        for (bad in listOf("javascript:alert(1)", "file:///data/private", "https://user:password@example.com/image")) {
            assertEquals("", card(profile.replace("https://example.com/portrait.png", bad)).image)
            // With every source unsafe there is nothing to cite, so there is no card.
            assertNull(RichResponse.decode(profile.replace("https://example.com/biography", bad)))
        }
        assertEquals(120, card(profile.replace("Example scientist", "x".repeat(121))).name.length)
    }

    @Test fun profileStreamingNeverShowsRawJsonBeforeTheCompleteFence() {
        val source = "```harness-ui\n$profile\n```"
        for (end in 11 until source.length) assertTrue(RichResponse.parse(source.take(end), true).none { it is RichResponse.Part.Content })
        assertTrue(RichResponse.parse(source, true).single() is RichResponse.Part.Content)
    }
}
