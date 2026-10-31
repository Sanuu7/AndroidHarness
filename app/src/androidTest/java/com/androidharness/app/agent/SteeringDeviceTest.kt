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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** AH Auto against the real engine loop: picked up between steps, never mid-tool, cache prefix intact. */
@RunWith(AndroidJUnit4::class)
class SteeringDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    private data class Request(val system: String, val tools: List<ToolSchema>, val messages: List<ChatMessage>)

    private fun steer(id: String, text: String) = ChatMessage(role = Role.USER, text = text, id = Steering.messageId(id))

    @Test fun messagesJoinBetweenStepsAndDuringTheFinalAnswer(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "steer-${System.nanoTime()}").apply { mkdirs() }
        val inbox = ConcurrentLinkedQueue<ChatMessage>()
        val requests = CopyOnWriteArrayList<Request>()
        // What the tool saw in the request history when it ran.
        var steeredBeforeToolFinished = false
        val slow = object : Tool {
            override val name = "slow_build"
            override val description = name
            override val parametersSchema = JsonObject(emptyMap())
            override val isReadOnly = false
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                inbox += steer("a", "Also run the tests")
                kotlinx.coroutines.delay(200)
                steeredBeforeToolFinished = requests.any { r -> r.messages.any { Steering.isSteering(it) } }
                return ToolResult(true, "built")
            }
        }
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                requests += Request(systemPrompt, tools.toList(), messages.toList())
                when (requests.size) {
                    1 -> {
                        emit(StreamEvent.ToolCallBatch(listOf(ToolCallData("call-1", "slow_build", "{}"))))
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    2 -> {
                        // Sent while the final answer streams.
                        inbox += steer("b", "And summarize the diff")
                        emit(StreamEvent.TextDelta("Built and tested."))
                        emit(StreamEvent.Done("stop"))
                    }
                    else -> {
                        emit(StreamEvent.TextDelta("Here is the diff summary."))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        val engine = AgentEngine({ provider }, ToolRegistry(listOf(slow)), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        try {
            val events = withTimeout(20_000) {
                engine.run("steer-session-${System.nanoTime()}", "turn-1",
                    ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"),
                    "", listOf(ChatMessage(role = Role.USER, text = "Build it")), { PermissionMode.FULL_ACCESS }, mutableSetOf(),
                    FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, repoMapEnabled = false,
                    steering = { buildList { while (true) add(inbox.poll() ?: break) } }).toList()
            }

            assertEquals(3, requests.size)
            assertTrue("The running tool was never interrupted by the message", !steeredBeforeToolFinished)
            // Step boundary: after the tool result, before the next request, wrapped for the model.
            val second = requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), second.map { it.role })
            assertEquals(Steering.forModel(steer("a", "Also run the tests")), second.last())
            // Final answer: the turn keeps going instead of finishing.
            val third = requests[2].messages
            assertEquals("Built and tested.", third[third.size - 2].text)
            assertEquals(Steering.forModel(steer("b", "And summarize the diff")), third.last())
            // Each request starts with exactly the previous one, so the cache keeps hitting.
            for (i in 1 until requests.size) {
                assertEquals(requests[i - 1].system, requests[i].system)
                assertEquals(requests[i - 1].tools, requests[i].tools)
                assertEquals(requests[i - 1].messages, requests[i].messages.take(requests[i - 1].messages.size))
            }
            val injected = events.filterIsInstance<AgentEvent.UserMessageInjected>().map { it.message.text }
            assertEquals(listOf("Also run the tests", "And summarize the diff"), injected)
            assertEquals(1, events.count { it is AgentEvent.Finished })
            assertTrue(events.last() is AgentEvent.Finished)
        } finally {
            root.deleteRecursively()
        }
    }
}
