package com.androidharness.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.androidharness.app.core.LocalPortProbe
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.StoredImage
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
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
    val url: String = "",
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
    val inViewport: Boolean = true,
    val disabled: Boolean = false,
)

@Serializable
data class BrowserState(
    val url: String,
    val title: String,
    val interactiveElements: List<BrowserElement> = emptyList(),
    val textSummary: String = "",
    val error: String? = null,
    val consoleErrorCount: Int = 0,
    val scrollY: Int = 0,
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

/** Outcome of a sandboxed [BrowserController.evalUser] call. */
data class BrowserEvalOutcome(
    val ok: Boolean,
    val value: String?,
    val error: String?,
)

/**
 * Manages headless and GUI-mirrored WebView automation for the Agent.
 *
 * Exposes methods to navigate, click indexed elements, type text, scroll,
 * evaluate JS, take screenshots, and inspect console logs/errors. Every
 * mutating action surfaces element-not-found and navigation failures instead
 * of returning a snapshot that only looks successful, and waits for page
 * loads and smooth-scroll animations to settle before reading state.
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

    // Ring buffer of console logs (capped)
    private val consoleLogs = CopyOnWriteArrayList<BrowserConsoleLog>()

    // Current workspace reference for resolving local HTML files/assets
    @Volatile
    var currentWorkspace: WorkspaceFs? = null

    /** Workspace-relative path of the HTML served as the current base document, if any. */
    @Volatile
    private var baseUrlPath: String? = null

    /**
     * Loopback server for workspace pages, so local sites navigate like real
     * ones: relative links, form submits, assets, and history all just work.
     */
    private val httpServer = WorkspaceHttpServer(workspace = { currentWorkspace }, rootDoc = { baseUrlPath })

    private val isPageLoading = AtomicBoolean(false)

    /** Completed by onPageFinished of the HEADLESS client; mirrored WebViews signal via URL polling. */
    @Volatile
    private var loadDeferred: CompletableDeferred<Unit>? = null

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

    private suspend fun currentUrl(): String? = withContext(Dispatchers.Main) {
        runCatching { getOrCreateWebView().url }.getOrNull()
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
                                url = url.orEmpty(),
                            )
                        )
                        if (consoleLogs.size > MAX_LOGS) {
                            consoleLogs.removeAt(0)
                        }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // A fresh deferred per navigation, so action methods can await
                    // click-triggered loads and not just the navigate() one.
                    loadDeferred = CompletableDeferred()
                    isPageLoading.set(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoading.set(false)
                    loadDeferred?.complete(Unit)
                }
            }
        }
        headlessWebView = wv
        wv
    }

    /**
     * Waits until a new navigation starts (fresh load deferred on the headless
     * client, or the URL changed on a mirrored one), then waits for it to
     * finish. Returns true when a navigation was observed.
     */
    private suspend fun awaitNavigation(urlBefore: String?, detectMs: Long = 1_500, finishMs: Long = 10_000): Boolean {
        val before = loadDeferred
        val started = withTimeoutOrNull(detectMs) {
            while (isActive) {
                if (loadDeferred !== before) return@withTimeoutOrNull true
                val u = currentUrl()
                if (urlBefore != null && u != null && u != urlBefore) return@withTimeoutOrNull true
                delay(80)
            }
            false
        } ?: false
        if (!started) return false
        withTimeoutOrNull(finishMs) { loadDeferred?.takeIf { it !== before }?.await() }
        // Mirrored WebView has no deferred; fall back to URL stability.
        if (loadDeferred === before) {
            var stable = 0
            var last = currentUrl()
            withTimeoutOrNull(finishMs) {
                while (isActive && stable < 3) {
                    delay(200)
                    val now = currentUrl()
                    stable = if (now != null && now == last) stable + 1 else 0
                    last = now
                }
            }
        }
        return true
    }

    /**
     * Smooth-scroll animations race the snapshot; wait until scrollY reads
     * the same twice in a row before reporting state.
     */
    private suspend fun awaitScrollSettle(maxMs: Long = 1_500) {
        var last = -1
        withTimeoutOrNull(maxMs) {
            while (isActive) {
                val y = evalRaw("String(Math.round(window.scrollY || 0))").trim('"').toIntOrNull() ?: 0
                if (y == last) return@withTimeoutOrNull
                last = y
                delay(150)
            }
        }
    }

    /** Settle sequence after a mutating action: catch navigation, then scroll. */
    private suspend fun awaitSettle(urlBefore: String?) {
        val navigated = awaitNavigation(urlBefore)
        if (!navigated) delay(350) // brief settle for SPA re-renders
        awaitScrollSettle()
    }

    /**
     * Navigate to an external URL, a localhost dev server, or a workspace
     * HTML file. Local files are served over the loopback HTTP server, which
     * is health-checked (and rebound if wedged) before every navigation.
     */
    suspend fun navigate(url: String, workspace: WorkspaceFs?): BrowserState {
        val detail = url.take(120)
        track("navigate", detail)
        try {
            currentWorkspace = workspace
            val target = url.trim()

            // Resolve local paths and confirm the file server is healthy OFF
            // the main thread: a socket connect() from the UI thread throws
            // NetworkOnMainThreadException, which the probe would otherwise
            // swallow and misreport as "server failed to start".
            val localUrl: String? = if (isWorkspaceFileTarget(target)) {
                withContext(Dispatchers.IO) {
                    val rel = normalizeWorkspacePath(target, workspace?.shellRoot?.absolutePath)
                        ?: throw IllegalArgumentException(
                            "Unsupported local path '$target'. Use a workspace-relative path like " +
                                "'index.html' or 'docs/about.html'; file:// and absolute paths must point " +
                                "inside the active workspace."
                        )
                    if (workspace == null) {
                        throw IllegalArgumentException("No active workspace is set, cannot open '$rel'.")
                    }
                    val node = runCatching { workspace.resolve(rel) }.getOrNull()
                    if (node == null || !node.exists) {
                        throw IllegalArgumentException(
                            "Workspace file not found: $rel (workspace: ${workspace.displayPath})"
                        )
                    }
                    baseUrlPath = rel
                    val port = ensureServerHealthy()
                    val served = rel.split('/').joinToString("/") { seg ->
                        java.net.URLEncoder.encode(seg, "UTF-8")
                    }
                    "http://127.0.0.1:$port/$served"
                }
            } else null

            withContext(Dispatchers.Main) {
                val wv = getOrCreateWebView()
                val before = wv.url
                if (localUrl != null) {
                    wv.loadUrl(localUrl)
                } else {
                    baseUrlPath = null
                    wv.loadUrl(LocalPortProbe.normalizeLocalUrl(target))
                }
                withTimeoutOrNull(15_000) { awaitNavigation(before, detectMs = 2_000) }
            }
            awaitScrollSettle()
            return extractState()
        } catch (e: Exception) {
            track("navigate", detail, ok = false)
            throw e
        }
    }

    /**
     * Local-file targets: scheme-less paths, "./"-relative names, and file://
     * URIs. Everything else (http/https, scheme-less localhost:PORT dev
     * servers) is treated as an external URL and never rewritten.
     */
    private fun isWorkspaceFileTarget(target: String): Boolean {
        if (target.startsWith("localhost:") || target.startsWith("127.0.0.1:")) return false
        if (target.contains("://") && !target.startsWith("file://")) return false
        return true
    }

    /**
     * Binds (or rebinds) the loopback server and proves it answers before the
     * WebView is pointed at it. A dead or wedged listener used to surface as
     * an opaque ERR_CONNECTION_REFUSED; now it either self-heals or fails with
     * an explicit, logged reason. Must be called OFF the main thread.
     */
    private fun ensureServerHealthy(): Int {
        var port = httpServer.ensureStarted()
        var reason = probeServer(port)
        if (reason == null) return port
        android.util.Log.w("HarnessBrowser", "Workspace file server probe failed on port $port ($reason); rebinding")
        httpServer.stop()
        port = httpServer.ensureStarted()
        reason = probeServer(port)
        if (reason == null) return port
        android.util.Log.w("HarnessBrowser", "Workspace file server rebind probe failed on port $port ($reason)")
        throw IllegalStateException("Local file server failed to start (port $port): $reason")
    }

    /** Returns null when the server answers an HTTP request, else the failure reason. */
    private fun probeServer(port: Int): String? {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
                s.soTimeout = 1_500
                s.getOutputStream().apply {
                    write("HEAD / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val status = reader.readLine()
                if (status != null && status.startsWith("HTTP/1.")) null else "no HTTP status line"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Click an interactive element by its assigned index or CSS selector.
     * Throws when the element is missing so the agent knows nothing happened.
     */
    suspend fun click(elementId: Int? = null, selector: String? = null): BrowserState {
        val detail = elementId?.let { "#$it" } ?: selector.orEmpty().take(80)
        track("click", detail)
        val js = when {
            elementId != null -> """
                (function() {
                    const el = document.querySelector('[data-harness-id="$elementId"]');
                    if (!el) return { ok: false, error: "Element with id $elementId not found. The page re-rendered; re-run browser_get_dom for fresh ids." };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    el.click();
                    return { ok: true };
                })();
            """.trimIndent()
            !selector.isNullOrBlank() -> """
                (function() {
                    const el = document.querySelector(${json.encodeToString(selector)});
                    if (!el) return { ok: false, error: "No element matches selector '$selector'. Re-run browser_get_dom for fresh ids." };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    el.click();
                    return { ok: true };
                })();
            """.trimIndent()
            else -> throw IllegalArgumentException("Either elementId or selector must be provided.")
        }

        try {
            val error = parseActionError(evalRaw(js))
            if (error != null) {
                track("click", detail, ok = false)
                throw IllegalStateException(error)
            }
            awaitSettle(currentUrl())
            return extractState()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            track("click", detail, ok = false)
            throw e
        }
    }

    /**
     * Type text into an input or textarea element. Throws when the target is
     * missing or not an input-like element.
     */
    suspend fun type(text: String, elementId: Int? = null, selector: String? = null, clearFirst: Boolean = false): BrowserState {
        val detail = buildString {
            append(elementId?.let { "#$it" } ?: selector.orEmpty().take(40))
            append(" \"").append(text.take(40)).append('"')
        }
        track("type", detail)
        val encodedText = json.encodeToString(text)
        val js = when {
            elementId != null -> """
                (function() {
                    const el = document.querySelector('[data-harness-id="$elementId"]');
                    if (!el) return { ok: false, error: "Element with id $elementId not found. The page re-rendered; re-run browser_get_dom for fresh ids." };
                    const tag = (el.tagName || '').toLowerCase();
                    if (tag !== 'input' && tag !== 'textarea' && tag !== 'select' && el.isContentEditable !== true) {
                        return { ok: false, error: "Element $elementId is a <" + tag + ">, not a text field." };
                    }
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
                    if (!el) return { ok: false, error: "No element matches selector '$selector'." };
                    const tag = (el.tagName || '').toLowerCase();
                    if (tag !== 'input' && tag !== 'textarea' && tag !== 'select' && el.isContentEditable !== true) {
                        return { ok: false, error: "Element matching '$selector' is a <" + tag + ">, not a text field." };
                    }
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

        try {
            val error = parseActionError(evalRaw(js))
            if (error != null) {
                track("type", detail, ok = false)
                throw IllegalStateException(error)
            }
            awaitSettle(currentUrl())
            return extractState()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            track("type", detail, ok = false)
            throw e
        }
    }

    /**
     * Scroll viewport or specific element.
     */
    suspend fun scroll(direction: String = "down", amountPx: Int = 500): BrowserState {
        track("scroll", "$direction ${amountPx}px")
        val dy = if (direction.equals("up", true)) -amountPx else amountPx
        val dx = if (direction.equals("left", true)) -amountPx else if (direction.equals("right", true)) amountPx else 0
        val js = "window.scrollBy({ top: $dy, left: $dx, behavior: 'instant' });"
        evalRaw(js)
        awaitScrollSettle()
        return extractState()
    }

    /**
     * Go back / forward in WebView history. Throws when there is nowhere to go.
     */
    suspend fun back(): BrowserState {
        track("back", "history")
        val canGo = withContext(Dispatchers.Main) { getOrCreateWebView().canGoBack() }
        if (!canGo) throw IllegalStateException("No previous page in history.")
        val before = currentUrl()
        withContext(Dispatchers.Main) { getOrCreateWebView().goBack() }
        awaitSettle(before)
        return extractState()
    }

    suspend fun forward(): BrowserState {
        track("forward", "history")
        val canGo = withContext(Dispatchers.Main) { getOrCreateWebView().canGoForward() }
        if (!canGo) throw IllegalStateException("No next page in history.")
        val before = currentUrl()
        withContext(Dispatchers.Main) { getOrCreateWebView().goForward() }
        awaitSettle(before)
        return extractState()
    }

    /**
     * Reload the current page in place. Console logs are preserved.
     */
    suspend fun refresh(): BrowserState {
        track("refresh", "reload")
        val before = currentUrl()
        withContext(Dispatchers.Main) { getOrCreateWebView().reload() }
        awaitSettle(before)
        return extractState()
    }

    /**
     * Blocks until a condition holds. Conditions: "selector" (CSS selector
     * exists and is visible), "text" (string present in body text),
     * "url_contains" (substring of the URL).
     */
    suspend fun waitFor(condition: String, value: String, timeoutMs: Long = 5_000): BrowserState {
        track("wait", "$condition=${value.take(60)}")
        val capped = timeoutMs.coerceIn(250, 30_000)
        val predicate = when (condition.lowercase()) {
            "selector" -> {
                val sel = json.encodeToString(value)
                "(function(){ const el = document.querySelector($sel); if (!el) return false; const r = el.getBoundingClientRect(); const s = window.getComputedStyle(el); return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0; })()"
            }
            "text" -> {
                val txt = json.encodeToString(value)
                "((document.body ? (document.body.innerText || '') : '').indexOf($txt) !== -1)"
            }
            "url_contains" -> {
                val txt = json.encodeToString(value)
                "window.location.href.indexOf($txt) !== -1"
            }
            else -> throw IllegalArgumentException("Unknown condition '$condition'. Use selector, text, or url_contains.")
        }
        val js = "(function(){ try { return JSON.stringify({ ok: true, hit: !!($predicate) }); } catch(e) { return JSON.stringify({ ok: false, error: String(e) }); } })()"
        val deadline = System.currentTimeMillis() + capped
        while (System.currentTimeMillis() < deadline) {
            when (val hit = parsePredicateHit(evalRaw(js))) {
                true -> {
                    awaitScrollSettle()
                    return extractState()
                }
                false -> delay(250)
                null -> throw IllegalStateException("Wait condition script failed on this page.")
            }
        }
        track("wait", "$condition=${value.take(60)}", ok = false)
        throw IllegalStateException("Timed out after ${capped}ms waiting for $condition '$value'.")
    }

    /**
     * Cheap read of the current URL and title. Read from the page itself:
     * getUrl() reports "about:blank" for synthetic loads, while
     * window.location.href always reflects where the page really is.
     */
    suspend fun getUrl(): Pair<String, String> {
        val raw = evalRaw(
            "(function(){ try { return JSON.stringify({ href: String(window.location.href || ''), title: String(document.title || '') }); } catch (e) { return ''; } })()"
        )
        val parsed = runCatching {
            val decoded = decodeJsJson(raw)
            if (decoded.isNullOrBlank()) return@runCatching null
            val obj = jsJson.parseToJsonElement(decoded).jsonObject
            val href = obj["href"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val title = obj["title"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            href to title
        }.getOrNull()
        val (url, title) = parsed ?: withContext(Dispatchers.Main) {
            val wv = getOrCreateWebView()
            (wv.url.orEmpty() to wv.title.orEmpty())
        }
        track("url", url.take(80))
        return url to title
    }

    /**
     * Evaluates JavaScript in the page inside a sandboxed synchronous wrapper:
     * the completion value (or an explicit `return`) is the result, and both
     * runtime throws and syntax errors surface as [BrowserEvalOutcome.error]
     * instead of a bare null. WebView's evaluateJavascript does not await
     * promises, so eval is synchronous by design; async flows should kick off
     * work, then use [waitFor].
     */
    suspend fun evalUser(code: String): BrowserEvalOutcome {
        val detail = code.replace('\n', ' ').take(80)
        val raw = evalRaw(buildEvalJs(code))
        val outcome = parseEvalOutcome(raw)
        track("eval", detail, ok = outcome.ok)
        return outcome
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
     * Retrieves recent console logs. Filter by level and/or a substring of the
     * page URL or script source.
     */
    fun getLogs(levelFilter: String? = null, sourceFilter: String? = null, clear: Boolean = false): List<BrowserConsoleLog> {
        track("logs", buildString {
            append(levelFilter?.uppercase()?.take(12) ?: "all")
            if (!sourceFilter.isNullOrBlank()) append(", src~")
            if (clear) append(", clear")
        })
        var list = if (levelFilter.isNullOrBlank()) {
            consoleLogs.toList()
        } else {
            consoleLogs.filter { it.level.equals(levelFilter, ignoreCase = true) }
        }
        if (!sourceFilter.isNullOrBlank()) {
            list = list.filter {
                it.url.contains(sourceFilter, ignoreCase = true) ||
                        it.source.contains(sourceFilter, ignoreCase = true)
            }
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
        val rawJson = evalRaw(DOM_INDEXING_SCRIPT)
        return runCatching {
            val clean = decodeJsJson(rawJson) ?: rawJson
            json.decodeFromString<BrowserState>(clean)
        }.getOrElse {
            BrowserState(
                url = currentUrl().orEmpty(),
                title = "",
                error = "Failed to parse DOM state: ${it.message}",
            )
        }
    }

    companion object {
        /** Trail entries kept in memory for the WebPreviewSheet activity panel. */
        private const val MAX_TRACK = 50

        private const val MAX_LOGS = 200

        /**
         * Maps a browser_navigate target to a workspace-relative HTML path.
         * Accepts plain names ("index.html"), "./"-relative forms, file:// URIs
         * under the workspace root, and absolute paths under the workspace root
         * (real-directory workspaces only). Returns null for anything it must
         * not rewrite: external URLs, paths outside the workspace, traversal,
         * and non-HTML files. Pure for tests.
         */
        fun normalizeWorkspacePath(target: String, rootPath: String?): String? {
            var rel = target.trim().removePrefix("file://")
            while (rel.startsWith("./")) rel = rel.substring(2)
            if (rel.startsWith("/")) {
                val root = rootPath?.trimEnd('/') ?: return null
                if (!rel.startsWith("$root/")) return null
                rel = rel.substring(root.length)
            }
            rel = rel.trim().trimStart('/')
            if (rel.isBlank()) return null
            if (!rel.endsWith(".html", true) && !rel.endsWith(".htm", true)) return null
            for (seg in rel.split('/')) {
                if (seg == ".." || seg == "\\") return null
            }
            return rel
        }

        /**
         * Builds the sandboxed eval script for [code]. WebView's
         * evaluateJavascript does NOT await promises, so this is synchronous:
         *
         * 1. `eval(code)` first, which returns the completion value, so bare
         *    trailing expressions like `2+2` work and side effects run once.
         * 2. On SyntaxError (e.g. an explicit `return` statement, which eval
         *    rejects), retry via `new Function(code)`, which allows `return`.
         * 3. Anything else that throws, including genuine syntax errors (which
         *    fail BOTH passes), comes back as {ok:false, error: message}.
         */
        fun buildEvalJs(code: String): String {
            val literal = jsJson.encodeToString(code)
            return """
                (function() {
                    try {
                        let __v = eval($literal);
                        return JSON.stringify({ ok: true, value: __v === undefined ? null : __v });
                    } catch (e) {
                        if (e instanceof SyntaxError) {
                            try {
                                const __f = new Function($literal);
                                const __r = __f.call(window);
                                return JSON.stringify({ ok: true, value: __r === undefined ? null : __r });
                            } catch (e2) {
                                return JSON.stringify({ ok: false, error: String(e2 && e2.message || e2) });
                            }
                        }
                        return JSON.stringify({ ok: false, error: String(e && e.message || e) });
                    }
                })();
            """.trimIndent()
        }

        private val jsJson = Json { isLenient = true; ignoreUnknownKeys = true }

        /**
         * evaluateJavascript may return a JSON string literal (quoted) when the
         * page code itself returned a string; unwrap that layer.
         */
        fun decodeJsJson(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return runCatching { jsJson.decodeFromString<String>(trimmed) }.getOrNull()
            }
            return trimmed.ifEmpty { null }
        }

        /** Parses a sandboxed eval envelope into [BrowserEvalOutcome]. */
        fun parseEvalOutcome(raw: String): BrowserEvalOutcome {
            val decoded = decodeJsJson(raw)
                ?: return BrowserEvalOutcome(false, null, "No result returned from evaluation.")
            return runCatching {
                val obj = jsJson.parseToJsonElement(decoded).jsonObject
                val ok = obj["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                if (ok) {
                    val v = obj["value"]
                    val rendered = when {
                        v == null || v is JsonNull -> "null"
                        v is JsonPrimitive -> v.content
                        else -> v.toString()
                    }
                    BrowserEvalOutcome(true, rendered, null)
                } else {
                    val err = obj["error"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "Unknown error"
                    BrowserEvalOutcome(false, null, err)
                }
            }.getOrElse {
                // The page returned something non-envelope (raw expression); pass it through as the value.
                BrowserEvalOutcome(true, decoded, null)
            }
        }

        /** Extracts the error message from a {ok:false,error} action envelope, or null on success. */
        fun parseActionError(raw: String): String? {
            val decoded = decodeJsJson(raw) ?: return "No response from page script."
            return runCatching {
                val obj = jsJson.parseToJsonElement(decoded).jsonObject
                val ok = obj["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                if (ok) null
                else obj["error"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "Unknown page script failure."
            }.getOrElse { null } // non-envelope result (shouldn't happen) = treat as success
        }

        /**
         * Parses a {ok, hit} predicate envelope: true/false when the condition
         * evaluated, null when the script itself threw.
         */
        fun parsePredicateHit(raw: String): Boolean? {
            val decoded = decodeJsJson(raw) ?: return null
            return runCatching {
                val obj = jsJson.parseToJsonElement(decoded).jsonObject
                val ok = obj["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                if (!ok) null
                else obj["hit"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
            }.getOrElse { null }
        }

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
                            const inViewport = rect.top < window.innerHeight && rect.bottom > 0 &&
                                               rect.left < window.innerWidth && rect.right > 0;
                            const disabled = el.disabled === true || el.getAttribute('aria-disabled') === 'true';

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
                                isClickable: true,
                                inViewport: inViewport,
                                disabled: disabled
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
                        consoleErrorCount: 0,
                        scrollY: Math.round(window.scrollY || 0)
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
