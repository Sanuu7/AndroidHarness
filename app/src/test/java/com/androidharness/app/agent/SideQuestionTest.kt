package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SideQuestionTest {
    private val user = ChatMessage(role = Role.USER, text = "Refactor the parser")
    private val calls = listOf(ToolCallData("a", "read_file", "{}"), ToolCallData("b", "shell", "{}"))
    private val assistant = ChatMessage(role = Role.ASSISTANT, text = "Reading first", toolCalls = calls)
    private fun result(id: String) = ChatMessage(role = Role.TOOL, text = "done $id", toolCallId = id, toolName = "x")

    private fun context(messages: List<ChatMessage>, warm: Boolean = true) = SideContext(
        sessionId = "s", turnId = "t", config = ProviderConfig("p", "P", ProviderType.OPENAI_COMPAT, "http://localhost", "m"),
        apiKey = "", systemPrompt = "system", tools = emptyList(), messages = messages, options = RequestOptions(),
        registry = ToolRegistry(emptyList()), workspace = FileFs(File(".")), sandboxOff = false,
        permissionMode = PermissionMode.CONFIRM_RISKY, runContext = null, warm = warm,
    )

    private fun tool(name: String, readOnly: Boolean) = object : Tool {
        override val name = name
        override val description = name
        override val parametersSchema = JsonObject(emptyMap())
        override val isReadOnly = readOnly
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
    }

    @Test
    fun `closed exchanges pass through untouched`() {
        val messages = listOf(user, assistant, result("a"), result("b"))
        assertSame(messages, SideQuestion.closePendingCalls(messages))
        assertEquals(listOf(user), SideQuestion.closePendingCalls(listOf(user)))
    }

    @Test
    fun `calls still running get placeholders in call order and keep finished results`() {
        val closed = SideQuestion.closePendingCalls(listOf(user, assistant, result("b")))
        assertEquals(listOf(user, assistant), closed.take(2))
        assertEquals(listOf("a", "b"), closed.drop(2).map { it.toolCallId })
        assertEquals(SideQuestion.PENDING_RESULT, closed[2].text)
        assertEquals("done b", closed[3].text)
    }

    @Test
    fun `an exchange followed by a user turn is never rewritten`() {
        val later = listOf(user, assistant, ChatMessage(role = Role.USER, text = "next"))
        assertSame(later, SideQuestion.closePendingCalls(later))
    }

    @Test
    fun `warm requests append the question after the unchanged prefix`() {
        val prefix = listOf(user, assistant, result("a"), result("b"))
        val request = SideQuestion.requestMessages(context(prefix), "  Why Room?  ")
        assertEquals(prefix, request.dropLast(1))
        assertEquals(Role.USER, request.last().role)
        assertTrue(request.last().text.contains("<side_question>"))
        assertTrue(request.last().text.contains("Why Room?"))
    }

    @Test
    fun `warm requests keep a trailing user turn as its own message`() {
        val request = SideQuestion.requestMessages(context(listOf(user)), "Why?")
        assertEquals(2, request.size)
        assertEquals(user, request.first())
    }

    @Test
    fun `cold requests fold the question into the transcript turn`() {
        val transcript = SideQuestion.coldMessages(listOf(user, ChatMessage(role = Role.ASSISTANT, text = "Done")), 10_000)
        val request = SideQuestion.requestMessages(context(transcript, warm = false), "Why?")
        assertEquals(1, request.size)
        assertTrue(request.single().text.startsWith("<transcript>"))
        assertTrue(request.single().text.contains("Refactor the parser"))
        assertTrue(request.single().text.contains("Why?"))
    }

    @Test
    fun `cold transcripts keep the newest part when bounded`() {
        val long = (1..400).map { ChatMessage(role = Role.USER, text = "message $it") }
        val text = SideQuestion.coldMessages(long, 2_000).single().text
        assertTrue(text.contains("[Earlier conversation omitted]"))
        assertTrue(text.contains("message 400"))
        assertFalse(text.contains("message 1\n"))
    }

    @Test
    fun `only side-effect free read-only tools may run`() {
        assertTrue(SideQuestion.allows(tool("read_file", true)))
        assertTrue(SideQuestion.allows(tool("grep", true)))
        assertTrue(SideQuestion.allows(tool("web_search", true)))
        assertFalse(SideQuestion.allows(tool("write_file", false)))
        assertFalse(SideQuestion.allows(tool("shell", false)))
        for (name in listOf("ask_user", "create_artifact", "http_request", "github_api", "browser_read", "mcp__x__read", "codegraph_sync"))
            assertFalse(name, SideQuestion.allows(tool(name, true)))
    }

    @Test
    fun `contexts are kept per chat and bounded`() {
        val store = SideContexts(capacity = 2)
        store.publish(context(listOf(user)).copy(sessionId = "1"))
        store.publish(context(listOf(user)).copy(sessionId = "2"))
        store["1"]
        store.publish(context(listOf(user)).copy(sessionId = "3"))
        assertNull(store["2"])
        assertTrue(store["1"] != null && store["3"] != null)
        store.clear("1")
        assertNull(store["1"])
    }
}
