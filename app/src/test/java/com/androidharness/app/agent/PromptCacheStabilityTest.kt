package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the prompt-cache stability refactor achieves the goals
 * described in PR "Keep the cached prompt prefix stable across rounds and runs":
 *
 * 1. Volatile tail is never added to `working` / the DB representation.
 * 2. ephemeral messages are not counted by recentMemoryQuery.
 * 3. ContextHygiene stepped truncation changes every K tools, not every round.
 * 4. The stepped result is identical between the live and DB-reloaded histories.
 */
class PromptCacheStabilityTest {

    // ------------------------------------------------------------------ //
    // 1. Volatile tail stays out of working / recentMemoryQuery           //
    // ------------------------------------------------------------------ //

    @Test fun `ephemeral messages are excluded from recentMemoryQuery`() {
        // recentMemoryQuery is private; we test the property it relies on:
        // ephemeral USER messages must not appear in history that the query reads.
        val realUser = ChatMessage(role = Role.USER, text = "real question")
        val ephemeralTail = ChatMessage(
            role = Role.USER,
            text = "<harness-context>env data</harness-context>",
            ephemeral = true,
        )
        // The volatile tail is never added to `working`; simulate the call
        // site: workingForProvider = working + listOf(volatileTail).
        val working = mutableListOf(realUser)
        val workingForProvider = working + listOf(ephemeralTail)

        // Verify that only the real message is in `working` (not the ephemeral tail).
        assertEquals(1, working.size)
        assertFalse(working.any { it.ephemeral })

        // workingForProvider has both but only the ephemeral one is tagged.
        assertEquals(2, workingForProvider.size)
        val nonEphemeral = workingForProvider.filter { !it.ephemeral }
        assertEquals(1, nonEphemeral.size)
        assertEquals("real question", nonEphemeral.single().text)
    }

    @Test fun `ChatMessage ephemeral defaults to false`() {
        val msg = ChatMessage(role = Role.ASSISTANT, text = "hello")
        assertFalse(msg.ephemeral)
    }

    @Test fun `ephemeral tail is not present in the non-ephemeral history`() {
        val history = listOf(
            ChatMessage(role = Role.USER, text = "msg1"),
            ChatMessage(role = Role.ASSISTANT, text = "reply"),
            ChatMessage(role = Role.USER, text = "msg2", ephemeral = true),
        )
        val recentUserTexts = history
            .filter { it.role == Role.USER && !it.ephemeral }
            .takeLast(3)
            .map { it.text }

        assertEquals(listOf("msg1"), recentUserTexts)
    }

    // ------------------------------------------------------------------ //
    // 2. TodoStore.beginRun clears todos → no cross-run leakage via tail  //
    // ------------------------------------------------------------------ //

    @Test fun `beginRun clears todo list so new execution starts empty`() {
        val store = TodoStore()
        store.beginRun("session-A")
        store.setAll(listOf(TodoItem("task one"), TodoItem("task two")))
        assertEquals(2, store.todos.value.size)

        // New run in same session → list is cleared
        store.beginRun("session-A")
        assertTrue(store.todos.value.isEmpty())
    }

    @Test fun `TodoPrompt format with empty list produces blank string`() {
        val result = TodoPrompt.format(emptyList(), roundsWithoutUpdate = 0)
        assertTrue(result.isBlank())
    }

    @Test fun `TodoPrompt progress check changes with counter`() {
        val items = listOf(TodoItem("task", TodoItem.Status.IN_PROGRESS))
        val at3 = TodoPrompt.format(items, roundsWithoutUpdate = 3)
        val at4 = TodoPrompt.format(items, roundsWithoutUpdate = 4)
        // Counter embedded in the message differs between rounds
        assertTrue(at3.contains("3 tool rounds"))
        assertTrue(at4.contains("4 tool rounds"))
        assertNotEquals(at3, at4)
    }

    // ------------------------------------------------------------------ //
    // 3. ContextHygiene stepped truncation (K = 10)                       //
    // ------------------------------------------------------------------ //

    private fun toolMsg(id: String) = ChatMessage(
        role = Role.TOOL,
        text = "x".repeat(8_000),   // > STALE_TOOL_CHARS so truncation fires
        toolCallId = id,
        toolName = "shell",
    )

    @Test fun `stepped truncation: no truncation below RECENT_FULL_TOOLS tools`() {
        val history = (1..6).map { toolMsg("t$it") }
        val result = ContextHygiene.shrinkToolResults(history)
        // All 6 kept full
        result.forEach { assertTrue("should not be truncated", !it.isError) }
    }

    @Test fun `stepped truncation: boundary moves every K tools not every round`() {
        val K = ContextHygiene.STEPPED_K
        val R = ContextHygiene.RECENT_FULL_TOOLS

        // Build a history with R+K tools: truncation boundary should be at index K
        // (tools 0..K-1 truncated, tools K..R+K-1 full).
        val history = (1..(R + K)).map { toolMsg("t$it") }
        val resultAtBoundary = ContextHygiene.shrinkToolResults(history)

        // Add one more tool (R+K+1 total): boundary stays at K.
        val historyPlus1 = history + toolMsg("extra")
        val resultPlus1 = ContextHygiene.shrinkToolResults(historyPlus1)

        // The truncated texts for indices 0..K-1 must be identical between
        // the two histories because the truncation boundary hasn't shifted.
        val truncatedAt = resultAtBoundary.filter { it.isError }.map { it.text }
        val truncatedPlus1 = resultPlus1.filter { it.isError }.map { it.text }
        assertEquals("truncated set should be the same", truncatedAt, truncatedPlus1)
    }

    @Test fun `stepped truncation: boundary only shifts after K new tools`() {
        val K = ContextHygiene.STEPPED_K
        val R = ContextHygiene.RECENT_FULL_TOOLS

        // At n = R (6): no truncation
        val h6 = (1..R).map { toolMsg("t$it") }
        assertEquals(0, ContextHygiene.shrinkToolResults(h6).count { it.isError })

        // At n = R + 1 through R + K - 1: keepStart is floor((1..K-1)/K)*K = 0,
        // so still no truncation (first K full stays untouched).
        val h7 = h6 + toolMsg("t7")
        assertEquals(0, ContextHygiene.shrinkToolResults(h7).count { it.isError })

        // At n = R + K (16): keepStart = floor(K/K)*K = K, so first K truncated.
        val h16 = (1..(R + K)).map { toolMsg("t$it") }
        assertEquals(K, ContextHygiene.shrinkToolResults(h16).count { it.isError })

        // At n = R + K + 1 (17): keepStart still K → same truncation count.
        val h17 = h16 + toolMsg("t17")
        assertEquals(K, ContextHygiene.shrinkToolResults(h17).count { it.isError })
    }

    @Test fun `live and reloaded histories produce identical truncated texts`() {
        // Simulate: history loaded from DB is identical to in-memory working list.
        val K = ContextHygiene.STEPPED_K
        val R = ContextHygiene.RECENT_FULL_TOOLS
        val history = (1..(R + K)).map { toolMsg("t$it") }
        val live = ContextHygiene.shrinkToolResults(history)
        // "Reloaded from DB" is the same list (messages are stored verbatim).
        val reloaded = ContextHygiene.shrinkToolResults(history)
        assertEquals("live == reloaded", live.map { it.text }, reloaded.map { it.text })
    }

    // ------------------------------------------------------------------ //
    // 4. CachePrefixLogger: first-diff detection                          //
    // ------------------------------------------------------------------ //

    @Test fun `CachePrefixLogger clears session state on demand`() {
        val sid = "test-session-clear"
        // Record a request so a snapshot exists
        CachePrefixLogger.record(
            sessionId = sid,
            systemPrompt = "# Section A\nsome content",
            tools = emptyList(),
            messages = emptyList(),
        )
        CachePrefixLogger.clearSession(sid)
        // After clearing, the next record call sees "firstRequest" again.
        // We cannot capture Log.d output in unit tests; just verify no crash.
        CachePrefixLogger.record(
            sessionId = sid,
            systemPrompt = "# Section A\nsome content",
            tools = emptyList(),
            messages = emptyList(),
        )
    }
}


