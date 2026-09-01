package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebResourceExtractorTest {

    @Test
    fun `extractUrls extracts both markdown links and raw urls`() {
        val text = """
            Here is the app running at [Local Dev](http://localhost:5173/dashboard).
            Also check out https://example.com/api and https://news.ycombinator.com.
        """.trimIndent()

        val urls = WebResourceExtractor.extractUrls(text)
        assertEquals(listOf("http://localhost:5173/dashboard", "https://example.com/api", "https://news.ycombinator.com"), urls)
    }

    @Test
    fun `extractHtmlFileReferences extracts html filenames`() {
        val text = "I created index.html and also updated src/pages/about.html and style.css."
        val htmls = WebResourceExtractor.extractHtmlFileReferences(text)
        assertEquals(listOf("index.html", "src/pages/about.html"), htmls)
    }

    @Test
    fun `findPrimaryPreviewTarget prioritizes localhost servers then html files then web urls`() {
        val localMsg = "Server started at http://localhost:3000. Enjoy!"
        val targetLocal = WebResourceExtractor.findPrimaryPreviewTarget(localMsg)
        assertNotNull(targetLocal)
        assertEquals(WebTargetType.LOCAL_SERVER, targetLocal?.type)
        assertEquals("http://localhost:3000", targetLocal?.urlOrPath)

        val htmlMsg = "Built the new single page app in public/index.html."
        val targetHtml = WebResourceExtractor.findPrimaryPreviewTarget(htmlMsg)
        assertNotNull(targetHtml)
        assertEquals(WebTargetType.WORKSPACE_HTML, targetHtml?.type)
        assertEquals("public/index.html", targetHtml?.urlOrPath)

        val webMsg = "Refer to the docs at https://developer.mozilla.org/en-US/docs/Web"
        val targetWeb = WebResourceExtractor.findPrimaryPreviewTarget(webMsg)
        assertNotNull(targetWeb)
        assertEquals(WebTargetType.CHAT_LINK, targetWeb?.type)
        assertEquals("https://developer.mozilla.org/en-US/docs/Web", targetWeb?.urlOrPath)

        val plainMsg = "No links or html files here."
        assertNull(WebResourceExtractor.findPrimaryPreviewTarget(plainMsg))
    }
}
