package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatContextPolicyTest {
    @Test fun `chat ceiling ignores the code setting and respects smaller models`() {
        assertEquals(250_000, ChatContextPolicy.limit(64_000, null, false))
        assertEquals(250_000, ChatContextPolicy.limit(1_000_000, 500_000, false))
        assertEquals(128_000, ChatContextPolicy.limit(1_000_000, 128_000, false))
        assertEquals(32_768, ChatContextPolicy.limit(32_768, null, true))
    }

    @Test fun `compaction reserves space for the reply`() {
        assertEquals(237_500, ChatContextPolicy.compactAt(250_000, 2_000))
        assertEquals(218_000, ChatContextPolicy.compactAt(250_000, 32_000))
    }
    @Test fun `code context shrinks to what the server reports and never grows`() {
        assertEquals(16_384, ChatContextPolicy.cap(1_000_000, 16_384))
        assertEquals(131_072, ChatContextPolicy.cap(131_072, 262_144))
        assertEquals(131_072, ChatContextPolicy.cap(131_072, null))
        assertEquals(131_072, ChatContextPolicy.cap(131_072, 0))
    }

    @Test fun `code context follows the model list and a learned window`() {
        assertEquals(198_000, ChatContextPolicy.codeLimit(1_000_000, null, 198_000, null))
        assertEquals(197_488, ChatContextPolicy.codeLimit(1_000_000, null, 1_048_576, 197_488))
        assertEquals(64_000, ChatContextPolicy.codeLimit(64_000, null, 198_000, null))
        assertEquals(32_768, ChatContextPolicy.codeLimit(1_000_000, 32_768, 198_000, 197_488))
        assertEquals(1_000_000, ChatContextPolicy.codeLimit(1_000_000, null, null, null))
    }

    @Test fun `a reply cap in the model list is not taken as the window`() {
        assertEquals(1_000_000, ChatContextPolicy.codeLimit(1_000_000, null, 8_192, null))
    }

    @Test fun `chat takes the smaller of the listed and learned windows`() {
        assertEquals(197_488L, ChatContextPolicy.modelLimit(1_048_576, 197_488))
        assertEquals(128_000L, ChatContextPolicy.modelLimit(128_000, null))
        assertEquals(null, ChatContextPolicy.modelLimit(null, null))
    }
}
