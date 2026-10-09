package com.androidharness.app.github

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GitHubAuthTest {
    private lateinit var server: ServerSocket
    private lateinit var api: GitHubApi
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private var reply: (String, Map<String, String>) -> Pair<Int, String> = { _, _ -> 200 to "{}" }
    private val time = AtomicLong(1_000_000)
    @Volatile private var saved: GitHubCredential? = null
    private val syncs = AtomicInteger()

    @Before fun start() {
        server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        var length = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val chars = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val read = reader.read(chars, offset, length - offset)
                            if (read < 0) break
                            offset += read
                        }
                        val form = String(chars).split('&').filter { it.contains('=') }
                            .associate { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8") }
                        requests += path to form
                        val (code, body) = reply(path, form)
                        val bytes = body.toByteArray()
                        val headers = "HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray())
                        socket.getOutputStream().write(bytes)
                        socket.getOutputStream().flush()
                    }
                } catch (e: java.net.SocketException) { if (!server.isClosed) throw e }
            }
        }
        val url = "http://127.0.0.1:${server.localPort}"
        api = GitHubApi(authBase = url, apiBase = url)
    }
    @After fun stop() { server.close() }

    private fun auth(pause: suspend (Long) -> Unit = { time.addAndGet(it); delay(1) }): GitHubAuth =
        GitHubAuth("publicClientId", { saved }, { saved = it }, { syncs.incrementAndGet() }, api, time::get, pause)

    private fun device() = """{"device_code":"private-device-code","user_code":"TEST-CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
    private fun token() = """{"access_token":"gho_access","refresh_token":"ghr_refresh","expires_in":28800,"refresh_token_expires_in":15897600,"scope":"repo"}"""

    @Test fun `device sign in respects pending slow down verifies identity and uses no secret`() = runBlocking {
        val polls = AtomicInteger()
        val delays = CopyOnWriteArrayList<Long>()
        reply = { path, _ -> 200 to when (path) {
            "/login/device/code" -> device()
            "/user" -> """{"login":"alice"}"""
            else -> when (polls.incrementAndGet()) {
                1 -> """{"error":"authorization_pending"}"""
                2 -> """{"error":"slow_down","interval":10}"""
                else -> token()
            }
        } }
        val connection = auth { delays += it; time.addAndGet(it); delay(1) }
        connection.startSignIn(workflow = true)
        connection.awaitSignIn()
        assertEquals(listOf(5000L, 5000L, 10000L), delays)
        assertEquals("alice", saved?.login)
        assertTrue(saved?.oauth == true)
        assertEquals("ghr_refresh", saved?.refreshToken)
        assertEquals(1, syncs.get())
        assertTrue(requests.first().second["scope"].orEmpty().contains("workflow"))
        assertTrue(requests.none { "client_secret" in it.second })
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", requests.first { it.first == "/login/oauth/access_token" }.second["grant_type"])
        assertFalse(connection.state.value.busy)
        assertNull(connection.state.value.code)
    }

    @Test fun `disabled device flow preserves PAT and gives actionable error`() = runBlocking {
        saved = GitHubCredential("ghp_existing", "alice")
        reply = { _, _ -> 400 to """{"error":"device_flow_disabled"}""" }
        val connection = auth()
        connection.startSignIn(); connection.awaitSignIn()
        assertEquals("ghp_existing", saved?.token)
        assertTrue(connection.state.value.message.orEmpty().contains("Enable Device Flow"))
    }

    @Test fun `declined sign in and expiry never replace an existing credential`() = runBlocking {
        saved = GitHubCredential("ghp_existing", "alice")
        for (error in listOf("access_denied", "expired_token")) {
            reply = { path, _ -> 200 to if (path == "/login/device/code") device() else """{"error":"$error"}""" }
            val connection = auth(); connection.startSignIn(); connection.awaitSignIn()
            assertEquals("ghp_existing", saved?.token)
            assertFalse(connection.state.value.busy)
        }
    }

    @Test fun `logout during device sign in prevents a late token from reconnecting`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        reply = { path, _ -> 200 to if (path == "/login/device/code") device() else token() }
        val connection = auth { entered.complete(Unit); release.await() }
        connection.startSignIn()
        withTimeout(5000) { entered.await() }
        connection.disconnect()
        release.complete(Unit)
        delay(30)
        assertNull(saved)
        assertNull(connection.state.value.login)
        assertNull(connection.state.value.code)
        assertEquals(0, requests.count { it.first == "/login/oauth/access_token" })
    }

    @Test fun `pending device sign in leaves existing PAT usable`() = runBlocking {
        saved = GitHubCredential("ghp_existing", "alice")
        val entered = CompletableDeferred<Unit>()
        reply = { _, _ -> 200 to device() }
        val connection = auth { entered.complete(Unit); awaitCancellation() }
        connection.startSignIn()
        withTimeout(5000) { entered.await() }
        assertEquals("ghp_existing", withTimeout(1000) { connection.accessToken() })
        connection.cancelSignIn()
    }

    @Test fun `concurrent expired token requests refresh once and keep identity`() = runBlocking {
        saved = GitHubCredential("gho_old", "alice", true, "ghr_old", time.get() - 1,
            time.get() + 1_000_000, clientId = "publicClientId")
        reply = { path, _ -> 200 to if (path == "/user") """{"login":"alice"}""" else token() }
        val connection = auth()
        val tokens = coroutineScope { (1..8).map { async(Dispatchers.Default) { connection.accessToken() } }.awaitAll() }
        assertTrue(tokens.all { it == "gho_access" })
        assertEquals(1, requests.count { it.first == "/login/oauth/access_token" })
        val form = requests.first().second
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("ghr_old", form["refresh_token"])
        assertFalse(form.containsKey("client_secret"))
    }

    @Test fun `refresh account mismatch never replaces credentials`() = runBlocking {
        val previous = GitHubCredential("gho_old", "alice", true, "ghr_old", time.get() - 1, clientId = "publicClientId")
        saved = previous
        reply = { path, _ -> 200 to if (path == "/user") """{"login":"bob"}""" else token() }
        assertTrue(runCatching { auth().accessToken() }.isFailure)
        assertEquals(previous, saved)
    }

    @Test fun `PAT replacement is validated before saving and invalid token keeps OAuth`() = runBlocking {
        val previous = GitHubCredential("gho_old", "alice", oauth = true)
        saved = previous
        reply = { _, _ -> 401 to """{"message":"Bad credentials"}""" }
        val connection = auth()
        assertTrue(runCatching { connection.connectPat("ghp_invalid") }.isFailure)
        assertEquals(previous, saved)
        reply = { _, _ -> 200 to """{"login":"bob"}""" }
        connection.connectPat("ghp_valid")
        assertEquals("bob", saved?.login)
        assertFalse(saved?.oauth == true)
        assertNull(saved?.refreshToken)
    }

    @Test fun `background refresh backs off after failure without blocking shell work`() = runBlocking {
        saved = GitHubCredential("gho_old", "alice", true, "ghr_old", time.get() - 1, clientId = "publicClientId")
        reply = { _, _ -> 503 to """{"message":"Service unavailable"}""" }
        val connection = auth()
        repeat(8) { connection.refreshIfNeeded() }
        assertEquals(1, requests.size)
        assertEquals("gho_old", saved?.token)
        time.addAndGet(60_001)
        connection.refreshIfNeeded()
        assertEquals(2, requests.size)
    }

    @Test fun `nonexpiring tokens remain supported and missing client id needs no network`() = runBlocking {
        val body = kotlinx.serialization.json.Json.parseToJsonElement("""{"access_token":"gho_token"}""").let { it as kotlinx.serialization.json.JsonObject }
        assertNull(tokenCredential(body, "alice", "client", time.get()).expiresAt)
        val connection = GitHubAuth("", { saved }, { saved = it }, {}, api)
        connection.startSignIn(); connection.awaitSignIn()
        assertTrue(connection.state.value.message.orEmpty().contains("Client ID"))
        assertTrue(requests.isEmpty())
    }
}
