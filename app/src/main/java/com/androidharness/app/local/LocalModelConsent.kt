package com.androidharness.app.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalModelWarning(val title: String, val messages: List<String>) {
    internal val answer = CompletableDeferred<Boolean>()
    fun continueAnyway() { answer.complete(true) }
    fun cancel() { answer.complete(false) }
}

/** Shared by settings, the provider picker and inference, including background runs. */
class LocalModelConsent {
    private val mutex = Mutex()
    private val accepted = mutableSetOf<String>()
    private val _warning = MutableStateFlow<LocalModelWarning?>(null)
    val warning = _warning.asStateFlow()
    suspend fun acknowledge(key: String) = mutex.withLock { accepted += key }

    suspend fun confirm(key: String, title: String, messages: List<String>) = mutex.withLock {
        if (messages.isEmpty() || key in accepted) return@withLock
        val request = LocalModelWarning(title, messages)
        _warning.value = request
        try {
            if (!request.answer.await()) throw CancellationException("Local model operation cancelled")
            accepted += key
        } finally { _warning.value = null }
    }
}
