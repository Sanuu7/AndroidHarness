package com.androidharness.app.ui.files

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Lossless round-trip codec between file bytes and editor text.
 *
 * Detects BOM charsets (UTF-8/UTF-16LE/UTF-16BE), strict-decodes UTF-8
 * otherwise, and records the dominant line ending so saving reproduces the
 * original byte shape instead of silently normalizing the user's file.
 */
object EditorFileCodec {

    data class Decoded(
        val text: String,
        /** Charset (+BOM) detected on load; reused verbatim on save. */
        val charset: Charset,
        val bom: Boolean,
        /** Dominant EOL in the source ("\n" fallback); lines join back with it. */
        val eol: String,
        /** Source had no final newline yet (informational badge only). */
        val missingTrailingNewline: Boolean,
    )

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Returns null when bytes look binary (NUL byte present) or are not decodable. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size >= 2 && hasPrefix(bytes, UTF16LE_BOM)) {
            val text = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            return finish(text, Charsets.UTF_16LE, true)
        }
        if (bytes.size >= 2 && hasPrefix(bytes, UTF16BE_BOM)) {
            val text = String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            return finish(text, Charsets.UTF_16BE, true)
        }
        if (hasPrefix(bytes, UTF8_BOM)) {
            val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            return finish(text, Charsets.UTF_8, true)
        }
        // No BOM: reject binaries early, then strict-UTF-8 decode.
        if (bytes.contains(0.toByte())) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return finish(text, Charsets.UTF_8, false)
    }

    private fun finish(text: String, charset: Charset, bom: Boolean): Decoded? {
        if (text.contains('\u0000')) return null
        val crlf = countOccurrences(text, "\r\n")
        val lf = text.count { it == '\n' } - crlf
        val eol = if (crlf > lf && crlf > 0) "\r\n" else "\n"
        return Decoded(
            text = normalizeEol(text),
            charset = charset,
            bom = bom,
            eol = eol,
            missingTrailingNewline = text.isNotEmpty() && !text.endsWith("\n"),
        )
    }

    /** Encodes [text] (editor-normalized LF) back to the file's original shape. */
    fun encode(decoded: Decoded, text: String): ByteArray {
        val shaped = if (decoded.eol == "\r\n") text.replace("\n", "\r\n") else text
        val body = shaped.toByteArray(decoded.charset)
        if (!decoded.bom) return body
        val bomBytes = when (decoded.charset) {
            Charsets.UTF_16LE -> UTF16LE_BOM
            Charsets.UTF_16BE -> UTF16BE_BOM
            else -> UTF8_BOM
        }
        return bomBytes + body
    }

    private fun normalizeEol(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = text.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun hasPrefix(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}
