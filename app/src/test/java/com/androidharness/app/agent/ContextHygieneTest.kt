package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHygieneTest {
    @Test fun evictedAndUnsupportedToolImagesNeverRetainATrueAttachmentClaim() {
        val tool = ChatMessage(Role.TOOL, "{\"ok\":true,\"image_attached\":true,\"image_id\":\"img_one\"}",
            toolName = "view_chat_image", imageData = listOf(com.androidharness.app.core.ImageData("image/png", "first")))
        val evicted = ContextHygiene.shrinkToolResults(listOf(tool), maxImages = 0).single()
        assertTrue(evicted.isError); assertTrue(evicted.text.contains("\"image_attached\":false"))
        assertTrue(evicted.text.contains("not_in_current_context")); assertTrue(evicted.imageData.isEmpty())
        val unsupported = ContextHygiene.stripImages(listOf(tool)).single()
        assertTrue(unsupported.isError); assertTrue(unsupported.text.contains("images_disabled"))
        assertTrue(unsupported.text.contains("\"image_attached\":false"))
        assertTrue(tool.text.contains("\"image_attached\":true"))
    }

    @Test fun newestToolBatchImagesAllReachTheNextRequest() {
        val calls = (1..4).map { com.androidharness.app.core.ToolCallData("image-$it", "view_chat_image", "{}") }
        val history = listOf(ChatMessage(Role.ASSISTANT, toolCalls = calls)) + calls.map {
            ChatMessage(Role.TOOL, "{\"ok\":true,\"image_attached\":true}", toolCallId = it.id,
                toolName = it.name, imageData = listOf(com.androidharness.app.core.ImageData("image/png", it.id)))
        }
        val current = ContextHygiene.shrinkToolResults(history)
        assertEquals(4, current.sumOf { it.imageData.size })
        assertTrue(current.drop(1).all { !it.isError && it.text.contains("\"image_attached\":true") })
        val later = ContextHygiene.shrinkToolResults(history + ChatMessage(Role.ASSISTANT, "Finished inspecting"))
        assertEquals(2, later.sumOf { it.imageData.size })
    }

    @Test fun everyPhotoInTheNewestUserMessageIsSentWhileOlderPhotosKeepTheBudget() {
        fun photos(n: Int, tag: String) = (1..n).map { com.androidharness.app.core.ImageData("image/png", "$tag-$it") }
        val older = ChatMessage(Role.USER, "earlier", imageData = photos(3, "old"))
        val reply = ChatMessage(Role.ASSISTANT, "seen")
        val newest = ChatMessage(Role.USER, "describe these", imageData = photos(5, "new"))
        val prepared = ContextHygiene.shrinkToolResults(listOf(older, reply, newest))
        assertEquals(5, prepared.last().imageData.size)
        assertEquals(0, prepared.first().imageData.size)
        // Tool steps inside the same turn still carry every photo the user sent.
        val call = com.androidharness.app.core.ToolCallData("t1", "shell", "{}")
        val midTurn = ContextHygiene.shrinkToolResults(listOf(newest, ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, "ok", toolCallId = "t1", toolName = "shell")))
        assertEquals(5, midTurn.first().imageData.size)
    }

    @Test
    fun `recent tool results stay intact`() {
        val history = (1..3).map { i ->
            ChatMessage(role = Role.TOOL, text = "full-$i-" + "x".repeat(2000), toolCallId = "c$i", toolName = "shell")
        }
        val out = ContextHygiene.shrinkToolResults(history)
        out.forEach { assertFalse(it.text.contains("truncated")) }
        assertEquals(history[0].text, out[0].text)
    }

    @Test
    fun `stale tool dumps are truncated`() {
        val stale = ChatMessage(
            role = Role.TOOL,
            text = "START-" + "y".repeat(20_000) + "-END",
            toolCallId = "old",
            toolName = "shell",
        )
        val recent = (1..ContextHygiene.RECENT_FULL_TOOLS).map { i ->
            ChatMessage(role = Role.TOOL, text = "keep-$i", toolCallId = "n$i", toolName = "grep")
        }
        val user = ChatMessage(role = Role.USER, text = "go")
        val out = ContextHygiene.shrinkToolResults(listOf(user, stale) + recent)
        assertTrue(out[1].text.contains("truncated"))
        assertTrue(out[1].text.contains("read_tool_output"))
        assertTrue(out[1].text.contains("tool_call_id=\"old\""))
        assertTrue(out[1].text.startsWith("START-"))
        assertTrue(out[1].text.endsWith("-END"))
        assertTrue(out[1].text.length < stale.text.length)
        assertEquals("keep-1", out[2].text)
    }

    @Test
    fun `non-tool messages are never touched`() {
        val big = ChatMessage(role = Role.ASSISTANT, text = "a".repeat(50_000))
        val out = ContextHygiene.shrinkToolResults(listOf(big))
        assertEquals(big.text, out[0].text)
    }

    @Test
    fun `compaction summary is a system message not a user message`() {
        val msg = ContextHygiene.summaryMessage("goal was X")
        assertEquals(Role.SYSTEM, msg.role)
        assertTrue(msg.text.contains("goal was X"))
        assertTrue(msg.text.contains(AgentEngine.COMPACTION_PREFIX))
        assertFalse(msg.text.startsWith("goal was X"))
    }

    @Test
    fun `forModel starts at the last compaction summary`() {
        val history = listOf(
            ChatMessage(role = Role.USER, text = "old goal"),
            ChatMessage(role = Role.TOOL, text = "y".repeat(20_000), toolCallId = "old", toolName = "shell"),
            ContextHygiene.summaryMessage("kept goal"),
            ChatMessage(role = Role.USER, text = "continue"),
            ChatMessage(role = Role.TOOL, text = "fresh", toolCallId = "n1", toolName = "grep"),
        )
        val out = ContextHygiene.forModel(history)
        assertEquals(Role.SYSTEM, out.first().role)
        assertTrue(out.first().text.contains("kept goal"))
        assertTrue(out.none { it.text == "old goal" })
        assertEquals("continue", out[1].text)
        assertEquals("fresh", out[2].text)
    }

    @Test
    fun `evicts older images when exceeding maxImages cap`() {
        val img = com.androidharness.app.core.ImageData("image/png", "b64")
        val history = (1..6).map { i ->
            ChatMessage(
                role = Role.TOOL,
                text = "result $i",
                toolCallId = "c$i",
                toolName = "read_image",
                imageData = listOf(img),
            )
        }
        val out = ContextHygiene.shrinkToolResults(history, maxImages = 2)
        // Only the last 2 messages retain imageData; older 4 are evicted
        assertEquals(0, out[0].imageData.size)
        assertEquals(0, out[1].imageData.size)
        assertEquals(0, out[2].imageData.size)
        assertEquals(0, out[3].imageData.size)
        assertEquals(1, out[4].imageData.size)
        assertEquals(1, out[5].imageData.size)
        // Text is preserved
        assertEquals("result 1", out[0].text)
    }
}
