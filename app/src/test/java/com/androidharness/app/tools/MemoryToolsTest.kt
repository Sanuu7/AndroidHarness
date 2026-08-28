package com.androidharness.app.tools

import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = ToolContext(FileFs(tmp.root))

    private suspend fun run(tool: Tool, vararg args: Pair<String, String>): ToolResult =
        tool.execute(
            buildJsonObject {
                args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            },
            ctx(),
        )

    // --- memory_write ---------------------------------------------------------

    @Test
    fun `memory_write without topic lands in the core file`() = runBlocking {
        val r = run(MemoryWriteTool(), "content" to "likes tabs")
        assertTrue(r.ok)
        assertTrue(r.output.contains(".harness/memory.md"))
        assertEquals("likes tabs\n", tmp.root.resolve(".harness/memory.md").readText())
    }

    @Test
    fun `memory_write with topic lands in the topic dir`() = runBlocking {
        val r = run(MemoryWriteTool(), "content" to "CI runs on push", "topic" to "GitHub Workflows")
        assertTrue(r.ok)
        assertTrue(r.output.contains(".harness/memory/github-workflows.md"))
        assertEquals("CI runs on push\n", tmp.root.resolve(".harness/memory/github-workflows.md").readText())
    }

    @Test
    fun `memory_write rejects an unsanitizable topic`() = runBlocking {
        try {
            run(MemoryWriteTool(), "content" to "x", "topic" to "///")
            error("expected ToolFailure")
        } catch (expected: ToolFailure) {
            assertTrue(expected.message.orEmpty().contains("Invalid topic"))
        }
    }

    // --- memory_read ----------------------------------------------------------

    @Test
    fun `memory_read lists core and topics with no arguments`() = runBlocking {
        run(MemoryWriteTool(), "content" to "core fact")
        run(MemoryWriteTool(), "content" to "topic fact", "topic" to "deploy")

        val r = run(MemoryReadTool())
        assertTrue(r.ok)
        assertTrue(r.output.contains("core fact"))
        assertTrue(r.output.contains("- deploy.md"))
    }

    @Test
    fun `memory_read returns a topic in full`() = runBlocking {
        run(MemoryWriteTool(), "content" to "topic fact", "topic" to "deploy")
        val r = run(MemoryReadTool(), "topic" to "deploy")
        assertTrue(r.ok)
        assertEquals("topic fact\n", r.output)
    }

    @Test
    fun `memory_read names available topics for a missing one`() = runBlocking {
        run(MemoryWriteTool(), "content" to "topic fact", "topic" to "deploy")
        val r = run(MemoryReadTool(), "topic" to "missing")
        assertTrue(!r.ok)
        assertTrue(r.output.contains("deploy"))
    }

    // --- memory_search --------------------------------------------------------

    @Test
    fun `memory_search finds matches across core and topics with line numbers`() = runBlocking {
        run(MemoryWriteTool(), "content" to "prefers tabs\nhates trailing spaces")
        run(MemoryWriteTool(), "content" to "Tabs are fine in CI", "topic" to "ci")

        val r = run(MemorySearchTool(), "query" to "TABS")
        assertTrue(r.ok)
        assertTrue(r.output.contains(".harness/memory.md:1:"))
        assertTrue(r.output.contains("prefers tabs"))
        assertTrue(r.output.contains(".harness/memory/ci.md:1:"))
        assertTrue(r.output.contains("2 match(es)"))
    }

    @Test
    fun `memory_search reports a miss without crashing on empty memory`() = runBlocking {
        val r = run(MemorySearchTool(), "query" to "anything")
        assertTrue(!r.ok)
        assertTrue(r.output.contains("No memory matches"))
    }

    // --- listMemoryTopics -----------------------------------------------------

    @Test
    fun `listMemoryTopics is sorted and md-only`() {
        tmp.newFolder(".harness", "memory")
        tmp.newFile(".harness/memory/b.md")
        tmp.newFile(".harness/memory/a.md")
        tmp.newFile(".harness/memory/ignore.txt")
        assertEquals(listOf("a", "b"), listMemoryTopics(FileFs(tmp.root)))
    }

    @Test
    fun `listMemoryTopics is empty without the memory dir`() {
        assertEquals(emptyList<String>(), listMemoryTopics(FileFs(tmp.root)))
    }
}
