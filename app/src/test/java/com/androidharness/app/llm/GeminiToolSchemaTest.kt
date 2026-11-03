package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.tools.HttpRequestTool
import com.androidharness.app.tools.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GeminiToolSchemaTest {
    private val json = ProviderFactory.json
    private val config = ProviderConfig(
        "fixture", "Gemini", ProviderType.GEMINI,
        "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
    )

    @Test fun `messages and doctor send the HTTP tool without a restricted parameters schema`() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent += json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """data: {"candidates":[{"content":{"parts":[{"text":"Ready"}]},"finishReason":"STOP"}]}"""
                        .plus("\n\n").toResponseBody("text/event-stream".toMediaType()),
                ).build()
        }.build()
        val http = HttpRequestTool(client)
        val tools = ToolRegistry(listOf(http)).schemas()
        val provider = GeminiProvider(client, json)

        for (message in listOf("Hello", "/doctor")) {
            val events = provider.streamChat(
                config, "fixture-key", "Help.", listOf(ChatMessage(Role.USER, message)), tools, RequestOptions(),
            ).toList()
            assertEquals(listOf("Ready"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
            assertTrue(events.none { it is StreamEvent.Failure })
        }

        assertEquals(2, sent.size)
        sent.forEach { body ->
            val declaration = declarations(body).single().jsonObject
            assertEquals("http_request", declaration["name"]!!.jsonPrimitive.content)
            assertEquals(http.description, declaration["description"]!!.jsonPrimitive.content)
            assertFalse(declaration.containsKey("parameters"))
            val parameters = declaration["parametersJsonSchema"]!!.jsonObject
            assertEquals(http.parametersSchema, parameters)
            assertEquals("string", parameters["properties"]!!.jsonObject["headers"]!!.jsonObject
                ["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `custom tools preserve nested dictionaries and JSON Schema constraints`() {
        val parameters = json.parseToJsonElement("""{
            "type": "object",
            "properties": {
                "records": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "labels": {"type": "object", "additionalProperties": {"type": "string"}},
                            "additionalProperties": {"type": "string"},
                            "value": {"anyOf": [{"type": "integer"}, {"type": "null"}]}
                        },
                        "required": ["labels", "value"],
                        "additionalProperties": false
                    }
                }
            },
            "required": ["records"],
            "additionalProperties": false
        }""").jsonObject
        val schema = ToolSchema("custom_tool", "Use structured records.", parameters)
        val body = GeminiProvider(OkHttpClient(), json).buildRequestBody(
            config, "Help.", listOf(ChatMessage(Role.USER, "Hello")), listOf(schema), RequestOptions(),
        )
        val declaration = declarations(body).single().jsonObject

        assertFalse(declaration.containsKey("parameters"))
        assertEquals(parameters, declaration["parametersJsonSchema"])
        assertEquals("custom_tool", declaration["name"]!!.jsonPrimitive.content)
        assertEquals(schema.description, declaration["description"]!!.jsonPrimitive.content)
    }

    @Test fun `requests without tools omit tool declarations`() {
        val body = GeminiProvider(OkHttpClient(), json).buildRequestBody(
            config, "Help.", listOf(ChatMessage(Role.USER, "Hello")), emptyList(), RequestOptions(),
        )

        assertFalse(body.containsKey("tools"))
    }

    private fun declarations(body: JsonObject) =
        body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
}
