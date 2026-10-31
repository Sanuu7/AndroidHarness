package com.androidharness.app.data.update

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundUpdateChecksTest {
    @Test fun `temporary startup failure retries and reopening checks again after cooldown`() = runBlocking {
        var now = 0L
        var attempts = 0
        val checks = ForegroundUpdateChecks({ now }, listOf(0, 0, 0))
        checks.run { ++attempts >= 2 }
        assertEquals(2, attempts)
        checks.run { attempts++; true }
        assertEquals(2, attempts)
        now = 60_001
        checks.run { attempts++; true }
        assertEquals(3, attempts)
    }

    @Test fun `failed checks do not suppress the next foreground session`() = runBlocking {
        var attempts = 0
        val checks = ForegroundUpdateChecks({ 0 }, listOf(0, 0, 0))
        checks.run { attempts++; false }
        checks.run { attempts++; true }
        assertEquals(4, attempts)
    }

    @Test fun `background cancellation releases checker for the next opening`() = runBlocking {
        val checks = ForegroundUpdateChecks({ 0 }, listOf(0))
        val entered = CompletableDeferred<Unit>()
        val pending = launch { checks.run { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        var duplicate = false
        checks.run { duplicate = true; true }
        assertFalse(duplicate)
        pending.cancelAndJoin()
        var retried = false
        checks.run { retried = true; true }
        assertTrue(retried)
    }
}
