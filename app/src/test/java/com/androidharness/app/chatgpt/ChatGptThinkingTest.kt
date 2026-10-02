package com.androidharness.app.chatgpt

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ModelEntry
import org.junit.Assert.*
import org.junit.Test

class ChatGptThinkingTest {
    @Test fun `Sol 6 point 1 has no off minimal or ultra and sends actual max`() {
        val model = ModelEntry("gpt-6.1-sol")
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX), ChatGptThinking.levels(model))
        assertEquals("max", ChatGptThinking.effort(model, ThinkingLevel.ULTRA))
        assertEquals(ThinkingLevel.MEDIUM, ChatGptThinking.selected(model, ThinkingLevel.OFF))
    }

    @Test fun `none is available only on models that support it`() {
        assertTrue(ThinkingLevel.OFF in ChatGptThinking.levels(ModelEntry("gpt-6-sol")))
        assertEquals("none", ChatGptThinking.effort(ModelEntry("gpt-6-luna"), ThinkingLevel.OFF))
        assertFalse(ThinkingLevel.OFF in ChatGptThinking.levels(ModelEntry("gpt-6-astra")))
        assertFalse(ThinkingLevel.MAX in ChatGptThinking.levels(ModelEntry("gpt-5.5")))
    }

    @Test fun `account capabilities override fallback and refresh the default`() {
        val models = ChatGptProtocol.models("""{"models":[{"slug":"gpt-6.1-sol","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],"default_reasoning_level":"high"}]}""")
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH), ChatGptThinking.levels(models.single()))
        assertEquals("high", ChatGptThinking.effort(models.single(), ThinkingLevel.OFF))
        assertEquals("high", ChatGptThinking.effort(models.single(), ThinkingLevel.MAX))
    }

    @Test fun `unknown and nonreasoning models never invent supported levels`() {
        assertTrue(ChatGptThinking.levels(ModelEntry("unknown-model")).isEmpty())
        assertNull(ChatGptThinking.effort(ModelEntry("unknown-model"), ThinkingLevel.HIGH))
        assertTrue(ChatGptThinking.levels(ModelEntry("gpt-6-sol", reasoning = false)).isEmpty())
        assertEquals(listOf(ThinkingLevel.LOW), ChatGptThinking.levels(ModelEntry("future", reasoningEfforts = listOf("unknown", "low"))))
    }
}
