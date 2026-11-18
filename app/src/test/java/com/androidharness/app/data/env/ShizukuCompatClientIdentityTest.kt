package com.androidharness.app.data.env

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ShizukuCompatClientIdentityTest {
    private val adbUids = setOf(0, 2000)

    @Test fun rootAdbdIsAccepted() {
        val client = startAdb(helloUid = 0)
        assertEquals(0, client.uid)
        assertTrue(client.isAlive)
        client.close()
    }

    @Test fun shellAdbdIsStillAccepted() {
        val client = startAdb(helloUid = 2000)
        assertEquals(2000, client.uid)
        client.close()
    }

    @Test fun appOrOtherUidsAreRejectedOnTheAdbPath() {
        for (uid in listOf(10123, 1000, 1)) {
            val failure = runCatching { startAdb(helloUid = uid) }.exceptionOrNull()
            assertTrue("uid $uid", failure is IllegalStateException)
            assertEquals("Compatibility runner identity check failed", failure?.message)
        }
    }

    @Test fun aSingleAllowedUidKeepsTheStrictShizukuMatch() {
        val runner = FakeRunner(helloUid = 0)
        val failure = runCatching { runBlocking { ShizukuCompatClient.start(runner, setOf(2000)) { } } }.exceptionOrNull()
        assertEquals("Compatibility runner identity check failed", failure?.message)
        val matching = runBlocking { ShizukuCompatClient.start(FakeRunner(helloUid = 0), setOf(0)) { } }
        assertEquals(0, matching.uid)
        matching.close()
    }

    @Test fun idCommandMustAgreeWithTheReportedUid() {
        val runner = FakeRunner(helloUid = 0, idUid = 2000)
        val failure = runCatching { runBlocking { ShizukuCompatClient.start(runner, adbUids) { } } }.exceptionOrNull()
        assertEquals("Compatibility runner execution check failed", failure?.message)
    }

    @Test fun anUnknownProtocolIsRejected() {
        val runner = FakeRunner(helloUid = 0, protocol = 2)
        val failure = runCatching { runBlocking { ShizukuCompatClient.start(runner, adbUids) { } } }.exceptionOrNull()
        assertEquals("Compatibility runner identity check failed", failure?.message)
    }

    @Test fun theClosedClientIsNotReportedAlive() {
        val client = startAdb(helloUid = 0)
        client.close()
        assertFalse(client.isAlive)
    }

    private fun startAdb(helloUid: Int): ShizukuCompatClient =
        runBlocking { ShizukuCompatClient.start(FakeRunner(helloUid), adbUids) { } }

    /** A runner that sends its hello up front and answers the `id -u` probe, like the real helper. */
    private class FakeRunner(helloUid: Int, private val idUid: Int = helloUid, protocol: Int = 1) : CompatProcess {
        private val json = ShizukuCompatProtocol.json
        private val toClient = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        init { reply(CompatResponse(0, uid = helloUid, protocol = protocol)) }

        private fun reply(response: CompatResponse) {
            toClient.put((json.encodeToString(CompatResponse.serializer(), response) + "\n").toByteArray())
        }

        override val inputStream: InputStream = object : InputStream() {
            private var current = ByteArray(0)
            private var position = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                while (position >= current.size) {
                    if (closed && toClient.isEmpty()) return -1
                    current = toClient.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    position = 0
                }
                val count = minOf(length, current.size - position)
                System.arraycopy(current, position, buffer, offset, count)
                position += count
                return count
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            private val line = ByteArrayOutputStream()

            override fun write(byte: Int) {
                if (byte != '\n'.code) { line.write(byte); return }
                val request = json.decodeFromString(CompatRequest.serializer(), line.toString(Charsets.UTF_8))
                line.reset()
                when (request.operation) {
                    "exec" -> reply(CompatResponse(request.id, "exit=0\ntimeout=0\n$idUid\n"))
                    "close" -> closed = true
                }
            }

            override fun close() { closed = true }
        }

        override val errorStream: InputStream? = null
        override val isAlive: Boolean get() = !closed
        override fun waitForTimeout(value: Long, unit: String) {}
        override fun destroy() { closed = true }
    }
}
