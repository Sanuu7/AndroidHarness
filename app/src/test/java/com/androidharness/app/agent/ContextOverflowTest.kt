package com.androidharness.app.agent

import com.androidharness.app.data.decodeContextLimits
import com.androidharness.app.data.encodeContextLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOverflowTest {
    private val gateway = """HTTP 400: {"error":{"message":"This model's maximum context length is 198000 tokens. """ +
        """Your messages are about 197589 tokens. Shorten the conversation.","type":"invalid_request_error",""" +
        """"code":"context_length_exceeded","param":"messages","estimated_tokens":197589,"max_input_tokens":197488}}"""

    @Test fun `gateway error gives the input window and its own count`() {
        assertTrue(ContextOverflow.isOverflow(gateway))
        assertEquals(197_488, ContextOverflow.reportedLimit(gateway))
        assertEquals(197_589, ContextOverflow.reportedTokens(gateway))
    }

    @Test fun `common provider wordings are recognised`() {
        assertEquals(128_000, ContextOverflow.reportedLimit(
            "This model's maximum context length is 128000 tokens. However, you requested 130000 tokens."))
        assertEquals(130_000, ContextOverflow.reportedTokens(
            "This model's maximum context length is 128000 tokens. However, you requested 130000 tokens."))
        assertEquals(200_000, ContextOverflow.reportedLimit("prompt is too long: 210345 tokens > 200000 maximum"))
        assertEquals(210_345, ContextOverflow.reportedTokens("prompt is too long: 210345 tokens > 200000 maximum"))
        assertEquals(1_048_576, ContextOverflow.reportedLimit(
            "The input token count (1100000) exceeds the maximum number of tokens allowed (1048576)."))
        assertEquals(32_768, ContextOverflow.reportedLimit(
            "the request exceeds the available context size (32768 tokens), try increasing it"))
    }

    @Test fun `overflow without a number is still recognised`() {
        assertTrue(ContextOverflow.isOverflow("Input is too long for requested model."))
        assertNull(ContextOverflow.reportedLimit("Input is too long for requested model."))
    }

    @Test fun `rate limits and unrelated errors are not overflow`() {
        assertFalse(ContextOverflow.isOverflow("Rate limit reached: too many tokens per minute (TPM). Limit 200000"))
        assertFalse(ContextOverflow.isOverflow("HTTP 401: invalid api key"))
        assertFalse(ContextOverflow.isOverflow(null))
    }

    @Test fun `learned limits survive storage with separators in the key`() {
        val limits = mapOf("openrouter|google/gemini=x" to 197_488, "p|m" to 32_768)
        assertEquals(limits, decodeContextLimits(encodeContextLimits(limits)))
        assertEquals(emptyMap<String, Int>(), decodeContextLimits(setOf("broken", "=5", "k=abc")))
    }

    @Test fun `a rejection lowers the budget, forces compaction and is retried twice at most`() {
        val budget = ContextBudget(1_000_000)
        assertTrue(budget.recover(gateway, 150_000, "gw|gemini"))
        assertEquals(197_488, budget.limit)
        assertEquals(197_488, budget.recovery.learned)
        assertEquals("gw|gemini", budget.recovery.key)
        assertTrue(budget.recovery.forceCompaction)
        // The server counted more than the estimate, so the trigger moves down by that much.
        assertEquals(150_000, budget.recovery.scaled(197_589))
        budget.settle()
        assertFalse(budget.recovery.forceCompaction)
        assertTrue(budget.recover(gateway, 150_000, "gw|gemini"))
        assertFalse(budget.recover(gateway, 150_000, "gw|gemini"))
    }

    @Test fun `other failures leave the budget alone`() {
        val budget = ContextBudget(200_000)
        assertFalse(budget.recover("HTTP 500: upstream error", 10_000, "k"))
        assertFalse(budget.recover(null, 10_000, "k"))
        assertEquals(200_000, budget.limit)
    }

    @Test fun `a named window above the budget is not learned`() {
        val budget = ContextBudget(128_000)
        assertTrue(budget.recover(gateway, 0, "k"))
        assertEquals(128_000, budget.limit)
        assertNull(budget.recovery.learned)
    }
}
