package com.androidharness.app.tools

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import okhttp3.Callback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ResearchHttpFailure(val status: Int, val retryAfterMs: Long, val host: String, keysTried: Int = 1) : IOException(
    buildString {
        if (keysTried > 1) append("All ").append(keysTried).append(" saved API keys were refused; the last one returned ")
        append("HTTP ").append(status).append(" from ").append(host).append(". ")
        when (status) {
            401 -> append("Authentication failed. Check the selected search API key or sign-in; do not retry unchanged credentials. ")
            402, 432, 433 -> append("API credit or plan limit reached. Check the provider dashboard; repeating the request will not restore credits. ")
            403 -> append("Access denied. This site may require sign-in or refuse automated access. Use an accessible source instead of repeatedly retrying. ")
            429 -> append("Too many requests. ")
            else -> append("The page is unavailable. Try another source or return later. ")
        }
        if (retryAfterMs > 0) append("Site cooldown: retry after ").append((retryAfterMs + 999) / 1000)
            .append(" seconds. Read other independent sources meanwhile; do not immediately repeat requests to this site.")
    },
)

/** Shared by all chats, search engines, page readers and the rendered-page fallback. */
internal class ResearchRequestPolicy(
    private val spacingMs: Long = 1000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private class Host {
        val mutex = Mutex()
        var nextAt = 0L
        @Volatile var blockedUntil = 0L
        @Volatile var status = 429
    }
    private val hosts = ConcurrentHashMap<String, Host>()
    private fun site(url: HttpUrl): String = url.topPrivateDomain() ?: url.host
    private fun host(url: HttpUrl, identity: String = ""): Host = hosts.getOrPut(site(url) + identity) { Host() }

    suspend fun <T> access(url: HttpUrl, identity: String = "", block: suspend () -> T): T {
        val host = host(url, identity)
        return host.mutex.withLock {
            currentCoroutineContext().ensureActive()
            // An anonymous site's 429 applies to browser fallback too. A plain
            // HTTP 403 must still allow a genuinely signed-in browser session.
            val common = this.host(url)
            if (identity.isNotEmpty() && common.status == 429 && common.blockedUntil > clock())
                throw ResearchHttpFailure(429, common.blockedUntil - clock(), url.host)
            val remaining = host.blockedUntil - clock()
            if (remaining > 0) throw ResearchHttpFailure(host.status, remaining, url.host)
            val wait = (host.nextAt - clock()).coerceAtLeast(0)
            if (wait > 0) pause(wait)
            currentCoroutineContext().ensureActive()
            host.nextAt = clock() + spacingMs
            block()
        }
    }

    fun cooldown(url: HttpUrl, status: Int, retryAfter: String? = null, identity: String = ""): Long {
        val default = when (status) { 401, 402, 432, 433 -> 300000L; 429 -> 30000L; 403 -> 60000L; else -> 1000L }
        val millis = retryAfterMillis(retryAfter, wallClock()) ?: default
        val host = host(url, identity)
        host.status = status
        host.blockedUntil = maxOf(host.blockedUntil, clock() + millis)
        return millis
    }

    companion object {
        fun retryAfterMillis(value: String?, now: Long): Long? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            text.toLongOrNull()?.let { if (it >= 0) return it.coerceAtMost(7 * 86400L) * 1000 }
            return runCatching {
                (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
                    .coerceIn(0, 7 * 86400_000L)
            }.getOrNull()
        }
    }
}

internal object ResearchHttp {
    val policy = ResearchRequestPolicy()
    private val connections = Semaphore(6)
    // Bounded single-flight locks stop duplicate parallel reads from racing the page cache.
    private val pageLocks = Array(64) { Mutex() }
    suspend fun <T> page(url: String, block: suspend () -> T): T =
        pageLocks[(canonicalResultUrl(url).hashCode() and Int.MAX_VALUE) % pageLocks.size].withLock { block() }

    fun credentialIdentity(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun bodyText(response: Response, maxBytes: Int = 8 * 1024 * 1024): String {
        val body = response.body ?: return ""
        val bytes = body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw ToolFailure("Research response exceeds the supported size limit.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return bytes.toString(body.contentType()?.charset() ?: Charsets.UTF_8)
    }

    suspend fun <T> read(client: OkHttpClient, request: Request, consume: (Response) -> T): T = withContext(Dispatchers.IO) {
        // Key replacement must not reuse another key's cooldown. No raw key enters logs or caches.
        val credential = request.header("Authorization") ?: request.header("X-Subscription-Token")
        val identity = credential?.let(::credentialIdentity).orEmpty()
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            try {
                return@withContext policy.access(request.url, identity) {
                    connections.withPermit {
                        send(client, request) { response ->
                            val code = response.code
                            if (!response.isSuccessful) {
                                val wait = if (code in setOf(401, 402, 403, 429, 432, 433, 502, 503, 504))
                                    policy.cooldown(request.url, code, response.header("Retry-After"), identity) else 0L
                                throw ResearchHttpFailure(code, wait, request.url.host)
                            }
                            consume(response)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ResearchHttpFailure) {
                // Never sleep through a long server cooldown or retry access/auth failures.
                if (attempt == 1 || e.status !in setOf(429, 502, 503, 504) || e.retryAfterMs > 2000) throw e
                delay(e.retryAfterMs.coerceAtLeast(200))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt == 1) throw e
                delay(500)
            }
        }
        error("Research request did not finish")
    }

    private suspend fun <T> send(client: OkHttpClient, request: Request, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val value = consume(response)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
}

/** Reject interstitials without misclassifying real articles discussing errors/rate limits. */
internal object ReadableWebPage {
    fun cooldownStatus(text: String, title: String = ""): Int? {
        val clean = text.trim()
        val heading = title.trim().lowercase()
        val lead = clean.take(600).lowercase()
        val blockedTitle = heading in setOf("access denied", "403 forbidden", "too many requests", "just a moment...", "attention required! | cloudflare", "log in - reddit", "sign in - reddit")
        val blockedLead = Regex("^(?:403 forbidden|429 too many requests|access denied|you(?:’|')ve been blocked by network security|whoa there, pardner|verify (?:you are|you're|that you are) human|checking your browser|just a moment\\.{0,3})(?:\\b|\\s|$)").containsMatchIn(lead)
        val redditWall = clean.length < 5000 && lead.contains("blocked by network security") &&
            (lead.contains("log in") || lead.contains("developer token"))
        if (blockedTitle || (clean.length < 4000 && blockedLead) || redditWall)
            return if (lead.contains("too many requests") || heading.contains("too many requests")) 429 else 403
        if (clean.length < 1200 && Regex("(?i)^(?:please )?(?:log in|sign in) to (?:continue|view this|read this)").containsMatchIn(clean))
            return 403
        return null
    }

    fun problem(text: String, title: String = ""): String? {
        val clean = text.trim()
        if (clean.lowercase().replace(Regex("\\s+"), " ").trim() in setOf("loading", "loading...", "loading…", "please wait", "please wait...", "please wait…", "something went wrong", "something went wrong.", "this page is not available", "page not found", "404 not found"))
            return "The page returned only a loading or error placeholder, not readable content. Try browser_read or another source."
        if (clean.isEmpty()) return "No readable page content was returned. Try browser_read for a JavaScript-rendered page or choose another source."
        if (cooldownStatus(clean, title) != null)
            return "The site returned an access-check or rate-limit page, not the requested article. Use an authorized browser session, Tavily extraction when configured, or another accessible source."
        if (clean.length < 1200 && Regex("(?i)^(?:please |you (?:must|need to) )?enable javascript").containsMatchIn(clean))
            return "This page requires JavaScript; no article content was available. Use browser_read or another accessible source."
        return null
    }

    fun legacyRead(text: String): Boolean = text.isNotBlank() &&
        !text.trimStart().startsWith("[binary content:") &&
        !text.trimStart().startsWith("[PDF with no extractable text:") &&
        !text.trimStart().startsWith("[Page read limit reached:") &&
        !text.contains("[no readable text]") && !text.contains("[no visible text;") &&
        !text.contains("[note: this page appears to require JavaScript;") && problem(text) == null
}
