package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test

class HuggingFaceSearchTest {
    private val search = HuggingFaceSearch()

    @Test fun `gguf and safetensors searches use their own filters`() {
        val gguf = search.url("qwen 3", LocalModelFormat.GGUF, HubSort.POPULAR, 25)
        assertEquals(listOf("gguf", "text-generation"), gguf.queryParameterValues("filter"))
        assertEquals("qwen 3", gguf.queryParameter("search"))
        assertEquals("downloads", gguf.queryParameter("sort"))
        val tensors = search.url("", LocalModelFormat.SAFETENSORS, HubSort.NEWEST, 500)
        assertEquals(listOf("safetensors"), tensors.queryParameterValues("filter"))
        assertEquals("text-generation", tensors.queryParameter("pipeline_tag"))
        assertNull(tensors.queryParameter("search"))
        assertEquals("lastModified", tensors.queryParameter("sort"))
        assertEquals("100", tensors.queryParameter("limit"))
    }

    @Test fun `safetensors results carry parameters and an estimated download`() {
        val models = search.parse("""[
          {"id":"Qwen/Qwen3-4B","gated":false,"private":false,"downloads":9114287,"likes":776,"lastModified":"2025-07-26T03:46:39.000Z",
           "safetensors":{"parameters":{"BF16":4000000000,"F32":1000},"total":4000001000}},
          {"id":"meta/gated-model","gated":"auto","downloads":5,"likes":1},
          {"id":"someone/private","gated":false,"private":true},
          {"id":"bad id with spaces","gated":false}
        ]""", LocalModelFormat.SAFETENSORS)
        assertEquals(1, models.size)
        val model = models.single()
        assertEquals("Qwen3-4B", model.name)
        assertEquals("Qwen", model.author)
        assertEquals(4_000_001_000L, model.parameters)
        assertEquals(8_000_004_000L, model.downloadBytes)
        assertEquals(9_114_287L, model.downloads)
    }

    @Test fun `gguf results read the size from the repository name`() {
        val models = search.parse("""[
          {"id":"bartowski/SmolLM2-135M-Instruct-GGUF","gated":false,"downloads":10,"likes":1},
          {"id":"unsloth/Llama-3.2-1B-Instruct-GGUF","gated":false,"downloads":9,"likes":2},
          {"id":"org/no-size-here-GGUF","gated":false,"downloads":1,"likes":0}
        ]""", LocalModelFormat.GGUF)
        assertEquals(listOf(135_000_000L, 1_000_000_000L, null), models.map { it.parameters })
        assertTrue(models.all { it.downloadBytes == null })
    }

    @Test fun `parameter counts are only read from standalone size tokens`() {
        assertEquals(1_500_000_000L, parseParameterCount("Qwen2.5-1.5B-Instruct"))
        assertEquals(30_000_000_000L, parseParameterCount("Qwen3-30B-A3B"))
        assertEquals(360_000_000L, parseParameterCount("SmolLM2-360M"))
        assertNull(parseParameterCount("Qwen2.5-Instruct-Q4_K_M"))
        assertNull(parseParameterCount("gemma-3n-E4B"))
    }

    @Test fun `fit estimate compares the converted size with this phone`() {
        val phone = LocalDeviceProfile(8 * GIB, 4 * GIB, 64 * GIB, "arm64-v8a", 8, false)
        val small = HubModel("a/b", 1, 1, 1_500_000_000L, null, null, LocalModelFormat.GGUF)
        val huge = HubModel("a/c", 1, 1, 70_000_000_000L, null, null, LocalModelFormat.GGUF)
        val unknown = HubModel("a/d", 1, 1, null, null, null, LocalModelFormat.GGUF)
        assertTrue(small.likelyFits(phone))
        assertFalse(huge.likelyFits(phone))
        assertTrue(unknown.likelyFits(phone))
        assertFalse(small.likelyFits(phone.copy(abi = "armeabi-v7a")))
    }

    @Test fun `counts are shortened for display`() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2K", compactCount(1_234))
        assertEquals("9.1M", compactCount(9_114_287))
        assertEquals("4.0B", compactCount(4_000_000_000))
    }
}
