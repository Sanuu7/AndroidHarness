package com.androidharness.app.core.response

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LenientProfileTest {
    @Test fun oneBadFactOrPictureDropsOnlyThatPart() {
        val json = """{"type":"profile","name":"Ramayana","subtitle":"${"x".repeat(300)}","description":"A film.","image":"https://example.com/a poster.jpg",""" +
            """"facts":[{"label":"Release","value":"5 November","source":"Times of India"},{"label":"Director","value":"Someone","source":"https://example.com/film"}],""" +
            """"sources":[{"title":"Film","url":"https://example.com/film"},{"title":"Broken","url":"not a url"}]}"""
        val node = RichResponse.decode(json) as RichResponse.Node.Profile
        assertEquals("", node.image)
        assertEquals("Director", node.facts.single().label)
        assertEquals(1, node.sources.size)
        assertTrue(node.subtitle.length <= 160)
    }

    @Test fun pictureWithoutItsPageFallsBackToTheFirstSource() {
        val json = """{"type":"profile","name":"Film","description":"A film.","image":"https://example.com/p.jpg","sources":[{"title":"Film","url":"https://example.com/film"}]}"""
        val node = RichResponse.decode(json) as RichResponse.Node.Profile
        assertEquals("https://example.com/film", node.imageSource)
    }

    @Test fun aProfileStillNeedsOneUsableSource() {
        assertNull(RichResponse.decode("""{"type":"profile","name":"Film","description":"A film.","sources":[{"title":"x","url":"nope"}]}"""))
    }

    @Test fun galleryKeepsTheGoodPictures() {
        val json = """{"type":"gallery","title":"Stills","images":[{"url":"https://example.com/a.jpg"},{"url":"bad"}]}"""
        assertEquals(1, (RichResponse.decode(json) as RichResponse.Node.Gallery).images.size)
    }

    @Test fun imageUrlsWithParenthesesStayWhole() {
        val text = "![Poster](https://example.com/ramayana_(2026)-e7b8b3b0.jpg)"
        val inventory = com.androidharness.app.tools.ChatImageInventory.from(listOf(
            com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.ASSISTANT, text)))
        assertEquals("https://example.com/ramayana_(2026)-e7b8b3b0.jpg",
            (inventory.single() as com.androidharness.app.tools.ChatImageSource.Remote).url)
    }
}
