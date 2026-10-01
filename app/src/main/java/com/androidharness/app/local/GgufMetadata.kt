package com.androidharness.app.local

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read only a bounded header, never the tensor data or a whole multi-GB file. */
object GgufMetadata {
    const val PROBE_BYTES = 2 * 1024 * 1024
    data class Estimate(val kvBytesPerToken: Long = 256 * 1024, val maxContext: Int = 8192)

    fun inspect(bytes: ByteArray): Estimate {
        require(bytes.size >= 24 && bytes.take(4) == listOf<Byte>(71, 71, 85, 70)) { "The link does not contain a GGUF model." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        require(buffer.int in 2..3) { "Unsupported GGUF version. Use a GGUF v2 or v3 model." }
        require(buffer.long > 0) { "This GGUF contains no model tensors." }
        val count = buffer.long
        require(count in 1..100_000) { "Invalid GGUF metadata." }
        val numbers = mutableMapOf<String, Long>()
        var architecture: String? = null
        var split = false
        fun string(): String {
            val length = buffer.long
            require(length in 0..PROBE_BYTES.toLong()) { "Invalid GGUF string length." }
            if (length > buffer.remaining()) throw java.nio.BufferUnderflowException()
            val data = ByteArray(length.toInt()); buffer.get(data)
            return data.toString(Charsets.UTF_8)
        }
        fun scalar(type: Int): Long? = when (type) {
            0 -> buffer.get().toLong() and 255; 1 -> buffer.get().toLong()
            2 -> buffer.short.toLong() and 65535; 3 -> buffer.short.toLong()
            4 -> buffer.int.toLong() and 0xffffffffL; 5 -> buffer.int.toLong()
            6 -> { buffer.float; null }; 7 -> buffer.get().toLong()
            8 -> { string(); null }; 10, 11 -> buffer.long
            12 -> { buffer.double; null }; else -> error("Invalid GGUF metadata type.")
        }
        // Metadata can exceed the probe (for example a tokenizer vocabulary). Use a
        // conservative cache allowance if the architecture details are incomplete.
        try {
            repeat(count.toInt()) {
                val key = string()
                val type = buffer.int
                if (type == 9) {
                    val element = buffer.int; val length = buffer.long
                    require(length in 0..1_000_000 && element != 9)
                    repeat(length.toInt()) { scalar(element) }
                } else if (key == "general.architecture" && type == 8) {
                    architecture = string()
                } else {
                    val number = scalar(type)
                    if (number != null) numbers[key] = number
                    if (key == "split.count" && number != null && number > 1) split = true
                }
            }
        } catch (_: java.nio.BufferUnderflowException) {
            // A large tokenizer can extend past the bounded probe.
        }
        require(!split) { "Split GGUF models are not supported. Choose a single-file GGUF." }
        val prefix = architecture ?: return Estimate()
        val context = numbers["$prefix.context_length"] ?: 8192
        require(context >= 512) { "This model supports fewer than 512 context tokens." }
        val fallback = Estimate(maxContext = context.coerceAtMost(8192).toInt())
        val layers = numbers["$prefix.block_count"] ?: return fallback
        val heads = numbers["$prefix.attention.head_count"] ?: return fallback
        val kvHeads = numbers["$prefix.attention.head_count_kv"] ?: heads
        val embedding = numbers["$prefix.embedding_length"] ?: return fallback
        if (layers !in 1..1024 || heads !in 1..1024 || kvHeads !in 1..1024 || embedding !in 1..65536) return fallback
        val keySize = numbers["$prefix.attention.key_length"] ?: (embedding / heads)
        val valueSize = numbers["$prefix.attention.value_length"] ?: (embedding / heads)
        if (keySize !in 1..65536 || valueSize !in 1..65536) return fallback
        val cache = (2L * layers * kvHeads * (keySize + valueSize)).coerceAtLeast(24 * 1024)
        if (cache > 64 * 1024 * 1024) return fallback
        return Estimate(cache, context.coerceAtMost(8192).toInt())
    }
}
