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
        assertFalse(GoalState("g", status = GoalState.Status.PAUSED).checking)
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

    @Test fun `parts parse, skip nameless entries and stay distinct`() {
        val verdict = GoalJudge.parse("""{"verdict":"continue","reason":"r","parts":[
            {"name":"Build compiles","done":true},{"name":"Tests pass"},{"done":true},{"name":"build compiles","done":false}]}""")
        assertEquals(listOf("Build compiles" to true, "Tests pass" to false), verdict?.parts)
        assertEquals(emptyList<Pair<String, Boolean>>(), GoalJudge.parse("""{"verdict":"met","parts":"none"}""")?.parts)
    }

    @Test fun `checks build history and parts keep the turn they were first done`() {
        var goal = GoalState("g", turns = 1)
        goal = goal.withCheck(GoalCheck(1, GoalCheck.Outcome.CONTINUE, "a"), listOf("Build" to true, "Tests" to false))
        goal = goal.copy(turns = 2).withCheck(GoalCheck(2, GoalCheck.Outcome.CONTINUE, "b"), listOf("build" to true, "Tests" to true))
        assertEquals(listOf(1, 2), goal.history.map { it.turn })
        assertEquals(listOf(1, 2), goal.parts.map { it.doneAtTurn })
        // A check without parts keeps the last known ones.
        goal = goal.withCheck(GoalCheck(3, GoalCheck.Outcome.FAILED, "x"), emptyList())
        assertEquals(2, goal.parts.size)
        var long = GoalState("g")
        repeat(GoalJudge.MAX_HISTORY + 5) { long = long.withCheck(GoalCheck(it, GoalCheck.Outcome.CONTINUE, "r"), emptyList()) }
        assertEquals(GoalJudge.MAX_HISTORY, long.history.size)
        assertEquals(5, long.history.first().turn)
    }

    @Test fun `continuations read back as checkpoints and other text does not`() {
        val text = GoalJudge.continuation(GoalState("ship it", turns = 3, turnLimit = 10),
            GoalVerdict(GoalVerdict.Outcome.CONTINUE, "Lint fails.\nTwo warnings.", "Fix the import."))
        assertEquals(GoalJudge.Checkpoint(3, 10, "Lint fails.\nTwo warnings.", "Fix the import."), GoalJudge.checkpoint(text))
        val bare = GoalJudge.continuation(GoalState("g", turns = 1), GoalVerdict(GoalVerdict.Outcome.CONTINUE, "r"))
        assertEquals("", GoalJudge.checkpoint(bare)?.next)
        assertNull(GoalJudge.checkpoint("Goal check 3 of 10: not done yet.\nno marker"))
        assertNull(GoalJudge.checkpoint("hello"))
        assertTrue(GoalJudge.isResume(GoalJudge.resume("g")))
        assertTrue(GoalJudge.isResume(GoalJudge.resume("g", improving = true)))
        assertTrue(GoalJudge.resume("g", improving = true).contains("improve its weakest part"))
        assertFalse(GoalJudge.isResume("Goal resumed."))
    }

    @Test fun `kickoff carries the check command and reads back to the goal text`() {
        val kickoff = GoalJudge.kickoff(" all tests pass ", "./gradlew test")
        assertEquals("all tests pass", GoalJudge.kickoffGoal(kickoff))
        assertTrue(kickoff.contains("`./gradlew test` passes"))
        assertFalse(GoalJudge.kickoff("g", " ").contains("passes after"))
        assertNull(GoalJudge.kickoffGoal("all tests pass"))
    }

    @Test fun `prompt names the user's check and earlier parts`() {
        val prompt = GoalJudge.prompt("g", emptyList(), "npm test", listOf(GoalPart("Build", true, 1)))
        assertTrue(prompt.contains("The user's own check for this goal is `npm test`"))
        assertTrue(prompt.contains("Keep these names and order"))
        assertTrue(prompt.contains("- Build\n"))
        assertFalse(GoalJudge.prompt("g", emptyList()).contains("user's own check"))
    }

    @Test fun `goals saved before history and parts still load`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"goal":{"text":"g","status":"ACTIVE","turns":2,"turnLimit":20,"setAt":1}}"""
        val goal = json.decodeFromString<TaskRecord>(old).goal
        assertEquals(emptyList<GoalCheck>(), goal?.history)
        assertNull(goal?.checkCommand)
        val full = TaskRecord(goal = GoalState("g", checkCommand = "make", notifyEachCheck = true)
            .withCheck(GoalCheck(0, GoalCheck.Outcome.BLOCKED, "key", at = 5), listOf("A" to true)))
        assertEquals(full.goal, json.decodeFromString<TaskRecord>(json.encodeToString(TaskRecord.serializer(), full)).goal)
    }

    @Test fun `keep improving adds the improve verdict and its continuation reads back as a pass`() {
        assertEquals(GoalVerdict.Outcome.IMPROVE, GoalJudge.parse("""{"verdict":"improve","reason":"Works.","next":"Tighten the mobile nav."}""")?.outcome)
        val prompt = GoalJudge.prompt("g", emptyList(), keepImproving = true)
        assertTrue(prompt.contains("\"continue\" | \"blocked\" | \"improve\""))
        assertTrue(prompt.contains("do not answer \"met\""))
        assertFalse(GoalJudge.prompt("g", emptyList()).contains("\"improve\""))
        val text = GoalJudge.continuation(GoalState("site", turns = 2, turnLimit = 5, keepImproving = true),
            GoalVerdict(GoalVerdict.Outcome.IMPROVE, "Works.", "Tighten the mobile nav."))
        assertTrue(text.startsWith("Goal check 2 of 5: reached, improving."))
        assertEquals(GoalJudge.Checkpoint(2, 5, "Works.", "Tighten the mobile nav.", improving = true), GoalJudge.checkpoint(text))
    }

    @Test fun `the turn that sends the goal counts as turn one`() {
        assertEquals(1, GoalState("g").turnsUsed)
        assertEquals(4, GoalState("g", turns = 3).turnsUsed)
        assertTrue(GoalState("g", reachedAtTurn = 1, keepImproving = true).improving)
        assertFalse(GoalState("g", status = GoalState.Status.MET, reachedAtTurn = 1).improving)
        assertFalse(GoalState("g").improving)
    }

    @Test fun `keep improving turns a reached verdict into another pass`() {
        assertEquals(GoalVerdict.Outcome.IMPROVE, GoalJudge.effectiveOutcome(GoalVerdict.Outcome.MET, keepImproving = true))
        assertEquals(GoalVerdict.Outcome.MET, GoalJudge.effectiveOutcome(GoalVerdict.Outcome.IMPROVE, keepImproving = false))
        assertEquals(GoalVerdict.Outcome.MET, GoalJudge.effectiveOutcome(GoalVerdict.Outcome.MET, keepImproving = false))
        assertEquals(GoalVerdict.Outcome.BLOCKED, GoalJudge.effectiveOutcome(GoalVerdict.Outcome.BLOCKED, keepImproving = true))
        // A pass with no suggestion still tells the agent what to do.
        val text = GoalJudge.continuation(GoalState("g", turns = 1, turnLimit = 5, keepImproving = true),
            GoalVerdict(GoalVerdict.Outcome.IMPROVE, "Reached."))
        assertEquals("Review the result yourself and improve its weakest part.", GoalJudge.checkpoint(text)?.next)
    }

    @Test fun `what a turn changed is parsed, sent on and read back from the checkpoint`() {
        val verdict = GoalJudge.parse("""{"verdict":"improve","changed":"Added tab roles","reason":"Arrow keys help keyboard users.","next":"Add arrow keys"}""")!!
        assertEquals("Added tab roles", verdict.changed)
        val text = GoalJudge.continuation(GoalState("Build the site", turns = 7, turnLimit = 12), verdict)
        val checkpoint = GoalJudge.checkpoint(text)!!
        assertEquals("Added tab roles", checkpoint.changed)
        assertEquals("Arrow keys help keyboard users.", checkpoint.reason)
        assertEquals("Add arrow keys", checkpoint.next)
        assertTrue(GoalJudge.prompt("Build the site", emptyList(), keepImproving = true, focus = listOf("Speed")).contains("Speed"))
    }

    @Test fun `old checkpoints without a changed line still read back`() {
        val checkpoint = GoalJudge.checkpoint("Goal check 2 of 5: not done yet.\nTests fail.\nNext: Fix them\n\nKeep working toward the goal: Build")!!
        assertEquals("", checkpoint.changed)
        assertEquals("Tests fail.", checkpoint.reason)
    }

    @Test fun `the improvement list keeps one item per line and a resume with one is still a resume`() {
        assertEquals(listOf("Add dark mode", "Speed up load"), GoalJudge.improvementList("- Add dark mode\n\n  Speed up load  \n"))
        val text = GoalJudge.resume("Build the site", improving = true, next = "Add dark mode")
        assertTrue(GoalJudge.isResume(text))
        assertTrue(text.contains("Next: Add dark mode"))
        assertTrue(GoalJudge.isResume(GoalJudge.resume("Build the site")))
        assertFalse(GoalJudge.isResume("Goal resumed.\nI did a thing\nand more\n\nKeep working toward the goal: x"))
    }
}
