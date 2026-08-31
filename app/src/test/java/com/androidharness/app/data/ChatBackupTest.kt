package com.androidharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackupTest {

    private fun session(id: String, vararg messages: BackupMessage) = BackupSession(
        id = id,
        title = "Chat $id",
        createdAt = 1_000,
        updatedAt = 2_000,
        messages = messages.toList(),
    )

    private fun message(id: String, text: String = "hello") = BackupMessage(
        id = id,
        role = "USER",
        text = text,
        createdAt = 1_500,
    )

    @Test
    fun `plan skips sessions whose id already exists`() {
        val incoming = listOf(session("a"), session("b"), session("c"))
        val plan = ChatBackupMerge.plan(setOf("a", "x"), incoming)
        assertEquals(listOf("b", "c"), plan.toImport.map { it.id })
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `plan with an empty database imports everything`() {
        val incoming = listOf(session("a"), session("b"))
        val plan = ChatBackupMerge.plan(emptySet(), incoming)
        assertEquals(2, plan.toImport.size)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `encode then decode round-trips sessions and messages`() {
        val file = ChatBackupFile(
            exportedAt = 99,
            sessions = listOf(
                session(
                    "a",
                    message("m1"),
                    BackupMessage(
                        id = "m2",
                        role = "ASSISTANT",
                        text = "hi",
                        toolCallsJson = """[{"name":"sh"}]""",
                        toolName = "sh",
                        isError = true,
                        thinking = "hmm",
                        thinkingMs = 42,
                        turnId = "t1",
                        createdAt = 2_500,
                    ),
                ),
            ),
        )
        assertEquals(file, ChatBackupCodec.decode(ChatBackupCodec.encode(file)))
    }

    @Test
    fun `decode tolerates unknown keys and missing optional fields`() {
        val text = """
            {"format":"androidharness-chats","version":1,"exportedAt":5,
             "futureField":true,
             "sessions":[{"id":"a","title":"T","createdAt":1,"updatedAt":2,
                          "messages":[{"id":"m1","role":"USER","text":"x","createdAt":3}],
                          "futureSessionField":1}]}
        """.trimIndent()
        val file = ChatBackupCodec.decode(text)
        assertEquals(1, file.sessions.size)
        val m = file.sessions[0].messages[0]
        assertEquals("x", m.text)
        assertEquals("[]", m.toolCallsJson)
    }

    @Test
    fun `decode rejects foreign-format files`() {
        val text = """{"format":"other-app","version":1,"exportedAt":0,"sessions":[]}"""
        try {
            ChatBackupCodec.decode(text)
            throw AssertionError("expected ChatBackupException")
        } catch (e: ChatBackupException) {
            assertEquals("Not a chat backup file", e.message)
        }
    }

    @Test
    fun `decode rejects backups from a newer app version`() {
        val text = """{"format":"androidharness-chats","version":999,"exportedAt":0,"sessions":[]}"""
        try {
            ChatBackupCodec.decode(text)
            throw AssertionError("expected ChatBackupException")
        } catch (e: ChatBackupException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun `decode rejects garbage`() {
        try {
            ChatBackupCodec.decode("definitely not json")
            throw AssertionError("expected ChatBackupException")
        } catch (e: ChatBackupException) {
            assertEquals("Not a chat backup file", e.message)
        }
    }
}
