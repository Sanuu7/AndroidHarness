package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPresentationTest {
    private fun call(name: String, argumentsJson: String = "{}") =
        ToolCallData(id = "call-1", name = name, argumentsJson = argumentsJson)

    private fun result(text: String, error: Boolean = false) =
        ChatMessage(role = Role.TOOL, text = text, isError = error)

    @Test
    fun `read file uses the filename as the semantic title`() {
        val presentation = toolPresentation(
            call("read_file", "{\"path\":\"app/src/main/ChatScreen.kt\"}"),
        )

        assertEquals("Read ChatScreen.kt", presentation.title)
        assertEquals("app/src/main/ChatScreen.kt", presentation.detail)
        assertEquals(ToolActivityKind.READ, presentation.kind)
    }

    @Test
    fun `grep reports result count from the actual tool output`() {
        val presentation = toolPresentation(
            call("grep", "{\"pattern\":\"ToolCallCard\"}"),
            result("one\ntwo\nthree\n"),
        )

        assertEquals("Searched 3 matches", presentation.title)
        assertEquals(ToolActivityKind.SEARCH, presentation.kind)
    }

    @Test
    fun `verification commands only report passed when the result succeeded`() {
        val verification = call("shell", "{\"command\":\"./gradlew testDebugUnitTest\"}")

        assertEquals("Run tests", toolPresentation(verification).title)
        assertEquals("Tests passed", toolPresentation(verification, result("BUILD SUCCESSFUL")).title)
        assertEquals("Tests failed", toolPresentation(verification, result("BUILD FAILED", error = true)).title)
    }

    @Test
    fun `unknown tools get a readable fallback title`() {
        assertEquals("Custom action", toolPresentation(call("custom_action")).title)
    }
}
