package com.androidharness.app.llm

import org.junit.Assert.*
import org.junit.Test

class CacheReportingTest {
    private val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
    private fun usage(extra: String): StreamEvent.Usage = provider.parseChunk(
        ProviderFactory.json.parseToJsonElement("""{"usage":{"prompt_tokens":100,"completion_tokens":5$extra},"choices":[]}"""),
        linkedMapOf(),
    ).filterIsInstance<StreamEvent.Usage>().single()

    @Test fun `omitted cache usage is unknown but explicit zero is a miss`() {
        assertFalse(usage("").cacheReported)
        val miss = usage(""", "prompt_tokens_details":{"cached_tokens":0}""")
        assertTrue(miss.cacheReported)
        assertEquals(0, miss.cachedInputTokens)
    }

    @Test fun `gateway cache hit field is preserved`() {
        val hit = usage(""", "prompt_cache_hit_tokens":80""")
        assertTrue(hit.cacheReported)
        assertEquals(80, hit.cachedInputTokens)
    }
}
