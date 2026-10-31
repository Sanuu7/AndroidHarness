package com.androidharness.app.data.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** One foreground session retries transient failures; only successful checks get a cooldown. */
internal class ForegroundUpdateChecks(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryDelays: List<Long> = listOf(0, 10_000, 30_000),
) {
    private val running = Mutex()
    private var lastSuccess: Long? = null

    suspend fun run(check: suspend () -> Boolean) {
        if (!running.tryLock()) return
        try {
            if (lastSuccess?.let { clock() - it in 0 until 60_000 } == true) return
            for (wait in retryDelays) {
                delay(wait)
                if (check()) { lastSuccess = clock(); return }
            }
        } finally { running.unlock() }
    }
}
