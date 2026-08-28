package com.androidharness.app.llm

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SSE reader must split ONLY on LF (stripping an immediately-preceding
 * CR). readUtf8Line()/BufferedReader.readLine() instead break on every lone
 * CR, which silently strips carriage returns inside streamed JSON strings,
 * the reported write_file CRLF→LF corruption.
 */
class SseLineReaderTest {

    private fun linesOf(body: String): List<String> {
        val source = Buffer().writeUtf8(body)
        val out = mutableListOf<String>()
        while (true) {
            val line = ProviderFactory.readSseLine(source) ?: break
            out += line
        }
        return out
    }

    @Test
    fun `plain LF framing`() {
        assertEquals(
            listOf("data: a", "data: b"),
            linesOf("data: a\ndata: b\n"),
        )
    }

    @Test
    fun `CRLF framing strips the CR terminator`() {
        assertEquals(listOf("data: x"), linesOf("data: x\r\n"))
    }

    @Test
    fun `interior lone CR survives - the write_file corruption case`() {
        // A nonconformant host emitting raw CR bytes inside a JSON string:
        // readUtf8Line() would split HERE (treating the lone CR as a line
        // break), tearing the JSON in half so both halves fail parsing and
        // the whole argument delta is silently dropped. The reader must
        // frame on LF only and keep the CR in the body.
        val payload = "data: {\"a\":\"line1\rline2\"}\n"
        assertEquals(1, linesOf(payload).size)
        assertEquals("data: {\"a\":\"line1\rline2\"}", linesOf(payload).single())
    }

    @Test
    fun `multiple lone CRs deep in the payload survive`() {
        val body = "data: x\ry\rz\n"
        assertEquals(listOf("data: x\ry\rz"), linesOf(body))
    }

    @Test
    fun `final unterminated line is returned`() {
        assertEquals(listOf("data: tail"), linesOf("data: tail"))
    }

    @Test
    fun `empty stream yields nothing`() {
        assertNull(ProviderFactory.readSseLine(Buffer()))
    }

    @Test
    fun `multi-byte UTF-8 survives across reads`() {
        // 'é' = 0xC3 0xA9 must not be split internally.
        assertEquals("café", linesOf("café\n").single())
    }

    @Test
    fun `blank SSE keep-alive lines are preserved as empty strings`() {
        assertEquals(listOf("", "data: x"), linesOf("\ndata: x\n"))
    }
}
