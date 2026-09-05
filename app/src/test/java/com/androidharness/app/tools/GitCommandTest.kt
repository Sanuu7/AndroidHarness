package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Command-shape guarantees for the shell-level git tools: the safe.directory
 * override and the auto-maintenance suppression must ride on EVERY segment of
 * a multi-step command, and runtime artifacts under .harness/ must be excluded
 * from staging.
 */
class GitCommandTest {

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun shell(command: String): Pair<Int, String> {
        val process = ProcessBuilder("bash", "-c", command).directory(tmp.root)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun success(command: String): String {
        val (code, output) = shell(command)
        assertEquals(output, 0, code)
        return output
    }

    @Test
    fun `commit runs in bash and removes tracked runtime artifacts`() {
        success(gitCmd("init", "config user.name Test", "config user.email test@example.com"))
        tmp.root.resolve(".harness").mkdirs()
        val artifact = tmp.root.resolve(".harness/screenshot.png").apply { writeText("private") }
        tmp.root.resolve("file.txt").writeText("first")
        success(gitCmd("add -A", "commit -m initial"))
        tmp.root.resolve("file.txt").writeText("second")
        success(gitCommitCmd("don't expand $(false)"))
        assertEquals("file.txt", success(gitCmd("ls-files")).trim())
        assertTrue(artifact.exists())
        assertEquals("don't expand $(false)", success(gitCmd("log -1 --format=%s")).trim())
    }

    @Test
    fun `first commit works with no tracked runtime directory`() {
        success(gitCmd("init", "config user.name Test", "config user.email test@example.com"))
        tmp.root.resolve("file.txt").writeText("first")
        tmp.root.resolve(".harness").mkdirs()
        tmp.root.resolve(".harness/private.txt").writeText("private")
        success(gitCommitCmd("initial"))
        assertEquals("file.txt", success(gitCmd("ls-files")).trim())
    }

    @Test
    fun `log explains a directory with no history and rejects an outside path`() {
        success(gitCmd("init", "config user.name Test", "config user.email test@example.com"))
        tmp.root.resolve("file.txt").writeText("first")
        success(gitCommitCmd("initial"))
        tmp.root.resolve("untracked").mkdirs()
        val (code, output) = shell(gitLogCmd(20, "untracked", false))
        val result = gitLogResult(buildGitResult(com.androidharness.app.data.env.ShellRunResult(
            code, false, output, "", com.androidharness.app.data.env.ExecutionTier.TOYBOX, null,
        )), "untracked")
        assertTrue(result.ok)
        assertTrue(result.output, result.output.contains("No commit history found for 'untracked'"))
        val (outsideCode, outsideOutput) = shell(gitLogCmd(20, tmp.root.parentFile.path, false))
        val outside = buildGitResult(com.androidharness.app.data.env.ShellRunResult(
            outsideCode, false, "", outsideOutput, com.androidharness.app.data.env.ExecutionTier.TOYBOX, null,
        ))
        assertFalse(outside.ok)
        assertFalse(outside.output, outside.output.contains("fatal:"))
    }

    @Test
    fun `non repository errors are actionable`() {
        for (command in listOf(gitCmd("status"), gitLogCmd(20, null, false), gitCmd("diff"))) {
            val (code, output) = shell(command)
            val result = buildGitResult(com.androidharness.app.data.env.ShellRunResult(
                code, false, "", output, com.androidharness.app.data.env.ExecutionTier.TOYBOX, null,
            ))
            assertFalse(result.ok)
            assertTrue(result.output, result.output.contains("git init"))
            assertFalse(result.output, result.output.contains("fatal:"))
            assertFalse(tmp.root.resolve(".git").exists())
        }
    }

    /** Prefix every git invocation must carry (see GitTools.GIT_BASE_ARGS). */
    private val base = "git -c 'safe.directory=*' -c gc.auto=0 -c maintenance.auto=false"

    @Test
    fun `gitCmd puts the overrides on every step`() {
        val cmd = gitCmd("status --short --branch")
        assertEquals("$base status --short --branch", cmd)
    }

    @Test
    fun `gitCmd repeats the overrides across chained steps`() {
        val cmd = gitCmd("add -A", "commit -m 'msg'")
        val parts = cmd.split(" && ")
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.startsWith("$base ") })
    }

    @Test
    fun `dubious ownership detector matches real git phrasing`() {
        assertTrue(
            isDubiousOwnership(
                "fatal: detected dubious ownership in repository at '/data/data/com.androidharness.app/files/ws'",
            ),
        )
        assertFalse(isDubiousOwnership("Everything up-to-date"))
    }

    @Test
    fun `gitLogCmd shapes the default history command`() {
        assertEquals(
            "$base log -n 20 --date=short --pretty=format:'%h %ad %an  %s'",
            gitLogCmd(20, null, false),
        )
    }

    @Test
    fun `gitLogCmd appends stat and a quoted path`() {
        val cmd = gitLogCmd(5, "app/src/Main.kt", true)
        assertTrue(cmd.contains("log -n 5 "))
        assertTrue(cmd.contains(" --stat"))
        assertTrue(cmd.endsWith("-- 'app/src/Main.kt'"))
    }

    @Test
    fun `gitShowCmd quotes the hash and keeps the stat under -s`() {
        assertEquals(
            "$base show --stat --patch 'HEAD~1'",
            gitShowCmd("HEAD~1", false),
        )
        // -s BEFORE --stat: --no-patch kills the stat in any position, while
        // "show -s --stat" keeps message + stat (verified on git 2.55).
        assertEquals(
            "$base show --stat -s 'HEAD'",
            gitShowCmd("HEAD", true),
        )
    }

    @Test
    fun `gitCheckoutCmd covers switch create and restore shapes`() {
        assertEquals(
            "$base checkout 'main'",
            gitCheckoutCmd("main", false, emptyList()),
        )
        assertEquals(
            "$base checkout -b 'feature/x'",
            gitCheckoutCmd("feature/x", true, emptyList()),
        )
        assertEquals(
            "$base checkout -- 'a.kt' 'b.kt'",
            gitCheckoutCmd(null, false, listOf("a.kt", " b.kt")),
        )
        assertEquals(
            "$base checkout 'main' -- 'a.kt'",
            gitCheckoutCmd("main", false, listOf("a.kt")),
        )
    }

    @Test
    fun `gitCheckoutCmd refuses an empty call`() {
        try {
            gitCheckoutCmd(" ", false, listOf("  "))
            fail("expected ToolFailure")
        } catch (expected: ToolFailure) {
        }
    }

    @Test
    fun `gitPushCmd defaults to origin and HEAD`() {
        assertEquals(
            "$base push 'origin' HEAD",
            gitPushCmd(null, null, false),
        )
        assertEquals(
            "$base push -u 'origin' 'feature/x'",
            gitPushCmd("origin", "feature/x", true),
        )
    }

    @Test
    fun `gitPullCmd maps modes and rejects unknown ones`() {
        assertEquals(
            "$base pull --ff-only 'origin'",
            gitPullCmd(null, null),
        )
        assertEquals(
            "$base pull 'origin'",
            gitPullCmd("origin", "merge"),
        )
        assertEquals(
            "$base pull --rebase 'origin'",
            gitPullCmd(null, "rebase"),
        )
        try {
            gitPullCmd(null, "squash")
            fail("expected ToolFailure")
        } catch (expected: ToolFailure) {
        }
    }

    @Test
    fun `upstream check detects missing tracking and push -u records it`() {
        assertEquals(
            "$base rev-parse --verify --quiet 'HEAD@{u}'",
            gitUpstreamCheckCmd(null),
        )
        assertEquals(
            "$base rev-parse --verify --quiet 'feature/x@{u}'",
            gitUpstreamCheckCmd("feature/x"),
        )
        // A plain push on a branch with no upstream leaves no tracking config.
        success(gitCmd("init", "config user.name Test", "config user.email test@example.com"))
        tmp.root.resolve("f.txt").writeText("first")
        success(gitCmd("add -A", "commit -m initial"))
        // Inside the per-test folder so repeated runs never see a stale
        // remote whose old master rejects the fresh push.
        val remote = tmp.root.resolve("upstream-remote.git").absolutePath
        success("git init -q --bare '$remote'")
        success(gitCmd("remote add origin '$remote'"))
        val (checkCode, _) = shell(gitUpstreamCheckCmd(null))
        assertFalse(checkCode == 0)
        success(gitPushCmd(null, null, true))
        val (checkCode2, _) = shell(gitUpstreamCheckCmd(null))
        assertEquals(0, checkCode2)
        assertEquals("origin", success(gitCmd("config branch.master.remote")).trim())
        assertEquals("refs/heads/master", success(gitCmd("config branch.master.merge")).trim())
    }
}
