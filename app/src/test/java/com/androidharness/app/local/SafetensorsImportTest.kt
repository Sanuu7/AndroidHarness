package com.androidharness.app.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SafetensorsImportTest {
    private fun repository(vararg names: String) = buildJsonObject {
        put("sha", "a".repeat(40))
        putJsonArray("siblings") { names.forEach { name -> add(buildJsonObject {
            put("rfilename", name); put("size", 128)
            if (name.endsWith(".safetensors")) putJsonObject("lfs") { put("sha256", "b".repeat(64)); put("size", 128) }
        }) } }
    }

    @Test fun `safetensors import pins every weight shard with its config and tokenizer`() {
        val link = CustomModelResolver.parseLink("https://huggingface.co/org/model/blob/main/model-00001-of-00002.safetensors", LocalModelFormat.SAFETENSORS)
        val model = CustomModelResolver().parseSafetensors(link, repository("config.json", "tokenizer.json", "tokenizer_config.json",
            "model-00001-of-00002.safetensors", "model-00002-of-00002.safetensors", "unrelated.py"))
        assertEquals(LocalModelFormat.SAFETENSORS, model.format)
        assertEquals(5, model.assets.size)
        assertEquals(640L, model.downloadBytes)
        assertTrue(model.assets.all { "/resolve/${"a".repeat(40)}/" in it.url })
        assertFalse(model.assets.any { it.filename.endsWith(".py") })
        val dir = Files.createTempDirectory("safetensors-registry").toFile()
        try {
            CustomModelRegistry(dir).add(model)
            assertEquals(model, CustomModelRegistry(dir).models.single())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `missing model components and escaping filenames cannot be imported`() {
        val link = CustomModelResolver.parseLink("https://huggingface.co/org/model", LocalModelFormat.SAFETENSORS)
        assertThrows(IllegalArgumentException::class.java) { CustomModelResolver().parseSafetensors(link, repository("model.safetensors", "config.json")) }
        val model = CustomModelResolver().parseSafetensors(link, repository("model.safetensors", "config.json", "tokenizer.json"))
        val dir = Files.createTempDirectory("safetensors-registry").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { CustomModelRegistry(dir).add(model.copy(assets = listOf(LocalModelAsset("../escape", model.url, 100)))) }
        } finally { dir.deleteRecursively() }
    }
}
