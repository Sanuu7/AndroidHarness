package com.androidharness.app.llm.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTraceStoreTest {

    private fun trace(id: Long, body: String? = null) = DebugTrace(
        id = id,
        startedAt = id,
        providerId = "p",
        providerName = "Provider",
        model = "m",
        protocol = "OPENAI_COMPAT",
        method = "POST",
        url = "https://example.invalid/v1/chat/completions",
        requestHeaders = emptyList(),
        requestBody = body,
    )

    @Test fun newestFirstAndTheOldestFallsOffTheEnd() {
        val store = DebugTraceStore(capacity = 3)
        (1L..5L).forEach { store.record(trace(it)) }
        assertEquals(listOf(5L, 4L, 3L), store.traces.value.map { it.id })
    }

    @Test fun clearEmptiesTheList() {
        val store = DebugTraceStore(capacity = 3)
        store.record(trace(1))
        store.clear()
        assertTrue(store.traces.value.isEmpty())
    }

    @Test fun oversizedBodiesAreCappedAndFlagged() {
        val long = "x".repeat(100)
        val (text, cut) = DebugCapture.cap(long, 40)
        assertEquals(40, text!!.length)
        assertTrue(cut)
        val (short, untouched) = DebugCapture.cap("small", 40)
        assertEquals("small", short)
        assertFalse(untouched)
    }

    @Test fun aFailedTraceIsRecognised() {
        assertTrue(trace(1).copy(statusCode = 429).failed)
        assertTrue(trace(1).copy(error = "timeout").failed)
        assertFalse(trace(1).copy(statusCode = 200).failed)
    }
}
