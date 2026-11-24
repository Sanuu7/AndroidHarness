package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditDraftTest {
    private val inline = FileAttachments.inlineBlock("App.kt", "text/plain", "2 KB", "fun main() {}\n", "kotlin")
    private val note = FileAttachments.workspaceNote("report.pdf", "application/pdf", "3.4 MB", ".harness/attachments/report.pdf")

    private fun draftOf(text: String, images: List<ImageRef> = emptyList()): MessageEditDraft {
        val (visible, files) = FileAttachments.splitForEdit(text)
        return MessageEditDraft(ChatMessage(id = "m1", role = Role.USER, text = text, images = images), visible, images, files)
    }

    @Test
    fun `an unchanged edit resends the exact original message`() {
        val original = "please review\n\n$inline\n\n$note"
        val draft = draftOf(original)
        assertEquals("please review", draft.text)
        assertEquals(listOf("App.kt", "report.pdf"), draft.trayFiles.map { it.name })
        assertEquals(original, draft.visibleMessage())
    }

    @Test
    fun `removing a kept file drops only its block`() {
        val draft = draftOf("please review\n\n$inline\n\n$note")
        val edited = draft.copy(keptFiles = draft.keptFiles.drop(1))
        assertEquals("please review\n\n$note", edited.visibleMessage())
    }

    @Test
    fun `added files follow the kept ones`() {
        val draft = draftOf("look\n\n$note")
        val added = FileAttachment("notes.txt", "text/plain", "12 B", "hello", null)
        val result = draft.copy(addedFiles = listOf(added)).visibleMessage()
        assertEquals("look\n\n$note\n\n${FileAttachments.block(added)}", result)
        assertEquals(listOf("report.pdf", "notes.txt"), draft.copy(addedFiles = listOf(added)).trayFiles.map { it.name })
    }

    @Test
    fun `a file-only message keeps no leading blank text`() {
        val draft = draftOf(inline)
        assertEquals("", draft.text)
        assertEquals(inline, draft.visibleMessage())
        assertTrue(draft.hasContent)
    }

    @Test
    fun `an empty edit has nothing to resend`() {
        val draft = draftOf("hi").copy(text = "  ")
        assertFalse(draft.hasContent)
        assertTrue(draft.copy(images = listOf(ImageRef("a.png", "image/png"))).hasContent)
    }
}
