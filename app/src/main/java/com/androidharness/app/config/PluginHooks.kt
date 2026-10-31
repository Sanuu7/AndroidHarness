package com.androidharness.app.config

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Pure JS hooks run in AndroidX's isolated process, with no Android, filesystem or network bridge. */
class PluginHooks(private val context: Context) {
    private val lock = Mutex()
    private var sandbox: JavaScriptSandbox? = null
    private val direct = Executor { it.run() }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            try { val value = get(); if (continuation.isActive) continuation.resume(value) }
            catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e.cause ?: e) }
        }, direct)
        continuation.invokeOnCancellation { cancel(true) }
    }
    suspend fun invoke(plugins: List<ConfigPlugin>, hook: String, event: JsonObject): HookResult {
        val active = plugins.filter { hook in it.hooks }
        if (active.isEmpty()) return HookResult()
        return lock.withLock {
            check(JavaScriptSandbox.isSupported()) { "Plugin hooks need a supported Android System WebView. Update WebView or disable the plugin in harness.json." }
            val engine = sandbox ?: withTimeout(5_000) { JavaScriptSandbox.createConnectedInstanceAsync(context).await() }.also { sandbox = it }
            check(engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION) &&
                engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                "Plugin hooks need WebView with JavaScript memory limits and termination. Update Android System WebView."
            }
            val notes = StringBuilder()
            var denied: String? = null
            for (plugin in active) {
                val params = IsolateStartupParameters().apply { setMaxHeapSizeBytes(16 * 1024 * 1024) }
                val isolate = engine.createIsolate(params)
                try {
                    val script = """
                        (async function() {
                          "use strict";
                          const module = { exports: {} }; const exports = module.exports;
                          ${plugin.script}
                          const handler = module.exports[${JsonPrimitive(hook)}];
                          if (typeof handler !== "function") throw new Error("Declared hook has no exported function");
                          const result = await handler(${event});
                          const encoded = JSON.stringify(result == null ? {} : result);
                          if (encoded.length > 8192) throw new Error("Hook result exceeds 8192 characters");
                          return encoded;
                        })()
                    """.trimIndent()
                    val output = withTimeout(2_000) { isolate.evaluateJavaScriptAsync(script).await() }
                    require(output.length <= 8192) { "Hook output is too large." }
                    val result = Json.parseToJsonElement(output).jsonObject
                    result["instructions"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        require(notes.length + text.length <= 16_000) { "Combined plugin instructions exceed 16,000 characters." }
                        notes.append("\nPlugin ${plugin.name}:\n").append(text).append('\n')
                    }
                    if (hook == "tool.before") result["deny"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { denied = "${plugin.name}: ${it.take(500)}" }
                } catch (e: TimeoutCancellationException) {
                    runCatching { engine.close() }; sandbox = null
                    throw IllegalStateException("Plugin '${plugin.name}' timed out in $hook. Disable or fix the plugin.")
                } catch (e: CancellationException) {
                    runCatching { engine.close() }; sandbox = null
                    throw e
                } catch (e: Exception) {
                    runCatching { engine.close() }; sandbox = null
                    // JS error strings may contain arguments or secrets; expose only the plugin and hook.
                    throw IllegalStateException("Plugin '${plugin.name}' failed in $hook. Check its entry and exported hook functions.")
                } finally { runCatching { isolate.close() } }
            }
            HookResult(notes.toString(), denied)
        }
    }
}

data class HookResult(val instructions: String = "", val denied: String? = null)
