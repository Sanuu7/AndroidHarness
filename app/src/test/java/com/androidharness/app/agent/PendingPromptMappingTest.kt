package com.androidharness.app.agent

import com.androidharness.app.PendingPrompt
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure LiveRunState → PendingPrompt mapping that drives answerable
 * notifications (see docs/specs/2026-08-24-notification-actions-design.md).
 */
class PendingPromptMappingTest {

    private fun stateWith(
        approval: ApprovalRequest? = null,
        question: QuestionRequest? = null,
        environment: EnvironmentRequest? = null,
    ) = RunManager.LiveRunState(
        sessionId = "s1",
        pendingApproval = approval,
        pendingQuestion = question,
        pendingEnvironment = environment,
    )

    @Test
    fun `approval maps with tool name, description and diff detail`() {
        val request = ApprovalRequest(
            call = ToolCallData("t1", "edit_file", """{"path":"a.py"}"""),
            toolDescription = "Edit an existing file",
            diffPreview = "--- a.py\n+++ b.py",
        )
        val prompts = RunManager.pendingPromptsOf(stateWith(approval = request), "My Chat")

        assertEquals(1, prompts.size)
        val prompt = prompts.single()
        assertEquals(PendingPrompt.Kind.APPROVAL, prompt.kind)
        assertEquals("s1", prompt.sessionId)
        assertEquals("My Chat", prompt.sessionTitle)
        assertEquals("edit_file — Edit an existing file", prompt.headline)
        assertEquals("--- a.py\n+++ b.py", prompt.detail)
    }

    @Test
    fun `question carries its options as buttons`() {
        val request = QuestionRequest(
            callId = "t2",
            question = "Which database?",
            options = listOf("Postgres", "SQLite"),
        )
        val prompts = RunManager.pendingPromptsOf(stateWith(question = request), "Chat")

        val prompt = prompts.single()
        assertEquals(PendingPrompt.Kind.QUESTION, prompt.kind)
        assertEquals("Which database?", prompt.headline)
        assertEquals(listOf("Postgres", "SQLite"), prompt.options)
    }

    @Test
    fun `environment prompt summarizes the blocking command`() {
        val request = EnvironmentRequest(
            call = ToolCallData("t3", "shell", "{}"),
            command = "python3 train.py",
            hints = listOf("python"),
        )
        val prompts = RunManager.pendingPromptsOf(stateWith(environment = request), "Chat")

        val prompt = prompts.single()
        assertEquals(PendingPrompt.Kind.ENVIRONMENT, prompt.kind)
        assertTrue(prompt.headline.contains("python3 train.py"))
    }

    @Test
    fun `no pendings produce no prompts`() {
        assertTrue(RunManager.pendingPromptsOf(stateWith(), "Chat").isEmpty())
    }

    @Test
    fun `several waiting prompts are all listed`() {
        val state = stateWith(
            approval = ApprovalRequest(ToolCallData("t1", "shell", "{}"), "Run a shell command"),
            question = QuestionRequest("t2", "Proceed?", listOf("Yes", "No")),
        )
        val prompts = RunManager.pendingPromptsOf(state, "Chat")

        assertEquals(
            listOf(PendingPrompt.Kind.APPROVAL, PendingPrompt.Kind.QUESTION),
            prompts.map { it.kind },
        )
    }
}
