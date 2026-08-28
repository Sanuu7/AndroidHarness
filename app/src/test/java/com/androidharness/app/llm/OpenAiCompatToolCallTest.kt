package com.androidharness.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for streamed tool-call accumulation. Gateways in the wild
 * stream parallel tool calls with every fragment carrying index 0, and echo
 * the call id on every fragment, the accumulator must keep calls separate by
 * ID and never lose argument fragments.
 */
class OpenAiCompatToolCallTest {

    private val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
    private val acc = LinkedHashMap<String, Triple<StringBuilder, StringBuilder, StringBuilder>>()
    private val indexToId = HashMap<Int, String>()

    private fun chunk(delta: String) {
        provider.parseChunk(
            ProviderFactory.json.parseToJsonElement(
                """{"choices":[{"delta":{"tool_calls":[$delta]}}]}""",
            ),
            acc,
            indexToId,
        )
    }

    private fun drain() = provider.drainAccumulated(acc)

    @Test
    fun `plain openai streaming keeps id-linked argument fragments`() {
        chunk("""{"index":0,"id":"call_A","function":{"name":"read_file","arguments":""}}""")
        chunk("""{"index":0,"function":{"arguments":"{\"pa"}}""")
        chunk("""{"index":0,"function":{"arguments":"th\":\"a.kt\""}}""")
        chunk("""{"index":0,"function":{"arguments":"}"}}""")

        val calls = drain()
        assertEquals(1, calls.size)
        assertEquals("call_A", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("""{"path":"a.kt"}""", calls[0].argumentsJson)
    }

    @Test
    fun `gateway reusing index 0 keeps parallel calls separate`() {
        // Both calls stream with index 0; the second announces itself with a new id.
        chunk("""{"index":0,"id":"call_A","function":{"name":"task","arguments":""}}""")
        chunk("""{"index":0,"function":{"arguments":"{\"p\":1}"}}""")
        chunk("""{"index":0,"id":"call_B","function":{"name":"task","arguments":""}}""")
        chunk("""{"index":0,"function":{"arguments":"{\"p\":2}"}}""")

        val calls = drain()
        assertEquals(2, calls.size)
        assertEquals("call_A", calls[0].id)
        assertEquals("""{"p":1}""", calls[0].argumentsJson)
        assertEquals("call_B", calls[1].id)
        assertEquals("""{"p":2}""", calls[1].argumentsJson)
    }

    @Test
    fun `gateway echoing the id on every fragment does not duplicate or corrupt`() {
        chunk("""{"index":0,"id":"call_A","function":{"name":"shell","arguments":"{\"co"}}""")
        chunk("""{"index":0,"id":"call_A","function":{"arguments":"mmand\":\"ls\"}"}}""")

        val calls = drain()
        assertEquals(1, calls.size)
        assertEquals("call_A", calls[0].id)
        assertEquals("""{"command":"ls"}""", calls[0].argumentsJson)
    }
}
