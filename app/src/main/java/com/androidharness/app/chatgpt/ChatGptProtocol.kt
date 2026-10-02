package com.androidharness.app.chatgpt

import com.androidharness.app.llm.ModelEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

object ChatGptProtocol {
    const val PROVIDER_PREFIX = "chatgpt:"
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val USAGE_URL = "https://chatgpt.com/settings/usage"
    const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    const val NAMESPACE = "harness"
    fun isProvider(id: String?) = id?.startsWith(PROVIDER_PREFIX) == true
    fun providerId(clientId: String) = PROVIDER_PREFIX + clientId
    fun random(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun challenge(verifier: String) = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(redirect: String, state: String, nonce: String, verifier: String, host: String, account: ChatGptRegistration?): String {
        val builder = AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", account?.clientId ?: "dynamic_agent_client")
            .addQueryParameter("ext_agent_host_id", host)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge(verifier))
        if (account == null) builder.addQueryParameter("agent_name_hint", "AndroidHarness")
        else {
            account.idToken?.let { builder.addQueryParameter("id_token_hint", it) }
            account.email?.let { builder.addQueryParameter("login_hint", it) }
        }
        return builder.build().toString()
    }

    fun callback(url: String, expectedState: String, returningClient: String?): Pair<String, String> {
        val params = url.toHttpUrl()
        require(params.queryParameterValues("state").size == 1 && MessageDigest.isEqual(
            params.queryParameter("state").orEmpty().toByteArray(), expectedState.toByteArray(),
        )) { "Invalid sign-in response. Please try again." }
        val error = params.queryParameter("error")
        check(error == null) { if (error == "access_denied") "ChatGPT sign-in was cancelled or plan access was declined." else "ChatGPT sign-in failed. Please try again." }
        require(params.queryParameterValues("code").size == 1 && params.queryParameterValues("client_id").size <= 1) { "Invalid sign-in response." }
        val code = params.queryParameter("code")?.takeIf { it.isNotBlank() } ?: error("Sign-in did not return a code.")
        val client = params.queryParameter("client_id") ?: returningClient ?: error("ChatGPT did not finish registering this app.")
        require(client.isNotBlank() && client != "dynamic_agent_client" && (returningClient == null || client == returningClient)) { "Sign-in returned a different account registration." }
        return code to client
    }

    data class Identity(val issuer: String, val subject: String, val email: String?)

    fun validateIdToken(token: String, jwks: JsonObject, clientId: String, nonce: String?, nowSeconds: Long): Identity {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ChatGPT identity token." }
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]), Charsets.UTF_8)).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256") { "Unsupported identity signature." }
        val kid = header["kid"]?.jsonPrimitive?.content ?: error("Missing identity signing key.")
        val key = jwks["keys"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["kid"]?.jsonPrimitive?.content == kid && it["kty"]?.jsonPrimitive?.content == "RSA" &&
                (it["use"] == null || it["use"]?.jsonPrimitive?.content == "sig") &&
                (it["alg"] == null || it["alg"]?.jsonPrimitive?.content == "RS256")
        } ?: error("ChatGPT identity signing key was not found.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(key.getValue("n").jsonPrimitive.content)),
            BigInteger(1, decoder.decode(key.getValue("e").jsonPrimitive.content)),
        ))
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decoder.decode(parts[2]))
        }
        require(verified) { "ChatGPT identity signature did not match." }
        val claims = Json.parseToJsonElement(String(decoder.decode(parts[1]), Charsets.UTF_8)).jsonObject
        require(claims["iss"]?.jsonPrimitive?.content == ISSUER) { "Unexpected ChatGPT identity issuer." }
        val audiences = when (val aud = claims["aud"]) {
            is JsonArray -> aud.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(aud.content)
            else -> emptyList()
        }
        require(clientId in audiences && (audiences.size == 1 || claims["azp"]?.jsonPrimitive?.content == clientId)) { "ChatGPT identity belongs to a different app." }
        require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0) > nowSeconds) { "ChatGPT identity has expired. Please sign in again." }
        require((claims["nbf"]?.jsonPrimitive?.longOrNull ?: 0) <= nowSeconds + 30) { "ChatGPT identity is not valid yet." }
        if (nonce != null) require(claims["nonce"]?.jsonPrimitive?.contentOrNull == nonce) { "ChatGPT sign-in did not match this attempt." }
        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("ChatGPT identity is missing.")
        return Identity(ISSUER, sub, claims["email"]?.jsonPrimitive?.contentOrNull)
    }

    fun models(body: String): List<ModelEntry> = Json.parseToJsonElement(body).jsonObject["models"]?.jsonArray
        ?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj["visibility"]?.jsonPrimitive?.contentOrNull != "list") return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val supported = (obj["supported_reasoning_levels"] ?: obj["supported_reasoning_efforts"]) as? JsonArray
            val efforts = supported?.mapNotNull {
                when (it) {
                    is JsonObject -> (it["effort"] as? JsonPrimitive)?.contentOrNull
                    is JsonPrimitive -> it.contentOrNull
                    else -> null
                }
            }?.distinct()
            ModelEntry(slug, displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull, note = "Using ChatGPT plan",
                reasoning = (obj["supports_reasoning"] as? JsonPrimitive)?.booleanOrNull
                    ?: efforts?.any { it != "none" }
                    ?: true.takeIf { ChatGptThinking.levels(ModelEntry(slug)).isNotEmpty() },
                reasoningEfforts = efforts, defaultReasoningEffort = (obj["default_reasoning_level"] ?: obj["default_reasoning_effort"])?.jsonPrimitive?.contentOrNull)
        }?.distinctBy { it.id } ?: error("ChatGPT returned an unsupported model list.")

    fun requestError(code: String?, status: Int? = null): String = when {
        code == "subscription_sharing_usage_limit_exceeded" || status == 429 -> "ChatGPT usage limit reached. Manage usage in ChatGPT settings: $USAGE_URL"
        code == "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is unavailable. Check this app's access in ChatGPT settings: $USAGE_URL"
        status == 401 -> "ChatGPT sign-in expired. Reconnect in Settings > Connected accounts."
        status == 403 -> "ChatGPT has not allowed this request. Check plan access and the selected model in ChatGPT settings: $USAGE_URL"
        else -> "ChatGPT could not complete this request${status?.let { " (HTTP $it)" }.orEmpty()}. Please try again."
    }
}

@Serializable
internal data class ChatGptStore(val hostId: String, val accounts: List<ChatGptRegistration> = emptyList(), val welcomeSeen: Boolean = false)

@Serializable
data class ChatGptRegistration(
    val clientId: String,
    val issuer: String? = null,
    val subject: String? = null,
    val email: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAt: Long = 0,
    val earliestRefreshAt: Long = 0,
    val scopes: Set<String> = emptySet(),
    val models: List<ModelEntry> = emptyList(),
    val verifiedModels: List<ModelEntry> = emptyList(),
) {
    val connected: Boolean get() = accessToken != null && ChatGptProtocol.PLAN_SCOPE in scopes
    override fun toString() = "ChatGptRegistration(clientId=$clientId, connected=$connected)"
}

/** A loopback-only receiver. Invalid callbacks cannot consume the pending attempt. */
internal class ChatGptLoopback : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
    override fun close() = server.close()

    suspend fun receive(state: String, clientId: String?): Pair<String, String> = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 3000
                try {
                    val input = it.getInputStream()
                    val line = StringBuilder()
                    while (line.length < 8192) {
                        val ch = input.read()
                        if (ch == -1 || ch == 10) break
                        line.append(ch.toChar())
                    }
                    val parts = line.toString().trim().split(' ')
                    val path = parts.getOrNull(1).orEmpty()
                    val validPath = parts.getOrNull(0) == "GET" && path.startsWith("/auth/callback?")
                    val result = if (validPath) runCatching { ChatGptProtocol.callback(redirectUri.substringBefore("/auth/") + path, state, clientId) } else null
                    val denied = validPath && path.toHttpUrlOrNullForLoopback(redirectUri)?.queryParameter("state") == state &&
                        path.toHttpUrlOrNullForLoopback(redirectUri)?.queryParameter("error") != null
                    val accepted = result?.isSuccess == true || denied
                    val body = if (accepted) "<h2>Return to AndroidHarness</h2><p>The app is finishing your ChatGPT connection.</p>" else "<h2>Invalid sign-in response</h2><p>Please return to AndroidHarness and try again.</p>"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(("HTTP/1.1 ${if (accepted) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                    if (accepted) return@withContext result!!.getOrThrow()
                } catch (e: IllegalStateException) { throw e } catch (_: java.io.IOException) { /* Retry a malformed or stalled connection. */ }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Sign-in stopped.")
    }

    private fun String.toHttpUrlOrNullForLoopback(redirect: String) = runCatching { (redirect.substringBefore("/auth/") + this).toHttpUrl() }.getOrNull()
}
