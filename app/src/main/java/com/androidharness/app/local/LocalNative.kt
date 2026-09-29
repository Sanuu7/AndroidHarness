package com.androidharness.app.local

import androidx.annotation.Keep

@Keep
object LocalNative {
    init { System.loadLibrary("harness_local") }
    external fun create(): Long
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
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
}
