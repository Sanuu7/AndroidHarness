package com.androidharness.app.llm

import com.androidharness.app.agent.StreamStalledException
import com.androidharness.app.agent.stallGuard
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A provider that reasons without streaming its thoughts often sends only SSE
 * comments for minutes. Those lines must keep the stall guard satisfied, while
 * a connection that goes fully quiet must still be cut off.
 */
class SseKeepAliveTest {

    private fun serve(keepAlives: Int, gapMs: Long, block: (Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                            repeat(keepAlives) {
                                Thread.sleep(gapMs)
                                write(": keep-alive\n\n".toByteArray())
                                flush()
                            }
                            Thread.sleep(gapMs)
                            write("data: {\"delta\":\"answer\"}\n\ndata: [DONE]\n\n".toByteArray())
                            flush()
                        }
                    }
                }
            }
            block(server.localPort)
        }
    }

    private fun request(port: Int) = Request.Builder()
        .url("http://127.0.0.1:$port/chat/completions")
        .post("{}".toRequestBody())
        .build()

    @Test(timeout = 20_000)
    fun keepAliveCommentsKeepTheStreamAlive() = serve(keepAlives = 8, gapMs = 100) { port ->
        val events = runBlocking { ProviderFactory.sseJson(request(port)).stallGuard(300).toList() }
        assertEquals(1, events.size)
    }

    @Test(timeout = 20_000)
    fun aQuietConnectionStillStalls() = serve(keepAlives = 1, gapMs = 1_000) { port ->
        try {
            runBlocking { ProviderFactory.sseJson(request(port)).stallGuard(300).toList() }
            fail("a silent connection must stall")
        } catch (e: StreamStalledException) {
            assertTrue(e.message!!.contains("timed out"))
        }
    }
}
