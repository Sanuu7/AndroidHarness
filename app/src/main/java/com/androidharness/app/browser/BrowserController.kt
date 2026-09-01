package com.androidharness.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.StoredImage
import com.androidharness.app.core.LocalPortProbe
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class BrowserConsoleLog(
    val level: String,
    val message: String,
    val source: String,
    val line: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class BrowserElement(
    val id: Int,
    val tag: String,
    val type: String? = null,
    val name: String? = null,
    val text: String? = null,
    val placeholder: String? = null,
    val ariaLabel: String? = null,
    val role: String? = null,
    val href: String? = null,
    val isVisible: Boolean = true,
    val isClickable: Boolean = true,
)

@Serializable
data class BrowserState(
    val url: String,
    val title: String,
    val interactiveElements: List<BrowserElement> = emptyList(),
    val textSummary: String = "",
    val error: String? = null,
    val consoleErrorCount: Int = 0,
)

/**
 * One agent browser action, newest-last. Rendered as a live trail in the
 * WebPreviewSheet while the agent drives the page.
 */
@Serializable
data class BrowserActionTrack(
    val action: String,
    val detail: String,
    val ok: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Manages headless and GUI-mirrored WebView automation for the Agent.
 *
 * Exposes methods to navigate, click indexed elements, type text, scroll,
 * evaluate JS, take screenshots, and inspect console logs/errors.
 */
class BrowserController(
    private val appContext: Context,
    private val imageStore: ImageStore,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // Mirror reference if WebPreviewSheet is open
    private var activeWebViewRef: WeakReference<WebView>? = null
    private var headlessWebView: WebView? = null

    // Ring buffer of console logs (capped at 200)
    private val consoleLogs = CopyOnWriteArrayList<BrowserConsoleLog>()

    // Current workspace reference for resolving local HTML files/assets
    @Volatile
    var currentWorkspace: WorkspaceFs? = null

    private val isPageLoading = AtomicBoolean(false)
    private var pageLoadDeferred: CompletableDeferred<Unit>? = null

    // Agent activity trail, newest last, capped; drives the WebPreviewSheet banner.
    private val trackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _actionTrack = MutableStateFlow<List<BrowserActionTrack>>(emptyList())
    val actionTrack: StateFlow<List<BrowserActionTrack>> = _actionTrack
    private val _isAgentControlling = MutableStateFlow(false)
    val isAgentControlling: StateFlow<Boolean> = _isAgentControlling
    private var controlIdleJob: Job? = null

    /** How long the "agent is controlling" banner stays up after the last action. */
    private val controlIdleResetMs = 10_000L

    private fun track(action: String, detail: String, ok: Boolean = true) {
        _actionTrack.value = (_actionTrack.value + BrowserActionTrack(action, detail, ok)).takeLast(MAX_TRACK)
        _isAgentControlling.value = true
        controlIdleJob?.cancel()
        controlIdleJob = trackScope.launch {
            delay(controlIdleResetMs)
            _isAgentControlling.value = false
        }
    }

    /** Clears the visible trail (does not affect page state). */
    fun clearTrack() {
        _actionTrack.value = emptyList()
        _isAgentControlling.value = false
        controlIdleJob?.cancel()
    }

    /**
     * Binds the visible WebPreviewSheet WebView so user can watch agent actions.
     */
    fun bindActiveWebView(webView: WebView) {
        activeWebViewRef = WeakReference(webView)
    }

    fun unbindActiveWebView(webView: WebView) {
        if (activeWebViewRef?.get() == webView) {
            activeWebViewRef = null
        }
    }

    private suspend fun getOrCreateWebView(): WebView = withContext(Dispatchers.Main) {
        activeWebViewRef?.get()?.let { return@withContext it }

        headlessWebView?.let { return@withContext it }

        val wv = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = "${settings.userAgentString} AndroidHarnessAgent/1.0"
            // Ensure headless webview has layout bounds for screenshots/rendering
            layout(0, 0, 1080, 1920)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg?.let {
                        val level = when (it.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                            ConsoleMessage.MessageLevel.WARNING -> "WARN"
                            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                            else -> "LOG"
                        }
                        consoleLogs.add(
                            BrowserConsoleLog(
                                level = level,
                                message = it.message().orEmpty(),
                                source = it.sourceId().orEmpty(),
                                line = it.lineNumber(),
                            )
                        )
                        if (consoleLogs.size > 200) {
                            consoleLogs.removeAt(0)
                        }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoading.set(false)
                    pageLoadDeferred?.complete(Unit)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val host = uri.host?.lowercase().orEmpty()

                    // Intercept local assets for workspace files served via https://localhost/
                    if ((host == "localhost" || host == "127.0.0.1") && uri.scheme == "https") {
                        val ws = currentWorkspace ?: return null
                        val rawPath = uri.path.orEmpty().removePrefix("/")
                        if (rawPath.isBlank()) return null

                        return runCatching {
                            val node = ws.resolve(rawPath)
                            if (node != null && node.exists && !node.isDirectory) {
                                val stream = node.openInputStream()
                                val bytes = stream?.use { it.readBytes() } ?: return@runCatching null
                                val mime = when {
                                    rawPath.endsWith(".html", true) -> "text/html"
                                    rawPath.endsWith(".css", true) -> "text/css"
                                    rawPath.endsWith(".js", true) -> "application/javascript"
                                    rawPath.endsWith(".json", true) -> "application/json"
                                    rawPath.endsWith(".png", true) -> "image/png"
                                    rawPath.endsWith(".jpg", true) || rawPath.endsWith(".jpeg", true) -> "image/jpeg"
                                    rawPath.endsWith(".svg", true) -> "image/svg+xml"
                                    else -> "application/octet-stream"
                                }
                                WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
                            } else null
                        }.getOrNull()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        headlessWebView = wv
        wv
    }

    /**
     * Navigate to an external URL, localhost endpoint, or workspace HTML file.
     */
    suspend fun navigate(url: String, workspace: WorkspaceFs?): BrowserState {
        currentWorkspace = workspace
        track("navigate", url.take(120))
        val deferred = CompletableDeferred<Unit>()
        pageLoadDeferred = deferred
        isPageLoading.set(true)

        withContext(Dispatchers.Main) {
            val wv = getOrCreateWebView()
            val target = url.trim()

            if (isWorkspaceHtml(target)) {
                val node = workspace?.resolve(target)
                if (node != null && node.exists) {
                    val html = node.readText()
                    wv.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
                } else {
                    deferred.complete(Unit)
                    throw IllegalArgumentException("Workspace HTML file not found: $target")
                }
            } else {
                val normalized = LocalPortProbe.normalizeLocalUrl(target)
                wv.loadUrl(normalized)
            }
        }

        // Wait up to 15s for page finish
        withTimeoutOrNull(15_000) { deferred.await() }

        // Inject helper DOM script & extract state
        return try {
            extractState()
        } catch (e: Exception) {
            track("navigate", url.take(120), ok = false)
            throw e
        }
    }

    /**
     * Click an interactive element by its assigned index or CSS selector.
     */
    suspend fun click(elementId: Int? = null, selector: String? = null): BrowserState {
        track("click", elementId?.let { "#$it" } ?: selector.orEmpty().take(80))
        val js = when {
            elementId != null -> """
                (function() {
                    const el = document.querySelector('[data-harness-id="$elementId"]');
                    if (!el) return { ok: false, error: "Element with index $elementId not found." };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    el.click();
                    return { ok: true };
                })();
            """.trimIndent()
            !selector.isNullOrBlank() -> """
                (function() {
                    const el = document.querySelector(${json.encodeToString(selector)});
                    if (!el) return { ok: false, error: "Element matching selector '$selector' not found." };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    el.click();
                    return { ok: true };
                })();
            """.trimIndent()
            else -> throw IllegalArgumentException("Either elementId or selector must be provided.")
        }

        evaluateJs(js)
        kotlinx.coroutines.delay(400) // Brief settle time for DOM updates
        return extractState()
    }

    /**
     * Type text into an input or textarea element.
     */
    suspend fun type(text: String, elementId: Int? = null, selector: String? = null, clearFirst: Boolean = false): BrowserState {
        track(
            "type",
            buildString {
                append(elementId?.let { "#$it" } ?: selector.orEmpty().take(40))
                append(" \"").append(text.take(40)).append('"')
            },
        )
        val encodedText = json.encodeToString(text)
        val js = when {
            elementId != null -> """
                (function() {
                    const el = document.querySelector('[data-harness-id="$elementId"]');
                    if (!el) return { ok: false, error: "Element with index $elementId not found." };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    ${if (clearFirst) "el.value = '';" else ""}
                    el.value = (el.value || '') + $encodedText;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return { ok: true };
                })();
            """.trimIndent()
            !selector.isNullOrBlank() -> """
                (function() {
                    const el = document.querySelector(${json.encodeToString(selector)});
                    if (!el) return { ok: false, error: "Element matching selector '$selector' not found." };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    ${if (clearFirst) "el.value = '';" else ""}
                    el.value = (el.value || '') + $encodedText;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return { ok: true };
                })();
            """.trimIndent()
            else -> throw IllegalArgumentException("Either elementId or selector must be provided.")
        }

        evaluateJs(js)
        kotlinx.coroutines.delay(200)
        return extractState()
    }

    /**
     * Scroll viewport or specific element.
     */
    suspend fun scroll(direction: String = "down", amountPx: Int = 500): BrowserState {
        track("scroll", "$direction ${amountPx}px")
        val dy = if (direction.equals("up", true)) -amountPx else amountPx
        val dx = if (direction.equals("left", true)) -amountPx else if (direction.equals("right", true)) amountPx else 0
        val js = "window.scrollBy({ top: $dy, left: $dx, behavior: 'instant' });"
        evaluateJs(js)
        kotlinx.coroutines.delay(200)
        return extractState()
    }

    /**
     * Evaluates arbitrary JavaScript in the page context (tracked as an agent action).
     */
    suspend fun evaluateJs(code: String): String {
        track("eval", code.replace('\n', ' ').take(80))
        return evalRaw(code)
    }

    private suspend fun evalRaw(code: String): String = withContext(Dispatchers.Main) {
        val wv = getOrCreateWebView()
        val deferred = CompletableDeferred<String>()
        wv.evaluateJavascript(code) { result ->
            deferred.complete(result ?: "null")
        }
        deferred.await()
    }

    /**
     * Captures a screenshot of the current page and saves it in ImageStore.
     */
    suspend fun screenshot(): StoredImage? = withContext(Dispatchers.Main) {
        val wv = getOrCreateWebView()
        runCatching {
            val width = wv.width.takeIf { it > 0 } ?: 1080
            val height = wv.height.takeIf { it > 0 } ?: 1920
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wv.draw(canvas)

            val dir = File(appContext.filesDir, "images").apply { mkdirs() }
            val file = File(dir, "browser_${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            track("screenshot", file.name)
            StoredImage(file, "image/png")
        }.onFailure { track("screenshot", "failed: ${it.message.orEmpty().take(60)}", ok = false) }.getOrNull()
    }

    /**
     * Retrieves recent console logs.
     */
    fun getLogs(levelFilter: String? = null, clear: Boolean = false): List<BrowserConsoleLog> {
        track("logs", buildString {
            append(levelFilter?.uppercase()?.take(12) ?: "all")
            if (clear) append(", clear")
        })
        val list = if (levelFilter.isNullOrBlank()) {
            consoleLogs.toList()
        } else {
            consoleLogs.filter { it.level.equals(levelFilter, ignoreCase = true) }
        }
        if (clear) {
            consoleLogs.clear()
        }
        return list
    }

    /**
     * Extracts interactive elements, DOM summary, page title, and URL.
     */
    suspend fun extractState(): BrowserState {
        val script = DOM_INDEXING_SCRIPT
        val rawJson = evalRaw(script)
        return runCatching {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                // evaluateJavascript returns JSON-encoded string literal in some versions
                json.decodeFromString<String>(rawJson)
            } else {
                rawJson
            }
            json.decodeFromString<BrowserState>(clean)
        }.getOrElse {
            BrowserState(
                url = withContext(Dispatchers.Main) { getOrCreateWebView().url.orEmpty() },
                title = withContext(Dispatchers.Main) { getOrCreateWebView().title.orEmpty() },
                error = "Failed to parse DOM state: ${it.message}",
            )
        }
    }

    private fun isWorkspaceHtml(target: String): Boolean {
        val lower = target.lowercase()
        return (lower.endsWith(".html") || lower.endsWith(".htm")) &&
                !lower.startsWith("http://") && !lower.startsWith("https://")
    }

    companion object {
        /** Trail entries kept in memory for the WebPreviewSheet activity panel. */
        private const val MAX_TRACK = 50

        /**
         * Robust DOM indexing and interactive element extractor script.
         * Assigns `data-harness-id` to clickable and input elements and generates compact summary.
         */
        val DOM_INDEXING_SCRIPT = """
            (function() {
                try {
                    let idCounter = 1;
                    const elements = [];
                    const interactiveSelectors = 'a, button, input, textarea, select, [role="button"], [role="link"], [role="checkbox"], [role="menuitem"], [role="tab"], [tabindex]:not([tabindex="-1"]), [onclick]';
                    
                    const nodes = document.querySelectorAll(interactiveSelectors);
                    nodes.forEach(el => {
                        // Check visibility
                        const rect = el.getBoundingClientRect();
                        const style = window.getComputedStyle(el);
                        const isVisible = style.display !== 'none' && 
                                          style.visibility !== 'hidden' && 
                                          parseFloat(style.opacity || '1') > 0 &&
                                          (rect.width > 0 || rect.height > 0 || el.tagName === 'INPUT');
                        
                        if (isVisible) {
                            const harnessId = idCounter++;
                            el.setAttribute('data-harness-id', String(harnessId));
                            
                            elements.push({
                                id: harnessId,
                                tag: el.tagName.toLowerCase(),
                                type: el.getAttribute('type'),
                                name: el.getAttribute('name'),
                                text: (el.innerText || el.textContent || '').trim().substring(0, 100),
                                placeholder: el.getAttribute('placeholder'),
                                ariaLabel: el.getAttribute('aria-label'),
                                role: el.getAttribute('role'),
                                href: el.getAttribute('href'),
                                isVisible: true,
                                isClickable: true
                            });
                        }
                    });

                    // Body readable text extract
                    const bodyText = (document.body ? (document.body.innerText || '') : '')
                        .split('\n')
                        .map(l => l.trim())
                        .filter(l => l.length > 0)
                        .slice(0, 40)
                        .join('\n');

                    return JSON.stringify({
                        url: window.location.href,
                        title: document.title || '',
                        interactiveElements: elements.slice(0, 120),
                        textSummary: bodyText.substring(0, 2000),
                        consoleErrorCount: 0
                    });
                } catch (e) {
                    return JSON.stringify({
                        url: window.location.href || '',
                        title: document.title || '',
                        interactiveElements: [],
                        textSummary: '',
                        error: String(e)
                    });
                }
            })();
        """.trimIndent()
    }
}
