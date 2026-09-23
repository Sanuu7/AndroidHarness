package com.androidharness.app.llm

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class SseStreamTest {
    @Test
    fun `slow collector receives every streamed chunk`() = runBlocking {
        val count = 500
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val sender = thread {
                server.accept().use { socket ->
                    val body = buildString {
                        repeat(count) { append("data: {\"index\":$it}\n\n") }
                        append("data: [DONE]\n\n")
                    }.toByteArray()
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                }
            }
            val received = mutableListOf<Int>()
            withTimeout(15_000) {
                ProviderFactory.sseJson(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                    .collect {
                        received += it.toString().substringAfter(':').substringBefore('}').toInt()
                        delay(1)
                    }
            }
            sender.join(5_000)
            assertEquals((0 until count).toList(), received)
        }
    }
}
