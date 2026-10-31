package com.androidharness.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.LlmProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** /btw against the real engine loop: same cached prefix, read-only tools, main task untouched. */
@RunWith(AndroidJUnit4::class)
class SideQuestionDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    private data class Request(val system: String, val tools: List<ToolSchema>, val messages: List<ChatMessage>, val options: RequestOptions) {
        val side get() = messages.any { it.role == Role.USER && it.text.contains("<side_question>") }
    }

    private fun tool(name: String, readOnly: Boolean, run: suspend () -> ToolResult) = object : Tool {
        override val name = name
        override val description = name
        override val parametersSchema = JsonObject(emptyMap())
        override val isReadOnly = readOnly
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = run()
    }

    @Test fun sideQuestionReplaysTheLivePrefixWhileTheMainToolRuns(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "btw-${System.nanoTime()}").apply { mkdirs() }
        File(root, "notes.txt").writeText("Room was chosen for its migrations.")
        val requests = CopyOnWriteArrayList<Request>()
        val executed = CopyOnWriteArrayList<String>()
        val release = CompletableDeferred<Unit>()
        val registry = ToolRegistry(listOf(
            tool("slow_write", readOnly = false) { executed += "slow_write"; release.await(); ToolResult(true, "written") },
            tool("read_notes", readOnly = true) { executed += "read_notes"; ToolResult(true, File(root, "notes.txt").readText()) },
            tool("write_notes", readOnly = false) { executed += "write_notes"; ToolResult(true, "should never run") },
        ))
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                val request = Request(systemPrompt, tools.toList(), messages.toList(), options).also { requests += it }
                val answered = messages.any { it.role == Role.TOOL && it.toolCallId?.startsWith("side-") == true }
                when {
                    request.side && !answered -> emit(StreamEvent.ToolCallBatch(listOf(
                        ToolCallData("side-read", "read_notes", "{}"), ToolCallData("side-write", "write_notes", "{}"))))
                    request.side -> {
                        emit(StreamEvent.TextDelta("Room, for its migrations."))
                        emit(StreamEvent.Usage(4_000, 12, cachedInputTokens = 3_900, cacheReported = true))
                    }
                    messages.none { it.role == Role.TOOL } -> emit(StreamEvent.ToolCallBatch(listOf(ToolCallData("main-1", "slow_write", "{}"))))
                    else -> emit(StreamEvent.TextDelta("Main task done."))
                }
                emit(StreamEvent.Done(if (request.side && !answered || messages.none { it.role == Role.TOOL }) "tool_calls" else "stop"))
            }
        }
        val engine = AgentEngine({ provider }, registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val session = "btw-session-${System.nanoTime()}"
        try {
            val main = launch(Dispatchers.Default) {
                engine.run(session, "turn-1", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"),
                    "", listOf(ChatMessage(role = Role.USER, text = "Write the notes")), { PermissionMode.FULL_ACCESS }, mutableSetOf(),
                    FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, repoMapEnabled = false).toList()
            }
            // The main task is now blocked inside slow_write.
            val context = withTimeout(10_000) {
                var ctx: SideContext? = null
                while (ctx == null || executed.isEmpty()) { kotlinx.coroutines.delay(20); ctx = engine.sideContexts[session] }
                ctx
            }
            val events = withTimeout(10_000) { engine.askAside(context, "Why Room?").toList() }
            release.complete(Unit)
            main.join()

            val mainRequests = requests.filterNot { it.side }
            val side = requests.filter { it.side }
            assertEquals(2, mainRequests.size)
            assertEquals(2, side.size)
            // Byte-identical prefix: what the provider caches is exactly what the side request starts with.
            val first = mainRequests.first()
            for (request in side) {
                assertEquals(first.system, request.system)
                assertEquals(first.tools, request.tools)
                assertEquals(first.options, request.options)
                assertEquals(first.messages, request.messages.take(first.messages.size))
            }
            val pending = side.first().messages.single { it.role == Role.TOOL && it.toolCallId == "main-1" }
            assertEquals(SideQuestion.PENDING_RESULT, pending.text)

            assertEquals(listOf("slow_write", "read_notes"), executed.toList())
            val results = events.filterIsInstance<SideEvent.ToolFinished>().associateBy { it.message.toolCallId }
            assertTrue(results.getValue("side-read").message.text.contains("migrations"))
            assertTrue(results.getValue("side-write").message.isError)
            assertEquals(3_900, events.filterIsInstance<SideEvent.Usage>().single().usage.cachedInputTokens)
            assertEquals("Room, for its migrations.", events.filterIsInstance<SideEvent.AssistantCommitted>().last().message.text)
            assertTrue(events.last() is SideEvent.Finished)

            // The main task never saw the side exchange.
            assertFalse(mainRequests.any { r -> r.messages.any { it.text.contains("<side_question>") || it.toolCallId?.startsWith("side-") == true } })
            assertTrue(mainRequests.last().messages.single { it.toolCallId == "main-1" }.text.startsWith("written"))
        } finally {
            release.complete(Unit)
            root.deleteRecursively()
        }
    }
}
