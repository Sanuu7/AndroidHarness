package com.androidharness.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

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
 * Which element an action targets. Exactly one of the two is set, chosen by
 * [BrowserController.resolveElementTarget].
 */
data class ElementTarget(val elementId: Int?, val selector: String?) {
    /** How this target is named in an error message. */
    val errorSubject: String
        get() = if (selector != null) "Element matching '$selector'" else "Element $elementId"
}

/** Saved screenshot outcome containing workspace relative path, cache file, and size. */
data class BrowserScreenshotResult(
    val filename: String,
    val relPath: String,
    val cachedFile: File,
    val sizeBytes: Long,
    val mime: String = "image/jpeg",
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
    private val logLock = Any()
    @Volatile
    private var lastLoggedMessage: String? = null
    @Volatile
    private var lastLoggedTimestamp: Long = 0L

    // Current workspace reference for resolving local HTML files/assets
    @Volatile
    var currentWorkspace: WorkspaceFs? = null

    /** Workspace-relative path of the HTML served as the current base document, if any. */
    @Volatile
    private var baseUrlPath: String? = null

    /**
     * Serves workspace pages to every WebView (headless and preview sheet)
     * under https://harness.workspace/... via request interception: no
     * sockets, and every local page is a real navigation with real history.
     * The handler owns the WHOLE host so same-origin fetch('/...') and XHR
     * resolve too.
     */
    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(WorkspacePathHandler.HOST)
        .addPathHandler(
            "/",
            WorkspacePathHandler(
                workspaceProvider = { currentWorkspace },
                rootDocProvider = { baseUrlPath },
            ),
        )
        .build()

    /**
     * Routes a WebView request through the workspace asset loader. Wired into
     * BOTH the headless client and the preview sheet's client; returns null
     * for hosts the loader does not own so callers fall through to default
     * loading.
     */
    fun interceptWorkspaceRequest(url: android.net.Uri?): WebResourceResponse? {
        if (url?.host != WorkspacePathHandler.HOST) return null
        return assetLoader.shouldInterceptRequest(url)
    }

    /**
     * Takes ownership of the preview sheet's WebView when the sheet closes,
     * so agent page state and history survive the sheet. Replaces (and
     * destroys) any previous background WebView.
     */
    fun adoptWebView(wv: WebView) {
        activeWebViewRef?.clear()
        activeWebViewRef = null
        val adoptedUrl = runCatching { wv.url }.getOrNull()
        if (adoptedUrl.isNullOrBlank() || adoptedUrl == "about:blank") {
            // Never overwrite active headless state with an uninitialized or blank view
            mainHandler.post {
                runCatching {
                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
            return
        }
        val old = headlessWebView
        headlessWebView = wv
        if (old != null && old !== wv) {
            mainHandler.post {
                runCatching {
                    (old.parent as? android.view.ViewGroup)?.removeView(old)
                    old.destroy()
                }
            }
        }
        // The sheet is gone; detach the adopted view from any leftover parent.
        mainHandler.post {
            runCatching { (wv.parent as? android.view.ViewGroup)?.removeView(wv) }
        }
    }

    /**
     * Navigation lifecycle of the active view. Fed by the headless client's
     * callbacks and by the preview sheet forwarding its own, so actions can
     * wait for a real document instead of guessing from `WebView.url`.
     */
    private val loadTracker = PageLoadTracker()

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
        // The preview sheet has its own WebViewClient, so a main-frame download
        // there is invisible to us unless we set the listener ourselves; without
        // it an attachment navigation looks like a successful, unchanged page.
        runCatching { webView.setDownloadListener(downloadListener) }
    }

    fun unbindActiveWebView(webView: WebView) {
        if (activeWebViewRef?.get() == webView) {
            activeWebViewRef = null
        }
    }

    /** Returns active browser URL if initialized or navigating. */
    fun getActiveUrl(): String? {
        return activeWebViewRef?.get()?.url ?: headlessWebView?.url
    }

    /**
     * Renders the WebView's own back/forward list for tests and diagnostics:
     * the entries a history step is judged against are otherwise invisible, and
     * a duplicate entry is exactly what makes a step look like it never moved.
     */
    internal suspend fun debugHistoryDump(): String = withContext(Dispatchers.Main) {
        runCatching {
            val list = getOrCreateWebView().copyBackForwardList() ?: return@runCatching "no list"
            (0 until list.size).joinToString(" | ") { i ->
                val item = list.getItemAtIndex(i)
                val mark = if (i == list.currentIndex) "*" else ""
                "$mark${item?.url?.substringAfterLast('/')}"
            }
        }.getOrElse { "unavailable: ${it.message}" }
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
                    recordConsoleMessage(msg, url.orEmpty())
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    if (headlessWebView === view) {
                        headlessWebView = null
                    }
                    if (activeWebViewRef?.get() === view) {
                        activeWebViewRef = null
                    }
                    runCatching {
                        (view?.parent as? android.view.ViewGroup)?.removeView(view)
                        view?.destroy()
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    notePageStarted()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    notePageFinished(url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    // A main-frame failure still replaces the page with an error
                    // document, so the wait must end here; the reason is worth
                    // reporting instead of a silent error page.
                    if (request?.isForMainFrame == true) {
                        notePageError(error?.description?.toString())
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    return request?.let { interceptWorkspaceRequest(it.url) }
                        ?: super.shouldInterceptRequest(view, request)
                }
            }

            setDownloadListener(downloadListener)
        }
        headlessWebView = wv
        wv.loadUrl("about:blank")
        wv
    }

    /**
     * Navigation lifecycle hooks. The headless client calls them directly; the
     * preview sheet forwards its own client's callbacks, so an action driven
     * against the visible WebView gets the same honest load signals as one
     * against the headless view.
     */
    fun notePageStarted() {
        loadTracker.onStarted()
    }

    fun notePageFinished(url: String?) {
        loadTracker.onFinished(url)
    }

    fun notePageError(description: String?) {
        loadTracker.onError(description)
    }

    /** Drops the previous action's download report before a new navigation. */
    private fun clearDownloadReport() {
        loadTracker.clearDownload()
    }

    /**
     * Main-frame responses the WebView refuses to render as a page (an
     * attachment download, for instance) produce no document at all, and the
     * navigation used to be reported as a success showing the previous page.
     * Recording the reason lets navigate() say what actually happened.
     */
    private val downloadListener = DownloadListener { url, _, contentDisposition, mimeType, _ ->
        loadTracker.onDownload(
            buildString {
                append(mimeType.ifBlank { "unknown content type" })
                val name = contentDisposition
                    ?.substringAfter("filename=", "")
                    ?.trim()
                    ?.trim('"')
                    .orEmpty()
                if (name.isNotEmpty()) append(" named \"").append(name).append('"')
                append(" from ").append(url.take(120))
            },
        )
    }

    /** Reads the URL of the document actually on screen, not the provisional one. */
    private suspend fun committedPageUrl(): String? =
        readDocumentProbe()?.url ?: currentUrl()

    /**
     * Asks the page itself where it is and whether it is done loading.
     * `WebView.url` reports a navigation's TARGET as soon as it starts, so it
     * cannot tell a still-loading page from a displayed one; `location.href`
     * only moves when the new document commits.
     */
    private suspend fun readDocumentProbe(): PageLoadTracker.DocumentProbe? {
        // Timeout around the JS round trip only: a page that answers slowly must
        // not stall the poll loop, and the callbacks decide instead when the
        // page says nothing at all.
        val raw = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { evalRaw(DOCUMENT_PROBE_SCRIPT) }.getOrNull()
        } ?: return null
        return runCatching {
            val decoded = decodeJsJson(raw) ?: return@runCatching null
            val obj = jsJson.parseToJsonElement(decoded).jsonObject
            PageLoadTracker.DocumentProbe(
                url = obj["href"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                ready = obj["ready"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true,
            )
        }.getOrNull()
    }

    /** Outcome of waiting for the navigation an action triggered. */
    private data class NavigationOutcome(
        val started: Boolean,
        val settled: Boolean,
        val error: String? = null,
        val download: String? = null,
    )

    /**
     * Waits for the navigation triggered by the last action: first for it to
     * begin, then for its document to commit and finish loading. The page probe
     * is only spent when the client callbacks have nothing to report (a WebView
     * whose client is not ours), because it costs a JS round trip per poll.
     *
     * [generationBefore] is passed in rather than read here: a caller that
     * triggers a navigation has to capture the baseline BEFORE its own call,
     * since onPageStarted can fire before that call returns.
     */
    private suspend fun awaitNavigation(
        generationBefore: Int,
        urlBefore: String?,
        detectMs: Long = 2_500,
        finishMs: Long = 15_000,
    ): NavigationOutcome {
        var probe: PageLoadTracker.DocumentProbe? = null

        var started = PageLoadTracker.didStart(loadTracker.snapshot(probe), generationBefore, urlBefore)
        var detectWaited = 0L
        while (!started && detectWaited < detectMs && !loadTracker.hasDownload) {
            delay(POLL_MS)
            detectWaited += POLL_MS
            if (loadTracker.currentGeneration > generationBefore) {
                started = true
                break
            }
            probe = readDocumentProbe() ?: probe
            started = PageLoadTracker.didStart(loadTracker.snapshot(probe), generationBefore, urlBefore)
        }
        // A download explains itself and no document is coming, started or not.
        if (loadTracker.hasDownload) {
            return NavigationOutcome(
                started = started,
                settled = false,
                error = loadTracker.snapshot().error,
                download = loadTracker.takeDownload(),
            )
        }
        if (!started) {
            return NavigationOutcome(started = false, settled = false, error = loadTracker.snapshot().error, download = null)
        }

        var settled = PageLoadTracker.isSettled(loadTracker.snapshot(probe), generationBefore, urlBefore)
        var finishWaited = 0L
        while (!settled && finishWaited < finishMs && !loadTracker.hasDownload) {
            delay(POLL_MS)
            finishWaited += POLL_MS
            if (loadTracker.snapshot().let { PageLoadTracker.isSettled(it, generationBefore, urlBefore) }) {
                settled = true
                break
            }
            probe = readDocumentProbe() ?: probe
            settled = PageLoadTracker.isSettled(loadTracker.snapshot(probe), generationBefore, urlBefore)
        }
        val snapshot = loadTracker.snapshot(probe)
        return NavigationOutcome(
            started = true,
            settled = settled,
            error = snapshot.error,
            download = loadTracker.takeDownload(),
        )
    }

    /**
     * Waits for a navigation that is still in flight to finish, so an action
     * does not act on a page whose history entry does not exist yet (see
     * [back]). Bounded: a wedged load must not hang the tool.
     */
    private suspend fun awaitPendingLoad(maxMs: Long = 8_000) {
        var waited = 0L
        while (loadTracker.isLoading && waited < maxMs) {
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    /**
     * Carries what the wait learned into the reported state: a load error the
     * page hit, a download where a page should have been, and an honest "this
     * may not be the page you asked for" when the navigation never finished
     * settling.
     */
    private fun BrowserState.withLoadNote(outcome: NavigationOutcome): BrowserState {
        val notes = listOfNotNull(
            outcome.download?.let {
                "the browser received a download ($it) instead of a page, so the page did not change"
            },
            outcome.error?.let { "the page reported a load error: $it" },
            if (outcome.started && !outcome.settled && outcome.download == null) {
                "the page was still loading when this state was read, so it may be incomplete " +
                    "or still show the previous document"
            } else {
                null
            },
        )
        if (notes.isEmpty()) return this
        return copy(error = listOfNotNull(error, notes.joinToString("; ")).joinToString("; "))
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

    /**
     * Settle sequence after a mutating action: catch navigation, then scroll.
     * The baseline is captured here, before the caller's action could have
     * started one.
     */
    private suspend fun awaitSettle(generationBefore: Int, urlBefore: String?): NavigationOutcome {
        val outcome = awaitNavigation(generationBefore, urlBefore)
        if (!outcome.started) delay(350) // brief settle for SPA re-renders
        awaitScrollSettle()
        return outcome
    }

    /**
     * Navigate to an external URL, a localhost dev server, or a workspace
     * HTML file. Local files are served under the fixed asset origin
     * https://harness.workspace/ws/... by WebViewAssetLoader: no sockets,
     * real navigation entries, and relative links/forms/assets that behave
     * like a normal site.
     */
    suspend fun navigate(url: String, workspace: WorkspaceFs?): BrowserState {
        val detail = url.take(120)
        track("navigate", detail)
        try {
            currentWorkspace = workspace
            val target = url.trim()

            // Validate local paths before touching the WebView.
            val localUrl: String? = if (isWorkspaceFileTarget(target)) {
                val (_, suffix) = splitLocalTarget(target)
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
                BrowserController.localFileUrl(rel) + suffix
            } else null

            // The document on screen BEFORE the load, read from the page: a
            // provisional wv.url can already be the target, which would make
            // the new document look like the one we started on.
            val before = committedPageUrl()
            clearDownloadReport()
            val generationBefore = loadTracker.currentGeneration
            withContext(Dispatchers.Main) {
                val wv = getOrCreateWebView()
                if (localUrl != null) {
                    wv.loadUrl(localUrl)
                } else {
                    baseUrlPath = null
                    wv.loadUrl(LocalPortProbe.normalizeLocalUrl(target))
                }
            }
            val outcome = awaitNavigation(generationBefore, before, detectMs = 2_500, finishMs = NAVIGATE_TIMEOUT_MS)
            // A download is a failure whether or not a navigation was observed:
            // either way no document is coming and the browser still shows the
            // previous page. Reporting that as a successful navigate is the bug
            // this check exists for.
            if (outcome.download != null || !outcome.started) {
                track("navigate", detail, ok = false)
                throw IllegalStateException(describeFailedNavigation(target, outcome))
            }
            awaitScrollSettle()
            return extractState().withLoadNote(outcome)
        } catch (e: Exception) {
            track("navigate", detail, ok = false)
            throw e
        }
    }

    /**
     * Why nothing loaded. A URL the WebView hands to a download, a scheme it
     * refuses to render, or a request that never left the device all end the
     * same way: no document, so reporting the previous page as the result is a
     * lie (on-device QA: an octet-stream with Content-Disposition: attachment
     * "navigated" successfully twice, with the old page in the result and no
     * request in the server log).
     */
    private fun describeFailedNavigation(target: String, outcome: NavigationOutcome): String {
        val download = outcome.download
        return if (download != null) {
            "The browser did not open '$target': the response was a download ($download), " +
                "which WebView does not display. The page did not change."
        } else {
            "The browser never started loading '$target', so nothing changed. WebView refuses " +
                "some targets outright (downloads, mailto:/intent:/blob: schemes, unsupported " +
                "content types) and reports no error for them. Check the URL and how the server " +
                "answers it (an attachment or non-page content type will not open here)."
        }
    }

    /**
     * Local-file targets: scheme-less paths, "./"-relative forms, and file://
     * URIs. Everything else (http/https, scheme-less localhost:PORT dev
     * servers) is treated as an external URL and never rewritten.
     */
    private fun isWorkspaceFileTarget(target: String): Boolean {
        if (target.startsWith("localhost:") || target.startsWith("127.0.0.1:")) return false
        if (target.contains("://") && !target.startsWith("file://")) return false
        return true
    }

    /**
     * Click an interactive element by its assigned index or CSS selector.
     * Throws when the element is missing so the agent knows nothing happened.
     * A supplied selector wins over a supplied id (see [resolveElementTarget]).
     */
    suspend fun click(elementId: Int? = null, selector: String? = null): BrowserState {
        val target = resolveElementTarget(elementId, selector)
            ?: throw IllegalArgumentException("Either elementId or selector must be provided.")
        val detail = target.selector?.take(80) ?: "#${target.elementId}"
        track("click", detail)
        val js = buildClickJs(target)

        // Read where the page is BEFORE the click: a click that navigates must
        // be waited out, and a click that does not must not be.
        val before = committedPageUrl()
        val generationBefore = loadTracker.currentGeneration
        try {
            val raw = evalRaw(js)
            val error = parseActionError(raw)
            if (error != null) {
                track("click", detail, ok = false)
                throw IllegalStateException(error)
            }
            val outcome = awaitSettle(generationBefore, before)
            return withActionNote(extractState(), raw, elementId, target).withLoadNote(outcome)
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
        val target = resolveElementTarget(elementId, selector)
            ?: throw IllegalArgumentException("Either elementId or selector must be provided.")
        val detail = buildString {
            append(target.selector?.take(40) ?: "#${target.elementId}")
            append(" \"").append(text.take(40)).append('"')
        }
        track("type", detail)
        val js = buildTypeJs(target, text, clearFirst)

        val before = committedPageUrl()
        val generationBefore = loadTracker.currentGeneration
        try {
            val raw = evalRaw(js)
            val error = parseActionError(raw)
            if (error != null) {
                track("type", detail, ok = false)
                throw IllegalStateException(error)
            }
            val outcome = awaitSettle(generationBefore, before)
            return withActionNote(extractState(), raw, elementId, target).withLoadNote(outcome)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            track("type", detail, ok = false)
            throw e
        }
    }

    /**
     * Carries the page's own warning (a disabled control) and the
     * selector-over-id note into the returned state, which is already rendered
     * with them.
     */
    private fun withActionNote(
        state: BrowserState,
        raw: String,
        elementId: Int?,
        target: ElementTarget,
    ): BrowserState {
        val override = if (target.selector != null && elementId != null) {
            "selector used; id $elementId ignored"
        } else {
            null
        }
        val note = listOfNotNull(parseActionWarning(raw), override).joinToString("; ")
        if (note.isEmpty()) return state
        return state.copy(error = listOfNotNull(state.error, note).joinToString("; "))
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
     * Go back in WebView history. If a step lands on a 301/302 redirect that
     * bounces back to the starting page, retries up to [MAX_HISTORY_STEPS] times.
     *
     * Two things must hold before a step is judged, or the retry walks backwards
     * through entries the caller never asked to skip (on-device QA, 2026-09-17:
     * navigate → click a link → back landed on an entry older than the page just
     * left, because the landing was read while the step was still loading and
     * every `Apage.html?...` compared as the same document):
     *
     *  - the current navigation is finished first, so a page whose document has
     *    not committed yet cannot make goBack() skip past it;
     *  - the landing is only judged once its own load has SETTLED, and only a
     *    settled landing back on the starting page is treated as a bounce.
     */
    suspend fun back(): BrowserState = historyStep(
        action = "back",
        canStep = { it.canGoBack() },
        step = { it.goBack() },
    )

    /**
     * Go forward in WebView history. Same rules as [back].
     */
    suspend fun forward(): BrowserState = historyStep(
        action = "forward",
        canStep = { it.canGoForward() },
        step = { it.goForward() },
    )

    /** Shared body of [back] and [forward]; see [back] for why each guard exists. */
    private suspend fun historyStep(
        action: String,
        canStep: (WebView) -> Boolean,
        step: (WebView) -> Unit,
    ): BrowserState {
        val back = action == "back"
        track(action, "history")
        awaitPendingLoad()

        // The WebView's own back/forward list is the only reliable account of
        // where a step landed: it is authoritative about the entry, whereas
        // asking the page (or wv.url) while a navigation is still in flight
        // reports the document being left behind. That stale read is what made
        // every step look unmoved and retried until history ran out.
        val start = historyPosition()
            ?: throw IllegalStateException("The browser has no history to step through yet.")
        val canGo = withContext(Dispatchers.Main) { canStep(getOrCreateWebView()) }
        if (!canGo) {
            throw IllegalStateException(
                if (back) "No previous page in history." else "No next page in history.",
            )
        }
        val startUrl = start.url ?: committedPageUrl().orEmpty()
        // A bounce steps to the entry behind the one we are on, so the target
        // always advances by one from the CURRENT entry on each attempt.
        var targetIndex = targetHistoryIndex(start.index, back)
        var attempts = 0

        while (true) {
            attempts++
            val from = historyPosition() ?: start
            targetIndex = targetHistoryIndex(from.index, back)
            // Baseline BEFORE the step: onPageStarted can fire before the call
            // returns, and a baseline taken afterwards would miss the very
            // navigation the step caused.
            val baseline = loadTracker.currentGeneration
            withContext(Dispatchers.Main) { step(getOrCreateWebView()) }

            val reached = awaitHistoryIndex(targetIndex)
            val position = historyPosition() ?: from
            val outcome = awaitNavigation(baseline, startUrl, detectMs = 2_500, finishMs = 8_000)
            val canStepFurther = withContext(Dispatchers.Main) { canStep(getOrCreateWebView()) }

            when (val decision = decideHistoryStep(
                startUrl = startUrl,
                targetIndex = targetIndex,
                position = position,
                reachedTarget = reached,
                back = back,
                attempts = attempts,
                maxAttempts = MAX_HISTORY_STEPS,
                canStepFurther = canStepFurther,
            )) {
                is HistoryStepOutcome.Landed -> {
                    awaitScrollSettle()
                    return extractState().withLoadNote(outcome)
                }

                is HistoryStepOutcome.Correct -> {
                    // The step overshot the intended entry; walk it back so the
                    // caller gets the page one step away, not two.
                    awaitLandOnIndex(decision.index, back = !back)
                    awaitScrollSettle()
                    return extractState().withLoadNote(outcome)
                }

                HistoryStepOutcome.Bounce -> Unit // Step again from the new position.

                is HistoryStepOutcome.Stalled -> {
                    awaitScrollSettle()
                    return extractState().withLoadNote(outcome.copy(error = outcome.error ?: decision.reason))
                }
            }
        }
    }

    /** Where the WebView stands in its own back/forward list, or null if unavailable. */
    private suspend fun historyPosition(): HistoryPosition? = withContext(Dispatchers.Main) {
        runCatching {
            val list = getOrCreateWebView().copyBackForwardList() ?: return@runCatching null
            val index = list.currentIndex
            HistoryPosition(
                index = index,
                size = list.size,
                url = list.getItemAtIndex(index)?.url,
            )
        }.getOrNull()
    }

    /**
     * Waits for the back/forward list to arrive at [index]. Returns true when
     * the list was OBSERVED there, even if it has since moved off it: a
     * redirecting entry is visited and then leaves again on its own, and that
     * visit is what proves the entry was consumed. Bounded, so a step that
     * never lands cannot hang the tool.
     */
    private suspend fun awaitHistoryIndex(index: Int, timeoutMs: Long = 5_000): Boolean {
        var waited = 0L
        var observed = false
        while (waited < timeoutMs) {
            if (historyPosition()?.index == index) observed = true
            // Settled: nothing more will change within the remaining budget.
            if (observed && !loadTracker.isLoading) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return observed || historyPosition()?.index == index
    }

    /** Steps until [index] is the current entry, within the history-step budget. */
    private suspend fun awaitLandOnIndex(index: Int, back: Boolean) {
        var attempts = 0
        while (attempts < MAX_HISTORY_STEPS && historyPosition()?.index != index) {
            attempts++
            withContext(Dispatchers.Main) {
                val wv = getOrCreateWebView()
                if (back) wv.goBack() else wv.goForward()
            }
            awaitHistoryIndex(index, timeoutMs = 3_000)
        }
    }

    /**
     * Reload the current page in place. Console logs are preserved.
     */
    suspend fun refresh(): BrowserState {
        track("refresh", "reload")
        val before = committedPageUrl()
        val generationBefore = loadTracker.currentGeneration
        withContext(Dispatchers.Main) { getOrCreateWebView().reload() }
        val outcome = awaitSettle(generationBefore, before)
        return extractState().withLoadNote(outcome)
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
     * instead of a bare null. If the code returns a Promise, the result is
     * staged into window.__harnessAsync by the page and polled here, so
     * `await`-style async evals work up to [awaitPromiseMs].
     */
    suspend fun evalUser(code: String, awaitPromiseMs: Long = 10_000): BrowserEvalOutcome {
        val detail = code.replace('\n', ' ').take(80)
        return try {
            var outcome = parseEvalOutcome(evalRaw(buildEvalJs(code)))
            if (outcome.ok && outcome.value == PROMISE_SENTINEL) {
                outcome = awaitStagedPromise(awaitPromiseMs)
            }
            track("eval", detail, ok = outcome.ok)
            outcome
        } catch (e: Exception) {
            track("eval", detail, ok = false)
            BrowserEvalOutcome(false, null, e.message ?: "Evaluation failed")
        }
    }

    /**
     * Polls for the value a staged promise parked in the page.
     *
     * The page has to say whether the staging happened AT ALL: a promise that
     * never settles and a page that navigated away mid-await both leave
     * `__harnessAsync` null, and the old "null means still pending" test spun
     * for the full timeout on the second case even though the navigation had
     * already succeeded (on-device QA, 2026-09-17: a JS-dispatched click left
     * browser_eval reporting "Promise did not settle within 10000ms" while
     * browser_get_url showed the new page).
     */
    private suspend fun awaitStagedPromise(timeoutMs: Long): BrowserEvalOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val raw = evalRaw(STAGED_PROMISE_PROBE_SCRIPT)
            val staged = parseEvalOutcome(raw)
            if (staged.ok && staged.value != PROMISE_SENTINEL) return staged
            if (!staged.ok) return staged
            delay(150)
        }
        return BrowserEvalOutcome(false, null, "Promise did not settle within ${timeoutMs}ms")
    }

    fun recordConsoleMessage(msg: ConsoleMessage?, currentUrl: String? = null) {
        msg ?: return
        val level = when (msg.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
            ConsoleMessage.MessageLevel.WARNING -> "WARN"
            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
            else -> "LOG"
        }
        val sourceUrl = currentUrl ?: getActiveUrl().orEmpty()
        val text = msg.message().orEmpty()
        val src = msg.sourceId().orEmpty()
        val line = msg.lineNumber()

        val signature = "$level:$text:$src:$line"
        val now = System.currentTimeMillis()
        synchronized(logLock) {
            if (signature == lastLoggedMessage && (now - lastLoggedTimestamp) < 150L) {
                // Deduplicate identical duplicate dispatches from simultaneous webview clients
                return
            }
            lastLoggedMessage = signature
            lastLoggedTimestamp = now

            consoleLogs.add(
                BrowserConsoleLog(
                    level = level,
                    message = text,
                    source = src,
                    line = line,
                    url = sourceUrl,
                )
            )
            if (consoleLogs.size > MAX_LOGS) {
                consoleLogs.removeAt(0)
            }
        }
    }

    private suspend fun unwedgeWebView(wv: WebView) = withContext(Dispatchers.Main) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                wv.webViewRenderProcess?.terminate()
            }
        }
        runCatching {
            wv.stopLoading()
            wv.settings.javaScriptEnabled = false
            wv.loadUrl("about:blank")
            wv.settings.javaScriptEnabled = true
        }
        if (headlessWebView === wv) {
            headlessWebView = null
        }
        if (activeWebViewRef?.get() === wv) {
            activeWebViewRef = null
        }
        runCatching {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    private suspend fun evalRawOn(wv: WebView, code: String, timeoutMs: Long = 10_000): String = withContext(Dispatchers.Main) {
        if (wv.url.isNullOrBlank()) {
            wv.loadUrl("about:blank")
        }
        val deferred = CompletableDeferred<String>()
        wv.evaluateJavascript(code) { result ->
            deferred.complete(result ?: "null")
        }
        val res = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        if (res == null) {
            unwedgeWebView(wv)
            throw IllegalStateException("JavaScript execution timed out after ${timeoutMs}ms (infinite loop or hang). Render process terminated.")
        }
        res
    }

    private suspend fun evalRaw(code: String): String = withContext(Dispatchers.Main) {
        evalRawOn(getOrCreateWebView(), code)
    }

    /**
     * Captures a screenshot of the current page as a JPEG, saves it into the workspace
     * under `.harness/screenshots/{timestamp}.jpg`, and copies it to ImageStore for chat viewing.
     */
    suspend fun screenshot(workspace: WorkspaceFs? = currentWorkspace): BrowserScreenshotResult? = withContext(Dispatchers.Main) {
        val wv = getOrCreateWebView()
        runCatching {
            val width = wv.width.takeIf { it > 0 } ?: 1080
            val height = wv.height.takeIf { it > 0 } ?: 1920
            if (wv.width <= 0 || wv.height <= 0) {
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                wv.layout(0, 0, width, height)
            }

            // Query DOM scroll offset and DPR directly on the captured WebView instance
            val (domScrollX, domScrollY, dpr) = runCatching {
                val raw = evalRawOn(
                    wv,
                    "(function(){ try { return JSON.stringify({ " +
                        "x: Number(window.scrollX || window.pageXOffset || 0), " +
                        "y: Number(window.scrollY || window.pageYOffset || 0), " +
                        "dpr: Number(window.devicePixelRatio || 1) " +
                        "}); } catch(e) { return ''; } })()"
                )
                val decoded = decodeJsJson(raw) ?: raw
                val obj = jsJson.parseToJsonElement(decoded).jsonObject
                Triple(
                    obj["x"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() } ?: 0.0,
                    obj["y"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() } ?: 0.0,
                    obj["dpr"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }?.takeIf { it > 0.0 }
                        ?: appContext.resources.displayMetrics.density.toDouble(),
                )
            }.getOrDefault(
                Triple(0.0, 0.0, appContext.resources.displayMetrics.density.toDouble())
            )

            val (scrollXPx, scrollYPx) = computeScreenshotScrollPixels(domScrollX, domScrollY, dpr)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val prevLayer = wv.layerType
            try {
                // Force synchronous software rasterization of current DOM state
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                val saveCount = canvas.save()
                canvas.translate(-scrollXPx.toFloat(), -scrollYPx.toFloat())
                wv.draw(canvas)
                canvas.restoreToCount(saveCount)
            } finally {
                wv.setLayerType(prevLayer, null)
            }

            val jpegBytes = ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                baos.toByteArray()
            }
            bitmap.recycle()

            val filename = formatScreenshotFilename()
            val workspaceRelPath = "$SCREENSHOTS_DIR/$filename"

            // 1. Save directly to active workspace under .harness/screenshots/{timestamp}.jpg
            val targetWs = workspace ?: currentWorkspace
            if (targetWs != null) {
                val node = targetWs.resolve(workspaceRelPath)
                node.writeBytes(jpegBytes)
            }

            // 2. Cache copy into ImageStore dir for chat transcript rendering and vision inspection
            val cachedFile = File(imageStore.imagesDir, filename)
            withContext(Dispatchers.IO) {
                cachedFile.writeBytes(jpegBytes)
            }

            track("screenshot", filename)
            BrowserScreenshotResult(
                filename = filename,
                relPath = workspaceRelPath,
                cachedFile = cachedFile,
                sizeBytes = jpegBytes.size.toLong(),
                mime = "image/jpeg",
            )
        }.onFailure { track("screenshot", "failed: ${it.message.orEmpty().take(60)}", ok = false) }.getOrNull()
    }

    /**
     * Retrieves recent console logs. Filter by level and/or a substring of the
     * page URL or script source.
     */
    fun getLogs(levelFilter: String? = null, sourceFilter: String? = null, clear: Boolean = false): List<BrowserConsoleLog> = synchronized(logLock) {
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
            if (levelFilter.isNullOrBlank() && sourceFilter.isNullOrBlank()) {
                consoleLogs.clear()
            } else {
                consoleLogs.removeAll(list.toSet())
            }
        }
        list
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

        /** Marker returned by eval when the result is a promise being awaited. */
        const val PROMISE_SENTINEL = "__harness_promise__"

        /** How often an action re-checks whether the page has committed/finished. */
        private const val POLL_MS = 100L

        /**
         * Cap on the in-page probe. It is a JS round trip, so a page that
         * answers slowly must not make the poll loop wait on it indefinitely:
         * a missing probe just means the client callbacks decide instead.
         */
        private const val PROBE_TIMEOUT_MS = 3_000L

        /** How long a browser_navigate waits for its document to finish loading. */
        private const val NAVIGATE_TIMEOUT_MS = 20_000L

        /** Redirect-bounce retries in back()/forward() before giving up. */
        private const val MAX_HISTORY_STEPS = 5

        /** The in-page view of a document: where it is, and whether it is done. */
        private val DOCUMENT_PROBE_SCRIPT =
            "(function(){ try { return JSON.stringify({ " +
                "href: String(window.location.href || ''), " +
                "ready: document.readyState === 'complete' }); } catch (e) { return ''; } })()"

        fun computeScreenshotScrollPixels(
            domScrollX: Double,
            domScrollY: Double,
            dpr: Double,
        ): Pair<Int, Int> {
            val safeDpr = if (dpr.isFinite() && dpr > 0.0) dpr else 1.0
            val safeX = if (domScrollX.isFinite()) domScrollX else 0.0
            val safeY = if (domScrollY.isFinite()) domScrollY else 0.0
            return Pair((safeX * safeDpr).roundToInt(), (safeY * safeDpr).roundToInt())
        }

        /**
         * Picks the target for an element action when both an id and a selector
         * were supplied: the SELECTOR wins.
         *
         * An id only means anything for the page state it was indexed from, and
         * ids are reassigned on every DOM read. Passing a stale id together
         * with a selector used to be decided silently in favour of the id,
         * which turned a correct selector call into "Element with id 0 not
         * found" with no way to tell the selector had been ignored (on-device
         * QA, 2026-09-17). A selector is re-resolved in the page, so it is the
         * one to trust; the action result says the id was ignored. Null means
         * neither was supplied and the caller must reject the call.
         */
        fun resolveElementTarget(elementId: Int?, selector: String?): ElementTarget? {
            val sel = selector?.takeIf { it.isNotBlank() }
            return when {
                sel != null -> ElementTarget(elementId = null, selector = sel)
                elementId != null -> ElementTarget(elementId = elementId, selector = null)
                else -> null
            }
        }

        /**
         * Page script for one click. Pure, so the action contract (which
         * lookup is used, and how a disabled control is reported) is testable
         * without a WebView.
         */
        fun buildClickJs(target: ElementTarget): String = """
            (function() {
                ${elementLookupJs(target)}
                el.scrollIntoView({ behavior: 'instant', block: 'center' });
                el.focus();
                el.click();
                return { ok: true$DISABLED_WARNING_JS };
            })();
        """.trimIndent()

        /**
         * Page script that types [text] into the target. Pure for the same
         * reason as [buildClickJs].
         */
        fun buildTypeJs(target: ElementTarget, text: String, clearFirst: Boolean): String {
            val encodedText = jsJson.encodeToString(text)
            // JSON-encoded so a selector with quotes/backslashes cannot break
            // the generated script.
            val notTextField = jsJson.encodeToString("${target.errorSubject} is a <")
            return """
                (function() {
                    ${elementLookupJs(target)}
                    const tag = (el.tagName || '').toLowerCase();
                    if (tag !== 'input' && tag !== 'textarea' && tag !== 'select' && el.isContentEditable !== true) {
                        return { ok: false, error: $notTextField + tag + ", not a text field." };
                    }
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    ${if (clearFirst) "el.value = '';" else ""}
                    el.value = (el.value || '') + $encodedText;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return { ok: true$DISABLED_WARNING_JS };
                })();
            """.trimIndent()
        }

        /** Lookup prologue: by id only when no selector was supplied. */
        private fun elementLookupJs(target: ElementTarget): String {
            val selector = target.selector
            if (selector != null) {
                val missing = jsJson.encodeToString(
                    "No element matches selector '$selector'. Re-run browser_get_dom for fresh ids.",
                )
                return "const el = document.querySelector(${jsJson.encodeToString(selector)});\n" +
                    "if (!el) return { ok: false, error: $missing };"
            }
            val missing = jsJson.encodeToString(
                "Element with id ${target.elementId} not found. The page re-rendered; " +
                    "re-run browser_get_dom for fresh ids.",
            )
            return "const el = document.querySelector('[data-harness-id=\"${target.elementId}\"]');\n" +
                "if (!el) return { ok: false, error: $missing };"
        }

        /**
         * Page-script fragment appended to an action's success envelope. A
         * disabled control swallows the interaction by design, so the action
         * used to come back as an ordinary-looking success with nothing
         * changed and the agent retried blind (on-device QA, 2026-09-17). The
         * action is still dispatched normally; only the reporting changes.
         */
        private const val DISABLED_WARNING_JS =
            ", warning: (el.disabled === true || el.getAttribute('aria-disabled') === 'true')" +
                " ? 'element is disabled, so this action most likely did nothing' : null"

        /**
         * Builds the stable URL for a workspace-relative HTML file. Used by
         * both the agent's navigate() and the preview sheet's own load path so
         * both share one origin and one, clean history stack.
         */
        fun localFileUrl(rel: String): String {
            val served = rel.split('/').joinToString("/") { seg ->
                java.net.URLEncoder.encode(seg, "UTF-8")
            }
            return "https://${WorkspacePathHandler.HOST}${WorkspacePathHandler.PATH_PREFIX}$served"
        }

        fun splitLocalTarget(target: String): Pair<String, String> {
            val trimmed = target.trim()
            val queryIdx = trimmed.indexOf('?').takeIf { it >= 0 } ?: trimmed.length
            val hashIdx = trimmed.indexOf('#').takeIf { it >= 0 } ?: trimmed.length
            val splitIdx = minOf(queryIdx, hashIdx)
            val pathPart = trimmed.substring(0, splitIdx)
            val suffix = trimmed.substring(splitIdx)
            return Pair(pathPart, suffix)
        }

        /**
         * Maps a browser_navigate target to a workspace-relative HTML path.
         * Accepts plain names ("index.html"), "./"-relative forms, file:// URIs
         * under the workspace root, and absolute paths under the workspace root
         * (real-directory workspaces only). Returns null for anything it must
         * not rewrite: external URLs, paths outside the workspace, traversal,
         * and non-HTML files. Pure for tests.
         */
        fun normalizeWorkspacePath(target: String, rootPath: String?): String? {
            val (pathOnly, _) = splitLocalTarget(target)
            var rel = pathOnly.removePrefix("file://")
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
         * Builds the sandboxed eval script for [code]. evaluateJavascript does
         * not await promises, so when the code yields a thenable, its result
         * is staged into window.__harnessAsync ([PROMISE_SENTINEL] signals the
         * Kotlin side to poll) and settled errors still come back as
         * {ok:false, error}. The sync path is unchanged:
         *
         * 1. `eval(code)` first: completion value, so bare trailing
         *    expressions like `2+2` work and side effects run once.
         * 2. On SyntaxError (e.g. an explicit `return` statement, which eval
         *    rejects), retry via `new Function(code)`, which allows `return`.
         * 3. Anything else that throws, including genuine syntax errors (which
         *    fail BOTH passes), comes back as {ok:false, error: message}.
         */
        fun buildEvalJs(code: String): String {
            val literal = jsJson.encodeToString(code)
            return """
                (function() {
                    $STAGE_PROMISE_FN
                    function __stageOf(v) {
                        const staged = __harnessStage(v);
                        return staged === null ? null : JSON.stringify({ ok: true, value: "$PROMISE_SENTINEL" });
                    }
                    try {
                        let __v = eval($literal);
                        const __s = __stageOf(__v);
                        if (__s !== null) return __s;
                        return JSON.stringify({ ok: true, value: __v === undefined ? null : __v });
                    } catch (e) {
                        if (e instanceof SyntaxError) {
                            try {
                                const __AsyncFunction = (function() {
                                    try { return Object.getPrototypeOf(async function(){}).constructor; } catch(_) { return null; }
                                })() || Function;
                                const __f = (typeof __AsyncFunction === 'function' && __AsyncFunction !== Function)
                                    ? new __AsyncFunction($literal)
                                    : new Function($literal);
                                const __r = __f.call(window);
                                const __s = __stageOf(__r);
                                if (__s !== null) return __s;
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

        /**
         * Page-side helper: when [v] is a thenable, park its settlement in
         * window.__harnessAsync and return the envelope string; otherwise
         * null. evaluateJavascript never awaits promises on its own.
         *
         * `__harnessAsyncActive` marks the parking as belonging to THIS
         * document, so the poll can tell "the promise has not settled yet" from
         * "the document that held it is gone".
         */
        private val STAGE_PROMISE_FN = """
            function __harnessStage(v) {
                if (v !== undefined && v !== null && typeof v.then === 'function') {
                    window.__harnessAsync = null;
                    window.__harnessAsyncActive = true;
                    Promise.resolve(v).then(
                        function(pv) {
                            try {
                                window.__harnessAsync = JSON.stringify({ ok: true, value: pv === undefined ? null : pv });
                            } catch (e) {
                                window.__harnessAsync = JSON.stringify({ ok: false, error: String(e && e.message || e) });
                            }
                            window.__harnessAsyncActive = false;
                        },
                        function(pe) {
                            window.__harnessAsync = JSON.stringify({ ok: false, error: String(pe && pe.message || pe) });
                            window.__harnessAsyncActive = false;
                        }
                    );
                    return { ok: true, value: "$PROMISE_SENTINEL" };
                }
                return null;
            }
        """.trimIndent()

        /**
         * Poll script for [BrowserController.awaitStagedPromise]: the settled
         * value once it exists, a still-pending marker while the staging is
         * live in this document, and a clean failure when the document that
         * started the promise is no longer the one running (README: a
         * navigation away is not a pending promise).
         */
        internal val STAGED_PROMISE_PROBE_SCRIPT = """
            (function(){
                try {
                    if (typeof window.__harnessAsync === 'string') return String(window.__harnessAsync);
                    if (window.__harnessAsyncActive !== true) {
                        return JSON.stringify({ ok: false, error: "the page navigated away before the promise settled" });
                    }
                    return JSON.stringify({ ok: true, value: "$PROMISE_SENTINEL" });
                } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })();
        """.trimIndent()

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
         * Extracts the warning from a {ok:true,warning} action envelope, or
         * null when the action had nothing to report. Separate from
         * [parseActionError] because the action DID happen: it just most
         * likely had no effect.
         */
        fun parseActionWarning(raw: String): String? {
            val decoded = decodeJsJson(raw) ?: return null
            return runCatching {
                val obj = jsJson.parseToJsonElement(decoded).jsonObject
                val ok = obj["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                if (!ok) null
                else obj["warning"]?.let { (it as? JsonPrimitive)?.contentOrNull?.takeIf { w -> w.isNotBlank() } }
            }.getOrNull()
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

        const val SCREENSHOTS_DIR = ".harness/screenshots"

        /** Generates a screenshot file name formatted with the current timestamp. */
        fun formatScreenshotFilename(timestampMillis: Long = System.currentTimeMillis()): String {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            return "${sdf.format(Date(timestampMillis))}.jpg"
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
