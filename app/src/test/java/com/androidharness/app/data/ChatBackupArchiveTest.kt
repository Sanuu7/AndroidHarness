package com.androidharness.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChatBackupArchiveTest {

    private fun message(id: String, images: String = "[]") = BackupMessage(id = id, role = "USER", text = "hi $id", imagesJson = images, createdAt = 5)

    private fun chat(id: String, vararg messages: BackupMessage) = BackupSession(
        id = id, title = "Chat $id", createdAt = 1, updatedAt = 2, messages = messages.toList(),
        usage = listOf(BackupUsage("OpenRouter", "model-a", 120, 40, createdAt = 3, inputPrice = 0.5)),
        answerVersions = listOf(BackupAnswerVersion("v1", "group", "[]", 4)),
        savedAnswers = listOf(BackupSavedAnswer(messages.first().id, "kept", 5, 6)),
    )

    private class Collected : ChatBackupArchive.Visitor {
        var manifest: BackupManifest? = null
        val chats = mutableListOf<BackupSession>()
        var memories = emptyList<BackupMemory>()
        var snippets = emptyList<BackupSnippet>()
        val images = mutableMapOf<String, String>()
        override suspend fun manifest(manifest: BackupManifest): Boolean { this.manifest = manifest; return true }
        override suspend fun chat(session: BackupSession) { chats += session }
        override suspend fun memories(memories: List<BackupMemory>) { this.memories = memories }
        override suspend fun snippets(snippets: List<BackupSnippet>) { this.snippets = snippets }
        override suspend fun image(name: String, original: Boolean, input: InputStream) {
            images[(if (original) "originals/" else "") + name] = input.readBytes().decodeToString()
        }
    }

    private fun archive(block: ChatBackupArchive.Writer.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { out -> ChatBackupArchive.Writer(out).use(block) }.toByteArray()

    @Test fun `chats, extras and images round-trip one entry at a time`() = runBlocking {
        val manifest = BackupManifest(exportedAt = 99, chats = listOf(BackupChatInfo("a", "Chat a", 1, 2, 2)), memories = 1, snippets = 1)
        val a = chat("a", message("m1", """[{"name":"pic.jpg","mime":"image/jpeg"}]"""), message("m2"))
        val b = chat("b", message("m3"))
        val bytes = archive {
            manifest(manifest)
            chat(a); chat(b)
            memories(listOf(BackupMemory(id = "mem", text = "Prefers Kotlin", category = "PREFERENCE", scope = "GLOBAL")))
            snippets(listOf(BackupSnippet("s", "review", "Review this")))
            image("pic.jpg", original = false, ByteArrayInputStream("preview".toByteArray()))
            image("pic.jpg", original = true, ByteArrayInputStream("original".toByteArray()))
        }
        assertTrue(ChatBackupArchive.isArchive(bytes.copyOf(4)))
        val seen = Collected()
        ChatBackupArchive.read(ByteArrayInputStream(bytes), seen)
        assertEquals(manifest, seen.manifest)
        assertEquals(listOf(a, b), seen.chats)
        assertEquals("Prefers Kotlin", seen.memories.single().text)
        assertEquals("review", seen.snippets.single().name)
        assertEquals(mapOf("pic.jpg" to "preview", "originals/pic.jpg" to "original"), seen.images)
        assertEquals(120L, seen.chats.first().usage.single().inputTokens)
    }

    @Test fun `manifest is read without touching the chats`() = runBlocking {
        val bytes = archive { manifest(BackupManifest(chats = listOf(BackupChatInfo("a", "A", 1, 2, 7)))); chat(chat("a", message("m"))) }
        assertEquals(7, ChatBackupArchive.readManifest(ByteArrayInputStream(bytes)).chats.single().messages)
    }

    @Test fun `files that do not start with the manifest are rejected`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("chats/00000.json")); zip.write("{}".toByteArray()); zip.closeEntry() }
        assertRejected(out.toByteArray(), "Not a backup file")
        assertRejected("not a zip".toByteArray(), "Not a backup file")
    }

    @Test fun `backups from a newer app version are rejected`() = runBlocking {
        val bytes = archive { manifest(BackupManifest(version = ChatBackupArchive.VERSION + 1)) }
        assertRejected(bytes, "This backup was written by a newer version of the app")
    }

    @Test fun `image entries that try to leave the images folder are ignored`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ChatBackupArchive.MANIFEST)); zip.write(ChatBackupArchive.json.encodeToString(BackupManifest.serializer(), BackupManifest()).toByteArray()); zip.closeEntry()
            for (name in listOf("images/../evil.jpg", "images/.hidden", "images/originals/a/b.jpg", "images/ok.jpg")) {
                zip.putNextEntry(ZipEntry(name)); zip.write("x".toByteArray()); zip.closeEntry()
            }
        }
        val seen = Collected()
        ChatBackupArchive.read(ByteArrayInputStream(out.toByteArray()), seen)
        assertEquals(setOf("ok.jpg"), seen.images.keys)
    }

    @Test fun `image names come only from plain file names`() {
        assertEquals(listOf("a.jpg"), ChatBackupArchive.imageNames("""[{"name":"a.jpg"},{"name":"../b.jpg"},{"mime":"x"}]"""))
        assertEquals(emptyList<String>(), ChatBackupArchive.imageNames("not json"))
        assertFalse(ChatBackupArchive.safeName("sub/dir.png"))
    }

    @Test fun `a kept copy gets fresh ids and keeps saved answers on the new messages`() {
        val original = chat("a", message("m1"), message("m2"))
        val copy = original.asCopy()
        assertNotEquals(original.id, copy.id)
        assertEquals("Chat a (copy)", copy.title)
        assertTrue(copy.messages.map { it.id }.none { it in setOf("m1", "m2") })
        assertEquals(original.messages.map { it.text }, copy.messages.map { it.text })
        assertEquals(copy.messages.first().id, copy.savedAnswers.single().messageId)
        assertTrue(copy.answerVersions.isEmpty())
    }

    @Test fun `older single-file backups still stream-decode`() {
        val legacy = ChatBackupFile(exportedAt = 5, sessions = listOf(BackupSession("old", "Old chat", 1, 2, messages = listOf(message("m")))))
        val decoded = ChatBackupCodec.decode(ByteArrayInputStream(ChatBackupCodec.encode(legacy).toByteArray()))
        assertEquals(legacy, decoded)
        assertFalse(ChatBackupArchive.isArchive(ChatBackupCodec.encode(legacy).toByteArray().copyOf(4)))
    }

    private suspend fun assertRejected(bytes: ByteArray, message: String) {
        try {
            ChatBackupArchive.read(ByteArrayInputStream(bytes), Collected())
            fail("Expected the file to be rejected")
        } catch (e: ChatBackupException) {
            assertEquals(message, e.message)
        }
    }
}
