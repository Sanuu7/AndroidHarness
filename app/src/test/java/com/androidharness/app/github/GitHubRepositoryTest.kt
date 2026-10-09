package com.androidharness.app.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import com.androidharness.app.automation.AutomationTask
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitHubRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var folder: File
    private lateinit var remote: File

    private fun git(vararg args: String, cwd: File = folder): GitCommandResult {
        val process = ProcessBuilder(listOf("git") + args).directory(cwd).apply {
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        }.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return GitCommandResult(process.waitFor(), out, err)
    }

    private fun setup(): GitHubRepository {
        folder = temp.newFolder("work")
        remote = temp.newFolder("remote.git")
        assertEquals(0, git("init", "--bare", cwd = remote).code)
        assertEquals(0, git("init", "-b", "main").code)
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("remote", "add", "origin", "https://github.com/test/repo.git")
        // Transport fixture only: the app still inspects a GitHub origin, while real Git pushes to a local bare repo.
        git("config", "url.${remote.absolutePath}.insteadOf", "https://github.com/test/repo.git")
        File(folder, "base.txt").writeText("base")
        git("add", "base.txt"); git("commit", "-m", "Initial")
        return GitHubRepository { args -> git(*args.toTypedArray()) }
    }

    @Test fun `selected files publish while unrelated staging and app history stay local`() = runBlocking {
        val repo = setup()
        File(folder, "picked ' file.txt").writeText("chosen")
        File(folder, "staged.txt").writeText("other")
        File(folder, ".harness").mkdirs()
        File(folder, ".harness/history.json").writeText("private")
        git("add", "staged.txt")
        val before = repo.inspect()
        assertFalse(before.changes.any { it.path.startsWith(".harness/") })
        val result = repo.publish(before, setOf("picked ' file.txt"), "Selected change", true)
        assertTrue(result.startsWith("Published"))
        val changed = git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD").out.trim()
        assertEquals("picked ' file.txt", changed)
        assertEquals("staged.txt", git("diff", "--cached", "--name-only").out.trim())
        assertEquals(git("rev-parse", "HEAD").out.trim(), git("rev-parse", "refs/heads/main", cwd = remote).out.trim())
    }

    @Test fun `failed push preserves commit and retry pushes without another commit`() = runBlocking {
        val repo = setup()
        File(remote, "hooks/pre-receive").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        File(folder, "change.txt").writeText("change")
        val state = repo.inspect()
        val failure = runCatching { repo.publish(state, setOf("change.txt"), "Kept locally", true) }.exceptionOrNull()
        assertTrue(failure is GitHubOperationFailure)
        assertEquals("Push", (failure as GitHubOperationFailure).stage)
        val kept = git("rev-parse", "HEAD").out.trim()
        assertEquals("Kept locally", git("log", "-1", "--format=%s").out.trim())
        File(remote, "hooks/pre-receive").delete()
        repo.publish(repo.inspect(), emptySet(), "", false)
        assertEquals(kept, git("rev-parse", "HEAD").out.trim())
        assertEquals(kept, git("rev-parse", "main", cwd = remote).out.trim())
    }

    @Test fun `branch switch blocks reviewed publish and saved preset`() = runBlocking {
        val repo = setup()
        val state = repo.inspect()
        git("checkout", "-b", "other")
        assertTrue(runCatching { repo.publish(state, emptySet(), "", false) }.isFailure)
        assertTrue(runCatching { repo.runPreset(GitHubPushPreset(state.remoteUrl, state.branch, "Update")) }.isFailure)
        assertNotEquals(0, git("rev-parse", "main", cwd = remote).code)
    }

    @Test fun `renamed and deleted paths commit correctly`() = runBlocking {
        val repo = setup()
        git("mv", "base.txt", "renamed.txt")
        val state = repo.inspect()
        assertEquals("base.txt", state.changes.single().originalPath)
        repo.publish(state, setOf("renamed.txt"), "Rename", true)
        File(folder, "renamed.txt").delete()
        repo.publish(repo.inspect(), setOf("renamed.txt"), "Delete", true)
        assertEquals("", git("ls-tree", "--name-only", "HEAD").out.trim())
    }

    @Test fun `unborn repository can publish its first commit`() = runBlocking {
        val repo = setup()
        git("checkout", "--orphan", "fresh")
        git("rm", "--cached", "base.txt")
        val state = repo.inspect()
        assertEquals("", state.head)
        repo.publish(state, setOf("base.txt"), "First", true)
        assertEquals(git("rev-parse", "HEAD").out.trim(), git("rev-parse", "fresh", cwd = remote).out.trim())
    }

    @Test fun `preset includes only chosen paths and does not create empty retry commits`() = runBlocking {
        val repo = setup()
        File(folder, "selected.txt").writeText("yes")
        File(folder, "other.txt").writeText("no")
        val preset = GitHubPushPreset("https://github.com/test/repo.git", "main", "Preset", listOf("selected.txt"))
        repo.runPreset(preset)
        val head = git("rev-parse", "HEAD").out.trim()
        repo.runPreset(preset)
        assertEquals(head, git("rev-parse", "HEAD").out.trim())
        assertEquals("?? other.txt", git("status", "--porcelain").out.trim())
    }

    @Test fun `alternate push address and nested workspace are refused`() = runBlocking {
        val repo = setup()
        git("remote", "set-url", "--push", "origin", "https://github.com/other/repo.git")
        assertTrue(runCatching { repo.inspect() }.isFailure)
        git("config", "--unset", "remote.origin.pushurl")
        val nested = File(folder, "subfolder").apply { mkdirs() }
        val nestedRepo = GitHubRepository { args -> git(*args.toTypedArray(), cwd = nested) }
        assertTrue(runCatching { nestedRepo.inspect() }.isFailure)
    }

    @Test fun `path parsing preserves unicode newline and rename pairs`() {
        val changes = parseGitChanges(" M hello\n世界.txt\u0000R  new.txt\u0000old.txt\u0000?? .harness/history\u0000")
        assertEquals(listOf("hello\n世界.txt", "new.txt"), changes.map { it.path })
        assertEquals("old.txt", changes[1].originalPath)
        assertTrue(runCatching { parseGitChanges("R  new.txt\u0000") }.isFailure)
    }

    @Test fun `repository input cannot inject credentials flags or another host`() {
        assertEquals("https://github.com/owner/repo.git", gitHubRepositoryUrl("owner/repo"))
        listOf("https://example.com/a/b", "https://token@github.com/a/b", "../repo", "owner/repo;touch bad", "-flag").forEach {
            assertTrue(it, runCatching { gitHubRepositoryUrl(it) }.isFailure)
        }
    }

    @Test fun `automation migration preserves old tasks and preset round trips`() {
        val old = Json.decodeFromString<AutomationTask>("""{"title":"Old","prompt":"Build","projectId":"a","projectName":"A"}""")
        assertNull(old.githubPush)
        val updated = old.copy(githubPush = GitHubPushPreset("https://github.com/owner/repo.git", "main", "Update", listOf("x")))
        val storageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(updated, storageJson.decodeFromString<AutomationTask>(storageJson.encodeToString(AutomationTask.serializer(), updated)))
    }

    @Test fun `diagnostics explain failures and scrub tokens`() {
        val hint = gitHubFailureHint(128, "fatal: Authentication failed for https://x-access-token:ghp_secret@github.com/owner/repo.git")
        assertTrue(hint.contains("Reconnect GitHub"))
        assertFalse(hint.contains("ghp_secret"))
        assertTrue(gitHubFailureHint(1, "rejected (fetch first)").contains("newer commits"))
        assertTrue(gitHubFailureHint(1, "GH013 protected branch").contains("branch rules"))
        val helper = gitHubFailureHint(128, "fatal: cannot exec 'remote-https': Permission denied")
        assertTrue(helper.contains("required helper"))
        assertFalse(helper.contains("write access"))
    }
}
