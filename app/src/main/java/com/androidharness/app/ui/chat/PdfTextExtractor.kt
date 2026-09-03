package com.androidharness.app.ui.chat

import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/**
 * Extracts plain text from PDF documents for inline attachment in the agent chat.
 *
 * Uses Android 15+ (API 35+) native [android.graphics.pdf.PdfRenderer] text extraction when
 * available on device. Falls back to a pure-Kotlin content-stream parser on older Android
 * versions (or JVM unit test environments) that decompresses Flate streams and parses PDF text
 * operators (BT, ET, Tj, TJ, ', ").
 */
object PdfTextExtractor {

    const val MAX_CHARS = FileAttachments.INLINE_CHAR_LIMIT // 32,000 chars
    const val MAX_PAGES = 30

    data class ExtractionResult(
        val text: String,
        val pageCount: Int,
    )

    /**
     * Extracts text from [bytes].
     * Returns null if no meaningful text could be extracted (e.g. scanned image-only PDF).
     */
    fun extract(bytes: ByteArray): ExtractionResult? {
        if (bytes.size < 8) return null
        val header = String(bytes, 0, minOf(bytes.size, 1024), StandardCharsets.US_ASCII)
        if (!header.contains("%PDF-")) return null

        // 1. On Android 15+ (API 35+) device runtime (Dalvik / ART), try native PdfRenderer
        val isAndroidRuntime = System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
        if (isAndroidRuntime && Build.VERSION.SDK_INT >= 35) {
            val nativeResult = runCatching { extractWithPdfRenderer(bytes) }.getOrNull()
            if (nativeResult != null && nativeResult.text.isNotBlank()) {
                return nativeResult
            }
        }

        // 2. Pure stream parser fallback (works on all Android versions and JVM unit tests)
        val streamResult = runCatching { extractFromStreams(bytes) }.getOrNull()
        if (streamResult != null && streamResult.text.isNotBlank()) {
            return streamResult
        }

        return null
    }

    fun extract(stream: InputStream): ExtractionResult? {
        val bytes = stream.use { it.readBytes() }
        return extract(bytes)
    }

    // --- Android 15+ native PdfRenderer -------------------------------------

    private fun extractWithPdfRenderer(bytes: ByteArray): ExtractionResult? {
        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("pdf_extract_", ".pdf")
            tempFile.writeBytes(bytes)

            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { descriptor ->
                val renderer = android.graphics.pdf.PdfRenderer(descriptor)
                renderer.use { pdfRenderer ->
                    val totalPages = pdfRenderer.pageCount
                    val pagesToProcess = minOf(totalPages, MAX_PAGES)
                    val sb = StringBuilder()

                    for (pageIdx in 0 until pagesToProcess) {
                        val page = pdfRenderer.openPage(pageIdx)
                        try {
                            val textContents = page.textContents
                            val pageText = textContents.joinToString("") { it.text }.trim()
                            if (pageText.isNotEmpty()) {
                                if (sb.isNotEmpty()) sb.append("\n\n")
                                sb.append("--- Page ${pageIdx + 1} ---\n")
                                sb.append(pageText)
                            }
                        } finally {
                            page.close()
                        }
                        if (sb.length >= MAX_CHARS) break
                    }

                    val finalString = sb.toString().trim()
                    return if (finalString.isNotBlank()) {
                        ExtractionResult(finalString.take(MAX_CHARS), totalPages)
                    } else null
                }
            }
        } finally {
            tempFile?.delete()
        }
    }

    // --- Pure Kotlin Content Stream Parser ----------------------------------

    internal fun extractFromStreams(bytes: ByteArray): ExtractionResult? {
        val streams = findStreams(bytes)
        if (streams.isEmpty()) return null

        val sb = StringBuilder()
        var pageNum = 1

        for (streamBytes in streams) {
            val text = parseContentStream(streamBytes)
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("--- Page $pageNum ---\n")
                sb.append(text)
                pageNum++
            }
            if (sb.length >= MAX_CHARS || pageNum > MAX_PAGES) break
        }

        val finalString = sb.toString().trim()
        return if (finalString.isNotBlank()) {
            ExtractionResult(finalString.take(MAX_CHARS), maxOf(1, pageNum - 1))
        } else null
    }

    /**
     * Scans the raw PDF bytes for stream ... endstream blocks and decompresses
     * FlateDecode streams.
     */
    private fun findStreams(data: ByteArray): List<ByteArray> {
        val results = mutableListOf<ByteArray>()
        var index = 0
        val streamTag = "stream".toByteArray(StandardCharsets.US_ASCII)
        val endstreamTag = "endstream".toByteArray(StandardCharsets.US_ASCII)

        while (index < data.size) {
            val streamStart = indexOf(data, streamTag, index)
            if (streamStart == -1) break

            // Skip "stream\r\n" or "stream\n"
            var bodyStart = streamStart + streamTag.size
            if (bodyStart < data.size && data[bodyStart] == '\r'.code.toByte()) bodyStart++
            if (bodyStart < data.size && data[bodyStart] == '\n'.code.toByte()) bodyStart++

            val streamEnd = indexOf(data, endstreamTag, bodyStart)
            if (streamEnd == -1) break

            // Determine if the preceding dict specifies /FlateDecode and /Length
            val dictHeaderStart = maxOf(0, streamStart - 400)
            val headerText = String(data, dictHeaderStart, streamStart - dictHeaderStart, StandardCharsets.ISO_8859_1)
            val isFlate = headerText.contains("/FlateDecode")

            val lengthMatch = Regex("/Length\\s+(\\d+)").find(headerText)
            val explicitLength = lengthMatch?.groupValues?.get(1)?.toIntOrNull()

            var actualEnd = streamEnd
            if (explicitLength != null && bodyStart + explicitLength <= data.size) {
                actualEnd = bodyStart + explicitLength
            } else {
                // Trim trailing \r\n or \n before endstream
                if (actualEnd > bodyStart && data[actualEnd - 1] == '\n'.code.toByte()) actualEnd--
                if (actualEnd > bodyStart && data[actualEnd - 1] == '\r'.code.toByte()) actualEnd--
            }

            val rawStream = data.copyOfRange(bodyStart, actualEnd)
            val decompressed = if (isFlate) {
                inflateSafely(rawStream)
            } else {
                rawStream
            }

            if (decompressed != null && decompressed.isNotEmpty()) {
                results.add(decompressed)
            }

            index = streamEnd + endstreamTag.size
        }
        return results
    }

    private fun inflateSafely(deflated: ByteArray): ByteArray? {
        // 1. Try standard zlib wrapped deflate
        val zlib = runCatching {
            val inflater = java.util.zip.Inflater(false)
            val bais = ByteArrayInputStream(deflated)
            InflaterInputStream(bais, inflater).use { it.readBytesLimited(512_000) }
        }.getOrNull()
        if (zlib != null && zlib.isNotEmpty()) return zlib

        // 2. Try raw deflate (nowrap)
        return runCatching {
            val inflater = java.util.zip.Inflater(true)
            val bais = ByteArrayInputStream(deflated)
            InflaterInputStream(bais, inflater).use { it.readBytesLimited(512_000) }
        }.getOrNull()
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        var n: Int
        while (read(buffer).also { n = it } != -1) {
            out.write(buffer, 0, n)
            total += n
            if (total >= maxBytes) break
        }
        return out.toByteArray()
    }

    /**
     * Parses PDF operators in a content stream to extract text.
     * Looks for BT (Begin Text) ... ET (End Text) and operators:
     * - (string) Tj
     * - [(s1) kerning (s2)] TJ
     * - (string) '
     * - (string) "
     */
    internal fun parseContentStream(streamBytes: ByteArray): String {
        val streamText = String(streamBytes, StandardCharsets.ISO_8859_1)
        val sb = StringBuilder()

        var inTextObject = false
        var i = 0
        val len = streamText.length

        while (i < len) {
            // Check for BT / ET markers
            if (!inTextObject) {
                if (streamText.startsWith("BT", i) && isDelimiter(streamText.getOrNull(i - 1)) && isDelimiter(streamText.getOrNull(i + 2))) {
                    inTextObject = true
                    i += 2
                    continue
                }
            } else {
                if (streamText.startsWith("ET", i) && isDelimiter(streamText.getOrNull(i - 1)) && isDelimiter(streamText.getOrNull(i + 2))) {
                    inTextObject = false
                    sb.append('\n')
                    i += 2
                    continue
                }
            }

            if (!inTextObject) {
                i++
                continue
            }

            // Inside BT ... ET:
            val c = streamText[i]

            // String literal: (...)
            if (c == '(') {
                val (str, nextIdx) = readPdfString(streamText, i)
                i = nextIdx
                // Peek ahead for operator: Tj, ', ", or TJ
                val op = peekOperator(streamText, i)
                if (op == "Tj" || op == "'" || op == "\"") {
                    sb.append(str)
                    if (op == "'" || op == "\"") sb.append('\n')
                }
                continue
            }

            // Hex string literal: <...>
            if (c == '<' && i + 1 < len && streamText[i + 1] != '<') {
                val (str, nextIdx) = readPdfHexString(streamText, i)
                i = nextIdx
                val op = peekOperator(streamText, i)
                if (op == "Tj" || op == "'" || op == "\"") {
                    sb.append(str)
                    if (op == "'" || op == "\"") sb.append('\n')
                }
                continue
            }

            // Array operator for TJ: [...] TJ
            if (c == '[') {
                val (arrText, nextIdx) = readPdfArray(streamText, i)
                i = nextIdx
                val op = peekOperator(streamText, i)
                if (op == "TJ") {
                    sb.append(arrText)
                }
                continue
            }

            // Newline operators: T*, etc.
            if (streamText.startsWith("T*", i) && isDelimiter(streamText.getOrNull(i - 1)) && isDelimiter(streamText.getOrNull(i + 2))) {
                sb.append('\n')
                i += 2
                continue
            }

            i++
        }

        return cleanExtractedText(sb.toString())
    }

    private fun readPdfString(text: String, start: Int): Pair<String, Int> {
        var depth = 1
        var idx = start + 1
        val sb = java.lang.StringBuilder()
        while (idx < text.length && depth > 0) {
            val ch = text[idx]
            if (ch == '\\' && idx + 1 < text.length) {
                val next = text[idx + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '(', ')', '\\' -> sb.append(next)
                    in '0'..'7' -> {
                        // Octal escape
                        val octal = text.substring(idx + 1, minOf(idx + 4, text.length)).takeWhile { it in '0'..'7' }
                        val code = octal.toIntOrNull(8) ?: 0
                        sb.append(code.toChar())
                        idx += octal.length
                        idx++
                        continue
                    }
                    else -> sb.append(next)
                }
                idx += 2
                continue
            } else if (ch == '(') {
                depth++
                sb.append('(')
            } else if (ch == ')') {
                depth--
                if (depth > 0) sb.append(')')
            } else {
                sb.append(ch)
            }
            idx++
        }
        return sb.toString() to idx
    }

    private fun readPdfHexString(text: String, start: Int): Pair<String, Int> {
        var idx = start + 1
        val hex = java.lang.StringBuilder()
        while (idx < text.length && text[idx] != '>') {
            val ch = text[idx]
            if (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') {
                hex.append(ch)
            }
            idx++
        }
        if (idx < text.length && text[idx] == '>') idx++

        val hexStr = hex.toString()
        val decoded = java.lang.StringBuilder()
        var h = 0
        while (h < hexStr.length) {
            val b = if (h + 1 < hexStr.length) hexStr.substring(h, h + 2) else "${hexStr[h]}0"
            val charCode = b.toIntOrNull(16) ?: 0
            if (charCode in 32..126 || charCode == 10 || charCode == 13 || charCode == 9) {
                decoded.append(charCode.toChar())
            }
            h += 2
        }
        return decoded.toString() to idx
    }

    private fun readPdfArray(text: String, start: Int): Pair<String, Int> {
        var idx = start + 1
        val sb = java.lang.StringBuilder()
        while (idx < text.length && text[idx] != ']') {
            val ch = text[idx]
            if (ch == '(') {
                val (str, next) = readPdfString(text, idx)
                sb.append(str)
                idx = next
            } else if (ch == '<' && idx + 1 < text.length && text[idx + 1] != '<') {
                val (str, next) = readPdfHexString(text, idx)
                sb.append(str)
                idx = next
            } else if (ch == '-' || (ch in '0'..'9')) {
                // Negative kerning number in TJ: if large enough (e.g. < -150), insert space
                val numStr = java.lang.StringBuilder()
                if (ch == '-') {
                    numStr.append('-')
                    idx++
                }
                while (idx < text.length && (text[idx] in '0'..'9' || text[idx] == '.')) {
                    numStr.append(text[idx])
                    idx++
                }
                val shift = numStr.toString().toDoubleOrNull() ?: 0.0
                if (shift < -150.0 && sb.isNotEmpty() && sb.last() != ' ') {
                    sb.append(' ')
                }
            } else {
                idx++
            }
        }
        if (idx < text.length && text[idx] == ']') idx++
        return sb.toString() to idx
    }

    private fun peekOperator(text: String, start: Int): String {
        var idx = start
        while (idx < text.length && text[idx].isWhitespace()) idx++
        val op = java.lang.StringBuilder()
        while (idx < text.length && !text[idx].isWhitespace() && text[idx] != '(' && text[idx] != '[' && text[idx] != '<') {
            op.append(text[idx])
            idx++
        }
        return op.toString()
    }

    private fun isDelimiter(c: Char?): Boolean {
        if (c == null) return true
        return c.isWhitespace() || c == '/' || c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
    }

    private fun indexOf(data: ByteArray, target: ByteArray, start: Int): Int {
        if (target.isEmpty()) return 0
        var i = start
        val limit = data.size - target.size
        while (i <= limit) {
            var match = true
            for (j in target.indices) {
                if (data[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }

    private fun cleanExtractedText(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
