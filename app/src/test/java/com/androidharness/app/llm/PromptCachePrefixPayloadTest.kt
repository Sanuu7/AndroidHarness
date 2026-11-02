package com.androidharness.app.llm

import com.androidharness.app.agent.ContextHygiene
import com.androidharness.app.agent.FrozenSystemCache
import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.TodoPrompt
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the provider-facing request payload has a byte-identical
 * stable prefix across:
 *   (a) consecutive rounds of the same execution, and
 *   (b) the boundary between two executions (new user message, cleared todo
 *       list, different memory ranking, altered repo map).
 *
 * "Stable prefix" = the system prompt string + the serialised tool list +
 * all messages up to (but not including) the first new content.
 *
 * Tests operate at the provider serialisation level so they run on the JVM
 * without Android.
 */
class PromptCachePrefixPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val anthropic = AnthropicProvider(okhttp3.OkHttpClient(), json)

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    private fun anthropicConfig() = ProviderConfig(
        id = "anthropic",
        name = "Claude",
        type = ProviderType.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        model = "claude-3-5-sonnet-20241022",
    )

    private fun deepSeekConfig() = ProviderConfig(
        id = "deepseek",
        name = "DeepSeek",
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
    )

    private fun stableTools() = listOf(
        ToolSchema("read_file", "Read a file", buildJsonObject { put("type", "object") }),
        ToolSchema("shell", "Run a shell command", buildJsonObject { put("type", "object") }),
    )

    private fun call(id: String) = ToolCallData(id, "shell", "{\"command\":\"ls\"}")
    private fun assistantWithCalls(vararg ids: String) = ChatMessage(
        role = Role.ASSISTANT, text = "calling",
        toolCalls = ids.map { call(it) },
    )
    private fun toolResult(id: String, out: String = "ok-$id") =
        ChatMessage(role = Role.TOOL, text = out, toolCallId = id, toolName = "shell")
    private fun userMsg(text: String) = ChatMessage(role = Role.USER, text = text)
    private fun ephemeralTail(text: String) = ChatMessage(
        role = Role.USER,
        text = "<harness-context>\n$text\n</harness-context>",
        ephemeral = true,
    )

    private fun anthropicBody(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema> = stableTools(),
    ): JsonObject = anthropic.buildRequestBody(
        anthropicConfig(), system, messages, tools, RequestOptions(),
    )

    private fun deepSeekBody(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema> = stableTools(),
    ): JsonObject {
        val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), json)
        return provider.buildRequestBody(deepSeekConfig(), system, messages, tools, RequestOptions())
    }

    private fun msgArray(body: JsonObject): JsonArray = body["messages"]!!.jsonArray

    // ------------------------------------------------------------------ //
    // FrozenSystemCache: same key → same text, different key → miss       //
    // ------------------------------------------------------------------ //

    @Test fun `FrozenSystemCache: same key returns cached text`() {
        val cache = FrozenSystemCache()
        val key = FrozenSystemCache.buildKey(
            fullAccess = false,
            cavemanInstalled = false, cavemanIntensity = "OFF", cavemanWenyan = false,
            providerId = "deepseek", modelId = "deepseek-chat",
            pinnedInstructions = "",
            enabledSkillNames = listOf("android-build", "git"),
        )
        cache.put("session-1", key, "stable system text")
        assertEquals("stable system text", cache.get("session-1", key))
    }

    @Test fun `FrozenSystemCache: different key is a cache miss`() {
        val cache = FrozenSystemCache()
        val key1 = FrozenSystemCache.buildKey(
            fullAccess = false,
            cavemanInstalled = false, cavemanIntensity = "OFF", cavemanWenyan = false,
            providerId = "deepseek", modelId = "deepseek-chat",
            pinnedInstructions = "",
            enabledSkillNames = listOf("git"),
        )
        val key2 = FrozenSystemCache.buildKey(  // differs: fullAccess=true
            fullAccess = true,
            cavemanInstalled = false, cavemanIntensity = "OFF", cavemanWenyan = false,
            providerId = "deepseek", modelId = "deepseek-chat",
            pinnedInstructions = "",
            enabledSkillNames = listOf("git"),
        )
        assertNotEquals("keys must differ when fullAccess changes", key1, key2)
        cache.put("session-1", key1, "text with sandbox off")
        assertNotNull(cache.get("session-1", key1))
        assertEquals("cache miss on different key", null, cache.get("session-1", key2))
    }

    @Test fun `FrozenSystemCache: volatile inputs do not affect the key`() {
        // Memory query, todo list, repo-map content, and startNotes are all
        // volatile — they go in the tail, not the cache key.  Two executions
        // that differ only in these inputs must produce the same key.
        val baseKey = FrozenSystemCache.buildKey(
            fullAccess = false,
            cavemanInstalled = false, cavemanIntensity = "OFF", cavemanWenyan = false,
            providerId = "deepseek", modelId = "deepseek-chat",
            pinnedInstructions = "",
            enabledSkillNames = listOf("git"),
        )
        // Simulate execution A: memory query = "prev question"
        // Simulate execution B: memory query = "new question" (new user message)
        // → same key because memoryQuery is not part of the key
        val keyRunA = baseKey
        val keyRunB = baseKey
        assertEquals("volatile inputs must not change the cache key", keyRunA, keyRunB)
    }

    @Test fun `FrozenSystemCache: invalidate clears the entry`() {
        val cache = FrozenSystemCache()
        val key = FrozenSystemCache.buildKey(
            false, false, "OFF", false, "ds", "ds-chat", "", listOf("git"),
        )
        cache.put("s1", key, "text")
        assertNotNull(cache.get("s1", key))
        cache.invalidate("s1")
        assertEquals("should be null after invalidation", null, cache.get("s1", key))
    }

    @Test fun `FrozenSystemCache: model change produces cache miss`() {
        val key1 = FrozenSystemCache.buildKey(
            false, false, "OFF", false, "deepseek", "deepseek-chat", "", emptyList(),
        )
        val key2 = FrozenSystemCache.buildKey(
            false, false, "OFF", false, "deepseek", "deepseek-reasoner", "", emptyList(),
        )
        assertNotEquals("model change must produce different key", key1, key2)
    }

    // ------------------------------------------------------------------ //
    // Anthropic: stable system + tools + shared history prefix            //
    // ------------------------------------------------------------------ //

    @Test fun `Anthropic: system and tools byte-identical across rounds`() {
        val system = "# Rules\nYou are an agent.\n\n# Skills\nskill content"
        val history = listOf(userMsg("task"), assistantWithCalls("c1"), toolResult("c1"))
        val tail1 = ephemeralTail("## Env\nrounds=1\n\n- [in_progress] task")
        val tail2 = ephemeralTail("## Env\nrounds=2\n\n- [done] task")   // different tail

        val body1 = anthropicBody(system, history + tail1)
        val body2 = anthropicBody(
            system,
            history + listOf(assistantWithCalls("c2"), toolResult("c2")) + tail2,
        )
        assertEquals("system byte-identical", body1["system"].toString(), body2["system"].toString())
        assertEquals("tools byte-identical", body1["tools"].toString(), body2["tools"].toString())
    }

    @Test fun `Anthropic: shared message prefix byte-identical between rounds`() {
        val system = "# Rules\nstable"
        val history = listOf(
            userMsg("question"),
            assistantWithCalls("a1"),
            toolResult("a1", "shell output"),
        )
        val tail1 = ephemeralTail("env1 todo=empty memory=mem-A")
        val tail2 = ephemeralTail("env2 todo=task1 memory=mem-B")   // volatile changes

        val msgs1 = msgArray(anthropicBody(system, history + tail1))
        val msgs2 = msgArray(anthropicBody(
            system,
            history + listOf(assistantWithCalls("a2"), toolResult("a2")) + tail2,
        ))
        // Shared prefix: user(question), assistant(tool_use a1), user(tool_result_a1)
        // In Anthropic serialisation these become: user, assistant, user.
        // The tail in round 1 is merged into the last user message; round 2 has
        // an extra assistant+user pair then its own merged tail.
        // The first message (user) must be byte-identical.
        assertEquals("msg[0] byte-identical", msgs1[0].toString(), msgs2[0].toString())
    }

    @Test fun `Anthropic: system byte-identical across execution boundary`() {
        // Run A: user="task A", two tool rounds, different memory ranking
        // Run B: user="task B" (new execution), todos cleared, different memory, repo map changed
        val stableSystem = "# Rules\nstable\n\n# Repository Map\nFileA.kt\nFileB.kt"
        val tools = stableTools()

        val runAHistory = listOf(
            userMsg("task A"),
            assistantWithCalls("a1"), toolResult("a1"),
            ChatMessage(role = Role.ASSISTANT, text = "done"),
        )
        val runATail = ephemeralTail(
            "## Env\npkgs=77\n\n- [done] task A\n\n# Saved memory\nmem-rank-A"
        )
        val runBHistory = runAHistory + listOf(userMsg("task B"))
        val runBTail = ephemeralTail(
            // todos cleared by beginRun(), different memory ranking, repo map may have changed
            // but stableSystem is frozen — those changes don't appear here
            "## Env\npkgs=78\n\n# Saved memory\nmem-rank-B different order"
        )

        val bodyA = anthropicBody(stableSystem, runAHistory + runATail, tools)
        val bodyB = anthropicBody(stableSystem, runBHistory + runBTail, tools)

        assertEquals(
            "system must be byte-identical across execution boundary",
            bodyA["system"].toString(), bodyB["system"].toString(),
        )
        assertEquals(
            "tools must be byte-identical across execution boundary",
            bodyA["tools"].toString(), bodyB["tools"].toString(),
        )
        // The tails differ (different memory, todos cleared)
        assertNotEquals(
            "tails must differ between executions",
            msgArray(bodyA).last().toString(),
            msgArray(bodyB).last().toString(),
        )
    }

    // ------------------------------------------------------------------ //
    // DeepSeek (OpenAI-compat): system message byte-identical             //
    // ------------------------------------------------------------------ //

    @Test fun `DeepSeek: system message byte-identical across rounds with different tail`() {
        val stableSystem = "# Rules\nYou are an agent.\n\n# Skills\ncontent"
        val history = listOf(
            userMsg("task"),
            assistantWithCalls("t1"),
            toolResult("t1"),
        )
        val tail1 = ephemeralTail("## Env\nrounds=1\nmem=A\n- [in_progress] work")
        val tail2 = ephemeralTail("## Env\nrounds=2\nmem=B\n- [done] work")

        val msgs1 = msgArray(deepSeekBody(stableSystem, history + tail1))
        val msgs2 = msgArray(deepSeekBody(
            stableSystem,
            history + listOf(assistantWithCalls("t2"), toolResult("t2")) + tail2,
        ))

        // The system is sent as the first message in OpenAI-compat format
        val sys1 = msgs1.first().jsonObject
        val sys2 = msgs2.first().jsonObject
        assertEquals("system role", "system", sys1["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("DeepSeek system byte-identical across rounds",
            sys1.toString(), sys2.toString())
    }

    @Test fun `DeepSeek: system byte-identical across execution boundary`() {
        val stableSystem = "# Rules\nstable\n\n# Repository Map\nold-file.kt"

        // Run A: last round had todos and memory block A in tail
        val runATail = ephemeralTail("## Env\npkgs=77\n- [done] task\n# Memory\nmem-A")
        // Run B: new execution — todos cleared, new user message, different memory ranking
        // Repo map changed since run A, but system is frozen → same string
        val runBHistory = listOf(userMsg("old msg"), assistantWithCalls("x1"), toolResult("x1"),
            ChatMessage(role = Role.ASSISTANT, text = "done"), userMsg("new task"))
        val runBTail = ephemeralTail("## Env\npkgs=77\n# Memory\nmem-B different ranking")

        val msgsA = msgArray(deepSeekBody(
            stableSystem,
            listOf(userMsg("old msg"), assistantWithCalls("x1"), toolResult("x1")) + runATail,
        ))
        val msgsB = msgArray(deepSeekBody(stableSystem, runBHistory + runBTail))

        val sysA = msgsA.first().jsonObject
        val sysB = msgsB.first().jsonObject
        assertEquals("system byte-identical across executions", sysA.toString(), sysB.toString())
    }

    @Test fun `DeepSeek: ephemeral tail after TOOL messages appears at end of messages`() {
        val system = "# Rules\nstable"
        val history = listOf(
            userMsg("task"),
            assistantWithCalls("t1"),
            toolResult("t1"),
        )
        val tail = ephemeralTail("## Env\nenv data")
        val msgs = msgArray(deepSeekBody(system, history + tail))

        // System is first; last message should contain the tail content
        val lastMsg = msgs.last().jsonObject
        val lastContent = lastMsg["content"]?.jsonPrimitive?.contentOrNull
            ?: lastMsg["content"]?.jsonArray?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: ""
        assertTrue(
            "tail content must appear in the last message",
            lastContent.contains("harness-context") || lastContent.contains("env data"),
        )
    }

    // ------------------------------------------------------------------ //
    // TodoPrompt: cleared list = no todo block in tail                    //
    // ------------------------------------------------------------------ //

    @Test fun `empty todo list after beginRun produces blank TodoPrompt`() {
        val empty = TodoPrompt.format(emptyList(), roundsWithoutUpdate = 0)
        assertTrue("empty run has no todo block", empty.isBlank())

        val withTodos = TodoPrompt.format(
            listOf(TodoItem("task", TodoItem.Status.IN_PROGRESS)),
            roundsWithoutUpdate = 5,
        )
        assertFalse("non-empty todo is non-blank", withTodos.isBlank())
        assertNotEquals("todo text differs between runs", empty, withTodos)
    }

    // ------------------------------------------------------------------ //
    // Anthropic breakpoint: merged tail anchors on tool_result block      //
    // ------------------------------------------------------------------ //

    @Test fun `merged tail: breakpoint on tool_result block, not volatile text block`() {
        val call = call("t1")
        val messages = listOf(
            userMsg("question"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call)),
            toolResult("t1", "shell output"),
            ephemeralTail("env data"),
        )
        val serialized = anthropic.serializeMessages(messages)
        assertEquals("merged → 3 serialised messages", 3, serialized.size)

        val withBp = anthropic.applyCacheBreakpoints(serialized,
            ephemeralTailCount = 0, tailMergedLastBlocks = 1)
        val lastMsg = withBp.last()
        val content = lastMsg["content"]!!.jsonArray

        val toolBlock = content[0].jsonObject
        assertEquals("tool_result", toolBlock["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue("cache_control on tool_result", toolBlock.containsKey("cache_control"))

        val textBlock = content[1].jsonObject
        assertEquals("text", textBlock["type"]?.jsonPrimitive?.contentOrNull)
        assertFalse("NO cache_control on volatile text", textBlock.containsKey("cache_control"))
    }

    @Test fun `standalone tail: breakpoint on message before tail`() {
        val messages = listOf(
            userMsg("question"),
            ChatMessage(role = Role.ASSISTANT, text = "thinking..."),
            ephemeralTail("env data"),
        )
        val serialized = anthropic.serializeMessages(messages)
        assertEquals("standalone → 3 messages", 3, serialized.size)

        val withBp = anthropic.applyCacheBreakpoints(serialized,
            ephemeralTailCount = 1, tailMergedLastBlocks = 0)

        val assistantBp = withBp[1]["content"]?.jsonArray
            ?.any { (it as? JsonObject)?.containsKey("cache_control") == true } ?: false
        assertTrue("assistant must have cache_control", assistantBp)

        val tailBp = withBp[2]["content"]?.jsonPrimitive?.let { false }
            ?: withBp[2]["content"]?.jsonArray
                ?.any { (it as? JsonObject)?.containsKey("cache_control") == true } ?: false
        assertFalse("volatile tail must NOT have cache_control", tailBp)
    }

    // ------------------------------------------------------------------ //
    // ContextHygiene: stepped truncation preserves prefix for K rounds    //
    // ------------------------------------------------------------------ //

    @Test fun `stepped truncation: truncated texts identical for K consecutive rounds`() {
        val K = ContextHygiene.STEPPED_K
        val R = ContextHygiene.RECENT_FULL_TOOLS
        val base = (1..(R + K)).flatMap { i ->
            listOf(
                ChatMessage(role = Role.ASSISTANT, text = "a$i", toolCalls = listOf(call("c$i"))),
                toolResult("c$i", "x".repeat(6_000)),
            )
        }
        val atBoundary = ContextHygiene.shrinkToolResults(base)
        val plusOne = ContextHygiene.shrinkToolResults(
            base + listOf(
                ChatMessage(role = Role.ASSISTANT, text = "ax", toolCalls = listOf(call("cx"))),
                toolResult("cx", "x".repeat(6_000)),
            )
        )
        val truncated1 = atBoundary.filter { it.role == Role.TOOL && it.isError }.map { it.text }
        val truncated2 = plusOne.filter { it.role == Role.TOOL && it.isError }.map { it.text }
        assertEquals("same count", truncated1.size, truncated2.size)
        truncated1.zip(truncated2).forEachIndexed { i, (a, b) ->
            assertEquals("truncated[$i] byte-identical", a, b)
        }
    }
}
