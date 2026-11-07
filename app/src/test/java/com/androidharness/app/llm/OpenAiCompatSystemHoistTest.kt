package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict chat templates (Qwen3.5/3.6 on SGLang/vLLM) reject any request with a
 * role=system message after index 0. These tests pin the wire shape produced by
 * [OpenAiCompatProvider.buildRequestBody]: exactly one system message, at index 0,
 * with mid-history system notes folded into its text.
 */
class OpenAiCompatSystemHoistTest {

    private val provider = OpenAiCompatProvider(OkHttpClient(), Json { ignoreUnknownKeys = true })
    private val config = ProviderConfig(
        id = "sglang",
        name = "SGLang",
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = "http://100.118.164.28:8000/v1",
        model = "qwen3.6-35b-a3b",
    )

    private fun roles(body: JsonObject): List<String> =
        body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    @Test
    fun `system notes from history are folded into the leading prompt`() {
        val body = provider.buildRequestBody(
            config,
            "You are a test assistant.",
            listOf(
                ChatMessage(role = Role.USER, text = "hi"),
                ChatMessage(role = Role.ASSISTANT, text = "hello"),
                ChatMessage(role = Role.SYSTEM, text = "Compacted earlier: discussed the reactor."),
                ChatMessage(role = Role.USER, text = "go"),
            ),
            emptyList(),
            RequestOptions(),
        )
        val roles = roles(body)
        assertEquals(1, roles.count { it == "system" })
        assertEquals("system", roles.first())
        assertEquals(listOf("user", "assistant", "user"), roles.drop(1))
        val leading = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(leading.startsWith("You are a test assistant."))
        assertTrue(leading.contains("Compacted earlier: discussed the reactor."))
    }

    @Test
    fun `history without system notes is unchanged`() {
        val body = provider.buildRequestBody(
            config,
            "sys",
            listOf(
                ChatMessage(role = Role.USER, text = "hi"),
                ChatMessage(role = Role.ASSISTANT, text = "hello"),
            ),
            emptyList(),
            RequestOptions(),
        )
        assertEquals(listOf("system", "user", "assistant"), roles(body))
        assertEquals("sys", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank system notes are dropped without a section header`() {
        val body = provider.buildRequestBody(
            config,
            "sys",
            listOf(
                ChatMessage(role = Role.SYSTEM, text = "   "),
                ChatMessage(role = Role.USER, text = "hi"),
            ),
            emptyList(),
            RequestOptions(),
        )
        assertEquals("sys", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user"), roles(body))
    }
}
