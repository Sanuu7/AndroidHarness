package com.androidharness.app.github

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class GitHubCredential(
    val token: String,
    val login: String,
    val oauth: Boolean = false,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val refreshExpiresAt: Long? = null,
    val scopes: String = "",
    val clientId: String = "",
)

data class GitHubDeviceCode(val deviceCode: String, val userCode: String, val url: String,
    val expiresIn: Long, val interval: Long)
data class GitHubConnectionState(val login: String? = null, val oauth: Boolean = false,
    val busy: Boolean = false, val code: GitHubDeviceCode? = null, val message: String? = null)

/** Direct GitHub requests: the device grant and refresh grant need only the public client ID. */
class GitHubApi(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val authBase: String = "https://github.com",
    private val apiBase: String = "https://api.github.com",
) {
    suspend fun form(path: String, fields: Map<String, String>): JsonObject = request(
        Request.Builder().url("$authBase$path").header("Accept", "application/json")
            .post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()).build(),
    )

    suspend fun get(path: String, token: String?): JsonElement = requestElement(
        Request.Builder().url("$apiBase$path").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").apply {
                if (token != null) header("Authorization", "Bearer $token")
            }.build(),
    )

    private suspend fun request(request: Request): JsonObject = requestElement(request).jsonObject
    private suspend fun requestElement(request: Request): JsonElement = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val parsed = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful) {
                (parsed as? JsonObject)?.string("error")?.let {
                    error(oauthError(it, parsed.string("error_description")))
                }
                val message = (parsed as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: (parsed as? JsonObject)?.get("error_description")?.jsonPrimitive?.contentOrNull
                error("GitHub returned HTTP ${response.code}: ${message ?: "check connection and account access"}")
            }
            parsed ?: error("GitHub returned an unreadable response. Try again.")
        }
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.seconds(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

internal fun tokenCredential(body: JsonObject, login: String, clientId: String, now: Long): GitHubCredential {
    val token = body.string("access_token")?.takeIf { it.isNotBlank() }
        ?: error("GitHub did not return an access token. Sign in again.")
    return GitHubCredential(token, login, oauth = true, refreshToken = body.string("refresh_token"),
        expiresAt = body.seconds("expires_in")?.let { now + it * 1000 },
        refreshExpiresAt = body.seconds("refresh_token_expires_in")?.let { now + it * 1000 },
        scopes = body.string("scope").orEmpty(), clientId = clientId)
}

/** Credentials and the legacy toolchain token are saved atomically by the supplied store. */
class GitHubAuth(
    val clientId: String,
    private val read: () -> GitHubCredential?,
    private val write: (GitHubCredential?) -> Unit,
    private val sync: suspend () -> Unit,
    val api: GitHubApi = GitHubApi(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var signIn: Job? = null
    private var refreshFailedAt: Long? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val _state = MutableStateFlow(read()?.let { GitHubConnectionState(it.login, it.oauth) } ?: GitHubConnectionState())
    val state: StateFlow<GitHubConnectionState> = _state

    fun cancelSignIn() {
        generation.incrementAndGet()
        signIn?.cancel()
        signIn = null
        _state.value = _state.value.copy(busy = false, code = null)
    }

    fun startSignIn(workflow: Boolean = false) {
        cancelSignIn()
        val attempt = generation.get()
        signIn = scope.launch {
                _state.value = _state.value.copy(busy = true, message = "Requesting a sign-in code…")
                try {
                    check(clientId.isNotBlank()) { "This build has no GitHub Client ID. Use a personal access token, or configure GITHUB_CLIENT_ID when building the app." }
                    val body = api.form("/login/device/code", mapOf("client_id" to clientId,
                        "scope" to if (workflow) "repo workflow offline_access" else "repo offline_access"))
                    check(body.string("error") == null) { oauthError(body.string("error"), body.string("error_description")) }
                    val code = GitHubDeviceCode(requireNotNull(body.string("device_code")),
                        requireNotNull(body.string("user_code")), requireNotNull(body.string("verification_uri")),
                        body.seconds("expires_in") ?: 900, (body.seconds("interval") ?: 5).coerceAtLeast(5))
                    check(code.url == "https://github.com/login/device") { "GitHub returned an unexpected sign-in address." }
                    _state.value = _state.value.copy(code = code, message = "Enter this code on GitHub, then approve access.")
                    val deadline = now() + code.expiresIn * 1000
                    var interval = code.interval
                    while (now() < deadline) {
                        pause(interval * 1000)
                        if (now() >= deadline) break
                        val token = api.form("/login/oauth/access_token", mapOf("client_id" to clientId,
                            "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
                        when (token.string("error")) {
                            "authorization_pending" -> Unit
                            "slow_down" -> interval = maxOf(interval + 5, token.seconds("interval") ?: 0)
                            null -> {
                                val login = identity(requireNotNull(token.string("access_token")))
                                currentCoroutineContext().ensureActive()
                                mutex.withLock {
                                    currentCoroutineContext().ensureActive()
                                    check(generation.get() == attempt) { "Sign-in was cancelled." }
                                    save(tokenCredential(token, login, clientId, now()))
                                }
                                return@launch
                            }
                            else -> error(oauthError(token.string("error"), token.string("error_description")))
                        }
                    }
                    error("The sign-in code expired. Request a new code.")
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (generation.get() == attempt) _state.value = _state.value.copy(busy = false, code = null, message = safeMessage(e))
                } finally {
                    if (generation.get() == attempt) _state.value = _state.value.copy(busy = false, code = null)
                }
        }
    }

    internal suspend fun awaitSignIn() { signIn?.join() }

    suspend fun connectPat(token: String) {
        cancelSignIn()
        val attempt = generation.get()
        mutex.withLock {
            val clean = token.trim()
            require(clean.isNotEmpty() && clean.none { it.isWhitespace() }) { "Paste the complete personal access token." }
            val login = identity(clean)
            currentCoroutineContext().ensureActive()
            check(generation.get() == attempt) { "Connection change was cancelled." }
            save(GitHubCredential(clean, login))
        }
    }

    suspend fun disconnect() {
        cancelSignIn()
        mutex.withLock {
            write(null)
            _state.value = GitHubConnectionState(message = "Signed out on this device.")
            sync()
        }
    }

    suspend fun accessToken(): String? = mutex.withLock { accessTokenLocked() }

    /** Refresh only expiring OAuth connections; ordinary shell work remains usable offline. */
    suspend fun refreshIfNeeded() {
        if (read()?.let { it.oauth && it.expiresAt?.let { expiry -> expiry <= now() + 60_000 } == true } == true) {
            try { mutex.withLock {
                if (refreshFailedAt?.let { now() - it < 60_000 } != true) {
                    try { accessTokenLocked(); refreshFailedAt = null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { refreshFailedAt = now(); throw e }
                }
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(message = safeMessage(e)) }
        }
    }

    suspend fun <T> withConnection(action: suspend (String?) -> T): T = mutex.withLock { action(accessTokenLocked()) }

    private suspend fun accessTokenLocked(): String? {
        var saved = read() ?: return null
        if (saved.oauth && saved.expiresAt?.let { it <= now() + 60_000 } == true) {
            val refresh = saved.refreshToken ?: error("GitHub sign-in expired. Sign in again or use a personal access token.")
            check(saved.refreshExpiresAt?.let { it > now() } != false) { "GitHub sign-in expired. Sign in again." }
            val body = api.form("/login/oauth/access_token", mapOf("client_id" to saved.clientId,
                "grant_type" to "refresh_token", "refresh_token" to refresh))
            check(body.string("error") == null) { oauthError(body.string("error"), body.string("error_description")) }
            val login = identity(requireNotNull(body.string("access_token")))
            check(login == saved.login) { "GitHub account changed. Sign in again before publishing." }
            saved = tokenCredential(body, login, saved.clientId, now())
            save(saved)
        }
        return saved.token
    }

    private suspend fun identity(token: String): String = (api.get("/user", token) as? JsonObject)
        ?.string("login")?.takeIf { it.isNotBlank() } ?: error("GitHub could not verify this account.")

    private suspend fun save(credential: GitHubCredential) {
        write(credential)
        _state.value = GitHubConnectionState(credential.login, credential.oauth, message = "Connected. Applying repository access…")
        // A sync failure must stay visible; the saved account can be checked and retried.
        try { sync(); _state.value = _state.value.copy(message = "Connected as ${credential.login}.") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = _state.value.copy(message = "Account saved. Repository access needs another check: ${safeMessage(e)}") }
    }

    fun safeMessage(e: Throwable): String {
        var message = e.message ?: "Could not reach GitHub. Check your connection and try again."
        read()?.let { credential ->
            message = message.replace(credential.token, "[hidden]")
            credential.refreshToken?.let { message = message.replace(it, "[hidden]") }
        }
        return redactGitHubSecrets(message)
    }
}

internal fun oauthError(code: String?, detail: String?): String = when (code) {
    "device_flow_disabled" -> "Enable Device Flow in the GitHub OAuth app settings, then try again. PAT login is also available."
    "access_denied" -> "GitHub sign-in was declined. You can try again."
    "expired_token" -> "The sign-in code expired. Request a new code."
    "incorrect_client_credentials" -> "GitHub rejected this build's Client ID. Check the OAuth app registration."
    else -> "GitHub sign-in failed: ${detail ?: code ?: "try again"}"
}

fun redactGitHubSecrets(text: String): String = text
    .replace(Regex("https://[^/\\s]+@github\\.com/"), "https://github.com/")
    .replace(Regex("(?:gh[pousr]_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+)"), "[hidden]")
