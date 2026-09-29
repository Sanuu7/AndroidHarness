package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LocalModelsTest {
    @Test fun `catalog uses pinned revisions and checksums`() {
        assertEquals(LocalModelCatalog.models.size, LocalModelCatalog.models.map { it.id }.distinct().size)
        LocalModelCatalog.models.forEach {
            assertTrue(it.revision.matches(Regex("[a-f0-9]{40}")))
            assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(it.url.startsWith("https://huggingface.co/"))
            assertTrue(it.filename.endsWith(".gguf"))
        }
    }

    @Test fun `limits reserve output space and reject overflow`() {
        LocalModelLimits().validate()
        listOf(LocalModelLimits(context = 0), LocalModelLimits(input = Int.MAX_VALUE),
            LocalModelLimits(output = 0), LocalModelLimits(input = 2048, output = 512),
            LocalModelLimits(threads = 9), LocalModelLimits(context = 8193)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }

    @Test fun `recommendations distinguish architecture memory and live availability`() {
        val small = LocalModelCatalog.models.first()
        val profile = LocalDeviceProfile(4 * GIB, 3 * GIB, 8 * GIB, "arm64-v8a", 8, false)
        assertTrue(profile.fits(small))
        assertTrue(profile.canLoad(small, 2048))
        assertFalse(profile.copy(abi = "armeabi-v7a").fits(small))
        assertFalse(profile.copy(totalRam = 2 * GIB).fits(small))
        assertFalse(profile.copy(availableRam = GIB).canLoad(small, 2048))
        assertFalse(profile.copy(lowMemory = true).canLoad(small, 2048))
        assertFalse(profile.fits(LocalModelCatalog.models.last()))
        assertTrue(small.estimatedMemory(8192) > small.estimatedMemory(2048))
    }

    @Test fun `verified install commits atomically and remove deletes real files`() = temporary { root ->
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        val store = LocalModelStore(root)
        store.install(model, data.inputStream(), { false }, {})
        assertTrue(store.installed(model))
        assertArrayEquals(data, store.file(model.id).readBytes())
        assertFalse(store.partial(model.id).exists())
        File(root, "${model.id}.json").writeText("settings")
        store.remove(model.id)
        assertFalse(store.file(model.id).exists())
        assertFalse(File(root, "${model.id}.json").exists())
        store.remove(model.id)
    }

    @Test fun `bad checksum truncation oversize and invalid magic leave no files`() = temporary { root ->
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        val store = LocalModelStore(root)
        for ((spec, body) in listOf(model.copy(sha256 = "0".repeat(64)) to data,
            model to data.dropLast(1).toByteArray(), model to (data + 1), fixture("HTMLfixture".toByteArray()) to "HTMLfixture".toByteArray())) {
            assertThrows(IllegalStateException::class.java) { store.install(spec, body.inputStream(), { false }, {}) }
            assertFalse(store.file(model.id).exists())
            assertFalse(store.partial(model.id).exists())
        }
    }

    @Test fun `cancellation during transfer deletes partial bytes`() = temporary { root ->
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        val store = LocalModelStore(root)
        var cancelled = false
        assertThrows(IllegalStateException::class.java) {
            store.install(model, data.inputStream(), { cancelled }, { cancelled = true })
        }
        assertFalse(store.file(model.id).exists())
        assertFalse(store.partial(model.id).exists())
    }

    @Test fun `store rejects path traversal and preserves installed files`() = temporary { root ->
        val store = LocalModelStore(root)
        assertThrows(IllegalArgumentException::class.java) { store.file("../escape") }
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        store.install(model, data.inputStream(), { false }, {})
        assertThrows(IllegalStateException::class.java) { store.install(model, data.inputStream(), { false }, {}) }
        assertArrayEquals(data, store.file(model.id).readBytes())
    }

    @Test fun `local inference reports zero API cost without changing cloud pricing`() {
        val local = com.androidharness.app.llm.ModelPrices.costFor("local-model:qwen-05b")
        assertEquals(0.0, local.input, 0.0)
        assertEquals(0.0, local.output, 0.0)
        assertTrue(com.androidharness.app.llm.ModelPrices.costFor("qwen-05b").input > 0)
    }

    @Test fun `UTF8 decoding keeps split multibyte tokens intact`() {
        val text = "Hello 世界 😀 café"
        val decoder = Utf8TokenDecoder()
        val result = buildString {
            text.toByteArray().forEach { append(decoder.append(byteArrayOf(it))) }
            append(decoder.finish())
        }
        assertEquals(text, result)
    }

    private fun fixture(bytes: ByteArray) = LocalModelCatalog.models.first().copy(bytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-model-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
