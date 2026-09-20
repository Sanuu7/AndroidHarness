package com.androidharness.app.data.env

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test

class TermuxSshTest {
    @Test fun `aliases use the same shared project and normalize traversal`() {
        assertEquals("/storage/emulated/0/Projects/a", termuxSharedPath("/sdcard/Projects/a"))
        assertEquals("/storage/emulated/0/Projects/a", termuxSharedPath("/storage/self/primary/Projects/b/../a"))
        listOf("/data/user/0/app/files", "/sdcard/../../data", "/storage/emulated/01/x",
            "/sdcard/Android/data/app/files", "/sdcard/Android/obb/x").forEach {
            assertThrows(IllegalArgumentException::class.java) { termuxSharedPath(it) }
        }
    }

    @Test fun `command and directory are literal shell arguments`() {
        val dir = "/sdcard/Project's \$(touch nope)"
        val command = "printf '%s' \"\$HOME\"; echo hi"
        val script = termuxCommand(command, dir)
        // Replace bash with a recorder and cd with a function. No directory or
        // user command is executed; the local shell parses our actual script.
        val proc = ProcessBuilder("bash", "-c", "cd() { printf '%s\\n' \"\$@\"; }; exec() { printf '%s\\n' \"\$@\"; }; $script").start()
        val lines = proc.inputStream.bufferedReader().readLines()
        assertEquals(0, proc.waitFor())
        assertEquals(listOf("--", "/storage/emulated/0/Project's \$(touch nope)", "bash", "-c", command), lines)
    }

    @Test fun `pin accepts only the exact host key`() {
        val key = "server key".toByteArray()
        val repo = FingerprintRepository(sshFingerprint(key))
        assertEquals(HostKeyRepository.OK, repo.check("localhost", key))
        assertEquals(HostKeyRepository.CHANGED, repo.check("localhost", "different server".toByteArray()))
    }

    @Test fun `output limit drains excess bytes and marks truncation`() {
        val output = LimitedSshOutput(5)
        output.write("hello world".toByteArray())
        output.write(33)
        assertEquals("hello\n[output truncated]", output.text())
        val exact = LimitedSshOutput(5)
        exact.write("hello".toByteArray())
        assertEquals("hello", exact.text())
    }

    @Test fun `invalid connection details cannot be enabled`() {
        val config = TermuxSshConfig(true, "u0_a123", 8022, "password", sshFingerprint(byteArrayOf(1)))
        config.validate()
        listOf(config.copy(username = "-o x"), config.copy(port = 0), config.copy(port = 65536),
            config.copy(password = ""), config.copy(fingerprint = "SHA256:nope")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }
}
