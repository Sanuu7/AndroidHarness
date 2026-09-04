package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocaleCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        LocaleCommand.ensureInstalled(tmp.root)
        val builder = ProcessBuilder(listOf("sh", tmp.root.resolve("bin/locale").path) + args)
            .redirectErrorStream(true)
        builder.environment().keys.removeAll { it == "LANG" || it.startsWith("LC_") }
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun `reports environment with LC ALL taking precedence`() {
        val (code, output) = run(env = mapOf("LANG" to "C", "LC_TIME" to "POSIX", "LC_ALL" to "C.UTF-8"))
        assertEquals(0, code)
        assertTrue(output, output.contains("LANG=C\n"))
        assertTrue(output, output.contains("LC_TIME=\"C.UTF-8\""))
        assertTrue(output, output.contains("LC_ALL=C.UTF-8"))
        assertTrue(run(env = mapOf("LANG" to "C.UTF-8", "LC_TIME" to "POSIX")).second.contains("LC_TIME=\"POSIX\""))
    }

    @Test fun `supports discovery and rejects unsupported queries`() {
        assertEquals(0 to "C\nC.UTF-8\nen_US.UTF-8\nPOSIX\n", run("-a"))
        assertEquals(0 to "UTF-8\n", run("charmap", env = mapOf("LANG" to "C.UTF-8")))
        assertEquals(0 to "ASCII\nUTF-8\n", run("-m"))
        assertEquals(0 to "charmap=\"UTF-8\"\n", run("-k", "charmap", env = mapOf("LANG" to "C.UTF-8")))
        assertEquals(0 to "ASCII\n", run("charmap", env = mapOf("LC_ALL" to "C")))
        assertEquals(1, run("charmap", env = mapOf("LC_CTYPE" to "invalid")).first)
        assertEquals(1, run("--bogus").first)
    }

    @Test fun `preserves a user installed command`() {
        val command = tmp.newFolder("bin").resolve("locale").apply { writeText("custom locale") }
        LocaleCommand.ensureInstalled(tmp.root)
        assertEquals("custom locale", command.readText())
    }
}
