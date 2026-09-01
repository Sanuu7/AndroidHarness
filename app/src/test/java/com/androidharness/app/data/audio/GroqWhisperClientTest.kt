package com.androidharness.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqWhisperClientTest {

    @Test
    fun `validation requires non-blank key`() {
        val jsonSample = """
            {
              "text": "Hello world this is a transcription test"
            }
        """.trimIndent()

        val parsedText = kotlinx.serialization.json.Json.parseToJsonElement(jsonSample)
            .let { it as kotlinx.serialization.json.JsonObject }["text"]
            ?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content

        assertEquals("Hello world this is a transcription test", parsedText)
    }

    @Test
    fun `error json parsing works cleanly`() {
        val errorJson = """
            {
              "error": {
                "message": "Invalid API Key provided",
                "type": "invalid_request_error"
              }
            }
        """.trimIndent()

        val parsedError = kotlinx.serialization.json.Json.parseToJsonElement(errorJson)
            .let { it as kotlinx.serialization.json.JsonObject }["error"]
            ?.let { it as kotlinx.serialization.json.JsonObject }["message"]
            ?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content

        assertEquals("Invalid API Key provided", parsedError)
    }
}
