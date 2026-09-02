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
    fun `stripDirectives removes directive syntax cleanly`() {
        val directiveMsg = "Page ready.\n::web-preview{target=\"index.html\"}\nEnjoy!"
        val clean = WebResourceExtractor.stripDirectives(directiveMsg)
        assertEquals("Page ready.\n\nEnjoy!", clean)
    }

    @Test
    fun `findPrimaryPreviewTarget prioritizes localhost directive over heuristics`() {
        val directiveMsg = """
            I created the landing page.
            ::web-preview{target="http://localhost:3000"}
            Also check out https://example.com and index.html
        """.trimIndent()

        val target = WebResourceExtractor.findPrimaryPreviewTarget(directiveMsg)
        assertNotNull(target)
        assertEquals(WebTargetType.LOCAL_SERVER, target?.type)
        assertEquals("http://localhost:3000", target?.urlOrPath)
        assertEquals("Open Web Preview", target?.title)
    }

    @Test
    fun `findPrimaryPreviewTarget only detects live localhost servers`() {
        val localMsg = "Server started at http://localhost:3000. Enjoy!"
        val targetLocal = WebResourceExtractor.findPrimaryPreviewTarget(localMsg)
        assertNotNull(targetLocal)
        assertEquals(WebTargetType.LOCAL_SERVER, targetLocal?.type)
        assertEquals("http://localhost:3000", targetLocal?.urlOrPath)

        val htmlMsg = "Built the new single page app in public/index.html."
        val targetHtml = WebResourceExtractor.findPrimaryPreviewTarget(htmlMsg)
        assertNull(targetHtml)

        val webMsg = "Refer to the docs at https://developer.mozilla.org/en-US/docs/Web"
        val targetWeb = WebResourceExtractor.findPrimaryPreviewTarget(webMsg)
        assertNull(targetWeb)

        val plainMsg = "No links or html files here."
        assertNull(WebResourceExtractor.findPrimaryPreviewTarget(plainMsg))
    }
}
