package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPortProbeTest {

    @Test
    fun `extractPortsFromText extracts localhost and loopback ports`() {
        val output = """
            VITE v5.4.2  ready in 320 ms
            ➜  Local:   http://localhost:5173/
            ➜  Network: http://192.168.1.100:5173/
            ➜  press h + enter to show help
            Other server on 127.0.0.1:8080 or 0.0.0.0:3000
        """.trimIndent()

        val ports = LocalPortProbe.extractPortsFromText(output)
        assertEquals(listOf(5173, 8080, 3000), ports)
    }

    @Test
    fun `isLocalhostUrl identifies local web addresses`() {
        assertTrue(LocalPortProbe.isLocalhostUrl("http://localhost:3000"))
        assertTrue(LocalPortProbe.isLocalhostUrl("https://localhost:8080/index.html"))
        assertTrue(LocalPortProbe.isLocalhostUrl("http://127.0.0.1:5173"))
        assertTrue(LocalPortProbe.isLocalhostUrl("localhost:4321"))
        assertTrue(LocalPortProbe.isLocalhostUrl("127.0.0.1:8000"))

        assertFalse(LocalPortProbe.isLocalhostUrl("https://google.com"))
        assertFalse(LocalPortProbe.isLocalhostUrl("http://example.com:8080"))
    }

    @Test
    fun `portOfUrl extracts explicit and default ports`() {
        assertEquals(3000, LocalPortProbe.portOfUrl("http://localhost:3000"))
        assertEquals(5173, LocalPortProbe.portOfUrl("http://localhost:5173/dashboard"))
        assertEquals(8080, LocalPortProbe.portOfUrl("127.0.0.1:8080"))
        assertEquals(80, LocalPortProbe.portOfUrl("http://localhost"))
        assertEquals(443, LocalPortProbe.portOfUrl("https://localhost/app"))
        assertEquals(null, LocalPortProbe.portOfUrl("https://example.com/page"))
    }

    @Test
    fun `normalizeLocalUrl formats ports and urls correctly`() {
        assertEquals("http://localhost:3000", LocalPortProbe.normalizeLocalUrl("3000"))
        assertEquals("http://localhost:5173", LocalPortProbe.normalizeLocalUrl("localhost:5173"))
        assertEquals("http://127.0.0.1:8000", LocalPortProbe.normalizeLocalUrl("127.0.0.1:8000"))
        assertEquals("http://localhost:3000/app", LocalPortProbe.normalizeLocalUrl("http://localhost:3000/app"))
    }
}
