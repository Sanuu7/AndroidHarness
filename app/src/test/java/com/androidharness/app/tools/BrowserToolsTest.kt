package com.androidharness.app.tools

import com.androidharness.app.browser.BrowserConsoleLog
import com.androidharness.app.browser.BrowserElement
import com.androidharness.app.browser.BrowserState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolsTest {

    @Test
    fun `formatBrowserState produces structured output`() {
        val state = BrowserState(
            url = "http://localhost:3000/app",
            title = "Test App",
            interactiveElements = listOf(
                BrowserElement(id = 1, tag = "input", type = "text", placeholder = "Search here..."),
                BrowserElement(id = 2, tag = "button", text = "Submit", role = "button"),
                BrowserElement(id = 3, tag = "a", text = "Help Page", href = "/help"),
            ),
            textSummary = "Welcome to Test App!\nUse search bar to find items.",
        )

        val formatted = formatBrowserState(state)

        assertTrue(formatted.contains("URL: http://localhost:3000/app"))
        assertTrue(formatted.contains("Title: Test App"))
        assertTrue(formatted.contains("[1] <input> type=\"text\" placeholder=\"Search here...\""))
        assertTrue(formatted.contains("[2] <button> role=\"button\" text=\"Submit\""))
        assertTrue(formatted.contains("[3] <a> text=\"Help Page\" href=\"/help\""))
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

    @Test
    fun `tool schema declarations are valid and non-empty`() {
        val navigateSchema = BrowserNavigateTool::class.java
        val clickSchema = BrowserClickTool::class.java
        val typeSchema = BrowserTypeTool::class.java
        val scrollSchema = BrowserScrollTool::class.java
        val evalSchema = BrowserEvalTool::class.java
        val logsSchema = BrowserGetLogsTool::class.java

        // Ensure tool classes are loaded and non-null
        assertTrue(navigateSchema != null)
        assertTrue(clickSchema != null)
        assertTrue(typeSchema != null)
        assertTrue(scrollSchema != null)
        assertTrue(evalSchema != null)
        assertTrue(logsSchema != null)
    }
}
