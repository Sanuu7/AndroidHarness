package com.androidharness.app.config

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Hook events by this app's name, and the Claude Code and Gemini CLI names that map to them. */
object HookEvents {
    const val SESSION_START = "session.start"
    const val MESSAGE = "message.received"
    const val PROMPT = "prompt.before"
    const val TOOL_BEFORE = "tool.before"
    const val TOOL_AFTER = "tool.after"
    const val TOOL_FAILED = "tool.failed"
    const val PERMISSION = "permission.request"
    const val NOTIFICATION = "notification"
    const val STOP = "stop"
    const val SUBAGENT_START = "subagent.start"
    const val SUBAGENT_STOP = "subagent.stop"
    const val COMPACT = "compact.before"
    const val SESSION_END = "session.end"
    val all = setOf(SESSION_START, MESSAGE, PROMPT, TOOL_BEFORE, TOOL_AFTER, TOOL_FAILED, PERMISSION, NOTIFICATION, STOP,
        SUBAGENT_START, SUBAGENT_STOP, COMPACT, SESSION_END)
    /** Claude Code names first, then Gemini CLI names. */
    val external = mapOf("SessionStart" to SESSION_START, "UserPromptSubmit" to MESSAGE, "PreToolUse" to TOOL_BEFORE,
        "PostToolUse" to TOOL_AFTER, "PostToolUseFailure" to TOOL_FAILED, "PermissionRequest" to PERMISSION, "Notification" to NOTIFICATION,
        "Stop" to STOP, "SubagentStart" to SUBAGENT_START, "SubagentStop" to SUBAGENT_STOP, "PreCompact" to COMPACT, "SessionEnd" to SESSION_END,
        "BeforeTool" to TOOL_BEFORE, "AfterTool" to TOOL_AFTER, "BeforeAgent" to MESSAGE, "AfterAgent" to STOP, "PreCompress" to COMPACT)
    /** The Claude Code name a command hook sees in hook_event_name. */
    val claudeName = mapOf(SESSION_START to "SessionStart", MESSAGE to "UserPromptSubmit", PROMPT to "UserPromptSubmit", TOOL_BEFORE to "PreToolUse",
        TOOL_AFTER to "PostToolUse", TOOL_FAILED to "PostToolUseFailure", PERMISSION to "PermissionRequest", NOTIFICATION to "Notification",
        STOP to "Stop", SUBAGENT_START to "SubagentStart", SUBAGENT_STOP to "SubagentStop", COMPACT to "PreCompact", SESSION_END to "SessionEnd")
    /** Hooks installed before these events existed recorded Stop and SubagentStop as session.end; their source name still tells them apart. */
    fun of(event: String, source: String): String? = external[source] ?: event.takeIf { it in all }
}

/** What a hook run knows about the run that fired it. */
data class HookContext(
    val sessionId: String = "androidharness",
    /** The open project folder, which is the working folder of command hooks; null when the workspace has no real folder. */
    val projectDir: String? = null,
    val transcriptPath: String? = null,
    val permissionMode: String = "default",
    /** Original names of plugin MCP servers by their namespaced name, so matchers such as mcp__github__.* still apply. */
    val serverNames: Map<String, String> = emptyMap(),
    /** Answers a prompt or agent hook with one model request; null when no model is available. */
    val evaluate: (suspend (system: String, prompt: String) -> String)? = null,
)

/**
 * The combined answer of every hook that ran. [denied] blocks the action (a tool call, a prompt or a permission),
 * [ask] forces an approval prompt, [allow] skips one, [updatedInput] replaces the tool arguments, [stop] ends the run
 * with its reason, [keepGoing] keeps a stopping agent working with its reason, and [messages] are shown to the user.
 */
data class HookResult(
    val instructions: String = "",
    val denied: String? = null,
    val ask: String? = null,
    val allow: Boolean = false,
    val updatedInput: JsonObject? = null,
    val stop: String? = null,
    val keepGoing: String? = null,
    val messages: List<String> = emptyList(),
) {
    operator fun plus(other: HookResult) = HookResult(
        listOf(instructions, other.instructions).filter(String::isNotBlank).joinToString("\n"),
        denied ?: other.denied, ask ?: other.ask, allow || other.allow, updatedInput ?: other.updatedInput, stop ?: other.stop,
        listOfNotNull(keepGoing, other.keepGoing).joinToString("\n").ifBlank { null }, messages + other.messages)
}

/**
 * Pure JS hooks run in AndroidX's isolated process, with no Android, filesystem or network bridge.
 * Command hooks (Claude Code hooks.json) run in the Linux shell from the open project, with the event as JSON on stdin,
 * exactly as Claude Code runs them. Prompt and agent hooks ask the run's model. A hook that fails is reported and
 * skipped; it never stops the run or the other plugins.
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

    /** The folder a plugin may keep files in across updates, as ${CLAUDE_PLUGIN_DATA}. */
    fun dataDir(plugin: String): File = File(context.filesDir, "plugins/data/${plugin.replace(Regex("[^a-z0-9-]"), "-")}").apply { mkdirs() }

    suspend fun invoke(plugins: List<ConfigPlugin>, hook: String, event: JsonObject, run: HookContext = HookContext()): HookResult {
        val scripted = runScripts(plugins.filter { hook in it.hooks && it.script.isNotBlank() }, hook, event)
        val handlers = plugins.flatMap { plugin -> plugin.commands.filter { it.event == hook && matches(it.matcher, event, run) }.map { plugin to it } }
        if (handlers.isEmpty()) return scripted
        val results = coroutineScope { handlers.map { (plugin, handler) -> async { runOne(plugin, handler, hook, event, run) } }.awaitAll() }
        return results.fold(scripted) { acc, next -> acc + next }
    }

    private fun toolNames(event: JsonObject, run: HookContext): Set<String> {
        val tool = event["tool"]?.jsonPrimitive?.contentOrNull ?: return emptySet()
        val names = mutableSetOf(tool)
        claudeTools[tool]?.let(names::add)
        if (tool.startsWith("mcp__")) {
            val server = tool.removePrefix("mcp__").substringBefore("__")
            val name = tool.substringAfter("mcp__$server", "")
            run.serverNames[server]?.let { original -> names += "mcp__$original$name"; names += "mcp__plugin_${original}$name" }
        }
        return names
    }
    private fun matches(matcher: String, event: JsonObject, run: HookContext): Boolean {
        if (matcher.isBlank() || matcher == "*") return true
        val names = toolNames(event, run).ifEmpty {
            listOf("source", "trigger", "reason", "notification_type", "agent_type").mapNotNull { event[it]?.jsonPrimitive?.contentOrNull }.toSet()
        }.ifEmpty { return true }
        val pattern = runCatching { Regex("^(?:$matcher)$") }.getOrNull() ?: return names.any { it == matcher }
        return names.any { pattern.matches(it) }
    }

    /** The stdin payload Claude Code hooks expect, filled from this app's event. */
    internal fun payload(handler: ConfigCommandHook, hook: String, event: JsonObject, run: HookContext, root: String) = buildJsonObject {
        put("session_id", run.sessionId)
        run.transcriptPath?.let { put("transcript_path", it) }
        put("cwd", run.projectDir ?: root)
        put("permission_mode", run.permissionMode)
        put("hook_event_name", handler.source.takeIf { it in HookEvents.external } ?: HookEvents.claudeName[hook] ?: hook)
        fun copy(from: String, to: String = from) { event[from]?.let { put(to, it) } }
        event["tool"]?.jsonPrimitive?.contentOrNull?.let { tool ->
            put("tool_name", claudeTools[tool] ?: tool)
            val arguments = event["arguments"]?.jsonPrimitive?.contentOrNull
            put("tool_input", arguments?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonObject(emptyMap()))
            copy("call_id", "tool_use_id")
        }
        when (hook) {
            HookEvents.TOOL_AFTER -> put("tool_response", buildJsonObject {
                event["ok"]?.let { put("success", it) }
                event["output"]?.let { put("output", it); put("stdout", it) }
            })
            HookEvents.TOOL_FAILED -> copy("output", "error")
            HookEvents.MESSAGE, HookEvents.PROMPT -> copy("message", "prompt")
            HookEvents.STOP, HookEvents.SUBAGENT_STOP -> put("stop_hook_active", event["stop_hook_active"] ?: JsonPrimitive(false))
            HookEvents.SESSION_START -> put("source", event["source"] ?: JsonPrimitive("startup"))
            HookEvents.SESSION_END -> put("reason", event["reason"] ?: JsonPrimitive("other"))
            HookEvents.COMPACT -> { put("trigger", event["trigger"] ?: JsonPrimitive("auto")); put("custom_instructions", event["custom_instructions"] ?: JsonPrimitive("")) }
            HookEvents.NOTIFICATION -> { copy("message"); copy("notification_type") }
        }
        if (hook == HookEvents.SUBAGENT_START || hook == HookEvents.SUBAGENT_STOP) { copy("agent_id"); copy("agent_type") }
    }

    private suspend fun runOne(plugin: ConfigPlugin, handler: ConfigCommandHook, hook: String, event: JsonObject, run: HookContext): HookResult =
        try {
            if (handler.type == "command") runCommand(plugin, handler, hook, event, run) else runPrompt(plugin, handler, hook, event, run)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { HookResult(messages = listOf("${label(plugin, handler, hook)} failed: ${e.message ?: e.javaClass.simpleName}")) }

    private suspend fun runCommand(plugin: ConfigPlugin, handler: ConfigCommandHook, hook: String, event: JsonObject, run: HookContext): HookResult = withContext(Dispatchers.IO) {
        val start = shell ?: return@withContext HookResult(messages = listOf("${label(plugin, handler, hook)} needs the Linux environment, which is not set up."))
        val builder = start(handler.command).directory(File(run.projectDir?.takeIf { File(it).isDirectory } ?: plugin.root))
        builder.environment().apply {
            put("CLAUDE_PLUGIN_ROOT", plugin.root); put("PLUGIN_ROOT", plugin.root); put("extensionPath", plugin.root)
            put("CLAUDE_PLUGIN_DATA", dataDir(plugin.name).absolutePath)
            put("CLAUDE_PROJECT_DIR", run.projectDir ?: plugin.root); put("GEMINI_PROJECT_DIR", run.projectDir ?: plugin.root)
            plugin.options.forEach { (key, value) ->
                put("CLAUDE_PLUGIN_OPTION_" + key.uppercase().replace(Regex("[^A-Z0-9_]"), "_"), value)
                if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) putIfAbsent(key, value)
            }
        }
        val process = runCatching { builder.start() }.getOrElse { return@withContext HookResult(messages = listOf("${label(plugin, handler, hook)} could not start: ${it.message}")) }
        try {
            val stdout = async { process.inputStream.use { String(com.androidharness.app.plugins.PluginArchives.readLimited(it, MAX_OUTPUT)) } }
            val stderr = async { process.errorStream.use { String(com.androidharness.app.plugins.PluginArchives.readLimited(it, MAX_OUTPUT)) } }
            runCatching { process.outputStream.use { it.write(payload(handler, hook, event, run, plugin.root).toString().toByteArray()) } }
            val finished = runInterruptible { process.waitFor(handler.timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS) }
            if (!finished) {
                process.destroyForcibly()
                return@withContext HookResult(messages = listOf("${label(plugin, handler, hook)} did not finish within ${handler.timeout} seconds and was stopped."))
            }
            val out = runCatching { stdout.await() }.getOrDefault("")
            val err = runCatching { stderr.await() }.getOrDefault("")
            interpret(plugin, handler, hook, process.exitValue(), out, err)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    /** Prompt and agent hooks: the model reads the event and answers {"ok": true} or {"ok": false, "reason": "..."}. */
    private suspend fun runPrompt(plugin: ConfigPlugin, handler: ConfigCommandHook, hook: String, event: JsonObject, run: HookContext): HookResult {
        val evaluate = run.evaluate ?: return HookResult(messages = listOf("${label(plugin, handler, hook)} needs a model and was skipped."))
        val input = payload(handler, hook, event, run, plugin.root).toString()
        val prompt = if ("\$ARGUMENTS" in handler.command) handler.command.replace("\$ARGUMENTS", input) else handler.command + "\n\nHook input:\n" + input
        val answer = withTimeout(handler.timeout * 1000L) {
            evaluate("You evaluate a hook for a coding agent. Reply with only a JSON object: {\"ok\": true} to let it continue, " +
                "or {\"ok\": false, \"reason\": \"why\"} to block it.", prompt)
        }
        val json = Regex("\\{[\\s\\S]*\\}").find(answer)?.value?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return HookResult(messages = listOf("${label(plugin, handler, hook)} got an answer that was not JSON and was ignored."))
        val ok = (json["ok"] as? JsonPrimitive)?.booleanOrNull ?: ((json["decision"] as? JsonPrimitive)?.contentOrNull !in setOf("block", "deny"))
        val reason = (json["reason"] as? JsonPrimitive)?.contentOrNull ?: "blocked by a plugin hook"
        return if (ok) HookResult() else blocking(hook, plugin, reason)
    }

    private suspend fun runScripts(active: List<ConfigPlugin>, hook: String, event: JsonObject): HookResult {
        if (active.isEmpty()) return HookResult()
        return lock.withLock {
            if (!JavaScriptSandbox.isSupported()) return@withLock HookResult(messages = listOf("Plugin JavaScript hooks need a supported Android System WebView. Update WebView."))
            var problem: String? = null
            suspend fun engine(): JavaScriptSandbox? {
                sandbox?.let { return it }
                val created = try { withTimeout(5_000) { JavaScriptSandbox.createConnectedInstanceAsync(context).await() } }
                    catch (e: TimeoutCancellationException) { problem = "Plugin JavaScript hooks could not start in time."; return null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { problem = "Plugin JavaScript hooks could not start: ${e.message}"; return null }
                if (!created.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION) ||
                    !created.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    runCatching { created.close() }
                    problem = "Plugin JavaScript hooks need WebView with JavaScript memory limits and termination. Update Android System WebView."
                    return null
                }
                return created.also { sandbox = it }
            }
            var result = HookResult()
            for (plugin in active) {
                val engine = engine() ?: break
                val params = IsolateStartupParameters().apply { setMaxHeapSizeBytes(64 * 1024 * 1024) }
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
                          return JSON.stringify(result == null ? {} : result);
                        })()
                    """.trimIndent()
                    val output = withTimeout(10_000) { isolate.evaluateJavaScriptAsync(script).await() }
                    val parsed = Json.parseToJsonElement(output).jsonObject
                    parsed["instructions"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        result += HookResult(instructions = "Plugin ${plugin.name}:\n$it")
                    }
                    parsed["deny"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { result += blocking(hook, plugin, it) }
                    parsed["message"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { result += HookResult(messages = listOf("Plugin ${plugin.name}: $it")) }
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) { runCatching { engine.close() }; sandbox = null; throw e }
                    runCatching { engine.close() }; sandbox = null
                    result += HookResult(messages = listOf("Plugin ${plugin.name} timed out in $hook and was skipped."))
                } catch (e: Exception) {
                    runCatching { engine.close() }; sandbox = null
                    // JS error strings may contain arguments or secrets; expose only the plugin and hook.
                    result += HookResult(messages = listOf("Plugin ${plugin.name} failed in $hook. Check its entry and exported hook functions."))
                } finally { runCatching { isolate.close() } }
            }
            problem?.let { result += HookResult(messages = listOf(it)) }
            result
        }
    }

    companion object {
        private const val MAX_OUTPUT = 16L * 1024 * 1024
        private fun label(plugin: ConfigPlugin, handler: ConfigCommandHook, hook: String) =
            "Plugin ${plugin.name}: ${handler.source.ifBlank { HookEvents.claudeName[hook] ?: hook }} hook"

        /** Turns one hook's exit code and output into a [HookResult], following Claude Code's rules. */
        internal fun interpret(plugin: ConfigPlugin, handler: ConfigCommandHook, hook: String, exit: Int, out: String, err: String): HookResult {
            val name = label(plugin, handler, hook)
            val parsed = out.trim().takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            if (exit == 2) return blocking(hook, plugin, err.trim().ifBlank { out.trim() }.ifBlank { "blocked by a plugin hook" })
            if (exit != 0) {
                val detail = err.trim().ifBlank { out.trim() }.lines().take(6).joinToString("\n").take(1000)
                return HookResult(messages = listOf("$name exited with code $exit." + if (detail.isBlank()) "" else "\n$detail"))
            }
            if (parsed == null) {
                val text = out.trim()
                return if (text.isNotBlank() && hook in setOf(HookEvents.SESSION_START, HookEvents.MESSAGE, HookEvents.PROMPT))
                    HookResult(instructions = "Plugin ${plugin.name}:\n$text") else HookResult()
            }
            fun str(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
            val specific = parsed["hookSpecificOutput"] as? JsonObject
            var result = HookResult()
            if ((parsed["continue"] as? JsonPrimitive)?.booleanOrNull == false)
                result = result.copy(stop = str(parsed, "stopReason")?.takeIf(String::isNotBlank) ?: "$name stopped the run.")
            str(parsed, "systemMessage")?.takeIf(String::isNotBlank)?.let { result = result.copy(messages = listOf("Plugin ${plugin.name}: $it")) }
            str(specific, "additionalContext")?.takeIf(String::isNotBlank)?.let { result = result.copy(instructions = "Plugin ${plugin.name}:\n$it") }
            when (str(parsed, "decision")) {
                "block" -> result = result + blocking(hook, plugin, str(parsed, "reason") ?: "blocked by a plugin hook")
                "approve" -> if (hook == HookEvents.TOOL_BEFORE) result = result.copy(allow = true)
            }
            when (str(specific, "permissionDecision")) {
                "deny" -> result = result.copy(denied = "${plugin.name}: ${str(specific, "permissionDecisionReason") ?: "blocked by a plugin hook"}")
                "ask" -> result = result.copy(ask = "${plugin.name}: ${str(specific, "permissionDecisionReason") ?: "asks for your approval"}")
                "allow" -> result = result.copy(allow = true)
            }
            (specific?.get("updatedInput") as? JsonObject)?.let { result = result.copy(updatedInput = it) }
            (specific?.get("decision") as? JsonObject)?.let { decision ->
                when (str(decision, "behavior")) {
                    "allow" -> result = result.copy(allow = true, updatedInput = decision["updatedInput"] as? JsonObject ?: result.updatedInput)
                    "deny" -> result = result.copy(denied = "${plugin.name}: ${str(decision, "message") ?: "denied by a plugin hook"}")
                }
            }
            if (str(parsed, "decision") == null && specific == null && parsed["continue"] == null && parsed["systemMessage"] == null &&
                hook in setOf(HookEvents.SESSION_START, HookEvents.MESSAGE)) result = result.copy(instructions = "Plugin ${plugin.name}:\n${out.trim()}")
            return result
        }

        /** What a blocking answer (exit code 2 or decision "block") means for each event. */
        private fun blocking(hook: String, plugin: ConfigPlugin, reason: String): HookResult = when (hook) {
            HookEvents.TOOL_BEFORE, HookEvents.PERMISSION, HookEvents.MESSAGE -> HookResult(denied = "${plugin.name}: $reason")
            HookEvents.TOOL_AFTER, HookEvents.TOOL_FAILED -> HookResult(instructions = "Plugin ${plugin.name}:\n$reason")
            HookEvents.STOP, HookEvents.SUBAGENT_STOP -> HookResult(keepGoing = "Plugin ${plugin.name}: $reason")
            else -> HookResult(messages = listOf("Plugin ${plugin.name}: $reason"))
        }
    }
}

/** Claude Code tool names for this app's tools, so hook matchers such as "Write|Edit" still apply. */
val claudeTools = mapOf("shell" to "Bash", "shell_background" to "Bash", "read_file" to "Read", "write_file" to "Write",
    "edit_file" to "Edit", "multi_edit" to "MultiEdit", "apply_patch" to "Edit", "list_dir" to "LS", "search_files" to "Glob", "grep" to "Grep",
    "web_fetch" to "WebFetch", "web_search" to "WebSearch", "todo_write" to "TodoWrite", "task" to "Task", "notebook_edit" to "NotebookEdit",
    "skill_view" to "Skill", "ask_user" to "AskUserQuestion")
