package com.androidharness.app.tools.mcp

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** Exercises the same manager entry point used by Settings' Test button. */
@RunWith(AndroidJUnit4::class)
class McpAuthenticationDeviceTest {
    @Test fun httpTestWithoutMetadataShowsAuthenticationInsteadOfThrowing() = runBlocking {
        fixture { manager, server ->
            val config = McpServerConfig(name = "issue90-http", type = "http", url = server.url + "/mcp")
            manager.addServer(config)
            for (challenge in listOf(null, "Bearer realm=\"fixture\"")) {
                server.challenge = challenge
                assertAuthRequired(manager, config)
                val auth = manager.startAuthentication(config.name)
                assertTrue(auth.exceptionOrNull() is McpOAuthException)
                assertTrue(auth.exceptionOrNull()!!.message!!.contains("static token"))
            }
        }
    }

    @Test fun sseTestWithoutMetadataShowsAuthenticationInsteadOfThrowing() = runBlocking {
        fixture { manager, server ->
            assertAuthRequired(manager, McpServerConfig(name = "issue90-sse", type = "sse", url = server.url + "/mcp"))
        }
    }

    @Test fun aNewChallengeWithoutMetadataClearsThePreviousDiscoveryUrl() = runBlocking {
        fixture { manager, server ->
            val config = McpServerConfig(name = "issue90-changing", type = "http", url = server.url + "/mcp")
            manager.addServer(config)
            server.challenge = "Bearer resource_metadata=\"${server.url}/advertised\""
            assertAuthRequired(manager, config)
            assertTrue(manager.startAuthentication(config.name).isFailure)
            assertTrue(server.paths.contains("/advertised"))

            server.challenge = null
            server.paths.clear()
            assertAuthRequired(manager, config)
            assertTrue(manager.startAuthentication(config.name).isFailure)
            assertFalse(server.paths.contains("/advertised"))
            assertTrue(server.paths.contains("/.well-known/oauth-protected-resource/mcp"))
        }
    }

    private suspend fun assertAuthRequired(manager: McpManager, config: McpServerConfig) {
        val result = manager.testConnection(config)
        assertTrue("Expected an auth failure, got $result", result.exceptionOrNull() is McpAuthRequiredException)
        val status = requireNotNull(manager.statusFor(config.name))
        assertEquals("auth", status.state)
        assertTrue(status.needsAuth)
    }

    private suspend fun fixture(block: suspend (McpManager, AuthServer) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val root = File(app.cacheDir, "mcp-auth-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = root
        }
        val manager = McpManager(context, app.container.linuxEnv, app.container.keys)
        try {
            AuthServer().use { block(manager, it) }
        } finally {
            manager.disconnectAll()
            root.deleteRecursively()
        }
    }

    private class AuthServer : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        @Volatile var challenge: String? = null
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(isDaemon = true, name = "mcp-auth-fixture") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                client.use {
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    paths.add(path)
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    val status = if (path == "/mcp") "401 Unauthorized" else "404 Not Found"
                    val header = if (path == "/mcp") challenge?.let { "WWW-Authenticate: $it\r\n" }.orEmpty() else ""
                    client.getOutputStream().write("HTTP/1.1 $status\r\n${header}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        override fun close() {
            socket.close()
            worker.join(5_000)
        }
    }
}
