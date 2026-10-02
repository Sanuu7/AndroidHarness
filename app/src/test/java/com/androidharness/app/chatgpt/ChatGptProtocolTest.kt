package com.androidharness.app.chatgpt

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ChatGptProtocolTest {
    private val signingKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwks: JsonObject get() {
        val key = signingKey.public as RSAPublicKey
        fun encode(n: java.math.BigInteger) = ChatGptProtocol.encode(n.toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it })
        return buildJsonObject { putJsonArray("keys") { add(buildJsonObject {
            put("kid", "fixture"); put("kty", "RSA"); put("alg", "RS256"); put("use", "sig")
            put("n", encode(key.modulus)); put("e", encode(key.publicExponent))
        }) } }
    }
    private fun jwt(nonce: String? = "nonce", audience: String = "oaiapp_fixture", exp: Long = System.currentTimeMillis() / 1000 + 3600, subject: String = "user1"): String {
        val header = ChatGptProtocol.encode("""{"alg":"RS256","kid":"fixture"}""".toByteArray())
        val claims = ChatGptProtocol.encode(buildJsonObject {
            put("iss", ChatGptProtocol.ISSUER); put("sub", subject); put("aud", audience); put("exp", exp)
            put("email", "fixture@example.invalid"); nonce?.let { put("nonce", it) }
        }.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey.private); update("$header.$claims".toByteArray()); sign()
        }
        return "$header.$claims.${ChatGptProtocol.encode(signature)}"
    }

    @Test fun `PKCE matches RFC 7636 and registration uses only public client fields`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url = ChatGptProtocol.authorizeUrl("http://127.0.0.1:18763/auth/callback", "state", "nonce", "verifier", "urn:uuid:fixture", null).toHttpUrl()
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("AndroidHarness", url.queryParameter("agent_name_hint"))
        assertTrue(url.queryParameter("scope")!!.split(' ').contains(ChatGptProtocol.PLAN_SCOPE))
        assertNull(url.queryParameter("client_secret"))
        val reconnect = ChatGptProtocol.authorizeUrl("http://127.0.0.1:18763/auth/callback", "state2", "nonce2", "v2", "urn:uuid:fixture",
            ChatGptRegistration("oaiapp_fixture", idToken = "retained-identity", email = "fixture@example.invalid")).toHttpUrl()
        assertEquals("oaiapp_fixture", reconnect.queryParameter("client_id"))
        assertNull(reconnect.queryParameter("agent_name_hint"))
        assertEquals("retained-identity", reconnect.queryParameter("id_token_hint"))
    }

    @Test fun `callbacks reject wrong state duplicate values denied permission and swapped clients`() {
        val base = "http://127.0.0.1:18763/auth/callback?"
        listOf("state=wrong&code=c&client_id=oaiapp_fixture", "state=state&state=wrong&code=c&client_id=oaiapp_fixture",
            "state=state&error=access_denied", "state=state&code=c", "state=state&code=c&client_id=dynamic_agent_client").forEach {
            assertTrue(runCatching { ChatGptProtocol.callback(base + it, "state", null) }.isFailure)
        }
        assertTrue(runCatching { ChatGptProtocol.callback(base + "state=state&code=c&client_id=other", "state", "oaiapp_fixture") }.isFailure)
        assertEquals("c" to "oaiapp_fixture", ChatGptProtocol.callback(base + "state=state&code=c", "state", "oaiapp_fixture"))
    }

    @Test fun `identity validation checks signature audience expiration and nonce`() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("user1", ChatGptProtocol.validateIdToken(jwt(), jwks, "oaiapp_fixture", "nonce", now).subject)
        listOf(jwt(nonce = "other"), jwt(audience = "other"), jwt(exp = now - 1), jwt().substringBeforeLast('.') + ".AA").forEach {
            assertTrue(runCatching { ChatGptProtocol.validateIdToken(it, jwks, "oaiapp_fixture", "nonce", now) }.isFailure)
        }
        assertTrue(runCatching { ChatGptProtocol.validateIdToken("eyJhbGciOiJub25lIn0.e30.", jwks, "oaiapp_fixture", "nonce", now) }.isFailure)
    }

    @Test fun `model discovery preserves account order and excludes hidden models`() {
        val entries = ChatGptProtocol.models("""{"models":[{"slug":"z","display_name":"First","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"a","display_name":"Second","visibility":"list"}]}""")
        assertEquals(listOf("z", "a"), entries.map { it.id })
        assertEquals("First", entries.first().displayName)
    }

    @Test fun `full sign in validates identity persists registration and discovers subscription models`() = runBlocking {
        val url = CompletableDeferred<String>()
        val saved = AtomicReference<String?>()
        val selected = CompletableDeferred<String>()
        val nonce = AtomicReference<String>()
        val client = mockHttp { request -> when (request.url.encodedPath) {
            "/api/accounts/oauth/token" -> {
                val form = request.body as FormBody
                assertEquals("oaiapp_fixture", form.value(form.names().indexOf("client_id")))
                assertFalse(form.names().contains("client_secret"))
                tokenResponse(jwt(nonce.get()))
            }
            "/.well-known/openid-configuration" -> discovery()
            "/.well-known/jwks.json" -> jwks.toString()
            "/v1/models" -> { assertEquals("Bearer fixture-access", request.header("Authorization")); catalog() }
            else -> error("Unexpected request")
        } }
        val accounts = ChatGptAccounts(saved::get, saved::set, { selected.complete(it.id) }, http = client, browserDispatcher = Dispatchers.Unconfined, refreshOnStart = false)
        accounts.startSignIn { nonce.set(it.toHttpUrl().queryParameter("nonce")); url.complete(it) }
        val auth = withTimeout(5000) { url.await() }.toHttpUrl()
        val callback = auth.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
            .addQueryParameter("state", auth.queryParameter("state")).addQueryParameter("code", "fixture-code")
            .addQueryParameter("client_id", "oaiapp_fixture").build().toString()
        withContext(Dispatchers.IO) { (URL(callback).openConnection() as HttpURLConnection).run { assertEquals(200, responseCode); disconnect() } }
        assertEquals("chatgpt:oaiapp_fixture", withTimeout(5000) { selected.await() })
        assertTrue(accounts.state.value.accounts.single().connected)
        assertTrue(accounts.state.value.showWelcome)
        assertEquals(listOf("fixture-model"), accounts.state.value.accounts.single().models.map { it.id })
        assertTrue(saved.get()!!.contains("fixture-refresh"))
        val restored = ChatGptAccounts(saved::get, saved::set, {}, http = client, refreshOnStart = false)
        assertEquals(accounts.state.value.accounts, restored.state.value.accounts)
        accounts.dismissWelcome()
        assertFalse(accounts.state.value.showWelcome)
    }

    @Test fun `refresh rotation is serialized and sign out clears tokens but retains registration`() = runBlocking {
        val count = AtomicInteger()
        val revoked = CompletableDeferred<Unit>()
        val initial = ChatGptStore("urn:uuid:fixture", listOf(ChatGptRegistration("oaiapp_fixture", issuer = ChatGptProtocol.ISSUER,
            subject = "user1", email = "fixture@example.invalid", accessToken = "old-access", refreshToken = "old-refresh", scopes = setOf(ChatGptProtocol.PLAN_SCOPE))))
        val saved = AtomicReference<String?>(Json.encodeToString(initial))
        val client = mockHttp { request -> when (request.url.encodedPath) {
            "/api/accounts/oauth/token" -> {
                count.incrementAndGet()
                val form = request.body as FormBody
                assertEquals("refresh_token", form.value(form.names().indexOf("grant_type")))
                assertEquals("old-refresh", form.value(form.names().indexOf("refresh_token")))
                assertFalse(form.names().contains("scope"))
                tokenResponse(jwt(nonce = null))
            }
            "/.well-known/openid-configuration" -> discovery()
            "/.well-known/jwks.json" -> jwks.toString()
            "/api/accounts/oauth/revoke" -> {
                val form = request.body as FormBody
                assertEquals("fixture-refresh", form.value(form.names().indexOf("token")))
                assertEquals("oaiapp_fixture", form.value(form.names().indexOf("client_id")))
                revoked.complete(Unit); ""
            }
            else -> error("Unexpected request")
        } }
        val accounts = ChatGptAccounts(saved::get, saved::set, {}, http = client, refreshOnStart = false)
        val tokens = coroutineScope { (1..12).map { async(Dispatchers.IO) { accounts.accessToken("chatgpt:oaiapp_fixture") } }.awaitAll() }
        assertEquals(1, count.get())
        assertTrue(tokens.all { it == "fixture-access" })
        val rotated = Json.decodeFromString<ChatGptStore>(saved.get()!!).accounts.single()
        assertEquals("fixture-refresh", rotated.refreshToken)
        assertTrue(rotated.expiresAt > System.currentTimeMillis() / 1000)
        accounts.signOut("chatgpt:oaiapp_fixture")
        withTimeout(5000) { accounts.state.first { !it.accounts.single().connected } }
        withTimeout(5000) { revoked.await() }
        val disconnected = Json.decodeFromString<ChatGptStore>(saved.get()!!).accounts.single()
        assertNull(disconnected.accessToken); assertNull(disconnected.refreshToken); assertNull(disconnected.idToken)
        assertEquals("oaiapp_fixture", disconnected.clientId)
        assertEquals("user1", disconnected.subject)
        assertTrue(runCatching { accounts.accessToken("chatgpt:oaiapp_fixture") }.isFailure)
    }

    private fun tokenResponse(identity: String) = buildJsonObject {
        put("access_token", "fixture-access"); put("refresh_token", "fixture-refresh"); put("id_token", identity)
        put("token_type", "Bearer"); put("expires_in", 3600); put("scope", ChatGptProtocol.SCOPES)
    }.toString()

    @Test fun `newer models require completed inference and survive refresh and restart`() = runBlocking {
        val id = "chatgpt:oaiapp_fixture"
        val saved = AtomicReference<String?>(Json.encodeToString(ChatGptStore("urn:uuid:fixture", listOf(
            ChatGptRegistration("oaiapp_fixture", issuer = ChatGptProtocol.ISSUER, subject = "user1", accessToken = "fixture-access",
                expiresAt = System.currentTimeMillis() / 1000 + 3600, scopes = setOf(ChatGptProtocol.PLAN_SCOPE)),
        ))))
        val checked = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val content = if (request.url.encodedPath == "/v1/models") catalog() else {
                assertEquals("https://api.openai.com/v1/responses", request.url.toString())
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val model = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("model").jsonPrimitive.content
                checked += model
                when (model) {
                    "gpt-6.1-sol" -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
                    "gpt-6-sol" -> { status = 403; "{}" }
                    else -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(content.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val accounts = ChatGptAccounts(saved::get, saved::set, {}, http = http, refreshOnStart = false)
        assertEquals(listOf("fixture-model", "gpt-6.1-sol"), accounts.discoverModels(id).map { it.id })
        assertEquals(listOf("fixture-model", "gpt-6.1-sol"), accounts.refreshModels(id).map { it.id })
        accounts.discoverModels(id)
        assertEquals(1, checked.count { it == "gpt-6.1-sol" })
        val restored = ChatGptAccounts(saved::get, saved::set, {}, http = http, refreshOnStart = false)
        assertEquals(accounts.state.value.accounts, restored.state.value.accounts)
        assertEquals(listOf("gpt-6.1-sol"), Json.decodeFromString<ChatGptStore>(saved.get()!!).accounts.single().verifiedModels.map { it.id })
        accounts.signOut(id)
        withTimeout(5000) { accounts.state.first { !it.accounts.single().connected } }
        assertTrue(Json.decodeFromString<ChatGptStore>(saved.get()!!).accounts.single().verifiedModels.isEmpty())
    }
    private fun discovery() = """{"issuer":"https://auth.openai.com","jwks_uri":"https://auth.openai.com/.well-known/jwks.json","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}"""
    private fun catalog() = """{"models":[{"slug":"fixture-model","display_name":"Fixture model","visibility":"list"}]}"""
    private fun mockHttp(reply: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun FormBody.names() = (0 until size).map { name(it) }
}
