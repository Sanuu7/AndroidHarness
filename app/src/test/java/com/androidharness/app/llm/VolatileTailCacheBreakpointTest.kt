package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the Anthropic provider:
 *
 * 1. Places the rolling cache breakpoint on the last *non-ephemeral* message
 *    when [ephemeralTailCount] > 0.
 * 2. Merges an ephemeral USER tail into the preceding user/tool-result message
 *    rather than emitting a back-to-back user turn.
 * 3. Existing breakpoint behaviour (no tail) is unchanged.
 */
class VolatileTailCacheBreakpointTest {

    private val provider = AnthropicProvider(
        client = okhttp3.OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
    )

    private fun hasCacheBreakpoint(msg: JsonObject): Boolean {
        val content = msg["content"] ?: return false
        return when (content) {
            is JsonPrimitive -> false  // bare string has no cache_control block
            is kotlinx.serialization.json.JsonArray -> content.any { block ->
                (block as? JsonObject)?.containsKey("cache_control") == true
            }
            else -> false
        }
    }

    private fun userMsg(text: String): JsonObject = buildJsonObject {
        put("role", "user"); put("content", text)
    }

    private fun assistantMsg(text: String): JsonObject = buildJsonObject {
        put("role", "assistant"); put("content", text)
    }

    // ------------------------------------------------------------------ //
    // applyCacheBreakpoints with ephemeralTailCount                       //
    // ------------------------------------------------------------------ //

    @Test fun `rolling breakpoint lands on last non-ephemeral message`() {
        val msgs = listOf(
            userMsg("first"),
            assistantMsg("reply1"),
            userMsg("tool results"),
            userMsg("volatile tail"),  // index 3 = ephemeral tail
        )
        val result = provider.applyCacheBreakpoints(msgs, ephemeralTailCount = 1)
        // Rolling breakpoint should be on index 2 (last non-ephemeral), not index 3.
        assertFalse("volatile tail must NOT get cache_control", hasCacheBreakpoint(result[3]))
        assertTrue("last stable message MUST get cache_control", hasCacheBreakpoint(result[2]))
    }

    @Test fun `rolling breakpoint on last message when no ephemeral tail`() {
        val msgs = listOf(userMsg("first"), assistantMsg("r"), userMsg("last"))
        val result = provider.applyCacheBreakpoints(msgs, ephemeralTailCount = 0)
        assertTrue(hasCacheBreakpoint(result[2]))   // rolling on last
        assertTrue(hasCacheBreakpoint(result[0]))   // anchor on first user
    }

    @Test fun `zero messages returns empty list`() {
        val result = provider.applyCacheBreakpoints(emptyList(), ephemeralTailCount = 0)
        assertTrue(result.isEmpty())
    }

    @Test fun `ephemeralTailCount exceeding list size does not crash`() {
        val msgs = listOf(userMsg("only"))
        // coerceAtLeast(0) prevents negative last index
        val result = provider.applyCacheBreakpoints(msgs, ephemeralTailCount = 5)
        assertEquals(1, result.size)
    }

    // ------------------------------------------------------------------ //
    // serializeMessages: ephemeral tail merged into preceding user msg    //
    // ------------------------------------------------------------------ //

    @Test fun `ephemeral tail is merged into preceding tool-result user message`() {
        val call = com.androidharness.app.core.ToolCallData("id1", "shell", "{}")
        val messages = listOf(
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call)),
            ChatMessage(
                role = Role.TOOL,
                text = "tool output",
                toolCallId = "id1",
                toolName = "shell",
            ),
            ChatMessage(
                role = Role.USER,
                text = "<harness-context>env</harness-context>",
                ephemeral = true,
            ),
        )
        val serialized = provider.serializeMessages(messages)
        // Anthropic: [assistant(tool_use), user(tool_result + ephemeral text)]
        assertEquals("should produce 2 messages (not 3)", 2, serialized.size)
        val lastMsg = serialized.last()
        assertEquals("user", lastMsg["role"]?.jsonPrimitive?.contentOrNull)
        // content must be an array containing both tool_result and the ephemeral text
        val content = lastMsg["content"]?.jsonArray
        assertNotNull("content should be an array", content)
        val types = content!!.mapNotNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull }
        assertTrue("should contain tool_result", "tool_result" in types)
        assertTrue("should contain text (ephemeral tail)", "text" in types)
        val textBlock = content.firstOrNull {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text"
        } as? JsonObject
        assertNotNull(textBlock)
        assertTrue(
            "text block should contain the volatile tail content",
            textBlock!!["text"]?.jsonPrimitive?.contentOrNull?.contains("harness-context") == true,
        )
    }

    @Test fun `ephemeral tail without preceding user message is emitted normally`() {
        // When the model hasn't made any tool calls, working ends with an ASSISTANT
        // message, so the volatile tail is not back-to-back with another user message.
        val messages = listOf(
            ChatMessage(role = Role.USER, text = "question"),
            ChatMessage(role = Role.ASSISTANT, text = "thinking"),
            ChatMessage(
                role = Role.USER,
                text = "<harness-context>env</harness-context>",
                ephemeral = true,
            ),
        )
        // ASSISTANT → USER(ephemeral): no merging needed, just emitted as a user message.
        val serialized = provider.serializeMessages(messages)
        assertEquals(3, serialized.size)
        assertEquals("user", serialized.last()["role"]?.jsonPrimitive?.contentOrNull)
    }

    @Test fun `non-ephemeral user message after tool results is NOT merged`() {
        val call = com.androidharness.app.core.ToolCallData("id2", "shell", "{}")
        val messages = listOf(
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call)),
            ChatMessage(role = Role.TOOL, text = "output", toolCallId = "id2", toolName = "shell"),
            // Regular (non-ephemeral) user message: should NOT be merged, but Anthropic
            // would reject this sequence.  The test just verifies that the merge only
            // happens for ephemeral messages.
            ChatMessage(role = Role.USER, text = "follow-up", ephemeral = false),
        )
        val serialized = provider.serializeMessages(messages)
        // Non-ephemeral is emitted as a separate user message (merge guard is off).
        // Result is [assistant, user(tool_result), user(follow-up)] — 3 items.
        // (Anthropic would later reject the back-to-back user turns, but that's a
        // pre-existing constraint unrelated to the volatile-tail logic.)
        assertEquals(3, serialized.size)
    }
}
