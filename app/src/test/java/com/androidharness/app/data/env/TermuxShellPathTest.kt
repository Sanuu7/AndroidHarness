package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SHELL_PATH neutralization for the Termux git ELF: the compiled-in
 * /data/data/com.termux/files/usr/bin/sh must be replaced in place with
 * /system/bin/sh — same total length, NUL-padded — so git can spawn helpers
 * (`gh auth git-credential`), hooks and aliases via `sh -c`. Scripts are
 * off-limits: the shebang rewriter owns them.
 */
class TermuxShellPathTest {

    private val termuxShell = "/data/data/com.termux/files/usr/bin/sh"
    private val neutralShell = "/system/bin/sh"

    private fun elf(vararg chunks: String): ByteArray =
        (byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0) +
            chunks.joinToString("").toByteArray(Charsets.UTF_8))

    @Test
    fun `replacement is the same length and nul padded`() {
        val bytes = elf("PREFIX=\"", termuxShell, "\"\n")
        assertEquals(1, TermuxShellPath.patchBytes(bytes))
        val text = bytes.toString(Charsets.UTF_8)
        // The file length is untouched, the dead path is gone...
        assertFalse(text.contains(termuxShell))
        // ...and the replacement reads as a NUL-terminated shorter string.
        assertTrue(text.contains("PREFIX=\"$neutralShell"))
        val idx = text.indexOf(neutralShell)
        assertEquals(0.toByte(), bytes[idx + neutralShell.length]) // first padding byte is NUL
        assertEquals(termuxShell.length, neutralShell.length + 24)
    }

    @Test
    fun `every occurrence is patched`() {
        val bytes = elf("a", termuxShell, "b", termuxShell, "c")
        assertEquals(2, TermuxShellPath.patchBytes(bytes))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(termuxShell))
    }

    @Test
    fun `bytes without the termux shell path are untouched`() {
        val bytes = elf("SHELL_PATH=/system/bin/sh\u0000nothing to see")
        val before = bytes.copyOf()
        assertEquals(0, TermuxShellPath.patchBytes(bytes))
        assertTrue(before.contentEquals(bytes))
    }

    @Test
    fun `near misses are not patched`() {
        // Prefix shared but the tail differs (bin/env, files/home, ...).
        val bytes = elf(
            "/data/data/com.termux/files/usr/bin/env ",
            "/data/data/com.termux/files/usr/libexec",
        )
        assertEquals(0, TermuxShellPath.patchBytes(bytes))
    }

    @Test
    fun `elf files are patched in place`() {
        val f = File.createTempFile("git", "elf")
        try {
            f.writeBytes(elf("SHELL_PATH=", termuxShell, "\u0000"))
            assertEquals(1, TermuxShellPath.patch(f))
            assertFalse(f.readBytes().toString(Charsets.UTF_8).contains(termuxShell))
            // Idempotent: a repaired binary is left alone.
            assertEquals(0, TermuxShellPath.patch(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `scripts carrying the literal are not touched`() {
        val f = File.createTempFile("git-remote-helper", ".sh")
        try {
            f.writeText("#!$termuxShell\necho hi\n")
            assertEquals(0, TermuxShellPath.patch(f))
            assertTrue(f.readText().startsWith("#!$termuxShell"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `patch applies to git territory only`() {
        assertTrue(TermuxShellPath.appliesTo("bin/git"))
        assertTrue(TermuxShellPath.appliesTo("libexec/git-core/git-remote-https"))
        assertFalse(TermuxShellPath.appliesTo("bin/bash"))
        assertFalse(TermuxShellPath.appliesTo("lib/python3/site.py"))
    }
}
