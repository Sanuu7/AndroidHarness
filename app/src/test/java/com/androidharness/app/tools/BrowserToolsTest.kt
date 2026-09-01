package com.androidharness.app.tools

import com.androidharness.app.browser.BrowserController
import com.androidharness.app.browser.BrowserConsoleLog
import com.androidharness.app.browser.BrowserElement
import com.androidharness.app.browser.BrowserState
import com.androidharness.app.browser.WorkspaceHttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolsTest {

    @Test
    fun `formatBrowserState produces structured output`() {
        val state = BrowserState(
            url = "http://localhost:3000/app",
            title = "Test App",
            scrollY = 1280,
            interactiveElements = listOf(
                BrowserElement(id = 1, tag = "input", type = "text", placeholder = "Search here..."),
                BrowserElement(id = 2, tag = "button", text = "Submit", role = "button"),
                BrowserElement(id = 3, tag = "a", text = "Help Page", href = "/help"),
                BrowserElement(id = 4, tag = "button", text = "Far below", inViewport = false, disabled = true),
            ),
            textSummary = "Welcome to Test App!\nUse search bar to find items.",
        )

        val formatted = formatBrowserState(state)

        assertTrue(formatted.contains("URL: http://localhost:3000/app"))
        assertTrue(formatted.contains("Title: Test App"))
        assertTrue(formatted.contains("Scroll: y=1280"))
        assertTrue(formatted.contains("[1] <input> type=\"text\" placeholder=\"Search here...\""))
        assertTrue(formatted.contains("[2] <button> role=\"button\" text=\"Submit\""))
        assertTrue(formatted.contains("[3] <a> text=\"Help Page\" href=\"/help\""))
        assertTrue(formatted.contains("[4] <button> text=\"Far below\" [DISABLED] [offscreen]"))
        assertTrue(formatted.contains("Page Text Excerpt:"))
        assertTrue(formatted.contains("Welcome to Test App!"))
    }

    @Test
    fun `formatBrowserState includes warnings when error present`() {
        val state = BrowserState(
            url = "http://localhost:3000",
            title = "Error Page",
            error = "Failed to parse DOM completely",
        )

        val formatted = formatBrowserState(state)
        assertTrue(formatted.contains("Warning: Failed to parse DOM completely"))
    }

    // --- JS envelope parsing (pure companion helpers of BrowserController) ---

    @Test
    fun `decodeJsJson unwraps quoted string literals`() {
        // evaluateJavascript returns a JSON string literal when the page returns a string
        val quoted = "\"{\\\"ok\\\":true}\""
        assertEquals("{\"ok\":true}", BrowserController.decodeJsJson(quoted))
        assertEquals("{\"ok\":true}", BrowserController.decodeJsJson("{\"ok\":true}"))
        assertNull(BrowserController.decodeJsJson(""))
    }

    @Test
    fun `parseEvalOutcome surfaces sandboxed values`() {
        val okString = BrowserController.parseEvalOutcome("\"{\\\"ok\\\":true,\\\"value\\\":\\\"hello\\\"}\"")
        assertTrue(okString.ok)
        assertEquals("hello", okString.value)
        assertNull(okString.error)

        val okNumber = BrowserController.parseEvalOutcome("\"{\\\"ok\\\":true,\\\"value\\\":42}\"")
        assertTrue(okNumber.ok)
        assertEquals("42", okNumber.value)

        val okNull = BrowserController.parseEvalOutcome("\"{\\\"ok\\\":true,\\\"value\\\":null}\"")
        assertTrue(okNull.ok)
        assertEquals("null", okNull.value)
    }

    @Test
    fun `parseEvalOutcome surfaces thrown errors instead of null`() {
        val failed = BrowserController.parseEvalOutcome(
            "\"{\\\"ok\\\":false,\\\"error\\\":\\\"boom: no such element\\\"}\""
        )
        assertTrue(!failed.ok)
        assertNull(failed.value)
        assertEquals("boom: no such element", failed.error)
    }

    @Test
    fun `parseEvalOutcome falls back to raw value for non-envelope results`() {
        // Page code that somehow bypasses the envelope still yields usable output
        val raw = BrowserController.parseEvalOutcome("\"just a string\"")
        assertTrue(raw.ok)
        assertEquals("just a string", raw.value)
    }

    @Test
    fun `parseActionError distinguishes success from missing element`() {
        assertNull(BrowserController.parseActionError("{\"ok\":true}"))
        val err = BrowserController.parseActionError(
            "{\"ok\":false,\"error\":\"Element with id 999 not found.\"}"
        )
        assertEquals("Element with id 999 not found.", err)
    }

    @Test
    fun `parsePredicateHit distinguishes false from script failure`() {
        assertEquals(true, BrowserController.parsePredicateHit("{\"ok\":true,\"hit\":true}"))
        assertEquals(false, BrowserController.parsePredicateHit("{\"ok\":true,\"hit\":false}"))
        assertNull(BrowserController.parsePredicateHit("{\"ok\":false,\"error\":\"TypeError: x is not defined\"}"))
    }

    @Test
    fun `eval sandbox is synchronous and embeds escaped code`() {
        val js = BrowserController.buildEvalJs("return 2 + 2")
        // sync wrapper (no async/await) so evaluateJavascript does not have to await promises
        assertTrue(!js.contains("async"))
        assertTrue(!js.contains("await"))
        // the user code is embedded as a JSON string literal: quotes escaped
        assertTrue(js.contains("\"return 2 + 2\""))
        // both eval and new Function passes present
        assertTrue(js.contains("eval("))
        assertTrue(js.contains("new Function("))
        // errors surface with the message
        assertTrue(js.contains("ok: false"))
    }

    @Test
    fun `workspace path normalization follows the contract`() {
        val norm = BrowserController::normalizeWorkspacePath
        val root = "/storage/emulated/0/AndroidHarness"
        // plain and ./-relative names
        assertEquals("index.html", norm("index.html", root))
        assertEquals("index.html", norm("./index.html", root))
        assertEquals("index.html", norm("  index.html  ", root))
        assertEquals("docs/about.html", norm("docs/about.html", root))
        assertEquals("docs/about.html", norm("./docs/about.html", null))
        // file:// under the workspace root relativizes
        assertEquals("index.html", norm("file://$root/index.html", root))
        assertEquals("sub/page.html", norm("file://$root/sub/page.html", root))
        // absolute path under the workspace root relativizes
        assertEquals("index.html", norm("$root/index.html", root))
        // absolute path OUTSIDE the workspace is refused, not rewritten
        assertEquals(null, norm("/storage/emulated/0/other/index.html", root))
        assertEquals(null, norm("file:///system/etc/index.html", null))
        // non-HTML files are refused
        assertEquals(null, norm("data.json", root))
        assertEquals(null, norm("assets/app.js", root))
        // traversal is refused
        assertEquals(null, norm("../secrets.html", root))
        assertEquals(null, norm("a/../../secrets.html", root))
        // dev-server style scheme-less hosts are not workspace files (handled elsewhere)
        assertEquals(null, norm("localhost:3000", root))
    }

    @Test
    fun `server path mapping handles roots, queries, and traversal`() {
        assertEquals("index.html", WorkspaceHttpServer.mapRequestPath("/", null))
        assertEquals("index.html", WorkspaceHttpServer.mapRequestPath("/?form=1", null))
        assertEquals("pages/about.html", WorkspaceHttpServer.mapRequestPath("/pages/about.html", null))
        assertEquals("pages/about.html", WorkspaceHttpServer.mapRequestPath("/pages/about.html?x=1#frag", null))
        assertEquals("my page.html", WorkspaceHttpServer.mapRequestPath("/my%20page.html", null))
        assertEquals("pages/index.html", WorkspaceHttpServer.mapRequestPath("/", "pages/index.html"))
        assertEquals("pages/index.html", WorkspaceHttpServer.mapRequestPath("/?a=b", "pages/index.html"))
        // traversal is rejected outright
        assertEquals(null, WorkspaceHttpServer.mapRequestPath("/../etc/passwd", null))
        assertEquals(null, WorkspaceHttpServer.mapRequestPath("/a/../../etc/passwd", null))
        assertEquals(null, WorkspaceHttpServer.mapRequestPath("/%2e%2e/secrets", null))
        // trailing-slash directory stays a path (server appends index.html itself)
        assertEquals("docs", WorkspaceHttpServer.mapRequestPath("/docs/", null))
    }

    @Test
    fun `server mime mapping covers common web assets`() {
        assertEquals("text/html", WorkspaceHttpServer.mimeFor("index.html"))
        assertEquals("text/css", WorkspaceHttpServer.mimeFor("style.css"))
        assertEquals("application/javascript", WorkspaceHttpServer.mimeFor("app.mjs"))
        assertEquals("image/png", WorkspaceHttpServer.mimeFor("img.png"))
        assertEquals("image/svg+xml", WorkspaceHttpServer.mimeFor("icon.SVG"))
        assertEquals("font/woff2", WorkspaceHttpServer.mimeFor("font.woff2"))
        assertEquals("application/octet-stream", WorkspaceHttpServer.mimeFor("data.bin"))
    }

    @Test
    fun `console log formatting includes page url`() {
        val log = BrowserConsoleLog(
            level = "ERROR",
            message = "Uncaught TypeError: boom",
            source = "app.js",
            line = 42,
            url = "https://localhost/index.html",
        )
        val text = "[${log.level}] ${log.message} (${log.source}:${log.line}) @ ${log.url}"
        assertTrue(text.contains("app.js:42"))
        assertTrue(text.contains("https://localhost/index.html"))
    }

    @Test
    fun `tool classes are loadable`() {
        assertTrue(BrowserNavigateTool::class.java != null)
        assertTrue(BrowserClickTool::class.java != null)
        assertTrue(BrowserTypeTool::class.java != null)
        assertTrue(BrowserScrollTool::class.java != null)
        assertTrue(BrowserEvalTool::class.java != null)
        assertTrue(BrowserGetLogsTool::class.java != null)
        assertTrue(BrowserWaitForTool::class.java != null)
        assertTrue(BrowserBackTool::class.java != null)
        assertTrue(BrowserForwardTool::class.java != null)
        assertTrue(BrowserRefreshTool::class.java != null)
        assertTrue(BrowserGetUrlTool::class.java != null)
    }
}
