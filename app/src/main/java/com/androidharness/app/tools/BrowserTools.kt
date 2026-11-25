package com.androidharness.app.tools

import kotlinx.coroutines.sync.withLock

import com.androidharness.app.browser.BrowserConsoleLog
import com.androidharness.app.browser.BrowserController
import com.androidharness.app.browser.BrowserElement
import com.androidharness.app.browser.BrowserState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Formats [BrowserState] into a clear, structured text representation for the agent.
 */
internal fun formatBrowserState(state: BrowserState): String {
    val sb = StringBuilder()
    sb.append("URL: ").append(state.url.ifBlank { "(blank)" }).append("\n")
    sb.append("Title: ").append(state.title.ifBlank { "(no title)" }).append("\n")
    sb.append("Scroll: y=${state.scrollY}\n")
    sb.append("Surface: ${state.visibility}; focused: ${state.hasFocus}\n")
    if (state.visibility == "hidden") sb.append("Hidden surface: tool scrolling uses instant behavior. Screenshots require opening the visible browser preview.\n")

    if (!state.error.isNullOrBlank()) {
        sb.append("Warning: ").append(state.error).append("\n")
    }
    state.notices.forEach { sb.append("Browser notice: ").append(it).append('\n') }

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
        "Navigate the in-app browser/WebView to a target URL, a localhost dev server (e.g. http://localhost:3000), " +
        "or a workspace-relative local file (e.g. 'index.html', 'docs/about.html'). Local files are served under " +
        "https://harness.workspace/ws/... via request interception, so relative links, form submits, assets, and " +
        "back/forward history behave like a real site. Returns page title, URL, scroll position, text excerpt, " +
        "and an indexed catalog of interactive elements ([id]) for clicking and typing. The call fails, rather " +
        "than reporting the previous page, when the target produces no page at all (a download or attachment " +
        "response, or a scheme the WebView refuses to render), and says so when a slow page has not finished " +
        "loading yet."
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
            "browser_get_dom) or a CSS selector. Supply only one: when both are given the selector is used and " +
            "the result says so, because an id is only valid for the page state it was indexed from. " +
            "Automatically scrolls the element into view and returns the updated page state. Clicking a disabled " +
            "element still dispatches the click (as a real user click would) but the result warns that nothing happened."
    override val parametersSchema = Schema.obj(
        mapOf(
            "id" to Schema.integer("The numeric element id from previous DOM indexing (e.g. 3). Ignored when 'selector' is supplied."),
            "selector" to Schema.string("Optional CSS selector (e.g. '#submit-btn', 'button.primary'). Takes precedence over 'id'."),
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
            "Supply only one: when both are given the selector is used and the result says so. " +
            "Dispatches standard input and change events so JavaScript reactive forms update properly. Typing into " +
            "a disabled field still dispatches the events but the result warns that nothing happened."
    override val parametersSchema = Schema.obj(
        mapOf(
            "text" to Schema.string("The text to type into the element."),
            "id" to Schema.integer("The numeric element id from previous DOM indexing. Ignored when 'selector' is supplied."),
            "selector" to Schema.string("Optional CSS selector targeting the input element. Takes precedence over 'id'."),
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
 * Read a JavaScript-rendered page with the in-app browser. web_fetch is a
 * plain HTTP client, so SPA shells can come back with almost no text; this
 * renders the page in the headless WebView and returns its visible text.
 */
class BrowserReadTool(
    private val controller: BrowserController,
    private val readBudget: SearchSourceBudget? = null,
    private val imageInputAvailable: () -> Boolean = { true },
) : Tool {
    override val name = "browser_read"
    override val description =
        "Render a URL in the in-app browser, let JavaScript run, and return the visible text with the " +
            "final URL and title. Use it when web_fetch reports that a page requires JavaScript or returns " +
            "almost no readable text. max_chars caps the returned text. Set images=true to collect the " +
            "image URLs the rendered page actually shows, including lazy-loaded gallery images. These are assets, not screenshots of HTML/CSS mockups. " +
            "Set screenshot=viewport or full_page to inspect the rendered page itself. A screenshot requires the unlocked app to be visible; the screenshot result explicitly reports whether an image was attached."
    override val parametersSchema = Schema.obj(
        mapOf(
            "url" to Schema.string("The http(s) URL to render."),
            "max_chars" to Schema.integer("Maximum characters to return (default 20000, max 60000)."),
            "images" to Schema.boolean("Also return direct image URLs rendered on the page (default false)."),
            "screenshot" to Schema.string("Optional: none (default), viewport, or full_page. Captures rendered HTML/CSS, not just image assets. Full pages are capped at 8 megapixels / 8192px height; inspect image_id with zoom_chat_image for detail."),
        ),
        required = listOf("url"),
    )
    override val isReadOnly = true

    private companion object { val renderedReads = kotlinx.coroutines.sync.Mutex() }

    internal fun forBudget(budget: SearchSourceBudget?): BrowserReadTool = BrowserReadTool(controller, budget, imageInputAvailable)
    internal fun forImageInput(available: () -> Boolean): BrowserReadTool = BrowserReadTool(controller, readBudget, available)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw ToolFailure("Only http(s) URLs are supported.")
        }
        val maxChars = (args["max_chars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20_000)
            .coerceIn(1_000, 60_000)
        val includeImages = args["images"]?.jsonPrimitive?.booleanOrNull == true
        val screenshot = args["screenshot"]?.jsonPrimitive?.contentOrNull ?: "none"
        if (screenshot !in setOf("none", "viewport", "full_page")) return ToolResult(false, "Use screenshot=none, viewport, or full_page.")
        if (readBudget != null && !readBudget.canRead(url)) return ToolResult(true, readLimitText(), research = readBudget.receipt())
        return try {
            renderedReads.withLock {
            ResearchHttp.page(url) { ResearchHttp.policy.access(requireNotNull(url.toHttpUrlOrNull()), "rendered-browser") {
            val state = controller.navigate(url, ctx.workspace)
            // Prefer the article/main container when it carries real text, then
            // fall back to the whole body. innerText reflects the rendered DOM,
            // which is the point of this tool.
            val outcome = controller.evalUser(
                "return (() => {" +
                    " const a = document.querySelector('article');" +
                    " const m = document.querySelector('main');" +
                    " if (a && a.innerText && a.innerText.trim().length > 200) return a.innerText;" +
                    " if (m && m.innerText && m.innerText.trim().length > 200) return m.innerText;" +
                    " return document.body ? document.body.innerText : " +
                    "  (document.documentElement ? document.documentElement.innerText : '');" +
                    " })()",
            )
            val text = (outcome.value ?: state.textSummary).trim()
            if (!state.error.isNullOrBlank()) return@access ToolResult(false, "Browser could not read $url: ${state.error}. This page was not counted as read.")
            ReadableWebPage.problem(text, state.title)?.let { problem ->
                ReadableWebPage.cooldownStatus(text, state.title)?.let { code ->
                    ResearchHttp.policy.cooldown(requireNotNull(url.toHttpUrlOrNull()), code,
                        identity = if (code == 429) "" else "rendered-browser")
                }
                return@access ToolResult(false, problem)
            }
            if (readBudget != null && !readBudget.claimRead(url)) return@access ToolResult(true, readLimitText(), research = readBudget.receipt())
            val images = if (includeImages) scanImages(state) else emptyList()
            WebResultCache.putPage(url, text, "browser_read")
            if (includeImages) WebResultCache.putPageImages(url, images)
            val imageNote = if (!includeImages) "" else if (images.isEmpty()) "\n[no image urls found]"
                else "\n" + PageImageText.render(images, images.size)
            val capture = if (screenshot == "none") null else if (!ctx.imageInputAllowed || !imageInputAvailable())
                imageInspectionFailure("images_disabled", "The page text was read, but this model cannot receive a screenshot.")
            else try {
                val captured = controller.captureReadPage(screenshot == "full_page")
                val ref = captured.image
                val inspected = imageInspectionSuccess(ref, ChatImageSource.Stored(ref, ref.caption.orEmpty(), "browser_screenshot", "tool"),
                    fetchSource = "browser_capture", description = "Rendered ${if (captured.fullPage) "full page" else "viewport"} screenshot. " +
                        if (captured.truncated) "The full page exceeds the capture limit; the bottom is truncated." else "The requested capture is complete.")
                val envelope = Json.parseToJsonElement(inspected.output).jsonObject
                inspected.copy(output = kotlinx.serialization.json.JsonObject(envelope + mapOf(
                    "truncated" to kotlinx.serialization.json.JsonPrimitive(captured.truncated),
                    "capture_mode" to kotlinx.serialization.json.JsonPrimitive(screenshot))).toString())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { imageInspectionFailure("capture_failed", "The page text was read but no screenshot was attached: ${e.message}") }
                val body = text.take(maxChars)
                ToolResult(true, buildString {
                    append("URL: ").append(state.url).append('\n')
                    append("Title: ").append(state.title).append("\n\n")
                    state.error?.takeIf { it.isNotBlank() }?.let { append("Warning: ").append(it).append("\n\n") }
                    append(body)
                    if (body.length < text.length) append("\n[truncated at ").append(maxChars).append(" chars]")
                    append(imageNote)
                    if (capture != null) append("\n[screenshot result; page text read successfully]\n").append(capture.output)
                    readSourceNote()?.let { append(it) }
                }, image = capture?.image, research = readBudget?.receipt(url, "browser_read") ?:
                    com.androidharness.app.core.ResearchReceipt(pageUrl = url, pageRead = true, backend = "browser_read"))
            } }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolResult(false, "Browser read failed: ${e.message}")
        }
    }

    private suspend fun scanImages(state: BrowserState): List<PageImage> {
        val raw = controller.evalUser(IMAGE_SCAN_JS).value ?: return emptyList()
        val pageUrl = state.url.toHttpUrlOrNull() ?: return emptyList()
        val fallbackTitle = state.title.ifBlank { pageUrl.host }
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                val imageUrl = httpImageUrl(item["url"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
                val alt = item["alt"]?.jsonPrimitive?.contentOrNull?.trim()
                PageImage(
                    title = alt?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
                    imageUrl = imageUrl,
                    pageUrl = pageUrl.toString(),
                    source = pageUrl.host,
                )
            }
        }.getOrDefault(emptyList()).distinctBy { it.imageUrl }.take(8)
    }

    private fun readLimitText(): String {
        val budget = readBudget ?: return ""
        return "[Page read limit reached: ${budget.pagesRead}/${budget.maxReads} pages this turn. " +
            "Answer from the pages already read.]"
    }

    private fun readSourceNote(): String? {
        val budget = readBudget ?: return null
        val limit = budget.maxReads ?: return null
        return "\n[pages read this turn: ${budget.pagesRead}/$limit]"
    }
}

/**
 * Collects images the rendered page actually shows: Open Graph first, then
 * visible img tags, lazy-load attributes, and CSS background images. The
 * result is a JSON array of {url, alt} objects.
 */
private val IMAGE_SCAN_JS = """
return (() => {
  const out = [];
  const seen = new Set();
  const add = (src, alt) => {
    if (!src) return;
    let abs;
    try { abs = new URL(src, document.baseURI).href; } catch (e) { return; }
    if (!/^https?:/i.test(abs) || /\.svg(\?|${'$'})/i.test(abs)) return;
    if (seen.has(abs)) return;
    seen.add(abs);
    out.push({url: abs, alt: (alt || '').trim()});
  };
  const og = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
  if (og) add(og.getAttribute('content'), document.title);
  for (const img of Array.from(document.images)) {
    if (out.length >= 8) break;
    if (img.naturalWidth && img.naturalWidth < 120) continue;
    if (img.naturalHeight && img.naturalHeight < 120) continue;
    const classes = String(img.className || '').toLowerCase();
    if (/(^|[\s_-])(icon|logo|sprite|emoji)(${'$'}|[\s_-])/.test(classes)) continue;
    const srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset');
    const firstSrc = srcset ? srcset.split(',')[0].trim().split(' ')[0] : null;
    const src = img.currentSrc || img.src || img.getAttribute('data-src') || img.getAttribute('data-original') || firstSrc;
    if (src && /(\/ico\/|\/icons?\/|\/sprite|favicon|app_icon|footerlogo)/i.test(src)) continue;
    add(src, img.alt || img.title);
  }
  for (const el of Array.from(document.querySelectorAll('[style*="background-image"]'))) {
    if (out.length >= 8) break;
    if (el.clientWidth < 120 || el.clientHeight < 120) continue;
    const bg = getComputedStyle(el).backgroundImage;
    const m = /url\(["']?(.*?)["']?\)/.exec(bg);
    if (m) add(m[1], el.getAttribute('aria-label') || el.title);
  }
  return out;
})()
"""

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
            val notices = outcome.notices.joinToString("") { "\nBrowser notice: $it" }
            if (outcome.ok) {
                ToolResult(true, "Result: ${outcome.value}$notices")
            } else {
                ToolResult(false, "JavaScript error: ${outcome.error}$notices")
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
        "chat transcript. Works without the preview open, including while Harness is in the background. " +
        "Waits for loaded fonts, images and finite animations first."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val result = controller.screenshot(ctx.workspace)
            if (result != null) {
                ToolResult(
                    true,
                    "Screenshot captured: ${result.relPath} (${result.sizeBytes} bytes). " +
                        "Saved to workspace and shown in chat transcript.",
                    image = com.androidharness.app.core.ImageRef(result.filename, result.mime),
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
