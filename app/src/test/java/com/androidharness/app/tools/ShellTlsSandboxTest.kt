package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the shell-tier build/run fixes:
 *  - Bug 1: TLS trust (CA bundle + SSL_CERT_FILE family exported by default)
 *  - Bug 2/3: designated exec-capable scratch dir + deliberate sandbox carve-out
 *  - Bug 4: tar extraction retargeted off exec-hostile storage
 */
class ShellTlsSandboxTest {

    // ------------------------------------------------------------------
    // Bug 1: TLS env vars
    // ------------------------------------------------------------------

    @Test
    fun `tls env vars carry every client stack`() {
        val vars = NetTls.envVars("/data/local/tmp/androidharness-scratch/etc/tls/cacert.pem")
        assertEquals("/data/local/tmp/androidharness-scratch/etc/tls/cacert.pem", vars["SSL_CERT_FILE"])
        assertEquals(vars["SSL_CERT_FILE"], vars["CURL_CA_BUNDLE"])
        assertEquals(vars["SSL_CERT_FILE"], vars["REQUESTS_CA_BUNDLE"])
        assertEquals(vars["SSL_CERT_FILE"], vars["GIT_SSL_CAINFO"])
        assertEquals(vars["SSL_CERT_FILE"], vars["NODE_EXTRA_CA_CERTS"])
    }

    @Test
    fun `ca bundle asset ships and parses as pem with many anchors`() {
        // Unit tests run with the module dir as cwd: read the shipped asset
        // straight from the source tree, falling back to the classpath.
        val assetFile = File("src/main/assets/net/mozilla-ca-bundle.pem")
        val text = if (assetFile.isFile) {
            assetFile.readText()
        } else {
            javaClass.classLoader?.getResourceAsStream("assets/net/mozilla-ca-bundle.pem")
                ?.bufferedReader()?.use { it.readText() }
                ?: throw AssertionError("missing asset net/mozilla-ca-bundle.pem")
        }
        assertTrue("bundle should be PEM", text.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue(
            "bundle should carry a real store of anchors",
            text.split("-----BEGIN CERTIFICATE-----").size > 50,
        )
    }

    @Test
    fun `ensureInstalled is idempotent and restores deleted bundle`() {
        val payload = "-----BEGIN CERTIFICATE-----\n" + "Zm9vYmFy\n".repeat(256) +
            "-----END CERTIFICATE-----\n"
        val prefix = kotlin.io.path.createTempDirectory("nettls-prefix").toFile()
        try {
            NetTls.ensureInstalled(prefix) { payload.byteInputStream() }
            val target = File(prefix, NetTls.BUNDLE_RELATIVE_PATH)
            assertTrue("bundle materialized", target.isFile)
            assertEquals(payload.length.toLong(), target.length())

            target.delete()
            NetTls.ensureInstalled(prefix) { payload.byteInputStream() }
            assertTrue("deleted bundle re-materialized", target.isFile)

            target.writeText("corrupted")
            NetTls.ensureInstalled(prefix) { payload.byteInputStream() }
            assertEquals("stale/corrupt bundle refreshed", payload, target.readText())
        } finally {
            prefix.deleteRecursively()
        }
    }

    @Test
    fun `ensureInstalled swallows missing-asset failures without throwing`() {
        val prefix = kotlin.io.path.createTempDirectory("nettls-prefix-missing").toFile()
        try {
            NetTls.ensureInstalled(prefix) {
                throw java.io.FileNotFoundException("no such asset")
            }
            assertFalse(File(prefix, NetTls.BUNDLE_RELATIVE_PATH).exists())
        } finally {
            prefix.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Bug 3: sandbox carve-out — allowed inside the two scratch roots
    // ------------------------------------------------------------------

    private fun sharedStorageWorkspace(): File =
        File("/storage/emulated/0/Android/data/com.androidharness/files/workspace")

    @Test
    fun `scratch carve-out allows read write cd and symlink targets in SCRATCH_TMP`() {
        val root = sharedStorageWorkspace()
        val s = ShellPolicy.SCRATCH_TMP
        for (cmd in listOf(
            "mkdir -p $s/jdk-21",
            "tar -xzf jdk.tar.gz -C $s/jdk-21",
            "cp jdk.tar.gz $s/jdk.tar.gz",
            "echo hi > $s/probe.txt",
            "$s/jdk-21/bin/java -version",
            "cd $s && ./gradlew assembleDebug",
            "ln -sf $s/jdk-21/bin/java $s/jdk-21/bin/javac-link",
        )) {
            assertNull("$cmd must be allowed by the carve-out", ShellPolicy.denyReason(cmd, root, root))
        }
    }

    @Test
    fun `scratch carve-out works for app-private mirror and canonical data forms`() {
        val root = sharedStorageWorkspace()
        for (base in listOf(ShellPolicy.SCRATCH_APPDATA, ShellPolicy.SCRATCH_APPDATA_DEBUG)) {
            assertNull(
                ShellPolicy.denyReason("tar -xf t.tar.gz -C $base/jdk", root, root),
            )
            assertNull(
                ShellPolicy.denyReason(
                    "tar -xf t.tar.gz -C /data/user/0/com.androidharness.debug/files/.harness-scratch/jdk",
                    root,
                    root,
                ),
            )
        }
    }

    @Test
    fun `sandbox still blocks everything outside workspace and scratch`() {
        val root = sharedStorageWorkspace()
        for (cmd in listOf(
            "cat /etc/passwd",
            "echo INJECT > ../escape.txt",
            "echo x > /storage/emulated/0/Download/outside.txt",
            "cd ..",
            "cat /storage/emulated/0/../probe.txt",
            "cat \"\$PWD/../rel_escape2.txt\"",
            "python3 -c \"import os; print(os.listdir('/storage/emulated/0'))\"",
            "eval \"cat /etc/shadow\"",
            "ln -sf /etc/passwd link",
            "D=/storage/emulated/0/Download; echo x > \"\$D/hax\"",
            "mkdir -p /storage/emulated/0/Android/data/com.other.app/files/x",
            "touch /data/local/tmp/unrelated-toolchain-file",
        )) {
            assertNotNull("$cmd must stay blocked", ShellPolicy.denyReason(cmd, root, root))
        }
    }

    @Test
    fun `near-miss paths just outside the carve-out stay blocked`() {
        val root = sharedStorageWorkspace()
        for (cmd in listOf(
            "cat /data/local/tmp/androidharness-scratcher/x",
            "rm -rf /data/local/tmp/androidharness-scratch-twin",
            "echo x > /storage/emulated/0/Android/data/com.androidharness/filex/hax.txt",
        )) {
            assertNotNull("$cmd must stay blocked", ShellPolicy.denyReason(cmd, root, root))
        }
    }

    // ------------------------------------------------------------------
    // Bug 4: tar extraction routing to the exec-capable scratch dir
    // ------------------------------------------------------------------

    @Test
    fun `workspace tar extraction with dash-C is retargeted into scratch`() {
        val cwd = sharedStorageWorkspace()
        val cmd = "curl -fsSL -o jdk.tar.gz https://example.invalid/jdk.tar.gz && tar -xzf jdk.tar.gz -C tools/jdk"
        val (out, note) = ExecScratchRouting.prepare(cmd, cwd)
        assertTrue(out.startsWith(cmd.substringBefore("&&")))
        assertTrue(out.contains("-C"))
        assertTrue(out.contains(ShellPolicy.SCRATCH_TMP) || out.contains(ShellPolicy.SCRATCH_APPDATA))
        assertFalse(out.contains("tools/jdk"))
        assertNotNull(note)
        assertTrue(note!!.contains("exec-capable scratch dir"))
        assertTrue(note.contains("JAVA_HOME="))
    }

    @Test
    fun `bare workspace tar extraction gets an explicit scratch -C appended`() {
        val cwd = File("/storage/emulated/0/Download/myproj")
        val cmd = "tar -xzf gradle-9.1-bin.tar.gz"
        val (out, note) = ExecScratchRouting.prepare(cmd, cwd)
        assertNotEquals(cmd, out)
        assertTrue(out.contains("-C \"${ShellPolicy.SCRATCH_TMP}/gradle-9.1-bin\"") ||
            out.contains("-C \"${ShellPolicy.SCRATCH_APPDATA}/gradle-9.1-bin\""))
        assertNotNull(note)
    }

    @Test
    fun `extraction outside exec-hostile storage is untouched`() {
        val cwd = File("/data/data/com.androidharness/files/workspace")
        val cmd = "tar -xzf sdk.tar.gz -C toolchains"
        val (out, note) = ExecScratchRouting.prepare(cmd, cwd)
        assertEquals(cmd, out)
        assertNull(note)
    }

    @Test
    fun `explicit extraction into scratch already is left alone`() {
        val cwd = sharedStorageWorkspace()
        val cmd = "tar -xzf jdk.tar.gz -C ${ShellPolicy.SCRATCH_TMP}/jdk"
        val (out, note) = ExecScratchRouting.prepare(cmd, cwd)
        assertEquals(cmd, out)
        assertNull(note)
    }

    @Test
    fun `non-extract archive ops are untouched`() {
        val cwd = sharedStorageWorkspace()
        for (cmd in listOf(
            "tar -czf backup.tar.gz project/",
            "tar -tf jdk.tar.gz",
            "git init",
        )) {
            val (out, note) = ExecScratchRouting.prepare(cmd, cwd)
            assertEquals(cmd, out)
            assertNull(note)
        }
    }

    // ------------------------------------------------------------------
    // Scratch-root path identity (exact-match defense in depth)
    // ------------------------------------------------------------------

    @Test
    fun `near-miss path is not treated as scratch root`() {
        fun inside(path: String, root: String) =
            path == root || path.startsWith("$root/")
        val nearMiss = "/data/local/tmp/androidharness-scratcher"
        assertFalse(ShellPolicy.SCRATCH_ROOTS.any { inside(nearMiss, it) })
        // The real scratch root does contain its own children.
        assertTrue(ShellPolicy.SCRATCH_ROOTS.any { inside("${ShellPolicy.SCRATCH_TMP}/jdk", ShellPolicy.SCRATCH_TMP) })
    }
}
