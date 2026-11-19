package com.androidharness.app.data.audio

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GroqWhisperClientTest {
    /** Shared CI runners can take several seconds to accept and close a socket. */
    private val socketTimeout = 10L

    private fun clip(block: suspend (File) -> Unit) = runBlocking {
        val file = Files.createTempFile("whisper-test", ".m4a").toFile().apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        try { block(file) } finally { file.delete() }
    }
    @Test fun multipartCarriesTheCompleteClipAndModelAndTrimsTheTranscript() = clip { file ->
        var body = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer fixture-key", chain.request().header("Authorization"))
            assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", chain.request().url.toString())
            body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"text\":\"  Voice fixture transcript  \"}".toResponseBody()).build()
        }.build()
        val result = GroqWhisperClient.transcribe(file, " fixture-key ", "whisper-large-v3-turbo", client)
        assertEquals("Voice fixture transcript", result.getOrThrow())
        assertTrue(body.contains("name=\"file\"")); assertTrue(body.contains(file.name)); assertTrue(body.contains("audio/m4a"))
        assertTrue(body.contains("whisper-large-v3-turbo")); assertTrue(body.contains("response_format")); assertTrue(body.contains("json"))
        assertTrue(body.contains(String(byteArrayOf(0, 1, 2, 3))))
    }
    @Test fun missingKeyAndEmptyClipsNeverMakeANetworkRequest() = clip { file ->
        val client = OkHttpClient.Builder().addInterceptor { error("No request expected") }.build()
        assertTrue(GroqWhisperClient.transcribe(file, " ", client = client).isFailure)
        file.writeBytes(byteArrayOf())
        assertTrue(GroqWhisperClient.transcribe(file, "fixture-key", client = client).isFailure)
        assertFalse(GroqWhisperClient.validateApiKey(" ", client).success)
    }
    @Test fun providerErrorsRemainFailures() = clip { file ->
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .body("{\"error\":{\"message\":\"Try again later\"}}".toResponseBody()).build()
        }.build()
        assertEquals("Try again later", GroqWhisperClient.transcribe(file, "fixture-key", client = client).exceptionOrNull()!!.message)
    }
    @Test fun malformedSuccessfulResponsesCannotBecomeAnEmptySuccessfulTranscript() = clip { file ->
        for (body in listOf("not json", "{}", "{\"text\":null}")) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            assertTrue(GroqWhisperClient.transcribe(file, "fixture-key", client = client).isFailure)
        }
    }
    @Test fun cancellingTranscriptionClosesAnUnresponsiveConnectionImmediately() = clip { file ->
        val server = ServerSocket(0)
        val accepted = CompletableFuture<Socket>()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { server.accept().use { socket -> accepted.complete(socket); socket.getInputStream().readBytes() } }
            catch (_: Exception) { }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.localPort}/transcribe").build())
        }.build()
        coroutineScope {
            val job = launch(Dispatchers.IO) { GroqWhisperClient.transcribe(file, "fixture-key", client = client) }
            try {
                withContext(Dispatchers.IO) { accepted.get(socketTimeout, TimeUnit.SECONDS) }
                withTimeout(5_000) { job.cancelAndJoin() }
            } finally {
                accepted.getNow(null)?.close(); server.close(); client.dispatcher.cancelAll(); job.cancelAndJoin(); worker.join(1000)
            }
        }
    }
    @Test fun cancellingWhileTheResponseBodyIsStalledAlsoClosesTheCall() = clip { file ->
        val server = ServerSocket(0)
        val sentHeaders = CompletableFuture<Socket>()
        val closedByClient = CompletableFuture<Unit>()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: application/json\r\n\r\n{\"text\":\"".toByteArray())
                        flush()
                    }
                    sentHeaders.complete(socket)
                    socket.getInputStream().readBytes()
                    closedByClient.complete(Unit)
                }
            } catch (_: Exception) { }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.localPort}/transcribe").build())
        }.build()
        coroutineScope {
            val job = launch(Dispatchers.IO) { GroqWhisperClient.transcribe(file, "fixture-key", client = client) }
            try {
                withContext(Dispatchers.IO) { sentHeaders.get(socketTimeout, TimeUnit.SECONDS) }
                withTimeout(5_000) { job.cancelAndJoin() }
                withContext(Dispatchers.IO) { closedByClient.get(socketTimeout, TimeUnit.SECONDS) }
            } finally {
                sentHeaders.getNow(null)?.close(); server.close(); client.dispatcher.cancelAll(); job.cancelAndJoin(); worker.join(1000)
            }
        }
    }
}
