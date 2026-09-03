package com.androidharness.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream

class PdfTextExtractorTest {

    /**
     * Builds a minimal, synthetic valid PDF with uncompressed or FlateDecode content streams
     * containing BT ... ET text operators.
     */
    private fun buildSimplePdf(content: String, compress: Boolean = false): ByteArray {
        val streamBytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        val finalStream = if (compress) {
            val baos = ByteArrayOutputStream()
            val deflater = DeflaterOutputStream(baos)
            deflater.write(streamBytes)
            deflater.close()
            baos.toByteArray()
        } else {
            streamBytes
        }

        val filterHeader = if (compress) "/Filter /FlateDecode\n" else ""

        val pdf = """
            |%PDF-1.4
            |1 0 obj
            |<< /Type /Catalog /Pages 2 0 R >>
            |endobj
            |2 0 obj
            |<< /Type /Pages /Kids [3 0 R] /Count 1 >>
            |endobj
            |3 0 obj
            |<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>
            |endobj
            |4 0 obj
            |<< /Length ${finalStream.size}
            |$filterHeader>>
            |stream
        """.trimMargin("|").trimStart() + "\n"

        val baos = ByteArrayOutputStream()
        baos.write(pdf.toByteArray(StandardCharsets.US_ASCII))
        baos.write(finalStream)
        baos.write("\nendstream\nendobj\nxref\n0 5\ntrailer\n<< /Root 1 0 R >>\n%%EOF".toByteArray(StandardCharsets.US_ASCII))
        return baos.toByteArray()
    }

    @Test
    fun `rejects non-pdf data`() {
        assertNull(PdfTextExtractor.extract(byteArrayOf()))
        assertNull(PdfTextExtractor.extract("Hello World not a pdf".toByteArray()))
    }

    @Test
    fun `extracts text from uncompressed content stream with Tj operator`() {
        val stream = """
            BT
            /F1 12 Tf
            (Hello from PDF) Tj
            ET
        """.trimIndent()

        val pdfBytes = buildSimplePdf(stream, compress = false)
        val result = PdfTextExtractor.extract(pdfBytes)

        assertNotNull(result)
        assertTrue(result!!.text.contains("Hello from PDF"))
        assertTrue(result.text.contains("--- Page 1 ---"))
    }

    @Test
    fun `test parseContentStream directly`() {
        val stream = "BT /F1 14 Tf [(First part) -200 (second part)] TJ ET"
        val text = PdfTextExtractor.parseContentStream(stream.toByteArray(StandardCharsets.ISO_8859_1))
        assertEquals("First part second part", text)
    }

    @Test
    fun `extracts text from flate-compressed stream with TJ array operator`() {
        val stream = """
            BT
            /F1 14 Tf
            [(First part) -200 (second part)] TJ
            ET
        """.trimIndent()

        val pdfBytes = buildSimplePdf(stream, compress = true)
        val result = PdfTextExtractor.extract(pdfBytes)

        assertNotNull(result)
        assertTrue(result!!.text.contains("First part"))
        assertTrue(result.text.contains("second part"))
    }

    @Test
    fun `parses escaped characters and newlines correctly`() {
        val stream = """
            BT
            (Line 1\nLine 2 \(parentheses\) \\backslash) Tj
            ET
        """.trimIndent()

        val pdfBytes = buildSimplePdf(stream, compress = false)
        val result = PdfTextExtractor.extract(pdfBytes)

        assertNotNull(result)
        assertTrue(result!!.text.contains("Line 1"))
        assertTrue(result.text.contains("Line 2"))
        assertTrue(result.text.contains("(parentheses)"))
        assertTrue(result.text.contains("\\backslash"))
    }

    @Test
    fun `handles hex encoded strings in Tj`() {
        // Hex "416e64726f6964" -> "Android"
        val stream = """
            BT
            <416e64726f6964> Tj
            ET
        """.trimIndent()

        val pdfBytes = buildSimplePdf(stream, compress = false)
        val result = PdfTextExtractor.extract(pdfBytes)

        assertNotNull(result)
        assertTrue(result!!.text.contains("Android"))
    }

    @Test
    fun `parses multi-page pdf content streams`() {
        val page1 = "BT (First page content) Tj ET"
        val page2 = "BT (Second page content) Tj ET"

        val pdf = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Length ${page1.length} >>
            stream
            $page1
            endstream
            endobj
            3 0 obj
            << /Length ${page2.length} >>
            stream
            $page2
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(StandardCharsets.US_ASCII)

        val result = PdfTextExtractor.extract(pdf)
        assertNotNull(result)
        assertTrue(result!!.text.contains("--- Page 1 ---"))
        assertTrue(result.text.contains("First page content"))
        assertTrue(result.text.contains("--- Page 2 ---"))
        assertTrue(result.text.contains("Second page content"))
        assertEquals(2, result.pageCount)
    }
}
