package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageWorkspaceNotesTest {

    private val saved = ImageRef("img-1.jpg", "image/jpeg", caption = "proof.png",
        workspacePath = ".harness/attachments/1125-1432-ab12cd34-proof.png")

    @Test
    fun userImageWithWorkspaceCopyGetsItsPathAfterTheText() {
        val out = ImageWorkspaceNotes.annotate(listOf(ChatMessage(Role.USER, "check this", images = listOf(saved)))).single()
        assertTrue(out.text.startsWith("check this\n\n"))
        assertTrue(out.text.contains(".harness/attachments/1125-1432-ab12cd34-proof.png"))
        assertTrue(out.text.contains("proof.png"))
    }

    @Test
    fun imageOnlyMessageIsJustTheNote() {
        val out = ImageWorkspaceNotes.annotate(listOf(ChatMessage(Role.USER, images = listOf(saved)))).single()
        assertEquals(ImageWorkspaceNotes.note("proof.png", saved.workspacePath!!), out.text)
    }

    @Test
    fun imagesWithoutACopyAndOtherRolesStayUntouched() {
        val plain = ChatMessage(Role.USER, "hi", images = listOf(ImageRef("a.jpg", "image/jpeg")))
        val tool = ChatMessage(Role.TOOL, "result", images = listOf(saved))
        assertEquals(listOf(plain, tool), ImageWorkspaceNotes.annotate(listOf(plain, tool)))
    }

    @Test
    fun annotatingTwiceDoesNotRepeatTheNote() {
        val once = ImageWorkspaceNotes.annotate(listOf(ChatMessage(Role.USER, "x", images = listOf(saved))))
        assertEquals(once, ImageWorkspaceNotes.annotate(once))
    }
}
