package com.androidharness.app.tools

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.BgProcessStore
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.PkgMeta
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AuditRecoveryDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    private fun fixtureContext(root: File) = object : ContextWrapper(c.appContext) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDataDir() = root
        override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
    }

    private fun coreFixture(root: File) {
        val prefix = File(root, "files/linux")
        for (path in listOf("bin/bash", "bin/busybox", "bin/git", "lib/libtermux-exec-ld-preload.so", "etc/tls/cacert.pem")) {
            File(prefix, path).apply { parentFile!!.mkdirs(); writeText("fixture") }
        }
        File(prefix, ".harness-installed").writeText("bash\nbusybox\ngit\ntermux-exec\nca-certificates\njq\noniguruma")
    }

    @Test fun unknownPackagesAndIndexFailuresLeaveExistingEnvironmentReady() = runBlocking {
        val root = File(c.appContext.cacheDir, "audit-package-${System.nanoTime()}").apply { mkdirs() }
        try {
            val context = fixtureContext(root)
            coreFixture(root)
            val before = File(root, "files/linux/.harness-installed").readBytes()
            val env = LinuxEnvironmentManager(context, packageIndexLoader = { emptyMap() })
            val tool = PkgInstallTool(env)
            val ctx = ToolContext(FileFs(File(root, "work")))
            repeat(2) {
                assertTrue(env.isReady)
                val failure = tool.execute(buildJsonObject { put("packages", buildJsonArray { add(JsonPrimitive("qa-nonexistent-pkg-xyz123")) }) }, ctx)
                assertFalse(failure.ok)
                assertTrue(failure.output, failure.output.contains("Unknown package(s): qa-nonexistent-pkg-xyz123"))
                assertTrue(env.state.value.toString(), env.state.value is EnvState.Ready)
                val reuse = tool.execute(buildJsonObject { put("packages", buildJsonArray { add(JsonPrimitive("jq")) }) }, ctx)
                assertTrue(reuse.output, reuse.ok)
                assertTrue(reuse.output, reuse.output.contains("Already installed, files checked on disk: jq"))
                assertArrayEquals(before, File(root, "files/linux/.harness-installed").readBytes())
            }
            val offline = LinuxEnvironmentManager(context, packageIndexLoader = { error("index unavailable") })
            assertTrue(offline.install(listOf("new-addon")).isFailure)
            assertTrue(offline.isReady)
            val cancel = LinuxEnvironmentManager(context, packageIndexLoader = { throw CancellationException("cancel fixture") })
            try { cancel.install(listOf("new-addon")); fail("Expected cancellation") }
            catch (_: CancellationException) { assertTrue(cancel.isReady) }
            val fresh = File(root, "fresh").apply { mkdirs() }
            val missing = LinuxEnvironmentManager(fixtureContext(fresh), packageIndexLoader = { emptyMap<String, PkgMeta>() })
            assertTrue(missing.install(listOf("missing")).isFailure)
            assertFalse(missing.isReady)
            assertTrue(missing.state.value is EnvState.Failed)
            val partialRoot = File(root, "bash-only").apply { mkdirs() }
            File(partialRoot, "files/linux/.harness-installed").apply { parentFile!!.mkdirs(); writeText("bash") }
            val partial = LinuxEnvironmentManager(fixtureContext(partialRoot), packageIndexLoader = { emptyMap() })
            assertTrue(partial.isReady)
            assertTrue(partial.install(listOf("missing")).isFailure)
            assertTrue("A validation error must preserve even a minimal existing installation", partial.isReady)
        } finally { root.deleteRecursively() }
    }

    @Test fun completedBackgroundJobsRetainExitCodesAcrossStoreRecreation() = runBlocking {
        val root = File(c.appContext.cacheDir, "audit-background-${System.nanoTime()}").apply { mkdirs() }
        val context = fixtureContext(root)
        val env = LinuxEnvironmentManager(context)
        val work = File(root, "work").apply { mkdirs() }
        val store = BgProcessStore(context, env, c.shizuku, work)
        try {
            val success = store.start("echo SUCCESS; exit 0", work).entry
            val failure = store.start("echo FAILED >&2; exit 3", work).entry
            withTimeout(10_000) {
                while (store.list().any { it.second }) delay(50)
            }
            val entries = store.list().associate { it.first.id to it.first }
            assertEquals(0, entries.getValue(success.id).exitCode)
            assertEquals(3, entries.getValue(failure.id).exitCode)
            assertTrue(store.tail(entries.getValue(failure.id)).contains("FAILED"))
            val restored = BgProcessStore(context, env, c.shizuku, work)
            val restoredEntries = restored.list().associate { it.first.id to it.first }
            assertEquals(3, restoredEntries.getValue(failure.id).exitCode)
            assertTrue(BgListTool(restored).execute(buildJsonObject {}, ToolContext(FileFs(work))).output.contains("exit 3"))
            val done = BgKillTool(restored).execute(buildJsonObject { put("id", failure.id) }, ToolContext(FileFs(work)))
            assertTrue(done.output, done.output.contains("already exited (exit 3)"))
            assertFalse(BgKillTool(restored).execute(buildJsonObject { put("id", 99999) }, ToolContext(FileFs(work))).ok)
            var cleaned = false
            restored.withIdleStorage { cleaned = true }
            assertTrue(cleaned)
            val running = store.start("sleep 30", work).entry
            assertTrue(runCatching { store.withIdleStorage { error("must not run") } }.isFailure)
            assertTrue(store.kill(running.id))
            assertEquals("killed", store.get(running.id)?.termination)
            assertFalse(store.list().any { it.second })
        } finally { store.killAll(); root.deleteRecursively() }
    }

    @Test fun malformedBytesAndIndentationAreHandledOnAndroidStorage() = runBlocking {
        for (base in listOfNotNull(c.appContext.cacheDir, c.appContext.getExternalFilesDir(null))) {
            val root = File(base, "audit-edits-${System.nanoTime()}").apply { mkdirs() }
            val ctx = ToolContext(FileFs(root))
            try {
                val bytes = "HEAD".toByteArray() + byteArrayOf(-1, -2) + "TAIL\n".toByteArray()
                val file = File(root, "bytes").apply { writeBytes(bytes) }
                val edit = buildJsonObject { put("path", file.name); put("old_string", "TAIL"); put("new_string", "END") }
                for ((tool, args) in listOf(EditFileTool() to edit, MultiEditTool() to buildJsonObject {
                    put("path", file.name); put("edits", buildJsonArray { add(edit) })
                })) {
                    try { tool.execute(args, ctx); fail("${tool.name} accepted malformed bytes") }
                    catch (e: ToolFailure) { assertTrue(e.message.orEmpty().contains("not valid UTF-8")) }
                    assertArrayEquals(bytes, file.readBytes())
                }
                file.writeText("start\r\n\tif x:\r\n\t\tdo()\r\nend\r\n")
                EditFileTool().execute(buildJsonObject {
                    put("path", file.name); put("old_string", "if x:\n  do()"); put("new_string", "if x:\n    DO_NEW()")
                }, ctx)
                assertArrayEquals("start\r\n\tif x:\r\n    DO_NEW()\r\nend\r\n".toByteArray(), file.readBytes())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun privilegedBackgroundExitCodesSurviveLostServiceConnection() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        withTimeout(30_000) { while (!c.shizuku.isServiceReady()) delay(100) }
        // A shared workspace is accessible to both the app and shell uid.
        val root = File("/storage/emulated/0/Download", "harness-audit-privileged-${System.nanoTime()}").apply { check(mkdirs()) }
        val context = fixtureContext(root)
        val env = LinuxEnvironmentManager(context)
        val work = File(root, "work").apply { mkdirs() }
        val store = BgProcessStore(context, env, c.shizuku, work)
        try {
            val started = store.start("echo PRIVILEGED; exit 3", work)
            assertTrue("Expected real privileged execution: ${c.shizuku.connectionReport()}", started.viaShizuku)
            withTimeout(10_000) { while (!File(started.entry.exitCodePath!!).isFile) delay(50) }
            // A completed job remains recoverable without a live binder.
            val restored = BgProcessStore(context, env, c.shizuku, work, privilegedLiveness = { null })
            val finished = restored.list().single()
            assertFalse(finished.second)
            assertEquals(3, finished.first.exitCode)
            assertTrue(restored.tail(finished.first).contains("PRIVILEGED"))
            restored.withIdleStorage { }
        } finally { store.killAll(); root.deleteRecursively() }
    }

    /** Read-only smoke test of the installed phone toolchain; never installs or removes packages. */
    @Test fun installedPhoneToolchainRunsGitPythonAndJq() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("verifyPhoneToolchain") == "true")
        assertTrue(c.linuxEnv.state.value.toString(), c.linuxEnv.isReady)
        val result = c.shellRouter.run("git --version && python3 --version && jq --version", c.appContext.cacheDir, 20_000, 4000)
        assertEquals(result.rawOutput + result.rawStderr, 0, result.exitCode)
        assertTrue(result.rawOutput, result.rawOutput.contains("git version") && result.rawOutput.contains("Python") && result.rawOutput.contains("jq-"))
        val diff = GitDiffTool(c.shellRouter, c.linuxEnv).execute(buildJsonObject {}, ToolContext(FileFs(c.appContext.cacheDir)))
        assertFalse(diff.ok)
        assertTrue(diff.output, diff.output.contains("not in a Git repository"))
        assertFalse(diff.output, diff.output.contains("com.termux"))
    }
}
