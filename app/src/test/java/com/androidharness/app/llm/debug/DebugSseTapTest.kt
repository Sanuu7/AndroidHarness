package com.androidharness.app.llm.debug

import com.androidharness.app.llm.ProviderFactory
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end: a request goes through the interceptor and its response body is
 * then read as SSE, which is the only path that can prove the tap keeps working
 * when the two halves run on different threads.
 */
class DebugSseTapTest {

    @Test(timeout = 20_000)
    fun aStreamingTurnRecordsItsStatusAndRawChunks() = runBlocking {
        DebugCapture.enabled = true
        DebugCapture.captureBodies = true
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                                    .toByteArray(),
                            )
                            write("data: {\"delta\":\"hi\"}\n\n".toByteArray())
                            write("data: [DONE]\n\n".toByteArray())
                            flush()
                        }
                    }
                }
                val request = Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/chat/completions")
                    .post("{}".toRequestBody())
                    .tag(DebugTag::class.java, DebugTag("p", "Provider", "m", "OPENAI_COMPAT", attempt = 2, keySlot = 1))
                    .tag(DebugStreamKey::class.java, DebugStreamKey("key-1"))
                    .build()
                val client = OkHttpClient.Builder().addInterceptor(DebugInterceptor()).build()

                ProviderFactory.sseJson(request, client).collect { }

                val trace = DebugCapture.store.traces.value.firstOrNull()
                assertNotNull("a trace was recorded", trace)
                assertEquals(200, trace!!.statusCode)
                assertTrue("marked as a stream", trace.stream)
                assertEquals(2, trace.attempt)
                assertEquals(1, trace.keySlot)
                val body = trace.streamBody.orEmpty()
                assertTrue("raw chunk kept: $body", body.contains("{\"delta\":\"hi\"}"))
                assertTrue("done marker kept: $body", body.contains("[DONE]"))
                assertTrue("line count records data lines, not keep-alives", trace.streamEvents == 2)
            }
        } finally {
            DebugCapture.store.clear()
            DebugCapture.enabled = false
            DebugCapture.captureBodies = false
        }
    }

    @Test(timeout = 20_000)
    fun aNonStreamingResponseIsPreviewedButNotTreatedAsSse() = runBlocking {
        DebugCapture.enabled = true
        DebugCapture.captureBodies = true
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val payload = "{\"error\":\"nope\"}"
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            write(payload.toByteArray())
                            flush()
                        }
                    }
                }
                val request = Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/chat/completions")
                    .post("{}".toRequestBody())
                    .build()
                val client = OkHttpClient.Builder().addInterceptor(DebugInterceptor()).build()
                client.newCall(request).execute().close()

                val trace = DebugCapture.store.traces.value.firstOrNull()
                assertNotNull(trace)
                assertEquals(429, trace!!.statusCode)
                assertTrue("failure recognised", trace.failed)
                assertTrue("error body captured: ${trace.errorBody}", trace.errorBody.orEmpty().contains("nope"))
            }
        } finally {
            DebugCapture.store.clear()
            DebugCapture.enabled = false
            DebugCapture.captureBodies = false
        }
    }
}
