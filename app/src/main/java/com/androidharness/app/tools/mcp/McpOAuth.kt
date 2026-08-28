package com.androidharness.app.tools.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Persisted per-server OAuth state: the discovered authorization-server
 * plumbing plus the current tokens. One JSON blob in the app's KeyStore.
 */
@Serializable
data class McpOAuthState(
    val context: McpOAuthContext,
    val accessToken: String = "",
    val refreshToken: String? = null,
    /** Epoch ms when the access token expires; null when the server says nothing. */
    val expiresAtMs: Long? = null,
) {
    fun accessTokenValid(nowMs: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && (expiresAtMs == null || nowMs < expiresAtMs - 30_000)
}

/** Discovered OAuth endpoints for one MCP server (RFC 9728 + RFC 8414). */
@Serializable
data class McpOAuthContext(
    /** Canonical resource identifier sent as the RFC 8707 `resource` parameter. */
    val resource: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scopes: String? = null,
    val resourceMetadataUrl: String? = null,
)

@Serializable
data class McpOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
)

/**
 * The MCP authorization flow (spec 2025-06-18): OAuth 2.1 with PKCE against
 * an authorization server discovered through Protected Resource Metadata,
 * self-registering via Dynamic Client Registration when available.
 * Verified against Supabase's MCP: 401 + `WWW-Authenticate: …
 * resource_metadata="https://mcp.supabase.com/.well-known/oauth-protected-resource/mcp?…"`
 * → authorization_servers ["https://api.supabase.com"] → AS metadata with a
 * registration_endpoint and S256 PKCE.
 */
object McpOAuth {

    /**
     * Redirect target for the browser round-trip. The MCP spec asks for
     * localhost/HTTPS redirects; a custom scheme is the pragmatic choice for
     * a native Android app and is accepted because we register it ourselves
     * during Dynamic Client Registration.
     */
    const val REDIRECT_URI = "androidharness://mcp/oauth"

    private val json = Json { ignoreUnknownKeys = true }

    // --- discovery -------------------------------------------------------------

    /**
     * Follows the discovery chain from a 401 challenge: protected resource
     * metadata → authorization server → authorization server metadata.
     * [challengeMetadataUrl] is the `resource_metadata` URL from the
     * WWW-Authenticate header; [serverUrl] seeds the fallback probe when the
     * header omitted it. Returns null when the server offers no OAuth.
     */
    suspend fun discover(
        client: OkHttpClient,
        challengeMetadataUrl: String?,
        serverUrl: String,
    ): McpOAuthContext? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            challengeMetadataUrl?.let { add(it) }
            runCatching { java.net.URI(serverUrl) }.getOrNull()?.let { uri ->
                val base = "${uri.scheme ?: "https"}://${uri.host ?: ""}" +
                    (uri.port.takeIf { it > 0 }?.let { ":$it" } ?: "")
                add("$base/.well-known/oauth-protected-resource${uri.path ?: ""}")
                add("$base/.well-known/oauth-protected-resource")
            }
        }
        val prm = candidates.firstNotNullOfOrNull { url ->
            fetchJson(client, url)?.takeIf { it.containsKey("authorization_servers") }
        } ?: return@withContext null
        val servers = (prm["authorization_servers"] as? kotlinx.serialization.json.JsonArray)
            ?: return@withContext null
        val issuer = servers.firstNotNullOfOrNull {
            runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
        }?.ifBlank { null } ?: return@withContext null
        val asMeta = listOf(
            "$issuer/.well-known/oauth-authorization-server",
            "$issuer/.well-known/oauth-authorization-server" +
                runCatching { java.net.URI(issuer).path ?: "" }.getOrDefault(""),
        ).firstNotNullOfOrNull { fetchJson(client, it) }
            ?: return@withContext null
        val authEndpoint = asMeta.string("authorization_endpoint") ?: return@withContext null
        val tokenEndpoint = asMeta.string("token_endpoint") ?: return@withContext null
        val scopes = (prm["scopes_supported"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.joinToString(" ")?.ifBlank { null }
        McpOAuthContext(
            resource = prm.string("resource") ?: serverUrl,
            authorizationEndpoint = authEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = asMeta.string("registration_endpoint"),
            scopes = scopes,
            resourceMetadataUrl = challengeMetadataUrl,
        )
    }

    /** Dynamic Client Registration (RFC 7591); fills clientId/clientSecret in place. */
    suspend fun registerClient(client: OkHttpClient, ctx: McpOAuthContext): McpOAuthContext =
        withContext(Dispatchers.IO) {
            val endpoint = ctx.registrationEndpoint ?: return@withContext ctx
            val body = buildString {
                append('{')
                append("\"client_name\":\"AndroidHarness\",")
                append("\"redirect_uris\":[\"$REDIRECT_URI\"],")
                append("\"grant_types\":[\"authorization_code\",\"refresh_token\"],")
                append("\"response_types\":[\"code\"],")
                append("\"token_endpoint_auth_method\":\"none\"")
                ctx.scopes?.let { append(",\"scope\":\"$it\"") }
                append('}')
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty().take(200)
                        throw McpOAuthException(
                            "The provider refused app registration (HTTP ${resp.code}). " +
                                text.ifBlank { "Try again, or ask the provider for a client id." },
                        )
                    }
                    val registered = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    ctx.copy(
                        clientId = registered.string("client_id") ?: ctx.clientId,
                        clientSecret = registered.string("client_secret"),
                    )
                }
            } catch (e: McpOAuthException) {
                throw e
            } catch (e: Exception) {
                throw McpOAuthException(
                    "Could not register this app with the provider: ${e.message ?: "connection failed"}",
                )
            }
        }

    // --- browser round-trip ------------------------------------------------------

    fun createVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return base64UrlNoPadding(bytes)
    }

    /** S256 code challenge for a PKCE verifier. */
    fun codeChallenge(verifier: String): String =
        base64UrlNoPadding(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    fun createState(): String = createVerifier()

    fun authorizationUrl(ctx: McpOAuthContext, state: String, codeChallenge: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to (ctx.clientId ?: ""),
            "redirect_uri" to REDIRECT_URI,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            // RFC 8707 resource indicators: MUST be sent, even when ignored.
            "resource" to ctx.resource,
        )
        ctx.scopes?.let { params["scope"] = it }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val sep = if (ctx.authorizationEndpoint.contains('?')) '&' else '?'
        return ctx.authorizationEndpoint + sep + query
    }

    // --- token endpoints -----------------------------------------------------------

    suspend fun exchangeCode(
        client: OkHttpClient,
        ctx: McpOAuthContext,
        code: String,
        verifier: String,
    ): McpOAuthTokens = tokenRequest(
        client, ctx,
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", ctx.clientId ?: "")
            .apply { ctx.clientSecret?.let { add("client_secret", it) } }
            .add("code_verifier", verifier)
            .add("resource", ctx.resource)
            .build(),
    )

    suspend fun refreshTokens(
        client: OkHttpClient,
        ctx: McpOAuthContext,
        refreshToken: String,
    ): McpOAuthTokens = tokenRequest(
        client, ctx,
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", ctx.clientId ?: "")
            .apply { ctx.clientSecret?.let { add("client_secret", it) } }
            .add("resource", ctx.resource)
            .build(),
    )

    private suspend fun tokenRequest(
        client: OkHttpClient,
        ctx: McpOAuthContext,
        form: FormBody,
    ): McpOAuthTokens = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(ctx.tokenEndpoint).post(form).build()
        try {
            client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        val detail = obj?.string("error_description") ?: obj?.string("error")
                        throw McpOAuthException(
                            "Token exchange failed (HTTP ${resp.code})" +
                                (detail?.let { ": $it" } ?: "") + ". Try authenticating again.",
                        )
                    }
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: throw McpOAuthException("Token endpoint returned an unreadable response.")
                    val access = obj.string("access_token")
                        ?: throw McpOAuthException("Token endpoint returned no access token.")
                    McpOAuthTokens(
                        accessToken = access,
                        refreshToken = obj.string("refresh_token"),
                        expiresAtMs = obj["expires_in"]?.jsonPrimitive?.longOrNull
                            ?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000 },
                    )
                }
        } catch (e: McpOAuthException) {
            throw e
        } catch (e: Exception) {
            throw McpOAuthException(
                "Could not reach the token endpoint: ${e.message ?: "connection failed"}",
            )
        }
    }

    // --- helpers ---------------------------------------------------------------------

    private fun base64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun fetchJson(client: OkHttpClient, url: String): JsonObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
}

/** OAuth steps failed after discovery, surfaced verbatim to the user. */
class McpOAuthException(message: String) : Exception(message)
