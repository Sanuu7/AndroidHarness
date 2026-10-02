package com.androidharness.app.local

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class LocalModelConsentTest {
    @Test fun `continue resumes the requested action and remembers the accepted settings`() = runBlocking {
        val consent = LocalModelConsent()
        var performed = false
        val operation = launch { consent.confirm("model:settings", "Warning", listOf("Low RAM")); performed = true }
        val warning = withTimeout(2000) { consent.warning.first { it != null }!! }
        assertFalse(performed)
        warning.continueAnyway(); operation.join()
        assertTrue(performed); assertNull(consent.warning.value)
        withTimeout(2000) { consent.confirm("model:settings", "Warning", listOf("Low RAM")) }
        assertNull(consent.warning.value)
    }

    @Test fun `cancel or stopping a request never performs its action or leaves a dialog`() = runBlocking {
        val consent = LocalModelConsent()
        var performed = false
        val operation = launch { consent.confirm("model", "Warning", listOf("Low RAM")); performed = true }
        withTimeout(2000) { consent.warning.first { it != null }!! }.cancel()
        operation.join(); assertFalse(performed); assertNull(consent.warning.value)
        val stopped = launch { consent.confirm("model", "Warning", listOf("Low RAM")); performed = true }
        withTimeout(2000) { consent.warning.first { it != null } }
        stopped.cancelAndJoin(); assertFalse(performed); assertNull(consent.warning.value)
    }
}
