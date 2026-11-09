package com.androidharness.app.agent

import com.androidharness.app.agent.ContextBreakdown.HintKind
import com.androidharness.app.agent.ContextBreakdown.Part
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBreakdownTest {
    private fun filler(chars: Int) = "x".repeat(chars)

    private fun assertClose(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected, got $actual", kotlin.math.abs(expected - actual) <= tolerance)

    private fun tokens(slices: List<ContextBreakdown.Slice>, part: Part) = slices.firstOrNull { it.part == part }?.tokens

    private fun schema(name: String, descriptionChars: Int = 400) =
        ToolSchema(name, filler(descriptionChars), buildJsonObject { put("type", "object") })

    @Test
    fun `system prompt splits on the harness headings only`() {
        val prompt = filler(400) + "\n" +
            "## Skills (mandatory)\n" + filler(800) + "\n" +
            "# AGENTS.md (project instructions)\n" + filler(1200) + "\n" +
            "# Repository Map (codebase index)\n" + filler(2000) + "\n" +
            "## Live AndroidHarness environment\n" + filler(400) + "\n"
        val slices = ContextBreakdown.splitSystem(prompt)
        assertEquals(listOf(Part.CORE, Part.SKILLS, Part.INSTRUCTIONS, Part.REPO_MAP, Part.ENVIRONMENT), slices.map { it.part })
        assertTrue(tokens(slices, Part.REPO_MAP)!! > tokens(slices, Part.SKILLS)!!)
        // Every character lands in exactly one section, so the parts add up to the whole.
        assertClose(prompt.length / 4, slices.sumOf { it.tokens }, 5)
    }

    @Test
    fun `a heading inside a skill or note stays with its section`() {
        val prompt = "intro\n## Skills (mandatory)\n" + filler(400) + "\n# Notes from the author\n" + filler(400) + "\n"
        val slices = ContextBreakdown.splitSystem(prompt)
        assertEquals(listOf(Part.CORE, Part.SKILLS), slices.map { it.part })
        assertTrue(tokens(slices, Part.SKILLS)!! >= 200)
    }

    @Test
    fun `memory and chat instruction headings are recognised`() {
        val prompt = "core\n# User instructions for this chat\n" + filler(400) + "\n# Saved memory\n" + filler(800) + "\n" +
            "# Agent memory (from previous sessions)\n" + filler(400) + "\n"
        val slices = ContextBreakdown.splitSystem(prompt)
        assertEquals(setOf(Part.CORE, Part.INSTRUCTIONS, Part.MEMORY), slices.map { it.part }.toSet())
        assertTrue(tokens(slices, Part.MEMORY)!! > tokens(slices, Part.INSTRUCTIONS)!!)
    }

    @Test
    fun `an empty prompt gives no slices`() {
        assertTrue(ContextBreakdown.splitSystem("").isEmpty())
    }

    @Test
    fun `tools group into built-in, codegraph and one slice per mcp server`() {
        val tools = listOf(
            schema("read_file"), schema("write_file"),
            schema("codegraph_explore", 2000),
            schema("mcp__github__list_issues", 1200), schema("mcp__github__get_issue", 1200),
            schema("mcp__notion__search", 400),
        )
        val groups = ContextBreakdown.groupTools(tools)
        assertEquals(2, groups.first { it.part == Part.TOOLS_BUILT_IN }.count)
        assertEquals(1, groups.first { it.part == Part.TOOLS_CODEGRAPH }.count)
        val github = groups.first { it.detail == "github" }
        assertEquals(Part.TOOLS_MCP, github.part)
        assertEquals(2, github.count)
        assertEquals("notion", groups.first { it.part == Part.TOOLS_MCP && it.detail != "github" }.detail)
        // Largest group first.
        assertEquals(groups.map { it.tokens }.sortedDescending(), groups.map { it.tokens })
    }

    @Test
    fun `mcp server name is the text between the double underscores`() {
        assertEquals("github", ContextBreakdown.mcpServer("mcp__github__list_issues"))
        assertEquals("my_server", ContextBreakdown.mcpServer("mcp__my_server__do_thing"))
        assertEquals("loose", ContextBreakdown.mcpServer("mcp__loose"))
    }

    @Test
    fun `messages split by role and tool call arguments`() {
        val history = listOf(
            ChatMessage(Role.USER, filler(400)),
            ChatMessage(Role.ASSISTANT, filler(800), toolCalls = listOf(ToolCallData("1", "read_file", filler(200)))),
            ChatMessage(Role.TOOL, filler(4000), toolCallId = "1", toolName = "read_file"),
            ChatMessage(Role.SYSTEM, filler(400)),
        )
        val slices = ContextBreakdown.splitMessages(history)
        assertEquals(100, tokens(slices, Part.USER))
        assertEquals(200, tokens(slices, Part.ASSISTANT))
        assertEquals(50, tokens(slices, Part.TOOL_CALLS))
        assertEquals(1000, tokens(slices, Part.TOOL_RESULTS))
        assertEquals(100, tokens(slices, Part.NOTES))
        assertEquals(Part.TOOL_RESULTS, slices.first().part)
    }

    @Test
    fun `message slices match the estimate formula`() {
        val history = listOf(
            ChatMessage(Role.USER, filler(1000)),
            ChatMessage(Role.ASSISTANT, filler(2000), toolCalls = listOf(ToolCallData("1", "shell", filler(400)))),
            ChatMessage(Role.TOOL, filler(6000), toolCallId = "1", toolName = "shell"),
        )
        val total = history.sumOf { it.text.length + it.toolCalls.sumOf { c -> c.argumentsJson.length } } / 4
        assertClose(total, ContextBreakdown.splitMessages(history).sumOf { it.tokens }, 3)
    }

    @Test
    fun `largest results are sorted, capped and skip small outputs`() {
        val history = buildList {
            add(ChatMessage(Role.TOOL, filler(100), toolCallId = "tiny", toolName = "tiny"))
            (1..8).forEach { add(ChatMessage(Role.TOOL, filler(it * 1000), toolCallId = "c$it", toolName = "tool$it")) }
            add(ChatMessage(Role.TOOL, filler(8000), toolCallId = "u"))
        }
        val results = ContextBreakdown.largestResults(history)
        assertEquals(ContextBreakdown.MAX_RESULTS, results.size)
        assertEquals(2000, results.first().tokens)
        assertTrue(results.none { it.toolName == "tiny" })
        assertEquals(results.map { it.tokens }.sortedDescending(), results.map { it.tokens })
        assertTrue(results.any { it.toolName == "tool8" || it.toolName == "tool" || it.toolName == "tool7" })
    }

    @Test
    fun `a result with no tool name gets a placeholder`() {
        val results = ContextBreakdown.largestResults(listOf(ChatMessage(Role.TOOL, filler(4000), toolCallId = "x")))
        assertEquals("tool", results.single().toolName)
    }

    @Test
    fun `of builds all four parts`() {
        val breakdown = ContextBreakdown.of(
            history = listOf(ChatMessage(Role.USER, filler(400))),
            systemPrompt = "core\n## Skills (mandatory)\n" + filler(400),
            tools = listOf(schema("read_file")),
        )
        assertTrue(breakdown.systemTokens > 0)
        assertTrue(breakdown.toolsTokens > 0)
        assertEquals(100, breakdown.messagesTokens)
        assertTrue(breakdown.largestResults.isEmpty())
    }

    private fun breakdown(
        system: List<ContextBreakdown.Slice> = emptyList(),
        tools: List<ContextBreakdown.Slice> = emptyList(),
        messages: List<ContextBreakdown.Slice> = emptyList(),
        largest: List<ContextBreakdown.ToolResult> = emptyList(),
    ) = ContextBreakdown(system, tools, messages, largest)

    @Test
    fun `no hints for a small healthy context`() {
        val small = breakdown(
            system = listOf(ContextBreakdown.Slice(Part.CORE, 1000)),
            messages = listOf(ContextBreakdown.Slice(Part.USER, 500)),
        )
        assertTrue(small.hints(used = 2000, limit = 200_000).isEmpty())
    }

    @Test
    fun `near the limit warns first`() {
        val hints = breakdown(messages = listOf(ContextBreakdown.Slice(Part.USER, 500))).hints(used = 170_000, limit = 200_000)
        assertEquals(HintKind.NEAR_LIMIT, hints.first().kind)
        assertTrue(breakdown().hints(used = 159_999, limit = 200_000).none { it.kind == HintKind.NEAR_LIMIT })
    }

    @Test
    fun `big tool results name the largest source`() {
        val b = breakdown(
            system = listOf(ContextBreakdown.Slice(Part.CORE, 2000)),
            messages = listOf(ContextBreakdown.Slice(Part.TOOL_RESULTS, 20_000), ContextBreakdown.Slice(Part.USER, 1000)),
            largest = listOf(ContextBreakdown.ToolResult("grep", 9000)),
        )
        val hint = b.hints(used = 23_000, limit = 200_000).single { it.kind == HintKind.TOOL_RESULTS }
        assertEquals(20_000, hint.tokens)
        assertEquals("grep", hint.detail)
    }

    @Test
    fun `mcp tools only count when they are a real share`() {
        val heavy = breakdown(
            system = listOf(ContextBreakdown.Slice(Part.CORE, 4000)),
            tools = listOf(
                ContextBreakdown.Slice(Part.TOOLS_BUILT_IN, 4000, count = 40),
                ContextBreakdown.Slice(Part.TOOLS_MCP, 3000, detail = "github", count = 20),
                ContextBreakdown.Slice(Part.TOOLS_MCP, 2000, detail = "notion", count = 10),
            ),
        )
        assertEquals(5000, heavy.hints(used = 13_000, limit = 200_000).single { it.kind == HintKind.MCP_TOOLS }.tokens)
        val light = breakdown(
            system = listOf(ContextBreakdown.Slice(Part.CORE, 40_000)),
            tools = listOf(ContextBreakdown.Slice(Part.TOOLS_MCP, 2000, detail = "github", count = 5)),
        )
        assertTrue(light.hints(used = 42_000, limit = 200_000).none { it.kind == HintKind.MCP_TOOLS })
    }

    @Test
    fun `skills and memory hints need a meaningful size`() {
        val b = breakdown(system = listOf(
            ContextBreakdown.Slice(Part.SKILLS, 3000),
            ContextBreakdown.Slice(Part.MEMORY, 900),
        ))
        val kinds = b.hints(used = 4000, limit = 200_000).map { it.kind }
        assertTrue(HintKind.SKILLS in kinds)
        assertTrue(HintKind.MEMORY !in kinds)
    }

    @Test
    fun `an unknown limit never claims the context is full`() {
        assertTrue(breakdown().hints(used = 500_000, limit = 0).none { it.kind == HintKind.NEAR_LIMIT })
    }

    @Test
    fun `estimates carry no breakdown unless asked for`() {
        assertNull(ContextEstimate(1, 2, 3).breakdown)
    }
}
