package com.androidharness.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class EditorFileCodecTest {

    @Test
    fun `plain utf8 roundtrip preserves content`() {
        val text = "line one\nline two\n"
        val dec = EditorFileCodec.decode(text.toByteArray(StandardCharsets.UTF_8))!!
        assertEquals(text, dec.text)
        assertFalse(dec.bom)
        assertEquals("\n", dec.eol)
        val back = String(EditorFileCodec.encode(dec, dec.text), StandardCharsets.UTF_8)
        assertEquals(text, back)
    }

    @Test
    fun `crlf files restore crlf on save`() {
        val raw = "a\r\nb\r\nc".toByteArray(StandardCharsets.UTF_8)
        val dec = EditorFileCodec.decode(raw)!!
        // Internal view normalizes to LF for the editor.
        assertEquals("a\nb\nc", dec.text)
        assertEquals("\r\n", dec.eol)
        assertArrayEquals(raw, EditorFileCodec.encode(dec, dec.text))
    }

    @Test
    fun `utf8 bom survives save`() {
        val raw = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "hi\n".toByteArray(StandardCharsets.UTF_8)
        val dec = EditorFileCodec.decode(raw)!!
        assertTrue(dec.bom)
        assertTrue(dec.text.startsWith("hi"))
        val back = EditorFileCodec.encode(dec, dec.text)
        assertArrayEquals(raw, back)
    }

    @Test
    fun `utf16le bom roundtrip`() {
        val raw = ("héllo\n").toByteArray(StandardCharsets.UTF_16LE).let { b ->
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + b
        }
        val dec = EditorFileCodec.decode(raw)!!
        assertEquals("héllo\n", dec.text)
        assertArrayEquals(raw, EditorFileCodec.encode(dec, dec.text))
    }

    @Test
    fun `binary data rejected`() {
        assertNull(
            EditorFileCodec.decode(
                byteArrayOf(0x50, 0x4B, 0x00, 0x00, 0x1F),
            ),
        )
    }

    private fun assertArrayEquals(a: ByteArray, b: ByteArray) =
        assertTrue("byte arrays differ", a.contentEquals(b))
}
