package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LocalModelManagementTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(bytes: ByteArray) = LocalModelCatalog.models.first().copy(bytes = bytes.size.toLong(), sha256 = sha(bytes))
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-model-management").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    /** Hands out at most 1000 bytes per read, like a network stream, so progress callbacks can interrupt mid-file. */
    private fun chunked(bytes: ByteArray): java.io.InputStream = object : java.io.FilterInputStream(bytes.inputStream()) {
        override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1000))
    }
    private val data = "GGUF".toByteArray() + ByteArray(5000) { (it % 251).toByte() }

    @Test fun `resuming appends to the stored bytes and verifies the whole file`() = temporary { root ->
        val model = fixture(data)
        val store = LocalModelStore(root)
        var cancelled = false
        assertThrows(IllegalStateException::class.java) {
            store.install(model, chunked(data), { cancelled }, { cancelled = true })
        }
        val stored = store.partial(model.id).length()
        assertTrue(stored in 1 until data.size)
        assertEquals(stored, store.resumableBytes(model))
        val seen = mutableListOf<Long>()
        store.install(model, data.copyOfRange(stored.toInt(), data.size).inputStream(), { false }, { seen += it }, resumeFrom = stored)
        assertTrue(store.installed(model))
        assertArrayEquals(data, store.file(model.id).readBytes())
        assertFalse(store.partial(model.id).exists())
        assertEquals(data.size.toLong(), seen.last())
        assertTrue(seen.first() > stored)
    }

    @Test fun `a resume offset that does not match the file on disk restarts cleanly`() = temporary { root ->
        val model = fixture(data)
        val store = LocalModelStore(root)
        store.partial(model.id).writeBytes(ByteArray(100) { 9 })
        store.install(model, data.inputStream(), { false }, {}, resumeFrom = 40)
        assertArrayEquals(data, store.file(model.id).readBytes())
    }

    @Test fun `a corrupted resume is rejected by the checksum and discarded`() = temporary { root ->
        val model = fixture(data)
        val store = LocalModelStore(root)
        store.partial(model.id).writeBytes(data.copyOf(100).also { it[50] = (it[50] + 1).toByte() })
        val error = assertThrows(IllegalStateException::class.java) {
            store.install(model, data.copyOfRange(100, data.size).inputStream(), { false }, {}, resumeFrom = 100)
        }
        assertTrue(error.message!!.contains("checksum"))
        assertFalse(store.partial(model.id).exists())
        assertFalse(store.file(model.id).exists())
    }

    @Test fun `asset resume keeps parts on interruption and renames only after verification`() = temporary { root ->
        val bytes = ByteArray(9000) { (it * 7).toByte() }
        val asset = LocalModelAsset("model.safetensors", "https://huggingface.co/a/b/resolve/x/model.safetensors", bytes.size.toLong(), sha(bytes))
        val store = LocalModelStore(root)
        val target = File(root, "model.safetensors")
        val part = File(root, "model.safetensors.part")
        var cancelled = false
        assertThrows(IllegalStateException::class.java) {
            store.installAsset(asset, chunked(bytes), target, { cancelled }, part) { cancelled = true }
        }
        assertFalse(target.exists())
        val stored = part.length()
        assertTrue(stored in 1 until bytes.size)
        store.installAsset(asset, bytes.copyOfRange(stored.toInt(), bytes.size).inputStream(), target, { false }, part, stored) {}
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(part.exists())
    }

    @Test fun `asset checksum failure removes the part file`() = temporary { root ->
        val bytes = ByteArray(300) { 3 }
        val asset = LocalModelAsset("model.safetensors", "https://x", bytes.size.toLong(), "0".repeat(64))
        val store = LocalModelStore(root)
        val part = File(root, "model.safetensors.part")
        assertThrows(IllegalStateException::class.java) {
            store.installAsset(asset, bytes.inputStream(), File(root, "model.safetensors"), { false }, part) {}
        }
        assertFalse(part.exists())
        assertFalse(File(root, "model.safetensors").exists())
    }

    @Test fun `startup cleanup keeps resumable downloads and removes finished leftovers`() = temporary { root ->
        val model = fixture(data)
        val store = LocalModelStore(root)
        store.partial(model.id).writeBytes(ByteArray(100))
        store.discardStale(model)
        assertEquals(100L, store.resumableBytes(model))
        store.partial(model.id).writeBytes(ByteArray(data.size))
        store.discardStale(model)
        assertFalse(store.partial(model.id).exists())
        store.file(model.id).writeBytes(data)
        store.partial(model.id).writeBytes(ByteArray(10))
        store.discardStale(model)
        assertFalse(store.partial(model.id).exists())
        assertTrue(store.installed(model))
    }

    @Test fun `safetensors resume counts finished assets and part files`() = temporary { root ->
        val model = LocalModelCatalog.models.first { it.format == LocalModelFormat.SAFETENSORS && it.assets.size >= 3 }
        val store = LocalModelStore(root)
        val source = store.sourceDirectory(model.id).also { it.mkdirs() }
        val first = model.assets[0]
        val second = model.assets[1]
        File(source, first.filename).writeBytes(ByteArray(first.bytes.toInt()))
        File(source, second.filename + ".part").writeBytes(ByteArray(5))
        assertEquals(first.bytes + 5, store.resumableBytes(model))
        store.discardStale(model)
        assertEquals(first.bytes + 5, store.resumableBytes(model))
    }

    @Test fun `content range headers are parsed strictly`() {
        assertEquals(ContentRange(100, 199, 1000), parseContentRange("bytes 100-199/1000"))
        assertEquals(ContentRange(0, 9, null), parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange("bytes 5-1/10"))
        assertNull(parseContentRange("items 0-1/2"))
        assertNull(parseContentRange(null))
    }

    @Test fun `storage report separates models unfinished downloads leftovers and other files`() = temporary { root ->
        val installedModel = fixture(data)
        val paused = LocalModelCatalog.models[1].let { it.copy(bytes = 10_000) }
        val store = LocalModelStore(root) { id -> listOf(installedModel, paused).firstOrNull { it.id == id } }
        store.file(installedModel.id).writeBytes(data)
        store.partial(paused.id).writeBytes(ByteArray(2000))
        File(root, "removed-model.gguf").writeBytes(ByteArray(700))
        File(root, "removed-model-source").also { it.mkdirs() }.resolve("config.json").writeBytes(ByteArray(50))
        File(root, "custom-models.json").writeText("[]")
        File(root, "file-import-abc").mkdirs()
        val report = store.usage(listOf(installedModel, paused))
        assertEquals(data.size.toLong(), report.installedBytes)
        assertEquals(2000L, report.pausedBytes)
        assertEquals(750L, report.leftoverBytes)
        assertEquals(2, report.models.size)
        assertTrue(report.otherBytes >= 2)
        assertEquals(data.size + 2000L + 750L + report.otherBytes, report.totalBytes)
    }

    @Test fun `cleaning leftovers never touches known models or the catalog file`() = temporary { root ->
        val model = fixture(data)
        val store = LocalModelStore(root) { id -> model.takeIf { it.id == id } }
        store.file(model.id).writeBytes(data)
        File(root, "custom-models.json").writeText("[]")
        File(root, "file-import-keep").mkdirs()
        File(root, "gone.gguf.part").writeBytes(ByteArray(40))
        File(root, "gone.json").writeBytes(ByteArray(10))
        assertEquals(50L, store.clearOrphans(setOf(model.id)))
        assertTrue(store.file(model.id).exists())
        assertTrue(File(root, "custom-models.json").exists())
        assertTrue(File(root, "file-import-keep").exists())
        assertFalse(File(root, "gone.gguf.part").exists())
    }
}
