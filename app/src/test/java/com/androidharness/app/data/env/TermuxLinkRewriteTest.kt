package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxLinkRewriteTest {

    private val USR = "/data/data/com.termux/files/usr/"

    /** Lexically resolves [fromDir]/[target] the way the kernel resolves symlinks. */
    private fun resolve(fromDir: String, target: String): String {
        val stack = ArrayDeque(fromDir.trim('/').split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> stack.removeLastOrNull()
                else -> stack.addLast(seg)
            }
        }
        return "/" + stack.joinToString("/")
    }

    @Test
    fun `bzcmp absolute link resolves inside our prefix`() {
        // Real bzip2 package content: bin/bzcmp -> /data/data/com.termux/files/usr/bin/bzdiff
        val rewritten = TermuxLinkRewrite.relativeTarget(USR + "bin/bzdiff", "bin/bzcmp")!!
        assertEquals("/prefix/bin/bzdiff", resolve("/prefix/bin", rewritten))
    }

    @Test
    fun `bzless absolute link resolves inside our prefix`() {
        val rewritten = TermuxLinkRewrite.relativeTarget(USR + "bin/bzmore", "bin/bzless")!!
        assertEquals("/prefix/bin/bzmore", resolve("/prefix/bin", rewritten))
    }

    @Test
    fun `deep absolute link climbs one level per segment`() {
        // share/man/man1/foo.1.gz lives 3 directories deep
        val out = TermuxLinkRewrite.relativeTarget(USR + "lib/node_modules/npm/x", "share/man/man1/foo.1.gz")!!
        assertEquals("../../../lib/node_modules/npm/x", out)
        assertEquals("/prefix/lib/node_modules/npm/x", resolve("/prefix/share/man/man1", out))
    }

    @Test
    fun `prefix-root link resolves without climbing`() {
        val out = TermuxLinkRewrite.relativeTarget(USR + "bin/bzdiff", "bzdiff")!!
        assertEquals("bin/bzdiff", out)
        assertEquals("/prefix/bin/bzdiff", resolve("/prefix", out))
    }

    @Test
    fun `relative links pass through unchanged`() {
        assertNull(TermuxLinkRewrite.relativeTarget("../lib/node_modules/npm/bin/npm-cli.js", "bin/npm"))
    }

    @Test
    fun `non-termux absolute links pass through unchanged`() {
        assertNull(TermuxLinkRewrite.relativeTarget("/system/bin/sh", "bin/sh"))
    }
}
