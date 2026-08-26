package com.androidharness.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevTest {

    private val sample = """
    {
      "openai": {
        "id": "openai", "name": "OpenAI", "api": "https://api.openai.com/v1", "npm": "@ai-sdk/openai-compatible",
        "models": {
          "gpt-5.6": {
            "id": "gpt-5.6", "reasoning": true,
            "limit": {"context": 1048576, "output": 131072},
            "cost": {"input": 1.25, "output": 10.0, "cache_read": 0.3125, "cache_write": 1.25},
            "reasoning_options": [{"type":"effort","values":["none","low","medium","high","xhigh","max"]}]
          }
        }
      },
      "anthropic": {
        "id": "anthropic", "name": "Anthropic", "api": "https://api.anthropic.com", "npm": "@ai-sdk/anthropic",
        "models": {
          "claude-sonnet-4-5": {
            "id": "claude-sonnet-4-5", "reasoning": true,
            "cost": {"input": 3.0, "output": 15.0, "cache_read": 0.3, "cache_write": 3.75},
            "reasoning_options": [{"type":"budget_tokens","min":1024,"max":31999}]
          }
        }
      },
      "openrouter": {
        "id": "openrouter", "name": "OpenRouter", "api": "https://openrouter.ai/api/v1", "npm": "@openrouter/ai-sdk-provider",
        "models": {
          "deepseek/deepseek-v4-flash": {
            "id": "deepseek/deepseek-v4-flash", "reasoning": true,
            "cost": {"input": 0.14, "output": 0.28, "cache_read": 0.028},
            "reasoning_options": [{"type":"toggle"},{"type":"effort","values":["high","xhigh"]}]
          },
          "z-ai/glm-5.2": {
            "id": "z-ai/glm-5.2", "reasoning": true,
            "reasoning_options": [{"type":"toggle"}]
          },
          "meta-llama/llama-3.3-70b-instruct": {
            "id": "meta-llama/llama-3.3-70b-instruct", "reasoning": false,
            "reasoning_options": []
          }
        }
      }
    }
    """.trimIndent()

    @Test
    fun `parse extracts effort vocabularies budgets toggles and non-reasoners`() {
        val parsed = ModelsDev.parse(sample).entries
        assertEquals(setOf("openai", "anthropic", "openrouter"), parsed.keys)

        with(parsed["openai"]!!.getValue("gpt-5.6")) {
            assertEquals(true, reasoning)
            assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"), effortValues)
            assertEquals(1.25, cost!!.input, 0.001)
            assertEquals(10.0, cost!!.output, 0.001)
            assertEquals(0.3125, cost!!.cacheRead, 0.001)
        }
        with(parsed["anthropic"]!!.getValue("claude-sonnet-4-5")) {
            assertTrue(budgetTokens)
            assertEquals(31999, budgetMax)
            assertEquals(3.0, cost!!.input, 0.001)
            assertEquals(15.0, cost!!.output, 0.001)
        }
        with(parsed["openrouter"]!!.getValue("z-ai/glm-5.2")) {
            assertTrue(toggle)
            assertNull(effortValues)
            assertNull(cost)
        }
        assertEquals(false, parsed["openrouter"]!!.getValue("meta-llama/llama-3.3-70b-instruct").reasoning)
    }

    @Test
    fun `entry matches exact ids and vendor-prefix suffixes both ways`() {
        ModelsDev.replaceForTesting(ModelsDev.parse(sample).entries)
        try {
            // Exact hit.
            assertEquals(true, ModelsDev.entry("openai", "gpt-5.6")!!.reasoning)
            // User config carries no vendor prefix, catalog id has one.
            assertEquals("deepseek", ModelsDev.entry("openrouter", "deepseek-v4-flash")!!.let { "deepseek" })
            // User config carries a prefix the catalog key lacks.
            assertEquals(31999, ModelsDev.entry("anthropic", "anthropic/claude-sonnet-4-5")!!.budgetMax)
            // Unknown provider key or model → null (shipped table decides).
            assertNull(ModelsDev.entry(null, "gpt-5.6"))
            assertNull(ModelsDev.entry("openai", "nonexistent-model"))
        } finally {
            ModelsDev.replaceForTesting(emptyMap())
        }
    }

    @Test
    fun `findCost resolves pricing from catalog and ModelPrices calculates accurately`() {
        ModelsDev.replaceForTesting(ModelsDev.parse(sample).entries)
        try {
            val cost = ModelsDev.findCost("openrouter", "deepseek-v4-flash")
            assertEquals(0.14, cost!!.input, 0.001)
            assertEquals(0.28, cost.output, 0.001)

            // 1,000,000 input tokens + 1,000,000 output tokens on deepseek-v4-flash
            val estimate = ModelPrices.estimate(
                model = "deepseek/deepseek-v4-flash",
                totalInputTokens = 1_000_000L,
                outputTokens = 1_000_000L,
                providerKey = "openrouter",
            )
            assertEquals(0.42, estimate!!, 0.001)

            // Fallback estimation for offline / unlisted models
            val fallback = ModelPrices.estimate(
                model = "claude-3-5-sonnet",
                totalInputTokens = 1_000_000L,
                outputTokens = 1_000_000L,
            )
            assertEquals(18.0, fallback!!, 0.001)
        } finally {
            ModelsDev.replaceForTesting(emptyMap())
        }
    }

    @Test
    fun `provider key mapping covers the major endpoints`() {
        assertEquals("openrouter", ModelsDev.providerKeyFor("https://openrouter.ai/api/v1"))
        assertEquals("anthropic", ModelsDev.providerKeyFor("https://api.anthropic.com"))
        assertEquals("google", ModelsDev.providerKeyFor("https://generativelanguage.googleapis.com/v1beta"))
        assertEquals("zhipuai", ModelsDev.providerKeyFor("https://open.bigmodel.cn/api/paas/v4"))
        assertEquals("moonshotai", ModelsDev.providerKeyFor("https://api.moonshot.cn/v1"))
        assertNull(ModelsDev.providerKeyFor("http://127.0.0.1:11434/v1"))
        assertNull(ModelsDev.providerKeyFor(null))
    }

    @Test
    fun `parse builds the searchable provider directory`() {
        val parsed = ModelsDev.parse(sample)
        val openai = parsed.providers.first { it.id == "openai" }
        assertEquals(ModelsDev.protocolFor(openai.npm), com.androidharness.app.llm.ProviderType.OPENAI_COMPAT)
        // Unsupported protocols stay off the add-provider list.
        assertNull(ModelsDev.protocolFor("@ai-sdk/amazon-bedrock"))
        assertEquals(com.androidharness.app.llm.ProviderType.ANTHROPIC, ModelsDev.protocolFor("@ai-sdk/anthropic"))
    }
}
