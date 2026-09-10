package com.androidharness.app.tools

import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogcatToolTest {

    private class FakeLogcatRunner(
        var response: LogcatRunResult = LogcatRunResult(ok = true, output = ""),
    ) : LogcatRunner {
        var lastQuery: LogcatQuery? = null

        override suspend fun runLogcat(query: LogcatQuery): LogcatRunResult {
            lastQuery = query
            return response
        }
    }

    private class StubWorkspace : WorkspaceFs {
        override val displayPath = "/test/workspace"
        override val shellRoot: File? = null
        override val isSaf: Boolean = false
        override fun resolve(path: String) = throw UnsupportedOperationException()
        override fun walk(path: String) = emptySequence<com.androidharness.app.workspace.FsNode>()
    }

    private val stubContext = ToolContext(workspace = StubWorkspace())

    @Test
    fun `tool metadata is correct and read-only`() {
        val tool = ReadLogcatTool()
        assertEquals("read_logcat", tool.name)
        assertTrue(tool.isReadOnly)
        assertTrue(tool.parametersSchema.containsKey("properties"))
    }

    @Test
    fun `default arguments execute with expected query parameters`() = runBlocking {
        val runner = FakeLogcatRunner(
            response = LogcatRunResult(ok = true, output = "09-03 12:00:00.000 123 123 I TestTag: Hello logcat\n"),
        )
        val tool = ReadLogcatTool(runner = runner)

        val res = tool.execute(buildJsonObject {}, stubContext)
        assertTrue(res.ok)
        assertTrue(res.output.contains("Hello logcat"))

        val query = runner.lastQuery
        assertEquals(100, query?.lines)
        assertEquals("V", query?.level)
        assertEquals(null, query?.tag)
        assertEquals(null, query?.packageName)
        assertEquals(null, query?.filter)
    }

    @Test
    fun `level parsing maps uppercase and common aliases`() = runBlocking {
        val runner = FakeLogcatRunner(
            response = LogcatRunResult(ok = true, output = "09-03 12:00:00.000 123 123 E Crash: NullPointerException\n"),
        )
        val tool = ReadLogcatTool(runner = runner)

        val res = tool.execute(
            buildJsonObject {
                put("level", "error")
                put("lines", 50)
                put("tag", "Crash")
            },
            stubContext,
        )

        assertTrue(res.ok)
        assertEquals(50, runner.lastQuery?.lines)
        assertEquals("E", runner.lastQuery?.level)
        assertEquals("Crash", runner.lastQuery?.tag)
    }

    @Test
    fun `filter trims output to matching lines`() = runBlocking {
        val logData = """
            09-03 12:00:00.000 123 123 D TagA: line 1
            09-03 12:00:01.000 123 123 E TagB: FATAL EXCEPTION happened
            09-03 12:00:02.000 123 123 I TagC: line 3
        """.trimIndent()
        val runner = FakeLogcatRunner(response = LogcatRunResult(ok = true, output = logData))
        val tool = ReadLogcatTool(runner = runner)

        val res = tool.execute(
            buildJsonObject {
                put("filter", "fatal exception")
            },
            stubContext,
        )

        assertTrue(res.ok)
        assertTrue(res.output.contains("FATAL EXCEPTION"))
        assertFalse(res.output.contains("line 1"))
        assertFalse(res.output.contains("line 3"))
    }

    @Test
    fun `package filtering matches process pid when resolved`() = runBlocking {
        val logData = "09-03 12:00:01.000 456 456 E AndroidRuntime: crash\n"
        val runner = FakeLogcatRunner(
            response = LogcatRunResult(ok = true, output = logData, pid = "456"),
        )
        val tool = ReadLogcatTool(runner = runner)

        val res = tool.execute(
            buildJsonObject {
                put("package_name", "com.androidharness.app")
            },
            stubContext,
        )

        assertTrue(res.ok)
        assertTrue(res.output.contains("[filtered by PID 456 for package com.androidharness.app]"))
        assertTrue(res.output.contains("crash"))
    }

    @Test
    fun `package filtering matches text occurrences when pid cannot be resolved`() = runBlocking {
        val logData = """
            09-03 12:00:01.000 100 100 I System: Unrelated line
            09-03 12:00:02.000 101 101 I ActivityManager: Start proc 123:com.example.test/u0a123
        """.trimIndent()
        val runner = FakeLogcatRunner(
            response = LogcatRunResult(ok = true, output = logData, pid = null),
        )
        val tool = ReadLogcatTool(runner = runner)

        val res = tool.execute(
            buildJsonObject {
                put("package_name", "com.example.test")
            },
            stubContext,
        )

        assertTrue(res.ok)
        assertTrue(res.output.contains("[package com.example.test is not currently running; matched text occurrences]"))
        assertTrue(res.output.contains("com.example.test"))
        assertFalse(res.output.contains("Unrelated line"))
    }

    @Test
    fun `runner failure propagates failure output`() = runBlocking {
        val runner = FakeLogcatRunner(
            response = LogcatRunResult(ok = false, output = "logcat: unknown buffer 'invalid'"),
        )
        val tool = ReadLogcatTool(runner = runner)

        val res = tool.execute(
            buildJsonObject {
                put("buffer", "invalid")
            },
            stubContext,
        )

        assertFalse(res.ok)
        assertEquals("logcat: unknown buffer 'invalid'", res.output)
    }

    @Test
    fun `buildLogcatArgs without filters uses direct logcat with -d and -t`() {
        val runner = DefaultLogcatRunner(null)
        val query = LogcatQuery(lines = 50, level = "V")
        val args = runner.buildLogcatArgs(query, pid = null)
        assertEquals(listOf("/system/bin/logcat", "-d", "-t", "50"), args)
    }

    @Test
    fun `buildLogcatArgs with tag uses shell pipeline with tail and filterspec`() {
        val runner = DefaultLogcatRunner(null)
        val query = LogcatQuery(lines = 25, level = "V", tag = "StressTest3")
        val args = runner.buildLogcatArgs(query, pid = null)
        assertEquals("/system/bin/sh", args[0])
        assertEquals("-c", args[1])
        assertTrue(args[2].contains("tail -n \"\$lines\""))
        assertEquals("_", args[3])
        assertEquals("25", args[4])
        assertEquals("", args[5])
        assertEquals("", args[6])
        assertEquals("/system/bin/logcat", args[7])
        assertEquals("-d", args[8])
        assertEquals("StressTest3:V", args[9])
        assertEquals("*:S", args[10])
    }

    @Test
    fun `buildLogcatArgs with filter uses shell pipeline with grep and tail`() {
        val runner = DefaultLogcatRunner(null)
        val query = LogcatQuery(lines = 100, level = "V", filter = "fresh-marker")
        val args = runner.buildLogcatArgs(query, pid = null)
        assertEquals("/system/bin/sh", args[0])
        assertEquals("-c", args[1])
        assertTrue(args[2].contains("grep -F -i -- \"\$f1\""))
        assertEquals("_", args[3])
        assertEquals("100", args[4])
        assertEquals("fresh-marker", args[5])
        assertEquals("", args[6])
        assertEquals("/system/bin/logcat", args[7])
        assertEquals("-d", args[8])
    }

    @Test
    fun `buildLogcatArgs with level above verbose uses shell pipeline with tail and level filterspec`() {
        val runner = DefaultLogcatRunner(null)
        val query = LogcatQuery(lines = 100, level = "W", buffer = "system")
        val args = runner.buildLogcatArgs(query, pid = null)
        assertEquals("/system/bin/sh", args[0])
        assertEquals("-c", args[1])
        assertTrue(args[2].contains("tail -n \"\$lines\""))
        assertEquals("_", args[3])
        assertEquals("100", args[4])
        assertEquals("", args[5])
        assertEquals("", args[6])
        assertEquals("/system/bin/logcat", args[7])
        assertEquals("-d", args[8])
        assertEquals("-b", args[9])
        assertEquals("system", args[10])
        assertEquals("*:W", args[11])
    }
}
