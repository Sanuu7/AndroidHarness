package com.androidharness.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAttachmentsTest {

    @Test
    fun `inline block round trips through the display splitter`() {
        val block = FileAttachments.inlineBlock("App.kt", "text/plain", "2 KB", "fun main() {}\n", "kotlin")
        val message = "please review\n\n$block"
        val (visible, chips) = FileAttachments.splitForDisplay(message)
        assertEquals(1, chips.size)
        assertEquals("App.kt", chips[0].name)
        assertEquals("text/plain", chips[0].mime)
        assertEquals("2 KB", chips[0].sizeLabel)
        assertEquals("please review", visible)
        assertFalse(visible.contains("fun main"))
    }

    @Test
    fun `binary notes become chips without the raw path line`() {
        val note = FileAttachments.workspaceNote("report.pdf", "application/pdf", "3.4 MB", ".harness/attachments/report.pdf")
        val (visible, chips) = FileAttachments.splitForDisplay("check this\n\n$note")
        assertEquals(1, chips.size)
        assertEquals("report.pdf", chips[0].name)
        assertEquals("check this", visible)
    }

    @Test
    fun `message suffix carries inline content and workspace paths`() {
        val files = listOf(
            FileAttachment("a.txt", "text/plain", "1 KB", "hello world", null),
            FileAttachment("b.zip", "application/zip", "5 MB", null, ".harness/attachments/b.zip"),
        )
        val suffix = FileAttachments.buildMessageSuffix(files)
        assertTrue(suffix.contains("[Attached file: a.txt (text/plain, 1 KB)]"))
        assertTrue(suffix.contains("```"))
        assertTrue(suffix.contains("hello world"))
        assertTrue(suffix.contains(".harness/attachments/b.zip"))
        assertTrue(suffix.contains("[Attached file: b.zip (application/zip, 5 MB)] saved into the workspace"))
    }

    @Test
    fun `text detection covers code and rejects binaries`() {
        assertTrue(FileAttachments.isTextLike("Main.kt", "application/octet-stream"))
        assertTrue(FileAttachments.isTextLike("data.bin-ish", "text/csv"))
        assertTrue(FileAttachments.isTextLike("anything", "application/json"))
        assertFalse(FileAttachments.isTextLike("photo.jpg", "image/jpeg"))
        assertFalse(FileAttachments.isTextLike("app.apk", "application/vnd.android.package-archive"))
        assertFalse(FileAttachments.isTextLike("clip.mp4", "video/mp4"))
        assertFalse(FileAttachments.isTextLike("unknown", "application/octet-stream"))
    }

    @Test
    fun `human bytes reads naturally`() {
        assertEquals("500 B", FileAttachments.humanBytes(500))
        assertEquals("12 KB", FileAttachments.humanBytes(12_000))
        assertEquals("3.4 MB", FileAttachments.humanBytes(3_400_000))
    }
}

class MentionTokenTest {

    @Test
    fun `trailing token parses with its start index`() {
        assertEquals(6 to "src/ma", MentionToken.parse("hello @src/ma"))
        assertEquals(0 to "file", MentionToken.parse("@file"))
        assertEquals(5 to "", MentionToken.parse("look @"))
    }

    @Test
    fun `only the last token matches`() {
        assertEquals(7 to "b", MentionToken.parse("one @a @b"))
    }

    @Test
    fun `no trigger mid sentence or after letters`() {
        assertNull(MentionToken.parse("see @src/main for details"))
        assertNull(MentionToken.parse("email me at user@example.com"))
        assertNull(MentionToken.parse("plain text"))
    }

    @Test
    fun `trigger is per line and indexes into the full text`() {
        assertNull(MentionToken.parse("@old-line\nnew text"))
        assertEquals(5 to "new.kt", MentionToken.parse("done\n@new.kt"))
    }
}
