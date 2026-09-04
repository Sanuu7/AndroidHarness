package com.androidharness.app.data.github

import android.net.Uri
import com.androidharness.app.BuildConfig
import com.androidharness.app.data.KeyStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

class GitHubOAuthManager(
    private val keys: KeyStoreManager,
    private val syncCredentials: suspend () -> Unit,
) {
    data class UiState(val login: String? = null, val connected: Boolean = false,
        val waiting: Boolean = false, val busy: Boolean = false, val message: String? = null)
    @Serializable private data class Pending(val state: String, val verifier: String, val createdAt: Long)
    @Serializable private data class Refresh(val token: String, val expiresAt: Long)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val mutableState = MutableStateFlow(UiState(keys.githubLogin(), keys.githubToken() != null,
        waiting = pending()?.let { System.currentTimeMillis() - it.createdAt in 0 until 600_000 } == true))
    val state = mutableState.asStateFlow()
    val redirectUri = "${BuildConfig.APPLICATION_ID}.oauth://github/callback"
    val configured: Boolean get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank() &&
        BuildConfig.GITHUB_AUTH_BACKEND.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        } == true

    init {
        scope.launch {
            while (isActive) {
                mutex.withLock {
                    pending()?.let {
                        if (System.currentTimeMillis() - it.createdAt !in 0 until 600_000) {
                            keys.removeKey(PENDING)
                            mutableState.value = mutableState.value.copy(waiting = false, message = "Sign-in expired. Please try again.")
                        }
                    }
                    val refresh = keys.getKey(REFRESH)?.let { runCatching { json.decodeFromString<Refresh>(it) }.getOrNull() }
                    if (configured && refresh != null && refresh.expiresAt <= System.currentTimeMillis() + 300_000) {
                        mutableState.value = mutableState.value.copy(busy = true)
                        try {
                            val response = post("refresh", buildJsonObject { put("refresh_token", refresh.token) })
                            persist(response, renewing = true)
                            syncSafely()
                        } catch (_: Exception) {
                            // A rotated pair may already be saved even if identity lookup failed.
                            syncSafely()
                            mutableState.value = mutableState.value.copy(busy = false, message = "Could not renew GitHub access. Check your connection or sign in again.")
                        }
                    }
                }
                delay(60_000)
            }
        }
    }

    /** Persist before opening the browser so process death does not lose PKCE/state. */
    fun begin(scopes: Set<String>): String {
        check(configured) { "GitHub login is not configured in this build." }
        check(mutex.tryLock()) { "GitHub is busy. Please try again." }
        try {
            val pending = Pending(GitHubOAuthProtocol.randomSecret(), GitHubOAuthProtocol.randomSecret(), System.currentTimeMillis())
            keys.putKey(PENDING, json.encodeToString(pending))
            mutableState.value = mutableState.value.copy(waiting = true, message = "Finish signing in in your browser.")
            return GitHubOAuthProtocol.authorizeUrl(BuildConfig.GITHUB_CLIENT_ID, redirectUri, pending.state, pending.verifier, scopes)
        } finally {
            mutex.unlock()
        }
    }

    fun cancel() {
        keys.removeKey(PENDING)
        mutableState.value = mutableState.value.copy(waiting = false, message = null)
    }

    fun browserFailed() {
        cancel()
        mutableState.value = mutableState.value.copy(message = "Could not open a browser. Install or enable one and try again.")
    }

    fun complete(uri: Uri) {
        if (uri.scheme != "${BuildConfig.APPLICATION_ID}.oauth" || uri.host != "github" || uri.path != "/callback") return
        scope.launch {
            mutex.withLock {
                val pending = pending() ?: return@withLock
                if (!GitHubOAuthProtocol.validState(pending.state, uri.getQueryParameter("state"), pending.createdAt, System.currentTimeMillis())) {
                    mutableState.value = mutableState.value.copy(message = "This sign-in link is invalid or expired. Please try again.")
                    return@withLock
                }
                keys.removeKey(PENDING) // Consume once, including denied requests.
                mutableState.value = mutableState.value.copy(waiting = false, busy = true, message = "Connecting to GitHub…")
                try {
                    check(uri.getQueryParameter("error") == null) { "GitHub sign-in was cancelled. You can try again." }
                    val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
                        ?: error("GitHub did not return a sign-in code. Please try again.")
                    val response = post("exchange", buildJsonObject {
                        put("code", code); put("code_verifier", pending.verifier); put("redirect_uri", redirectUri)
                    })
                    persist(response)
                    syncSafely()
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(busy = false,
                        message = if (uri.getQueryParameter("error") != null) "GitHub sign-in was cancelled."
                            else "Could not finish GitHub sign-in. Check your connection and try again.")
                }
            }
        }
    }

    fun logout() {
        mutableState.value = mutableState.value.copy(busy = true)
        scope.launch {
            mutex.withLock {
                keys.removeKey(PENDING)
                keys.removeGitHubToken(); keys.removeGitHubLogin(); keys.removeKey(REFRESH)
                mutableState.value = UiState(busy = true)
                syncSafely()
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private fun pending(): Pending? = keys.getKey(PENDING)?.let {
        runCatching { json.decodeFromString<Pending>(it) }.getOrNull()
    }

    private fun post(path: String, body: JsonObject): JsonObject {
        check(configured)
        val request = Request.Builder().url(BuildConfig.GITHUB_AUTH_BACKEND.trimEnd('/') + "/" + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use {
            check(it.isSuccessful) { "GitHub login service unavailable" }
            json.parseToJsonElement(it.body!!.string()).jsonObject
        }
    }

    private fun persist(response: JsonObject, renewing: Boolean = false) {
        val token = response["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("Missing token")
        val refresh = response["refresh_token"]?.jsonPrimitive?.content
        val seconds = response["expires_in"]?.jsonPrimitive?.longOrNull
        val refreshJson = if (refresh != null && seconds != null)
            json.encodeToString(Refresh(refresh, System.currentTimeMillis() + seconds * 1000)) else null
        // Refresh invalidates the old pair immediately. Save the replacement before
        // any further network request, which may fail while the device is offline.
        if (renewing) keys.putGitHubOAuth(token, keys.githubLogin().orEmpty(), refreshJson)
        // Validate identity independently before replacing the existing account.
        val request = Request.Builder().url("https://api.github.com/user")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
        val login = client.newCall(request).execute().use {
            check(it.isSuccessful)
            json.parseToJsonElement(it.body!!.string()).jsonObject["login"]!!.jsonPrimitive.content
        }
        keys.putGitHubOAuth(token, login, refreshJson)
        mutableState.value = mutableState.value.copy(login = login, connected = true, busy = true, message = "Signed in as $login")
    }

    private suspend fun syncSafely() {
        try {
            syncCredentials()
            mutableState.value = mutableState.value.copy(busy = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(busy = false,
                message = "Account saved, but terminal credentials could not be updated. Restart the app to retry.")
        }
    }

    private companion object {
        const val PENDING = "github_oauth_pending"
        const val REFRESH = "github_oauth_refresh"
    }
}
