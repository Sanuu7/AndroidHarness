package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingSpecsTest {

    @Test
    fun `gpt-5 family uses effort style with minimal rung`() {
        val spec = ThinkingSpecs.forModel("gpt-5.6-sol")
        assertEquals(ThinkingSpecs.Style.EFFORT, spec.style)
        assertEquals(
            listOf(
                ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
            ),
            spec.levels,
        )
        // MAX and ULTRA fold onto the family's highest wire value.
        assertEquals("xhigh", ThinkingSpecs.effortWire("gpt-5.6-sol", ThinkingLevel.XHIGH))
        assertEquals("xhigh", ThinkingSpecs.effortWire("gpt-5.6-sol", ThinkingLevel.MAX))
        assertEquals("xhigh", ThinkingSpecs.effortWire("gpt-5.6-sol", ThinkingLevel.ULTRA))
        assertEquals("minimal", ThinkingSpecs.effortWire("gpt-5.6-sol", ThinkingLevel.MINIMAL))
    }

    @Test
    fun `openai o-series routes through effort table`() {
        assertEquals(ThinkingSpecs.Style.EFFORT, ThinkingSpecs.forModel("o4-mini").style)
        assertEquals("medium", ThinkingSpecs.effortWire("o4-mini", ThinkingLevel.MEDIUM))
    }

    @Test
    fun `grok exposes only low and high efforts`() {
        val spec = ThinkingSpecs.forModel("grok-4-fast")
        assertEquals(ThinkingSpecs.Style.EFFORT, spec.style)
        assertEquals(
            listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH),
            spec.levels,
        )
    }

    @Test
    fun `hermes clamp - non-native tier takes nearest weaker never stronger`() {
        // Grok speaks only low/high: a Medium ask must NOT silently escalate
        // cost (old behavior rounded UP to high); it clamps down to low.
        assertEquals("low", ThinkingSpecs.effortWire("grok-4-fast", ThinkingLevel.MEDIUM))
        assertEquals("high", ThinkingSpecs.effortWire("grok-4-fast", ThinkingLevel.XHIGH))
        // Nothing weaker than MINIMAL exists -> floor keeps the ask enabled.
        assertEquals("low", ThinkingSpecs.effortWire("grok-4-fast", ThinkingLevel.MINIMAL))
        // ULTRA folds onto the family top.
        assertEquals("high", ThinkingSpecs.effortWire("grok-4-fast", ThinkingLevel.ULTRA))
    }

    @Test
    fun `resolveLevel is monotonic and never targets off`() {
        val spec = ThinkingSpecs.forModel("grok-4-fast")
        val seq = listOf(
            ThinkingLevel.MINIMAL, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
        )
        val resolved = seq.mapNotNull { ThinkingSpecs.resolveLevel(it, spec) }
        // A stronger request never resolves weaker than a weaker request.
        assertTrue(resolved.zipWithNext().all { (a, b) -> b >= a })
        // An enabled ask stays enabled.
        assertTrue(resolved.none { it == ThinkingLevel.OFF })
        // OFF passes through only when explicitly requested.
        assertEquals(ThinkingLevel.OFF, ThinkingSpecs.resolveLevel(ThinkingLevel.OFF, spec))
    }

    @Test
    fun `claude and gemini take budgets with all levels`() {
        for (model in listOf("claude-sonnet-4-5", "gemini-2.5-flash")) {
            val spec = ThinkingSpecs.forModel(model)
            assertEquals(ThinkingSpecs.Style.BUDGET, spec.style)
            assertEquals(ThinkingLevel.entries.toList(), spec.levels)
            // Budget-style models take no effort parameter.
            assertNull(ThinkingSpecs.effortWire(model, ThinkingLevel.HIGH))
        }
    }

    @Test
    fun `deepseek thinks on its own - no wire parameter`() {
        val spec = ThinkingSpecs.forModel("deepseek-v4-flash")
        assertEquals(ThinkingSpecs.Style.NONE, spec.style)
        assertNull(ThinkingSpecs.effortWire("deepseek-v4-flash", ThinkingLevel.HIGH))
        assertNull(ThinkingSpecs.effortWire("deepseek-v4-flash", ThinkingLevel.MAX))
    }

    @Test
    fun `unknown models default to budget with every level`() {
        val spec = ThinkingSpecs.forModel("some-future-model")
        assertEquals(ThinkingSpecs.Style.BUDGET, spec.style)
        assertEquals(ThinkingLevel.entries.toList(), spec.levels)
    }

    @Test
    fun `off never sends a wire value`() {
        assertNull(ThinkingSpecs.effortWire("gpt-5.6-sol", ThinkingLevel.OFF))
    }

    @Test
    fun `openrouter unified effort reaches budget families`() {
        // Claude/Gemini through the OpenAI-compatible API had NO dial before:
        // the unified object clamps them to the standard vocabulary.
        for (model in listOf("anthropic/claude-sonnet-4-5", "google/gemini-2.5-flash")) {
            assertEquals("low", ThinkingSpecs.openRouterEffort(model, ThinkingLevel.LOW))
            assertEquals("medium", ThinkingSpecs.openRouterEffort(model, ThinkingLevel.MEDIUM))
            assertEquals("high", ThinkingSpecs.openRouterEffort(model, ThinkingLevel.HIGH))
            assertEquals("high", ThinkingSpecs.openRouterEffort(model, ThinkingLevel.MAX))
        }
    }

    @Test
    fun `openrouter unified effort keeps exact values for effort families`() {
        assertEquals("xhigh", ThinkingSpecs.openRouterEffort("openai/gpt-5.6-sol", ThinkingLevel.XHIGH))
        assertEquals("xhigh", ThinkingSpecs.openRouterEffort("openai/gpt-5.6-sol", ThinkingLevel.MAX))
        assertEquals("medium", ThinkingSpecs.openRouterEffort("openai/o4-mini", ThinkingLevel.MEDIUM))
    }

    @Test
    fun `openrouter unified effort clamps like hermes`() {
        // Effort families keep exact natives…
        assertEquals("xhigh", ThinkingSpecs.openRouterEffort("openai/gpt-5.6-sol", ThinkingLevel.XHIGH))
        assertEquals("medium", ThinkingSpecs.openRouterEffort("openai/o4-mini", ThinkingLevel.MEDIUM))
        // …Grok speaks only low/high: Medium clamps DOWN (never escalates).
        assertEquals("low", ThinkingSpecs.openRouterEffort("x-ai/grok-4-fast", ThinkingLevel.MEDIUM))
        // MAX folds onto the family top.
        assertEquals("high", ThinkingSpecs.openRouterEffort("x-ai/grok-4-fast", ThinkingLevel.MAX))
    }

    @Test
    fun `openrouter unified effort leaves inherent reasoners alone`() {
        assertNull(ThinkingSpecs.openRouterEffort("deepseek/deepseek-v4-flash", ThinkingLevel.HIGH))
        assertNull(ThinkingSpecs.openRouterEffort("gpt-5.6-sol", ThinkingLevel.OFF))
    }

    @Test
    fun `router-prefixed ids still match their family`() {
        assertEquals(ThinkingSpecs.Style.EFFORT, ThinkingSpecs.forModel("openai/o3-mini").style)
        assertTrue(ThinkingSpecs.forModel("deepseek/deepseek-r1").levels.contains(ThinkingLevel.MEDIUM))
    }

    // ---- models.dev dynamic layer -----------------------------------------

    @org.junit.After
    fun resetModelsDev() = com.androidharness.app.llm.ModelsDev.replaceForTesting(emptyMap())

    @Test
    fun `dynamic catalog vocabulary overrides the shipped table`() {
        com.androidharness.app.llm.ModelsDev.replaceForTesting(
            mapOf(
                "openrouter" to mapOf(
                    // Shipped table calls deepseek NONE (inherent); the catalog
                    // says this lane takes an effort dial — catalog wins.
                    "deepseek/deepseek-v4-flash" to com.androidharness.app.llm.ModelsDev.Entry(
                        reasoning = true, effortValues = listOf("high", "xhigh"),
                        budgetTokens = false, budgetMax = null, toggle = true,
                    ),
                ),
            ),
        )
        val spec = ThinkingSpecs.forModel("deepseek/deepseek-v4-flash", "openrouter")
        assertEquals(ThinkingSpecs.Style.EFFORT, spec.style)
        assertEquals(
            listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH, ThinkingLevel.XHIGH),
            spec.levels,
        )
        // Wire values come from the catalog vocabulary, clamping to nearest.
        assertEquals("high", ThinkingSpecs.effortWire("deepseek/deepseek-v4-flash", ThinkingLevel.LOW, "openrouter"))
        assertEquals("xhigh", ThinkingSpecs.effortWire("deepseek/deepseek-v4-flash", ThinkingLevel.MAX, "openrouter"))
    }

    @Test
    fun `dynamic reasoning-false kills the dial entirely`() {
        com.androidharness.app.llm.ModelsDev.replaceForTesting(
            mapOf(
                "openrouter" to mapOf(
                    "some/fake-gpt" to com.androidharness.app.llm.ModelsDev.Entry(
                        reasoning = false, effortValues = null,
                        budgetTokens = false, budgetMax = null, toggle = false,
                    ),
                ),
            ),
        )
        assertEquals(listOf(ThinkingLevel.OFF), ThinkingSpecs.forModel("some/fake-gpt", "openrouter").levels)
        assertNull(ThinkingSpecs.openRouterReasoning("some/fake-gpt", ThinkingLevel.HIGH))
    }

    @Test
    fun `dynamic toggle-only models send enabled on openrouter`() {
        com.androidharness.app.llm.ModelsDev.replaceForTesting(
            mapOf(
                "openrouter" to mapOf(
                    "z-ai/glm-5.2" to com.androidharness.app.llm.ModelsDev.Entry(
                        reasoning = true, effortValues = null,
                        budgetTokens = false, budgetMax = null, toggle = true,
                    ),
                ),
            ),
        )
        val rr = ThinkingSpecs.openRouterReasoning("z-ai/glm-5.2", ThinkingLevel.HIGH)
        assertEquals(true, rr?.enabled)
        assertNull(rr?.effort)
        // No dynamic entry → shipped table fallback still clamps as before.
        com.androidharness.app.llm.ModelsDev.replaceForTesting(emptyMap())
        assertEquals("high", ThinkingSpecs.openRouterReasoning("anthropic/claude-sonnet-4-5", ThinkingLevel.HIGH)?.effort)
    }
}
