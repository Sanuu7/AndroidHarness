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

/**
 * Pure JS hooks run in AndroidX's isolated process, with no Android, filesystem or network bridge.
 * Command hooks (Claude Code hooks.json) run in the Linux shell from the plugin's folder, with the event as JSON on stdin.
 */
class PluginHooks(private val context: Context, private val shell: ((String) -> ProcessBuilder)? = null) {
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
        val scripted = runScripts(plugins.filter { hook in it.hooks && it.script.isNotBlank() }, hook, event)
        val commands = plugins.flatMap { plugin -> plugin.commands.filter { it.event == hook && matches(it.matcher, event) }.map { plugin to it } }
        if (commands.isEmpty()) return scripted
        val ran = runCommands(commands, hook, event)
        return HookResult((scripted.instructions + ran.instructions).take(32_000), scripted.denied ?: ran.denied)
    }

    private fun toolNames(event: JsonObject): Set<String> {
        val tool = event["tool"]?.jsonPrimitive?.contentOrNull ?: return emptySet()
        return setOfNotNull(tool, claudeTools[tool])
    }
    private fun matches(matcher: String, event: JsonObject): Boolean {
        if (matcher.isBlank() || matcher == "*") return true
        val names = toolNames(event).ifEmpty { return true }
        val pattern = runCatching { Regex("^(?:$matcher)$") }.getOrNull() ?: return names.any { it == matcher }
        return names.any { pattern.matches(it) }
    }
    /** The stdin payload Claude Code hooks expect, filled from this app's event. */
    private fun payload(command: ConfigCommandHook, event: JsonObject) = buildJsonObject {
        put("hook_event_name", command.source.ifBlank { command.event })
        put("session_id", "androidharness")
        event["tool"]?.jsonPrimitive?.contentOrNull?.let { tool ->
            put("tool_name", claudeTools[tool] ?: tool)
            val arguments = event["arguments"]?.jsonPrimitive?.contentOrNull
            put("tool_input", arguments?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonObject(emptyMap()))
        }
        event["ok"]?.let { put("tool_response", buildJsonObject { put("success", it) }) }
        event["message"]?.let { put("prompt", it) }
    }
    private suspend fun runCommands(commands: List<Pair<ConfigPlugin, ConfigCommandHook>>, hook: String, event: JsonObject): HookResult = withContext(Dispatchers.IO) {
        val start = shell ?: return@withContext HookResult()
        val notes = StringBuilder()
        var denied: String? = null
        for ((plugin, command) in commands) {
            val builder = start(command.command).directory(java.io.File(plugin.root))
            builder.environment()["CLAUDE_PLUGIN_ROOT"] = plugin.root
            builder.environment()["PLUGIN_ROOT"] = plugin.root
            val process = runCatching { builder.start() }.getOrNull() ?: continue
            try {
                val stdout = async { process.inputStream.use { String(com.androidharness.app.plugins.PluginArchives.readLimited(it, 64 * 1024)) } }
                val stderr = async { process.errorStream.use { String(com.androidharness.app.plugins.PluginArchives.readLimited(it, 16 * 1024)) } }
                process.outputStream.use { it.write(payload(command, event).toString().toByteArray()) }
                val finished = runInterruptible { process.waitFor(command.timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS) }
                if (!finished) { process.destroyForcibly(); continue }
                val out = runCatching { stdout.await() }.getOrDefault("").trim()
                val err = runCatching { stderr.await() }.getOrDefault("").trim()
                val parsed = out.takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val specific = parsed?.get("hookSpecificOutput") as? JsonObject
                val blockReason = when {
                    process.exitValue() == 2 -> err.ifBlank { "blocked by a plugin hook" }
                    parsed?.get("decision")?.jsonPrimitive?.contentOrNull == "block" -> parsed["reason"]?.jsonPrimitive?.contentOrNull ?: "blocked by a plugin hook"
                    specific?.get("permissionDecision")?.jsonPrimitive?.contentOrNull == "deny" ->
                        specific["permissionDecisionReason"]?.jsonPrimitive?.contentOrNull ?: "blocked by a plugin hook"
                    else -> null
                }
                if (blockReason != null && hook == "tool.before" && denied == null) denied = "${plugin.name}: ${blockReason.take(500)}"
                val context = specific?.get("additionalContext")?.jsonPrimitive?.contentOrNull
                    ?: parsed?.get("systemMessage")?.jsonPrimitive?.contentOrNull
                    ?: out.takeIf { parsed == null && process.exitValue() == 0 && hook in setOf("session.start", "message.received") }
                    ?: blockReason?.takeIf { hook != "tool.before" }
                context?.takeIf(String::isNotBlank)?.let { if (notes.length < 16_000) notes.append("\nPlugin ${plugin.name}:\n").append(it.take(8_000)).append('\n') }
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        HookResult(notes.toString(), denied)
    }

    private suspend fun runScripts(active: List<ConfigPlugin>, hook: String, event: JsonObject): HookResult {
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

/** Claude Code tool names for this app's tools, so hook matchers such as "Write|Edit" still apply. */
private val claudeTools = mapOf("shell" to "Bash", "shell_background" to "Bash", "read_file" to "Read", "write_file" to "Write",
    "edit_file" to "Edit", "multi_edit" to "MultiEdit", "apply_patch" to "Edit", "list_dir" to "LS", "search_files" to "Glob", "grep" to "Grep",
    "web_fetch" to "WebFetch", "web_search" to "WebSearch", "todo_write" to "TodoWrite", "task" to "Task")
