package com.androidharness.app.llm.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBodiesTest {

    private fun hugeRequest(): String {
        val content = "x".repeat(40_000)
        return """{"model":"deepseek-flash","stream":true,"messages":[{"role":"system","content":"$content"},{"role":"user","content":"请分析这段代码"}],"tools":[{"type":"function","function":{"name":"read_file"}}]}"""
    }

    @Test fun aHugeRequestStaysValidJsonAndKeepsTheMessages() {
        val raw = hugeRequest()
        val body = DebugBodies.shrink(raw, 4_000)
        assertTrue("should be elided", body.elided)
        assertTrue("valid=${body.validJson} len=${body.text.length} omitted=${body.omitted}", body.validJson)
        assertTrue("something was dropped", body.omitted > 0)
        val parsed = Json.parseToJsonElement(body.text).jsonObject
        val messages = parsed["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("请分析这段代码", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue("tools survive", body.text.contains("read_file"))
    }

    @Test fun aSmallBodyIsReturnedUnchanged() {
        val raw = """{"model":"m","messages":[]}"""
        val body = DebugBodies.shrink(raw, 128_000)
        assertEquals(raw, body.text)
        assertFalse(body.elided)
    }

    @Test fun aNonJsonBodyKeepsAVisibleMarker() {
        val body = DebugBodies.shrink("{\"a\": " + "z".repeat(500), 100)
        assertTrue(body.elided)
        assertFalse(body.validJson)
        assertTrue(body.text.contains("省略"))
        assertTrue(body.text.length < 200)
    }

    @Test fun anOversizedArrayKeepsTheNewestEntries() {
        val messages = (1..40).joinToString(",") { """{"role":"user","content":"message number $it"}""" }
        val raw = """{"messages":[$messages]}"""
        val body = DebugBodies.shrink(raw, 600)
        assertTrue(body.validJson)
        val parsed = Json.parseToJsonElement(body.text).jsonObject["messages"]!!.jsonArray
        assertTrue("kept some", parsed.isNotEmpty())
        assertTrue("kept the newest", body.text.contains("message number 40"))
    }
}
