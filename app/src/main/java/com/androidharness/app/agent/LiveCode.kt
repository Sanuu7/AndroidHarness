package com.androidharness.app.agent

import com.androidharness.app.core.Diff
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.core.splitLines
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CodeDraftStage(val label: String) { GENERATING("Generating draft"), READY("Draft ready"), WRITING("Writing"), SAVED("Saved"), FAILED("Not saved") }
data class LiveCodeFile(val path: String, val added: Int, val removed: Int, val lines: List<LiveCodeLine>)
data class LiveCodeLine(val sign: Char, val text: String)
data class LiveCodeDraft(
    val id: String,
    val tool: String,
    val files: List<LiveCodeFile>,
    val estimatedTokens: Int,
    val stage: CodeDraftStage = CodeDraftStage.GENERATING,
    val limited: Boolean = false,
    /** Exact provider total for the response, including reasoning and other output; never per-file. */
    val responseOutputTokens: Int? = null,
    val comparisonAvailable: Boolean = true,
    val workspaceName: String? = null,
)

/** Reads string fields from incomplete JSON without executing or repairing the tool arguments. */
internal object LiveCodePreview {
    val tools = setOf("write_file", "edit_file", "multi_edit", "apply_patch")
    private const val MAX_ARGUMENTS = 1_000_000
    private const val MAX_DIFF_CHARS = 80_000
    private const val MAX_PREVIEW_LINES = 80

    /** Rebuild a bounded preview from saved arguments, using measured counts when available. */
    suspend fun fromCall(call: com.androidharness.app.core.ToolCallData,
        result: com.androidharness.app.core.ChatMessage?, running: Boolean): LiveCodeDraft? {
        val raw = build(call.id, call.name, call.argumentsJson, complete = true) ?: return null
        val draft = raw.copy(files = raw.files.map { file -> call.workspaceId?.let {
            file.copy(path = "${com.androidharness.app.workspace.ChatWorkspaceFs.PREFIX}/$it/${file.path.removePrefix("./")}")
        } ?: file })
        val measured = call.codeChanges.associateBy { com.androidharness.app.workspace.normalizeRelPath(it.path) }
        return draft.copy(
            workspaceName = call.workspaceName,
            stage = when {
                result?.isError == true -> CodeDraftStage.FAILED
                result != null -> CodeDraftStage.SAVED
                running -> CodeDraftStage.WRITING
                else -> CodeDraftStage.READY
            },
            comparisonAvailable = call.name != "write_file" || measured.isNotEmpty() || result?.text?.startsWith("Created ") == true,
            files = draft.files.map { file ->
                measured[com.androidharness.app.workspace.normalizeRelPath(file.path)]?.let {
                    file.copy(added = it.added, removed = it.removed)
                } ?: file
            },
        )
    }
    internal data class Field(val key: String, val text: String, val complete: Boolean)

    internal fun fields(json: CharSequence): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        var depth = 0
        fun string(): Pair<String, Boolean> {
            i++
            val text = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                if (c == '"') return text.toString() to true
                if (c != '\\') { text.append(c); continue }
                if (i >= json.length) break
                when (val escaped = json[i++]) {
                    'n' -> text.append('\n')
                    'r' -> text.append('\r')
                    't' -> text.append('\t')
                    'b' -> text.append('\b')
                    'f' -> text.append('\u000C')
                    '"', '\\', '/' -> text.append(escaped)
                    'u' -> {
                        if (i + 4 > json.length) break
                        val hex = json.subSequence(i, i + 4).toString().toIntOrNull(16) ?: break
                        text.append(hex.toChar()); i += 4
                    }
                    else -> break
                }
            }
            return text.toString() to false
        }
        while (i < json.length) {
            if (json[i] != '"') {
                if (json[i] == '{') depth++ else if (json[i] == '}') depth--
                i++; continue
            }
            val (key, closed) = string()
            if (!closed) break
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') continue
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == '"') {
                val (text, complete) = string()
                if ((depth == 1 && key in setOf("path", "content", "patch")) ||
                    (depth in 1..2 && key in setOf("old_string", "new_string"))) out += Field(key, text, complete)
            }
        }
        return out
    }

    suspend fun build(id: String, tool: String, arguments: CharSequence, complete: Boolean,
        baseline: String? = null, argumentChars: Int = arguments.length): LiveCodeDraft? {
        if (tool !in tools) return null
        val limited = arguments.length > MAX_ARGUMENTS || argumentChars > MAX_ARGUMENTS
        val fields = fields(arguments.take(MAX_ARGUMENTS))
        val path = fields.firstOrNull { it.key == "path" && it.complete }?.text ?: "File name arriving…"
        val files = if (tool == "apply_patch") {
            patchFiles(fields.firstOrNull { it.key == "patch" }?.text.orEmpty(), complete)
        } else {
            val old = fields.filter { it.key == "old_string" }.joinToString("\n") { it.text }
            val newFields = fields.filter { it.key == if (tool == "write_file") "content" else "new_string" }
            val new = newFields.joinToString("\n") { it.text }
            val finished = (complete || (newFields.isNotEmpty() && newFields.all { it.complete })) && !limited
            val before = if (tool == "write_file") baseline.orEmpty() else old
            // A partial replacement is a growing prefix, not a deletion of the ungenerated tail.
            val countedNew = if (finished) new else completedLines(new).joinToString("\n")
            val countedOld = if (finished || tool != "write_file") before else
                splitLines(before).take(completedLines(new).size).joinToString("\n")
            val (added, removed) = if (before.length + new.length <= MAX_DIFF_CHARS) {
                Diff.lineCounts(countedOld, countedNew)
            } else splitLines(countedNew).size to splitLines(countedOld).size
            val preview = if (finished && before.length + new.length <= MAX_DIFF_CHARS) {
                Diff.unified(before, new, path).lineSequence().filter {
                    it.isNotEmpty() && it[0] in "+- " && !it.startsWith("+++") && !it.startsWith("---")
                }.map { LiveCodeLine(it[0], it.drop(1).take(500)) }.toList().ifEmpty {
                    splitLines(new).map { LiveCodeLine(' ', it.take(500)) }
                }
            } else {
                (if (tool == "write_file") emptyList() else splitLines(old).map { LiveCodeLine('-', it.take(500)) }) +
                    splitLines(new).map { LiveCodeLine('+', it.take(500)) }
            }
            listOf(LiveCodeFile(path, added, removed, preview.takeLast(MAX_PREVIEW_LINES)))
        }
        return LiveCodeDraft(id, tool, files, ((argumentChars.toLong() + 3) / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            if (complete) CodeDraftStage.READY else CodeDraftStage.GENERATING,
            limited || fields.any { it.text.length > MAX_DIFF_CHARS })
    }

    private fun completedLines(text: String): List<String> = splitLines(text).let {
        if (text.endsWith('\n') || text.endsWith('\r')) it else it.dropLast(1)
    }

    private fun patchFiles(patch: String, complete: Boolean): List<LiveCodeFile> {
        val lines = if (complete) splitLines(patch) else completedLines(patch)
        val files = mutableListOf<LiveCodeFile>()
        var path = "File name arriving…"
        var oldPath: String? = null
        var added = 0
        var removed = 0
        var hunk = false
        var oldRemaining = 0
        var newRemaining = 0
        val preview = mutableListOf<LiveCodeLine>()
        fun flush() {
            if (preview.isNotEmpty() || path != "File name arriving…") files += LiveCodeFile(path, added, removed, preview.takeLast(MAX_PREVIEW_LINES))
            added = 0; removed = 0; preview.clear(); hunk = false
        }
        for (line in lines) {
          when {
            line.startsWith("--- ") && !hunk -> { flush(); oldPath = line.drop(4).removePrefix("a/").substringBefore('\t'); path = "File name arriving…" }
            line.startsWith("+++ ") && !hunk -> { path = line.drop(4).removePrefix("b/").substringBefore('\t'); if (path == "/dev/null") path = oldPath ?: path }
            line.startsWith("@@") -> {
                val header = Regex("@@ -[0-9]+(?:,([0-9]+))? \\+[0-9]+(?:,([0-9]+))? @@").find(line)
                oldRemaining = header?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                newRemaining = header?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                hunk = true
            }
            hunk && line.startsWith('+') -> { added++; newRemaining--; preview += LiveCodeLine('+', line.drop(1).take(500)) }
            hunk && line.startsWith('-') -> { removed++; oldRemaining--; preview += LiveCodeLine('-', line.drop(1).take(500)) }
            hunk && line.startsWith(' ') -> { oldRemaining--; newRemaining--; preview += LiveCodeLine(' ', line.drop(1).take(500)) }
          }
          if (hunk && oldRemaining <= 0 && newRemaining <= 0) hunk = false
        }
        flush()
        return files.ifEmpty { listOf(LiveCodeFile(path, 0, 0, emptyList())) }
    }
}

/** Request-local buffers; UI updates are throttled, while every fragment is retained for the next preview. */
internal class LiveCodeTracker(private val now: () -> Long = System::nanoTime) {
    private data class Pending(val text: StringBuilder = StringBuilder(), var chars: Int = 0, var last: Long = 0)
    private val pending = mutableMapOf<String, Pending>()
    private val baselines = mutableMapOf<String, String?>()
    fun clear() { pending.clear(); baselines.clear() }

    suspend fun delta(event: StreamEvent.ToolCallDelta, workspace: WorkspaceFs, force: Boolean = false): LiveCodeDraft? {
        if (LiveCodePreview.tools.none { it.startsWith(event.name) }) return null
        val p = pending.getOrPut(event.id) { Pending() }
        if (event.replace) { p.text.setLength(0); p.chars = 0 }
        p.chars = (p.chars.toLong() + event.arguments.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        p.text.append(event.arguments.take((1_000_000 - p.text.length).coerceAtLeast(0)))
        if (event.name !in LiveCodePreview.tools) return null
        val time = now()
        if (!force && p.last != 0L && time - p.last < 80_000_000L) return null
        p.last = time
        return preview(event.id, event.name, p.text, false, p.chars, workspace)
    }

    suspend fun ready(call: ToolCallData, workspace: WorkspaceFs): LiveCodeDraft? {
        pending.remove(call.id)
        return preview(call.id, call.name, call.argumentsJson, true, call.argumentsJson.length, workspace)
    }

    private suspend fun preview(id: String, tool: String, arguments: CharSequence, complete: Boolean,
        chars: Int, workspace: WorkspaceFs): LiveCodeDraft? {
        if (tool !in LiveCodePreview.tools) return null
        val fields = LiveCodePreview.fields(arguments.take(1_000_000))
        val selected = workspace as? com.androidharness.app.workspace.ChatWorkspaceFs
        val targetId = fields.firstOrNull { it.key == "workspace_id" && it.complete }?.text
        val path = fields.firstOrNull { it.key == "path" && it.complete }?.text?.let { path ->
            if (selected == null) path else if (!complete && targetId == null) null
            else runCatching { selected.key(targetId ?: selected.primaryId, path) }.getOrNull()
        }
        if (tool == "write_file" && path != null && path !in baselines) {
            baselines[path] = withContext(Dispatchers.IO) {
                try {
                    val node = workspace.resolve(path)
                    if (!node.exists) "" else if (node.isFile && node.length <= 80_000) node.readText() else null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        }
        val draft = LiveCodePreview.build(id, tool, arguments, complete, baselines[path], chars) ?: return null
        return draft.copy(
            workspaceName = selected?.let { fs -> if (complete || targetId != null) runCatching { fs.binding(targetId).name }.getOrNull() else null },
            files = if (selected == null || (!complete && targetId == null)) draft.files else draft.files.map { file ->
                runCatching { file.copy(path = selected.key(targetId ?: selected.primaryId, file.path)) }.getOrDefault(file)
            },
            comparisonAvailable = tool != "write_file" || (path != null && baselines[path] != null),
        )
    }
}
