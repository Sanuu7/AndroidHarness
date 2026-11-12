package com.androidharness.app.llm.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded, in-memory log of provider exchanges.
 *
 * Nothing is written to disk: the console exists to explain a failure the user
 * just hit, and a prompt body on disk would outlive that reason. [record] is
 * callable from an OkHttp worker thread, so it takes a plain lock rather than a
 * coroutine.
 */
class DebugTraceStore(
    val capacity: Int = 50,
    /** Per-body character cap; request bodies, response previews and stream taps use it. */
    val maxBodyChars: Int = 128_000,
) {
    private val lock = Any()
    private val entries = ArrayDeque<DebugTrace>()
    private val mutableTraces = MutableStateFlow<List<DebugTrace>>(emptyList())

    /** Newest first. */
    val traces: StateFlow<List<DebugTrace>> = mutableTraces.asStateFlow()

    fun record(trace: DebugTrace) {
        synchronized(lock) {
            entries.addFirst(trace)
            while (entries.size > capacity) entries.removeLast()
            mutableTraces.value = entries.toList()
        }
    }

    /** Fills in the stream that belongs to the trace recorded for [streamKey]. */
    fun attachStream(
        streamKey: String,
        body: String,
        truncated: Boolean,
        events: Int,
        usage: String? = null,
    ) {
        if (streamKey.isEmpty()) return
        synchronized(lock) {
            val index = entries.indexOfFirst { it.streamKey == streamKey }
            if (index < 0) return
            val existing = entries.elementAt(index)
            entries[index] = existing.copy(
                streamBody = body.take(maxBodyChars),
                streamEvents = events,
                streamUsage = usage ?: existing.streamUsage,
                truncated = existing.truncated || truncated,
            )
            mutableTraces.value = entries.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            mutableTraces.value = emptyList()
        }
    }
}

/**
 * Process-wide switch for the provider debug console.
 *
 * Off by default and never persisted: capture starts when the user turns it on
 * in Settings and stops when the app process ends. [captureBodies] is a second,
 * separate decision because prompt bodies are the sensitive half of a trace.
 */
object DebugCapture {
    @Volatile var enabled: Boolean = false
    @Volatile var captureBodies: Boolean = false

    /** Holds capture without touching the saved switches, so a noisy run can be skipped. Not persisted. */
    @Volatile var paused: Boolean = false

    /** True when a request made right now would be recorded. */
    val recording: Boolean get() = enabled && !paused

    val store = DebugTraceStore()

    // Ids are the primary key of the saved log, so they must not restart at 1 with
    // each process: seeding from the clock keeps a new run from replacing old rows.
    private val nextId = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1_000)

    fun nextTraceId(): Long = nextId.getAndIncrement()

    /** Caps [value] and reports whether anything was cut. */
    fun cap(value: String?, limit: Int = store.maxBodyChars): Pair<String?, Boolean> {
        if (value == null) return null to false
        if (value.length <= limit) return value to false
        return value.take(limit) to true
    }

    /** A tap for one stream, or null when capture is off or the body is not wanted. */
    fun streamTap(key: String?): DebugStreamTap? =
        if (recording && captureBodies && !key.isNullOrEmpty()) DebugStreamTap(key, store.maxBodyChars) else null
}

/**
 * Collects raw SSE lines for the console without touching what the parser sees.
 *
 * Appending stops at the cap; [events] keeps counting so a truncated trace still
 * says how much went past.
 */
class DebugStreamTap(private val key: String, private val limit: Int) {
    private val buffer = StringBuilder()
    var events: Int = 0
        private set
    var truncated: Boolean = false
        private set
    private var usage: String? = null
    private var lastPublish: Long = 0

    fun line(value: String) {
        // Keep-alive blank lines carry nothing; counting them doubled the event
        // number and filled the tail of a truncated stream with empty lines.
        if (value.isBlank()) return
        if (value.startsWith("data:")) {
            events++
            readUsage(value)
        }
        if (buffer.length + value.length + 1 > limit) {
            truncated = true
        } else {
            buffer.append(value).append('\n')
        }
        val now = System.currentTimeMillis()
        if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
            lastPublish = now
            publish()
        }
    }

    fun finish() {
        if (truncated) buffer.append(": [truncated by the request log]\n")
        publish()
    }

    private fun publish() {
        DebugCapture.store.attachStream(key, buffer.toString(), truncated, events, usage)
    }

    /** Pulls the token totals out of the final usage chunk so the header can show them. */
    private fun readUsage(line: String) {
        if (!line.contains("\"usage\"")) return
        fun number(name: String) = Regex("\"$name\":(\\d+)").find(line)?.groupValues?.get(1)
        val prompt = number("prompt_tokens")
        val completion = number("completion_tokens")
        val total = number("total_tokens")
        val cached = number("prompt_cache_hit_tokens") ?: number("cached_tokens")
        if (prompt == null && completion == null) return
        usage = buildString {
            append("in ${prompt ?: "?"}")
            append(", out ${completion ?: "?"}")
            if (total != null) append(", total $total")
            if (cached != null) append(", cached $cached")
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 500L
    }
}
