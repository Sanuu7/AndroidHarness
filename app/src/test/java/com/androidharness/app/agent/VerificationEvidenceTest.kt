package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class VerificationEvidenceTest {
    private fun exchange(command: String, output: String = "exit code: 0\nstdout:\nOK", error: Boolean = false, id: String = "a") = listOf(
        ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCallData(id, "shell", kotlinx.serialization.json.buildJsonObject {
            put("command", kotlinx.serialization.json.JsonPrimitive(command))
        }.toString()))),
        ChatMessage(Role.TOOL, output, toolCallId = id, isError = error),
    )
    @Test fun `uses actual exit codes not assistant claims`() {
        assertEquals(CheckStatus.PASSED, VerificationEvidence.collect(exchange("npm run build")).single().status)
        assertEquals(CheckStatus.PASSED, VerificationEvidence.collect(exchange("npm run 'test:unit'")).single().status)
        assertEquals(CheckStatus.FAILED, VerificationEvidence.collect(exchange("./gradlew test", "exit code: 1", true)).single().status)
        assertEquals(CheckStatus.UNKNOWN, VerificationEvidence.collect(exchange("pytest tests", "All passed")).single().status)
        assertTrue(VerificationEvidence.collect(listOf(ChatMessage(Role.ASSISTANT, "All tests passed"))).isEmpty())
    }
    @Test fun `rejects echo pipelines and fake test commands`() {
        for (command in listOf("echo npm test", "npm test | cat", "npm test; true", "npm install", "git status")) {
            assertTrue(command, VerificationEvidence.collect(exchange(command)).isEmpty())
        }
    }
    @Test fun `later mutations make passed checks stale`() {
        val edit = listOf(ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCallData("b", "edit_file", "{}"))),
            ChatMessage(Role.TOOL, "Edited", toolCallId = "b"))
        assertEquals(CheckStatus.STALE, VerificationEvidence.collect(exchange("npm test") + edit).single().status)
        assertEquals(CheckStatus.PASSED, VerificationEvidence.collect(exchange("npm test") + edit + exchange("npm test", id = "c")).single().status)
    }
}
