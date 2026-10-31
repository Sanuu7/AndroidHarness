package com.androidharness.app.plugins

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Exchange(private val socket: Socket, path: String, val requestHeaders: Map<String, String>) : Closeable {
        val requestURI = URI(path)
        val responseHeaders = mutableMapOf<String, String>()
        val responseBody get() = socket.getOutputStream()
        fun sendResponseHeaders(status: Int, length: Long) {
            responseBody.write(buildString {
                append("HTTP/1.1 $status Fixture\r\nContent-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n")
                responseHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }.toByteArray())
        }
        override fun close() { socket.close() }
    }
    private class Registry : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val url get() = "http://127.0.0.1:${server.localPort}"
        @Volatile var handler: (Exchange) -> Unit = { exchange ->
            exchange.sendResponseHeaders(404, -1); exchange.close()
        }
        private val worker = kotlin.concurrent.thread(isDaemon = true, name = "plugin-registry-fixture") {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) { val line = input.readLine().orEmpty(); if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val exchange = Exchange(socket, path, headers)
                    requests += path to headers["authorization"]
                    handler(exchange)
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }
        fun reply(exchange: Exchange, bytes: ByteArray) {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fun downloads(token: String? = null) = PluginDownloads(npmRegistry = url, githubApi = url,
            githubToken = { token }, allowLocalHttp = true)
        override fun close() { server.close(); worker.join(1000) }
    }

    @Test fun `npm resolves scoped package and tag verifies archive and searches with compatibility keyword`() = runBlocking {
        Registry().use { registry ->
            val bytes = "registry archive".toByteArray()
            val integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))
            registry.handler = { exchange ->
                val body = when (exchange.requestURI.path) {
                    "/@team/plugin/beta" -> """{"name":"@team/plugin","version":"2.1.0-beta.1","dist":{"tarball":"${registry.url}/archive","integrity":"$integrity"}}""".toByteArray()
                    "/-/v1/search" -> """{"objects":[{"package":{"name":"@team/plugin","version":"2.1.0","description":"Fixture","publisher":{"username":"team"}}},{"package":{"name":"https://untrusted.invalid/package"}}]}""".toByteArray()
                    else -> bytes
                }
                registry.reply(exchange, body)
            }
            val downloads = registry.downloads("private-github-token")
            val resolved = downloads.resolve(PluginOrigin("npm", "@team/plugin@beta"))
            assertEquals("2.1.0-beta.1", resolved.revision)
            assertEquals(PluginOrigin("npm", "@team/plugin", "beta"), resolved.origin)
            val archive = tmp.newFile()
            val progress = mutableListOf<Long>()
            downloads.download(resolved, archive) { n, _ -> progress += n }
            assertArrayEquals(bytes, archive.readBytes())
            assertEquals(bytes.size.toLong(), progress.last())
            val listings = downloads.searchNpm("review & test")
            assertEquals(listOf("@team/plugin"), listings.map { it.name })
            assertEquals("team", listings.single().publisher)
            assertFalse(listings.single().builtIn)
            assertTrue(registry.requests.last().first.contains("keywords%3Aandroidharness-plugin"))
            assertTrue(registry.requests.all { it.second == null })
        }
    }

    @Test fun `github pins default and slash branch to immutable commits and passes account token`() = runBlocking {
        Registry().use { registry ->
            val sha = "a".repeat(40)
            registry.handler = { exchange -> registry.reply(exchange, when {
                exchange.requestURI.path == "/repos/team/repo" -> """{"default_branch":"feature/plugins"}""".toByteArray()
                exchange.requestURI.path.contains("/commits/") -> """{"sha":"$sha"}""".toByteArray()
                else -> "zip bytes".toByteArray()
            }) }
            val downloads = registry.downloads("fixture-token")
            val resolved = downloads.resolve(PluginOrigin("github", "https://github.com/team/repo.git", subdirectory = "plugins/review"))
            assertEquals(sha, resolved.revision)
            assertEquals("feature/plugins", resolved.origin.ref)
            assertEquals("plugins/review", resolved.origin.subdirectory)
            assertTrue(resolved.url.endsWith("/zipball/$sha"))
            downloads.download(resolved, tmp.newFile())
            assertTrue(registry.requests.any { it.first.endsWith("/commits/feature%2Fplugins") })
            assertTrue(registry.requests.all { it.second == "Bearer fixture-token" })
        }
    }

    @Test fun `github archive redirect does not forward credentials to a different origin`() = runBlocking {
        Registry().use { archive -> Registry().use { api ->
            val sha = "b".repeat(40)
            api.handler = { exchange ->
                if (exchange.requestURI.path.contains("/zipball/")) {
                    exchange.responseHeaders["Location"] = archive.url + "/payload"
                    exchange.sendResponseHeaders(302, -1); exchange.close()
                } else api.reply(exchange, """{"sha":"$sha"}""".toByteArray())
            }
            archive.handler = { archive.reply(it, "archive".toByteArray()) }
            val downloads = api.downloads("sensitive-fixture-token")
            downloads.download(downloads.resolve(PluginOrigin("github", "team/repo", "v1")), tmp.newFile())
            assertNull(archive.requests.single().second)
        } }
    }

    @Test fun `missing or mismatched package metadata and corrupt downloads are refused`() = runBlocking {
        Registry().use { registry ->
            var metadata = """{"name":"other-plugin","version":"1.0.0"}"""
            registry.handler = { registry.reply(it, metadata.toByteArray()) }
            val downloads = registry.downloads()
            suspend fun refuses(block: suspend () -> Unit) {
                try { block(); fail("Untrusted registry response should be refused") } catch (_: IllegalArgumentException) { }
                catch (_: IllegalStateException) { }
            }
            refuses { downloads.resolve(PluginOrigin("npm", "test-plugin")) }
            metadata = """{"name":"test-plugin","version":"1.0.0","dist":{"tarball":"${registry.url}/payload"}}"""
            refuses { downloads.resolve(PluginOrigin("npm", "test-plugin")) }
            metadata = """{"sha":"moving-branch"}"""
            refuses { downloads.resolve(PluginOrigin("github", "team/repo", "main")) }
            refuses { downloads.download(ResolvedPluginDownload(PluginOrigin("npm", "test-plugin"), "1.0.0", registry.url + "/payload", "sha512-" + Base64.getEncoder().encodeToString(ByteArray(64))), tmp.newFile()) }
        }
    }

    @Test fun `oversize response and insecure source fail before installation`() = runBlocking {
        Registry().use { registry ->
            registry.handler = { exchange -> exchange.sendResponseHeaders(200, PluginArchives.MAX_DOWNLOAD + 1); exchange.close() }
            try {
                registry.downloads().download(ResolvedPluginDownload(PluginOrigin("npm", "test-plugin"), "1", registry.url), tmp.newFile())
                fail("Oversize response must be refused")
            } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("25 MiB")) }
            try {
                PluginDownloads(npmRegistry = registry.url).resolve(PluginOrigin("npm", "test-plugin"))
                fail("HTTP must not be allowed outside explicit test transport")
            } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("HTTPS")) }
        }
    }

    @Test fun `cancelling an active download closes it without waiting for response body`() = runBlocking {
        Registry().use { registry ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            registry.handler = { exchange ->
                try {
                    exchange.sendResponseHeaders(200, 8192)
                    exchange.responseBody.write(1); exchange.responseBody.flush(); started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            try {
                val job = async(Dispatchers.Default) { registry.downloads().download(
                    ResolvedPluginDownload(PluginOrigin("npm", "test-plugin"), "1", registry.url), tmp.newFile()) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                withTimeout(1500) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            } finally { release.countDown() }
        }
    }
}
