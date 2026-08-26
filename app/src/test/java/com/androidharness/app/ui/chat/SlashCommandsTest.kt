package com.androidharness.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    private val skills = setOf("git", "systematic-debugging")
    private val snippets = mapOf("review" to "Review this: \$ARG")

    private fun resolve(input: String) = SlashCommands.resolve(
        input = input,
        skillNames = skills,
        snippetBodies = snippets,
        skillContent = { name -> if (name in skills) "# $name\nDo the work." else null },
    )

    @Test
    fun `local commands never start an agent turn`() {
        for (cmd in listOf("/clear", "/compact", "/cost", "/skills")) {
            val result = resolve(cmd)
            assertNull("$cmd should stay local", result.agentText)
            assertFalse(result.startsAgent)
        }
        assertEquals(SlashCommands.Kind.COST, resolve("/cost").kind)
        assertEquals(SlashCommands.Kind.SKILLS, resolve("/skills").kind)
    }

    @Test
    fun `init expands to the workspace prompt`() {
        val result = resolve("/init")
        assertEquals(SlashCommands.Kind.INIT, result.kind)
        assertTrue(result.startsAgent)
        assertTrue(result.agentText!!.contains("AGENTS.md"))
    }

    @Test
    fun `doctor expands to the harness self-test battery prompt`() {
        val result = resolve("/doctor")
        assertEquals(SlashCommands.Kind.DOCTOR, result.kind)
        assertTrue(result.startsAgent)
        assertTrue(result.agentText!!.contains("Harness Doctor"))
        assertTrue(result.agentText!!.contains("FILE CRUD & UNICODE"))
        assertTrue(result.agentText!!.contains("SANDBOX BOUNDARIES"))
    }

    @Test
    fun `skill with instruction expands instead of queuing the raw slash text`() {
        val result = resolve("/git commit this")
        assertEquals(SlashCommands.Kind.SKILL, result.kind)
        assertTrue(result.startsAgent)
        assertTrue(result.agentText!!.contains("invoked the \"git\" skill"))
        assertTrue(result.agentText!!.contains("commit this"))
        assertFalse(result.agentText!!.startsWith("/git"))
    }

    @Test
    fun `snippet substitutes the argument`() {
        val result = resolve("/review the login flow")
        assertEquals(SlashCommands.Kind.SNIPPET, result.kind)
        assertEquals("Review this: the login flow", result.agentText)
    }

    @Test
    fun `unknown command is an error and does not start a run`() {
        val result = resolve("/nope")
        assertEquals(SlashCommands.Kind.UNKNOWN, result.kind)
        assertNull(result.agentText)
        assertTrue(result.error!!.contains("/nope"))
    }

    @Test
    fun `running agent queues expanded skill text instead of cancelling`() {
        val expanded = resolve("/git fix crash").agentText!!
        val target = SlashCommands.dispatchTarget(isRunning = true, agentText = expanded)
        assertEquals(SlashCommands.Dispatch.QUEUE, target.mode)
        assertEquals(expanded, target.text)
    }

    @Test
    fun `idle agent starts a new run with expanded text`() {
        val expanded = resolve("/init").agentText!!
        val target = SlashCommands.dispatchTarget(isRunning = false, agentText = expanded)
        assertEquals(SlashCommands.Dispatch.START, target.mode)
        assertEquals(expanded, target.text)
    }

    @Test
    fun `picking a bare skill attaches a badge instead of sending`() {
        val action = SlashCommands.pickAction(
            entryCommand = "/git",
            typedQuery = "/git",
            kind = SlashCommands.Kind.SKILL,
        )
        assertEquals(SlashCommands.Pick.AttachSkill("git", ""), action)
    }

    @Test
    fun `picking a skill after typing an instruction keeps the leftover in the box`() {
        val action = SlashCommands.pickAction(
            entryCommand = "/git",
            typedQuery = "/git fix the crash",
            kind = SlashCommands.Kind.SKILL,
        )
        assertEquals(SlashCommands.Pick.AttachSkill("git", "fix the crash"), action)
    }

    @Test
    fun `picking a skill from a partial query attaches the full name`() {
        val action = SlashCommands.pickAction(
            entryCommand = "/git",
            typedQuery = "/g",
            kind = SlashCommands.Kind.SKILL,
        )
        assertEquals(SlashCommands.Pick.AttachSkill("git", ""), action)
    }

    @Test
    fun `send payload prefixes the attached skill and keeps the typed note`() {
        assertEquals("/git", SlashCommands.composeSend("git", ""))
        assertEquals("/git fix the crash", SlashCommands.composeSend("git", "fix the crash"))
        assertEquals("hello", SlashCommands.composeSend(null, "hello"))
    }

    @Test
    fun `picking a local command sends immediately`() {
        val action = SlashCommands.pickAction(
            entryCommand = "/cost",
            typedQuery = "/co",
            kind = SlashCommands.Kind.COST,
        )
        assertEquals(SlashCommands.Pick.Send("/cost"), action)
    }
}
