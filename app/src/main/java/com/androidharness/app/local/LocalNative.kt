package com.androidharness.app.local

import androidx.annotation.Keep

@Keep
object LocalNative {
    init { System.loadLibrary("harness_local") }
    external fun create(): Long
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun generateChat(handle: Long, path: ByteArray, projector: ByteArray, request: ByteArray,
        images: Array<ByteArray>, context: Int, input: Int, output: Int, threads: Int, callback: ChatCallback): ByteArray
    external fun convertSafetensors(handle: Long, directory: ByteArray, output: ByteArray, threads: Int)
    external fun generate(
        handle: Long,
        path: ByteArray,
        roles: Array<String>,
        contents: Array<ByteArray>,
        context: Int,
        input: Int,
        output: Int,
        threads: Int,
        callback: Callback,
    ): IntArray

    @Keep
    interface Callback {
        fun onToken(bytes: ByteArray): Boolean
    }

    @Keep
    interface ChatCallback {
        fun onEvent(bytes: ByteArray): Boolean
        fun onWarning(bytes: ByteArray): Boolean
    }
}
