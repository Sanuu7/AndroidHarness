package com.androidharness.app.chatgpt

import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.agent.ThinkingLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatGptAccount(val providerId: String, val label: String, val connected: Boolean, val models: List<ModelEntry>,
    val useForAutoSwitch: Boolean = true, val fallbackModel: String? = null, val usageLimited: Boolean = false) {
    fun config() = ProviderConfig(providerId, "ChatGPT · $label", ProviderType.OPENAI_RESPONSES, ChatGptProtocol.RESOURCE, models.first().id)
}

data class ChatGptAccountState(
    val accounts: List<ChatGptAccount> = emptyList(),
    val signingIn: Boolean = false,
    val error: String? = null,
    val showWelcome: Boolean = false,
    val checkingModelsFor: String? = null,
    val modelCheckResults: Map<String, String> = emptyMap(),
    val autoSwitch: Boolean = false,
)

/** The app owns OAuth credentials; providers only ask for a current access token. */
class ChatGptAccounts(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val onConnected: suspend (ProviderConfig) -> Unit,
    private val onDisconnected: suspend (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build(),
    private val browserDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val refreshOnStart: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val discoveryLock = Mutex()
    private var store = read()?.let { runCatching { json.decodeFromString<ChatGptStore>(it) }.getOrNull() }
        ?: ChatGptStore("urn:uuid:${UUID.randomUUID()}").also { write(json.encodeToString(it)) }
    private val mutableState = MutableStateFlow(ChatGptAccountState())
    val state = mutableState.asStateFlow()
    private var signInJob: Job? = null
    private var listener: ChatGptLoopback? = null

    init {
        publish()
        if (refreshOnStart) store.accounts.filter { it.connected }.forEach { refresh(ChatGptProtocol.providerId(it.clientId)) }
    }

    private fun publish(error: String? = mutableState.value.error) {
        mutableState.update { it.copy(accounts = store.accounts.mapIndexed { index, entry ->
            ChatGptAccount(ChatGptProtocol.providerId(entry.clientId), "${entry.email ?: "Account"} · ${index + 1}", entry.connected, entry.models,
                entry.useForAutoSwitch, entry.fallbackModel, entry.usageLimitedAt != null)
        }, autoSwitch = store.autoSwitch, error = error) }
    }

    private fun save(updated: ChatGptStore) {
        write(json.encodeToString(updated)) // Commit all rotating credentials together before publishing them.
        store = updated
        publish()
    }

    private fun registration(providerId: String): ChatGptRegistration = store.accounts.firstOrNull {
        ChatGptProtocol.providerId(it.clientId) == providerId
    } ?: error("Connect ChatGPT in Settings > Connected accounts.")

    private fun replace(account: ChatGptRegistration) = save(store.copy(accounts =
        if (store.accounts.any { it.clientId == account.clientId }) store.accounts.map { if (it.clientId == account.clientId) account else it }
        else store.accounts + account))

    fun startSignIn(providerId: String? = null, openBrowser: (String) -> Unit) {
        if (mutableState.value.signingIn) return
        mutableState.value = mutableState.value.copy(signingIn = true, error = null)
        signInJob = scope.launch {
            try {
                withTimeout(300_000) {
                    val selected = lock.withLock { providerId?.let(::registration) }
                    val state = ChatGptProtocol.random()
                    val nonce = ChatGptProtocol.random()
                    val verifier = ChatGptProtocol.random()
                    ChatGptLoopback().use { receiver ->
                        listener = receiver
                        val hostId = lock.withLock { store.hostId }
                        val url = ChatGptProtocol.authorizeUrl(receiver.redirectUri, state, nonce, verifier, hostId, selected)
                        withContext(browserDispatcher) { openBrowser(url) }
                        val (code, clientId) = receiver.receive(state, selected?.clientId)
                        lock.withLock {
                            // Keep the issued registration even if the code expires before exchange.
                            if (store.accounts.none { it.clientId == clientId }) replace(ChatGptRegistration(clientId))
                            val token = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code")
                                .add("client_id", clientId).add("code", code).add("code_verifier", verifier)
                                .add("redirect_uri", receiver.redirectUri).add("resource", ChatGptProtocol.RESOURCE).build())
                            val account = credentials(registration(ChatGptProtocol.providerId(clientId)), token, nonce)
                            currentCoroutineContext().ensureActive()
                            replace(account)
                            check(account.connected) { "Signed in, but ChatGPT plan access was not enabled. Reconnect and allow plan usage." }
                        }
                        val id = ChatGptProtocol.providerId(clientId)
                        val models = refreshModels(id)
                        check(models.isNotEmpty()) { "ChatGPT did not offer any models for this account. Check plan access, then refresh models." }
                        val account = stateAccount(id)
                        onConnected(account.config())
                        lock.withLock {
                            if (!store.welcomeSeen) mutableState.value = mutableState.value.copy(showWelcome = true)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                publish("ChatGPT sign-in timed out. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                publish(safeError(e))
            } finally {
                listener = null
                mutableState.value = mutableState.value.copy(signingIn = false)
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        listener?.close()
    }

    suspend fun dismissWelcome() = lock.withLock {
        save(store.copy(welcomeSeen = true))
        mutableState.value = mutableState.value.copy(showWelcome = false)
    }

    fun stateAccount(providerId: String) = state.value.accounts.first { it.providerId == providerId }

    suspend fun setAutoSwitch(enabled: Boolean) = lock.withLock { save(store.copy(autoSwitch = enabled)) }

    suspend fun setFallback(providerId: String, enabled: Boolean, model: String?) = lock.withLock {
        val account = registration(providerId)
        require(model == null || account.models.any { it.id == model }) { "Choose a model offered by this account." }
        replace(account.copy(useForAutoSwitch = enabled, fallbackModel = model))
    }

    suspend fun recordUsageLimit(providerId: String) = lock.withLock {
        replace(registration(providerId).copy(usageLimitedAt = System.currentTimeMillis()))
    }

    suspend fun recordUsageSuccess(providerId: String) = lock.withLock {
        val account = registration(providerId)
        if (account.usageLimitedAt != null) replace(account.copy(usageLimitedAt = null))
    }

    /** Manual selection preserves a supported current model, then uses this account's preference. */
    suspend fun selectAccount(providerId: String, currentModel: String): ProviderConfig {
        val offered = refreshModels(providerId, includeVerified = false)
        return lock.withLock {
            val account = registration(providerId)
            check(account.connected) { "Reconnect ChatGPT to use this account." }
            val model = fallbackModel(account.copy(models = offered), currentModel) ?: error("This account did not offer any models.")
            stateAccount(providerId).config().copy(model = model.id)
        }
    }

    suspend fun nextAccount(current: ProviderConfig, attempted: Set<String>, system: String,
        messages: List<ChatMessage>, tools: List<com.androidharness.app.llm.ToolSchema>): ProviderConfig? {
        val ordered = lock.withLock {
            if (!store.autoSwitch) return null
            val accounts = store.accounts
            val start = accounts.indexOfFirst { ChatGptProtocol.providerId(it.clientId) == current.id }
            (accounts.drop(start + 1) + accounts.take(start + 1)).map { ChatGptProtocol.providerId(it.clientId) }
        }
        val hasImages = messages.any { it.images.isNotEmpty() }
        val estimatedTokens = (system.length.toLong() + messages.sumOf { it.text.length.toLong() +
            it.toolCalls.sumOf { call -> call.argumentsJson.length.toLong() } } + tools.sumOf { it.parametersJson.toString().length.toLong() }) / 4
        for (id in ordered) {
            if (id in attempted) continue
            val candidate = state.value.accounts.firstOrNull { it.providerId == id } ?: continue
            if (!candidate.connected || !candidate.useForAutoSwitch) continue
            try {
                val offered = refreshModels(id, includeVerified = false)
                val config = lock.withLock {
                    val account = registration(id)
                    if (!store.autoSwitch || !account.connected || !account.useForAutoSwitch) return@withLock null
                    val model = fallbackModel(account.copy(models = offered), current.model) { model ->
                        (!hasImages || com.androidharness.app.llm.visionCapable(model.id)) &&
                            (model.contextTokens == null || model.contextTokens > estimatedTokens + 4096)
                    } ?: return@withLock null
                    stateAccount(id).config().copy(model = model.id)
                }
                if (config != null) return config
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* One unavailable account must not prevent trying the next. */ }
        }
        return null
    }

    private fun fallbackModel(account: ChatGptRegistration, currentModel: String,
        compatible: (ModelEntry) -> Boolean = { true }): ModelEntry? {
        val models = account.models.filter(compatible)
        return models.firstOrNull { it.id == currentModel } ?: models.firstOrNull { it.id == account.fallbackModel } ?: models.firstOrNull()
    }

    suspend fun accessToken(providerId: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val account = registration(providerId)
            check(account.connected) { "Reconnect ChatGPT in Settings > Connected accounts." }
            val now = System.currentTimeMillis() / 1000
            if (!forceRefresh && account.expiresAt > now + 60) return@withLock account.accessToken!!
            if (account.earliestRefreshAt > now && account.expiresAt > now) return@withLock account.accessToken!!
            val refresh = account.refreshToken ?: error("Reconnect ChatGPT in Settings > Connected accounts.")
            try {
                val token = tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", account.clientId)
                    .add("refresh_token", refresh).add("resource", ChatGptProtocol.RESOURCE).build())
                val updated = credentials(account, token, null)
                currentCoroutineContext().ensureActive()
                replace(updated)
                check(updated.connected) { "ChatGPT plan access was removed. Reconnect and allow plan usage." }
                updated.accessToken!!
            } catch (e: OAuthException) {
                if (e.code in setOf("invalid_grant", "invalid_client")) replace(account.withoutTokens())
                throw e
            }
        }
    }

    suspend fun refreshModels(providerId: String, includeVerified: Boolean = true): List<ModelEntry> = withContext(Dispatchers.IO) {
        // Check the session again before publishing, so logout cannot resurrect access.
        val token = accessToken(providerId)
        val request = Request.Builder().url(ChatGptProtocol.RESOURCE + "/models").header("Authorization", "Bearer $token").build()
        val models = execute(request).use {
            if (!it.isSuccessful) throw IOException(ChatGptProtocol.requestError(errorCode(it.body?.string()), it.code))
            ChatGptProtocol.models(it.body?.string() ?: error("ChatGPT returned an empty model list."))
        }
        lock.withLock {
            val latest = registration(providerId)
            check(latest.connected && latest.accessToken == token) { "ChatGPT connection changed. Please refresh models again." }
            replace(latest.copy(models = (models + latest.verifiedModels).distinctBy { it.id }))
            publish(null)
        }
        if (includeVerified) stateAccount(providerId).models else models
    }

    /** The account catalog can omit usable models. Only remember successful inference checks. */
    suspend fun discoverModels(providerId: String): List<ModelEntry> = withContext(Dispatchers.IO) {
        discoveryLock.withLock {
            mutableState.value = mutableState.value.copy(checkingModelsFor = providerId, error = null)
            try {
                val before = lock.withLock { registration(providerId).also { check(it.connected) { "Reconnect ChatGPT to check models." } } }
                val catalog = refreshModels(providerId)
                // https://developers.openai.com/api/docs/models (still requires a real access check).
                val candidates = listOf(
                    ModelEntry("gpt-6.1-sol", reasoning = true, displayName = "GPT-6.1 Sol"),
                    ModelEntry("gpt-6-sol", reasoning = true, displayName = "GPT-6 Sol"),
                    ModelEntry("gpt-6-luna", reasoning = true, displayName = "GPT-6 Luna"),
                ).filter { candidate -> catalog.none { it.id == candidate.id } }
                val provider = ChatGptProvider(::accessToken, http)
                var added = 0
                for (candidate in candidates) {
                    val events = withTimeoutOrNull(45_000) {
                        provider.streamChat(ProviderConfig(providerId, "ChatGPT", ProviderType.OPENAI_RESPONSES, ChatGptProtocol.RESOURCE, candidate.id),
                            "managed-oauth", "", listOf(ChatMessage(Role.USER, "Reply exactly OK.")), emptyList(),
                            RequestOptions(thinking = ThinkingLevel.LOW)).toList()
                    }.orEmpty()
                    val succeeded = events.any { it is StreamEvent.Done } && events.none { it is StreamEvent.Failure } &&
                        events.filterIsInstance<StreamEvent.TextDelta>().any { it.text.isNotBlank() }
                    if (succeeded) lock.withLock {
                        val latest = registration(providerId)
                        check(latest.connected && latest.subject == before.subject) { "ChatGPT connection changed. Check models again." }
                        val verified = (latest.verifiedModels + candidate.copy(note = "Verified with ChatGPT plan")).distinctBy { it.id }
                        replace(latest.copy(verifiedModels = verified, models = (latest.models + verified).distinctBy { it.id }))
                        added++
                    }
                }
                val result = when {
                    added > 0 -> "Added $added newer ${if (added == 1) "model" else "models"}."
                    candidates.isEmpty() -> "Your checked models are up to date."
                    else -> "No newer models could be confirmed. Check your connection or try again later."
                }
                mutableState.value = mutableState.value.copy(modelCheckResults = mutableState.value.modelCheckResults + (providerId to result))
                stateAccount(providerId).models
            } finally {
                mutableState.value = mutableState.value.copy(checkingModelsFor = null)
            }
        }
    }

    fun checkNewerModels(providerId: String) { scope.launch {
        try { discoverModels(providerId) } catch (e: CancellationException) { throw e } catch (e: Exception) { publish(safeError(e)) }
    } }

    fun refresh(providerId: String) { scope.launch {
        try { refreshModels(providerId) } catch (e: CancellationException) { throw e } catch (e: Exception) { publish(safeError(e)) }
    } }

    fun signOut(providerId: String) {
        cancelSignIn()
        scope.launch {
            lock.withLock {
                val account = registration(providerId)
                replace(account.withoutTokens())
                onDisconnected(providerId)
                var revoked = account.refreshToken == null
                if (!revoked) {
                    try {
                        val endpoint = discovery()["revocation_endpoint"]?.jsonPrimitive?.content ?: error("Missing revocation endpoint.")
                        trustedAuthUrl(endpoint)
                        val request = Request.Builder().url(endpoint).post(FormBody.Builder()
                            .add("token", account.refreshToken!!).add("token_type_hint", "refresh_token").add("client_id", account.clientId).build()).build()
                        repeat(2) { attempt ->
                            if (!revoked) {
                                revoked = try { execute(request).use { it.code == 200 } }
                                    catch (e: CancellationException) { throw e } catch (_: IOException) { false }
                                if (!revoked && attempt == 0) delay(1000)
                            }
                        }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Local logout must still complete. */ }
                }
                publish(if (revoked) null else "Signed out locally. Remote disconnection could not be confirmed. Disconnect AndroidHarness in ChatGPT settings.")
            }
        }
    }

    private fun ChatGptRegistration.withoutTokens() = copy(accessToken = null, refreshToken = null, idToken = null,
        expiresAt = 0, earliestRefreshAt = 0, scopes = emptySet(), models = emptyList(), verifiedModels = emptyList())

    private suspend fun discovery(): JsonObject = getJson(ChatGptProtocol.DISCOVERY).also {
        require(it["issuer"]?.jsonPrimitive?.content == ChatGptProtocol.ISSUER) { "Unexpected ChatGPT authentication server." }
    }

    private fun trustedAuthUrl(url: String) {
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.host == "auth.openai.com" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Unexpected ChatGPT authentication endpoint." }
    }

    private suspend fun getJson(url: String): JsonObject {
        trustedAuthUrl(url)
        return execute(Request.Builder().url(url).build()).use {
            check(it.isSuccessful) { "Could not verify ChatGPT sign-in. Please try again." }
            json.parseToJsonElement(it.body?.string() ?: error("Empty authentication response.")).jsonObject
        }
    }

    private suspend fun credentials(previous: ChatGptRegistration, token: JsonObject, nonce: String?): ChatGptRegistration {
        val idToken = token["id_token"]?.jsonPrimitive?.contentOrNull
        val identity = if (idToken != null) {
            val jwksUrl = discovery()["jwks_uri"]?.jsonPrimitive?.content ?: error("Missing identity signing keys.")
            ChatGptProtocol.validateIdToken(idToken, getJson(jwksUrl), previous.clientId, nonce, System.currentTimeMillis() / 1000)
        } else {
            check(nonce == null && previous.subject != null && previous.issuer != null) { "ChatGPT did not return a verified identity." }
            ChatGptProtocol.Identity(previous.issuer, previous.subject, previous.email)
        }
        require(previous.subject == null || (previous.subject == identity.subject && previous.issuer == identity.issuer)) { "ChatGPT returned a different account. Add it as a new account." }
        require(token["token_type"]?.jsonPrimitive?.contentOrNull?.equals("Bearer", true) == true) { "Unsupported ChatGPT token type." }
        val access = token["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("ChatGPT did not return access credentials.")
        val refresh = token["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: if (nonce == null) previous.refreshToken else null
        val lifetime = token["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..86_400 } ?: error("Invalid ChatGPT token expiry.")
        val granted = token["scope"]?.jsonPrimitive?.contentOrNull?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet()
            ?: if (nonce == null) previous.scopes else emptySet()
        return previous.copy(issuer = identity.issuer, subject = identity.subject, email = identity.email,
            accessToken = access, refreshToken = refresh, idToken = idToken ?: previous.idToken,
            expiresAt = System.currentTimeMillis() / 1000 + lifetime,
            earliestRefreshAt = token["earliest_refresh_at"]?.jsonPrimitive?.longOrNull ?: 0, scopes = granted)
    }

    private suspend fun tokenRequest(body: FormBody): JsonObject = execute(Request.Builder().url(ChatGptProtocol.TOKEN).post(body).build()).use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw OAuthException(errorCode(text), it.code)
        json.parseToJsonElement(text).jsonObject
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun errorCode(body: String?) = runCatching {
        val root = json.parseToJsonElement(body.orEmpty()).jsonObject
        when (val error = root["error"]) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> error["code"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }.getOrNull()

    private class OAuthException(val code: String?, status: Int) : IOException(
        if (code in setOf("invalid_grant", "invalid_client")) "ChatGPT sign-in expired. Please reconnect your account."
        else "ChatGPT could not finish sign-in (HTTP $status). Please try again.",
    )

    private fun safeError(error: Exception): String = when (error) {
        is IOException -> if (error is OAuthException || error.message?.startsWith("ChatGPT") == true) error.message!! else "Could not reach ChatGPT. Check your connection and try again."
        is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { !it.contains("token", true) } ?: "Could not verify ChatGPT sign-in. Please try again."
        else -> "ChatGPT connection failed. Please try again."
    }
}
