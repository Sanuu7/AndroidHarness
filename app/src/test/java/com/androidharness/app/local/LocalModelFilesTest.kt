package com.androidharness.app.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LocalModelFilesTest {
    private val companions = listOf("config.json", "tokenizer.json")

    @Test fun `full weights take precedence over an alternate shard set`() {
        val names = companions + listOf("model.safetensors", "model-00001-of-00002.safetensors", "model-00002-of-00002.safetensors", "README.md")
        assertEquals(setOf("model.safetensors", "config.json", "tokenizer.json"), LocalModelImportFiles.select(names).toSet())
        val data = buildJsonObject {
            put("sha", "a".repeat(40))
            putJsonArray("siblings") { names.forEach { name -> add(buildJsonObject {
                put("rfilename", name); put("size", 128)
                if (name.endsWith(".safetensors")) putJsonObject("lfs") { put("sha256", "b".repeat(64)); put("size", 128) }
            }) } }
        }
        val link = CustomModelResolver.parseLink("https://huggingface.co/org/model", LocalModelFormat.SAFETENSORS)
        assertEquals(3, CustomModelResolver().parseSafetensors(link, data).assets.size)
    }

    @Test fun `missing shards companions duplicates and escaping names are rejected`() {
        val invalid = listOf(
            companions + "model-00001-of-00002.safetensors",
            companions + listOf("model-00000-of-00002.safetensors", "model-00001-of-00002.safetensors"),
            listOf("model.safetensors", "config.json"),
            companions + listOf("model.safetensors", "model.safetensors"),
            companions + "../escape.safetensors",
            companions + "folder\\escape.safetensors",
        )
        invalid.forEach { names -> assertThrows(IllegalArgumentException::class.java) { LocalModelImportFiles.select(names) } }
        assertEquals(4, LocalModelImportFiles.select(companions + listOf("model-00001-of-00002.safetensors", "model-00002-of-00002.safetensors")).size)
    }

    @Test fun `weight index requires every referenced file inside the chosen folder`() = temporary { root ->
        val index = File(root, "model.safetensors.index.json")
        File(root, "part.safetensors").writeBytes(byteArrayOf(1))
        index.writeText("""{"weight_map":{"tensor":"part.safetensors"}}""")
        LocalModelImportFiles.verifyIndex(root)
        listOf("missing.safetensors", "../part.safetensors", "folder\\\\part.safetensors").forEach { name ->
            index.writeText("""{"weight_map":{"tensor":"$name"}}""")
            assertThrows(IllegalArgumentException::class.java) { LocalModelImportFiles.verifyIndex(root) }
        }
    }

    @Test fun `unknown size providers copy with exact checksum without changing the source`() = temporary { root ->
        val data = ByteArray(600_000) { (it % 251).toByte() }
        val source = File(root, "source.gguf").apply { writeBytes(data) }
        val target = File(root, "copy.part")
        var progress = 0L
        val (count, hash) = source.inputStream().use { LocalModelImportFiles.copy(it, target, null, {}, { bytes -> progress = bytes }) }
        assertEquals(data.size.toLong(), count)
        assertEquals(count, progress)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, hash)
        assertArrayEquals(data, target.readBytes())
        assertArrayEquals(data, source.readBytes())
    }

    @Test fun `failed size verification and cancellation leave no partial copy`() = temporary { root ->
        val target = File(root, "copy.part")
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelImportFiles.copy(byteArrayOf(1, 2, 3).inputStream(), target, 4, {}, {})
        }
        assertFalse(target.exists())
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            LocalModelImportFiles.copy(ByteArray(600_000).inputStream(), target, null,
                { if (cancelled) throw CancellationException("Cancelled") }, { cancelled = true })
        }
        assertFalse(target.exists())
    }

    @Test fun `imported provenance and converted recommendations survive catalog reload`() = temporary { root ->
        val local = LocalModelCatalog.models.first().copy(id = "custom-" + "1".repeat(32), custom = true,
            localFile = true, repository = "", revision = "", downloadUrl = "", sourcePage = "")
        val registry = CustomModelRegistry(root)
        registry.add(local)
        val converted = LocalModelCatalog.models.first { it.format == LocalModelFormat.SAFETENSORS }.copy(bytes = 100_000)
        registry.add(converted)
        val reloaded = CustomModelRegistry(root)
        assertNull(reloaded.loadError)
        assertEquals(local, reloaded.models.first { it.id == local.id })
        val merged = (LocalModelCatalog.models + reloaded.models).associateBy { it.id }
        assertEquals(100_000L, merged.getValue(converted.id).bytes)
        assertThrows(IllegalArgumentException::class.java) { registry.add(local.copy(filename = "../escape.gguf")) }
        assertThrows(IllegalArgumentException::class.java) { registry.add(local.copy(assets = listOf(LocalModelAsset("../escape.safetensors", "", 100)))) }
    }

    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-model-files").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
