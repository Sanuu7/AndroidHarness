package com.androidharness.app.tools

import com.androidharness.app.browser.BrowserConsoleLog
import com.androidharness.app.browser.BrowserController
import com.androidharness.app.browser.BrowserElement
import com.androidharness.app.browser.BrowserState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Formats [BrowserState] into a clear, structured text representation for the agent.
 */
internal fun formatBrowserState(state: BrowserState): String {
    val sb = StringBuilder()
    sb.append("URL: ").append(state.url.ifBlank { "(blank)" }).append("\n")
    sb.append("Title: ").append(state.title.ifBlank { "(no title)" }).append("\n")
    sb.append("Scroll: y=${state.scrollY}\n")

    if (!state.error.isNullOrBlank()) {
        sb.append("Warning: ").append(state.error).append("\n")
    }

    if (state.interactiveElements.isNotEmpty()) {
        sb.append("\nInteractive Elements (use numeric id with browser_click or browser_type):\n")
        state.interactiveElements.forEach { el ->
            sb.append("  [").append(el.id).append("] <").append(el.tag).append(">")
            if (!el.type.isNullOrBlank()) sb.append(" type=\"").append(el.type).append("\"")
            if (!el.name.isNullOrBlank()) sb.append(" name=\"").append(el.name).append("\"")
            if (!el.placeholder.isNullOrBlank()) sb.append(" placeholder=\"").append(el.placeholder).append("\"")
            if (!el.ariaLabel.isNullOrBlank()) sb.append(" aria-label=\"").append(el.ariaLabel).append("\"")
            if (!el.role.isNullOrBlank()) sb.append(" role=\"").append(el.role).append("\"")
            if (!el.text.isNullOrBlank()) sb.append(" text=\"").append(el.text.replace("\n", " ")).append("\"")
            if (!el.href.isNullOrBlank()) sb.append(" href=\"").append(el.href).append("\"")
            if (el.disabled) sb.append(" [DISABLED]")
            if (!el.inViewport) sb.append(" [offscreen]")
            sb.append("\n")
        }
    } else {
        sb.append("\nInteractive Elements: (none found)\n")
    }

    if (state.textSummary.isNotBlank()) {
        sb.append("\nPage Text Excerpt:\n")
        sb.append(state.textSummary.take(1500))
        if (state.textSummary.length > 1500) {
            sb.append("\n... [truncated]")
        }
    }
    return sb.toString().trimEnd()
}

/**
 * Navigate to a URL, localhost endpoint, or workspace HTML file.
 */
class BrowserNavigateTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_navigate"
    override val description =
        "Navigate the in-app browser/WebView to a target URL, a localhost server (e.g. http://localhost:3000), " +
        "or a workspace-relative local file (e.g. 'index.html', 'docs/about.html'). Local files are served over " +
        "a loopback HTTP server, so relative links, form submits, and assets work like a real site. Returns page " +
        "title, URL, scroll position, text excerpt, and an indexed catalog of interactive elements ([id]) for " +
        "clicking and typing."
    override val parametersSchema = Schema.obj(
        mapOf(
            "url" to Schema.string("The target URL (e.g. 'https://example.com', 'http://localhost:5173', or 'index.html')."),
        ),
        required = listOf("url"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: url")

        return try {
            val state = controller.navigate(url, ctx.workspace)
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "Failed to navigate to '$url': ${e.message}")
        }
    }
}

/**
 * Click an interactive element by numeric ID or CSS selector.
 */
class BrowserClickTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_click"
    override val description =
        "Click an interactive element on the current browser page by its numeric id (from browser_navigate or " +
        "browser_get_dom) or a CSS selector. Automatically scrolls the element into view and returns the updated page state."
    override val parametersSchema = Schema.obj(
        mapOf(
            "id" to Schema.integer("The numeric element id from previous DOM indexing (e.g. 3)."),
            "selector" to Schema.string("Optional CSS selector (e.g. '#submit-btn', 'button.primary')."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args["id"]?.jsonPrimitive?.intOrNull
        val selector = args["selector"]?.jsonPrimitive?.content

        if (id == null && selector.isNullOrBlank()) {
            throw ToolFailure("Provide either 'id' (integer) or 'selector' (string) to click an element.")
        }

        return try {
            val state = controller.click(elementId = id, selector = selector)
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "Failed to click element: ${e.message}")
        }
    }
}

/**
 * Type text into an input or textarea element.
 */
class BrowserTypeTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_type"
    override val description =
        "Type text into an input, textarea, or contenteditable element by its numeric id or CSS selector. " +
        "Dispatches standard input and change events so JavaScript reactive forms update properly."
    override val parametersSchema = Schema.obj(
        mapOf(
            "text" to Schema.string("The text to type into the element."),
            "id" to Schema.integer("The numeric element id from previous DOM indexing."),
            "selector" to Schema.string("Optional CSS selector targeting the input element."),
            "clear_first" to Schema.boolean("Whether to clear existing text before typing (default false)."),
        ),
        required = listOf("text"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = args["text"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: text")
        val id = args["id"]?.jsonPrimitive?.intOrNull
        val selector = args["selector"]?.jsonPrimitive?.content
        val clearFirst = args["clear_first"]?.jsonPrimitive?.booleanOrNull ?: false

        if (id == null && selector.isNullOrBlank()) {
            throw ToolFailure("Provide either 'id' (integer) or 'selector' (string) targeting the input element.")
        }

        return try {
            val state = controller.type(text = text, elementId = id, selector = selector, clearFirst = clearFirst)
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "Failed to type into element: ${e.message}")
        }
    }
}

/**
 * Scroll the browser viewport.
 */
class BrowserScrollTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_scroll"
    override val description =
        "Scroll the current page viewport in a specified direction (up, down, left, right) by a pixel amount. " +
        "Returns the updated DOM state."
    override val parametersSchema = Schema.obj(
        mapOf(
            "direction" to Schema.string("Scroll direction: 'down' (default), 'up', 'left', or 'right'."),
            "amount" to Schema.integer("Scroll distance in pixels (default 500)."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val direction = args["direction"]?.jsonPrimitive?.content ?: "down"
        val amount = args["amount"]?.jsonPrimitive?.intOrNull ?: 500

        return try {
            val state = controller.scroll(direction = direction, amountPx = amount)
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "Failed to scroll page: ${e.message}")
        }
    }
}

/**
 * Re-extracts interactive elements and text from the active page.
 */
class BrowserGetDomTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_get_dom"
    override val description =
        "Extract the current DOM state, visible text, and interactive elements ([id]) from the active page without re-navigating."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val state = controller.extractState()
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "Failed to extract DOM state: ${e.message}")
        }
    }
}

/**
 * Executes arbitrary JavaScript in the page context.
 */
class BrowserEvalTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_eval"
    override val description =
        "Evaluate JavaScript in the page context inside a sandboxed async function: `await` is allowed, the " +
        "completion value (or an explicit `return`) is the result, and thrown errors are reported as tool " +
        "failures. Each call is isolated; persist state via localStorage/sessionStorage, not globals."
    override val parametersSchema = Schema.obj(
        mapOf(
            "code" to Schema.string("JavaScript to run, e.g. `return document.title;` or `await fetch('/api').then(r => r.json())`."),
        ),
        required = listOf("code"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val code = args["code"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: code")

        return try {
            val outcome = controller.evalUser(code)
            if (outcome.ok) {
                ToolResult(true, "Result: ${outcome.value}")
            } else {
                ToolResult(false, "JavaScript error: ${outcome.error}")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to execute JavaScript: ${e.message}")
        }
    }
}

/**
 * Captures a screenshot of the current page.
 */
class BrowserScreenshotTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_screenshot"
    override val description =
        "Capture a screenshot image of the current page/WebView viewport. The image is shown inline in the " +
        "chat transcript so the user sees it, and the tool result reports the saved file details."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val image = controller.screenshot()
            if (image != null) {
                ToolResult(
                    true,
                    "Screenshot captured: ${image.file.name} (${image.file.length()} bytes, path=${image.file.absolutePath}). " +
                        "It is shown in the chat transcript; the user can see it.",
                    image = com.androidharness.app.core.ImageRef(image.file.name, image.mime),
                )
            } else {
                ToolResult(false, "Failed to capture screenshot: WebView is not initialized or failed to render canvas.")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to capture screenshot: ${e.message}")
        }
    }
}

/**
 * Retrieves console logs and runtime errors.
 */
class BrowserGetLogsTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_get_logs"
    override val description =
        "Retrieve recent browser console logs, warnings, and JavaScript runtime errors, each tagged with the page " +
        "URL that produced it. Set clear=true to empty the buffer after reading (equivalent to browser_clear_logs)."
    override val parametersSchema = Schema.obj(
        mapOf(
            "level" to Schema.string("Filter by level: 'ERROR', 'WARN', 'LOG', 'DEBUG', or leave empty for all."),
            "source" to Schema.string("Only logs whose page URL or script source contains this substring."),
            "clear" to Schema.boolean("Whether to clear the log buffer after reading (default false)."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val level = args["level"]?.jsonPrimitive?.content
        val source = args["source"]?.jsonPrimitive?.content
        val clear = args["clear"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val logs = controller.getLogs(levelFilter = level, sourceFilter = source, clear = clear)
            if (logs.isEmpty()) {
                ToolResult(true, "No console logs captured.")
            } else {
                val sb = StringBuilder("Captured Console Logs (${logs.size}):\n")
                logs.forEach { log ->
                    sb.append("[${log.level}] ${log.message}")
                    if (log.source.isNotBlank() || log.line > 0) {
                        sb.append(" (${log.source}:${log.line})")
                    }
                    if (log.url.isNotBlank()) {
                        sb.append(" @ ${log.url.take(100)}")
                    }
                    sb.append("\n")
                }
                ToolResult(true, sb.toString().trimEnd())
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to read console logs: ${e.message}")
        }
    }
}

/**
 * Blocks until a condition holds on the page.
 */
class BrowserWaitForTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_wait_for"
    override val description =
        "Block until a condition holds on the current page: 'selector' (CSS selector exists and is visible), " +
        "'text' (string appears in body text), or 'url_contains' (URL substring). Use after clicks that trigger " +
        "async re-renders or navigations instead of guessing."
    override val parametersSchema = Schema.obj(
        mapOf(
            "condition" to Schema.string("selector | text | url_contains"),
            "value" to Schema.string("The CSS selector, text, or URL substring to wait for."),
            "timeout_ms" to Schema.integer("Max wait in milliseconds (default 5000, max 30000)."),
        ),
        required = listOf("condition", "value"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val condition = args["condition"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: condition")
        val value = args["value"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: value")
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5_000L

        return try {
            val state = controller.waitFor(condition, value, timeoutMs)
            ToolResult(true, "Condition met.\n\n" + formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "browser_wait_for failed: ${e.message}")
        }
    }
}

/**
 * History navigation: back, forward, and in-place refresh.
 */
class BrowserBackTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_back"
    override val description =
        "Go back one page in browser history. Preserves console logs and, for workspace pages, restores app " +
        "state better than re-navigating."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val state = controller.back()
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "browser_back failed: ${e.message}")
        }
    }
}

class BrowserForwardTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_forward"
    override val description = "Go forward one page in browser history (after a browser_back)."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val state = controller.forward()
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "browser_forward failed: ${e.message}")
        }
    }
}

class BrowserRefreshTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_refresh"
    override val description =
        "Reload the current page in place. Unlike re-navigating, in-page state that the browser owns " +
        "(history entry, scroll anchor) behaves like a normal reload, and console logs are preserved."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val state = controller.refresh()
            ToolResult(true, formatBrowserState(state))
        } catch (e: Exception) {
            ToolResult(false, "browser_refresh failed: ${e.message}")
        }
    }
}

/**
 * Cheap read of the current URL and title.
 */
class BrowserGetUrlTool(
    private val controller: BrowserController,
) : Tool {
    override val name = "browser_get_url"
    override val description =
        "Return just the current page URL and title, without re-reading the DOM. Cheap way to confirm where " +
        "the browser ended up after a click or navigation."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val (url, title) = controller.getUrl()
            ToolResult(true, "URL: ${url.ifBlank { "(blank)" }}\nTitle: ${title.ifBlank { "(no title)" }}")
        } catch (e: Exception) {
            ToolResult(false, "browser_get_url failed: ${e.message}")
        }
    }
}
