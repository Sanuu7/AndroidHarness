package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalJudgeTest {
    @Test fun `verdicts parse from plain, fenced and wrapped replies`() {
        assertEquals(GoalVerdict(GoalVerdict.Outcome.MET, "Tests pass.", ""),
            GoalJudge.parse("""{"verdict": "met", "reason": "Tests pass."}"""))
        val fenced = GoalJudge.parse("Here it is:\n```json\n{\"verdict\":\"continue\",\"reason\":\"Two tests fail.\",\"next\":\"Fix the parser.\"}\n```")
        assertEquals(GoalVerdict(GoalVerdict.Outcome.CONTINUE, "Two tests fail.", "Fix the parser."), fenced)
        assertEquals(GoalVerdict.Outcome.BLOCKED, GoalJudge.parse("""{"verdict":"BLOCKED","reason":"Needs an API key"}""")?.outcome)
    }

    @Test fun `missing or unknown verdicts never count as met`() {
        assertNull(GoalJudge.parse("The goal is met."))
        assertNull(GoalJudge.parse("""{"verdict": "maybe", "reason": "unsure"}"""))
        assertNull(GoalJudge.parse("""{"reason": "no verdict field"}"""))
        assertNull(GoalJudge.parse("{not json}"))
    }

    @Test fun `blank reasons get a readable default and long ones are capped`() {
        assertEquals("Work remains.", GoalJudge.parse("""{"verdict":"continue"}""")?.reason)
        val long = "x".repeat(5_000)
        assertEquals(600, GoalJudge.parse("""{"verdict":"continue","reason":"$long"}""")?.reason?.length)
    }

    @Test fun `goal command reads status, clear words and goal text`() {
        assertEquals(GoalJudge.Command.Status, GoalJudge.command("  "))
        for (word in listOf("clear", "OFF", "stop", "cancel", "none")) assertEquals(GoalJudge.Command.Clear, GoalJudge.command(word))
        assertEquals(GoalJudge.Command.Set("all tests pass"), GoalJudge.command(" all tests pass "))
        assertEquals(GoalJudge.MAX_GOAL_CHARS, (GoalJudge.command("y".repeat(9_000)) as GoalJudge.Command.Set).text.length)
    }

    @Test fun `only active and blocked goals are checked`() {
        assertTrue(GoalState("g").checking)
        assertTrue(GoalState("g", status = GoalState.Status.BLOCKED).checking)
        assertFalse(GoalState("g", status = GoalState.Status.MET).checking)
        assertFalse(GoalState("g", status = GoalState.Status.LIMIT).checking)
    }

    @Test fun `prompt carries the goal and recorded check results`() {
        val prompt = GoalJudge.prompt("make the build green", listOf(
            VerificationCheck("./gradlew test", CheckStatus.FAILED),
            VerificationCheck("./gradlew lint", CheckStatus.STALE),
        ))
        assertTrue(prompt.contains("make the build green"))
        assertTrue(prompt.contains("`./gradlew test`: failed"))
        assertTrue(prompt.contains("`./gradlew lint`: passed, but files changed afterwards"))
        assertFalse(GoalJudge.prompt("g", emptyList()).contains("Checks run in this chat"))
    }

    @Test fun `continuation names progress, the gap, the next step and the goal`() {
        val text = GoalJudge.continuation(GoalState("ship it", turns = 3, turnLimit = 20),
            GoalVerdict(GoalVerdict.Outcome.CONTINUE, "Lint fails.", "Fix the unused import."))
        assertTrue(text.startsWith("Goal check 3 of 20: not done yet."))
        assertTrue(text.contains("Lint fails.\nNext: Fix the unused import."))
        assertTrue(text.endsWith("Keep working toward the goal: ship it"))
        assertFalse(GoalJudge.continuation(GoalState("g"), GoalVerdict(GoalVerdict.Outcome.CONTINUE, "r")).contains("Next:"))
    }

    @Test fun `warm check adds the final reply the last request could not hold`() {
        val call = ToolCallData("c1", "shell", "{}")
        val request = listOf(
            ChatMessage(role = Role.USER, text = "goal", id = "u"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call), id = "a1"),
            ChatMessage(role = Role.TOOL, text = "ok", toolCallId = "c1", toolName = "shell", id = "t1"),
        )
        val answer = ChatMessage(role = Role.ASSISTANT, text = "All done, tests pass.", id = "a2")
        val turn = request + answer
        assertEquals(request + answer, GoalJudge.warmMessages(request, turn))
        // Already present: nothing is added twice.
        assertEquals(request + answer, GoalJudge.warmMessages(request + answer, turn))
        // A turn that ended on tool calls adds nothing.
        assertEquals(request, GoalJudge.warmMessages(request, request))
    }

    @Test fun `saved goal survives a task record round trip and old records have none`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val record = TaskRecord(goal = GoalState("all tests pass", status = GoalState.Status.BLOCKED, turns = 4, lastReason = "Needs a key"))
        assertEquals(record.goal, json.decodeFromString<TaskRecord>(json.encodeToString(TaskRecord.serializer(), record)).goal)
        assertNull(json.decodeFromString<TaskRecord>("""{"status":"idle"}""").goal)
    }
}
