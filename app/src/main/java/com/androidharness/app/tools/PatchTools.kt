package com.androidharness.app.tools

import com.androidharness.app.core.splitLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Apply several string replacements to one file in a single write. */
class MultiEditTool : Tool {
    override val name = "multi_edit"
    override val description =
        "Apply multiple edits to one file atomically. Each edit is a string replacement " +
            "(tolerant of whitespace drift); the whole call fails if any edit's old_string is missing or ambiguous. " +
            "Applies edits in order, so make each old_string unique against the result of the previous edits."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("File path relative to the workspace root."),
            "edits" to Schema.array(
                Schema.obj(
                    mapOf(
                        "old_string" to Schema.string("The text to replace."),
                        "new_string" to Schema.string("The replacement text."),
                        "replace_all" to Schema.boolean("Replace all occurrences. Defaults to false."),
                    ),
                    required = listOf("old_string", "new_string"),
                ),
                "Ordered list of edits to apply.",
            ),
        ),
        required = listOf("path", "edits"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val edits = args["edits"]?.jsonArray
                ?: throw ToolFailure("Missing required argument: edits")

            val file = ctx.workspace.resolve(path)
            if (!file.exists || !file.isFile) throw ToolFailure("File does not exist: $path")

            var text = file.readText()
            var applied = 0
            var fuzzy = false
            edits.forEachIndexed { idx, el ->
                val edit = el.jsonObject
                val old = edit["old_string"]?.jsonPrimitive?.content
                    ?: throw ToolFailure("edits[$idx].old_string missing")
                val new = edit["new_string"]?.jsonPrimitive?.content
                    ?: throw ToolFailure("edits[$idx].new_string missing")
                val replaceAll = edit["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false

                when (val r = FuzzyEdit.replace(text, old, new, replaceAll)) {
                    is FuzzyEdit.Result.Ok -> {
                        text = r.newText
                        applied++
                        fuzzy = fuzzy || r.level != FuzzyEdit.Level.EXACT
                    }
                    is FuzzyEdit.Result.Ambiguous -> throw ToolFailure(
                        "edits[$idx]: old_string appears ${r.count} times in $path; make it more specific or set replace_all."
                    )
                    is FuzzyEdit.Result.NotFound -> throw ToolFailure("edits[$idx]: ${r.detail} (in $path)")
                }
            }
            file.writeText(text)
            ToolResult(
                true,
                "Applied $applied edit(s) to $path" +
                    if (fuzzy) " (some matched with whitespace tolerance)" else "",
            )
        }
}

/**
 * Applies a unified diff. Supports modifying files, creating new files
 * (`--- /dev/null`) and deleting files (`+++ /dev/null`).
 *
 * Newline handling follows the POSIX convention (same as git): a trailing
 * newline terminates the last line rather than starting a new one, so
 * "a\nb\n" and "a\nb" are both two lines. The file's existing trailing-newline
 * state is preserved; new files end with a newline unless the patch carries a
 * "\ No newline at end of file" marker.
 */
class ApplyPatchTool : Tool {
    override val name = "apply_patch"
    override val description =
        "Apply a unified diff to the workspace. Format: '--- a/path', '+++ b/path', '@@ ...' hunks. " +
            "Use '--- /dev/null' to create a file and '+++ /dev/null' to delete one. " +
            "Context lines must match the CURRENT file contents (whitespace drift is tolerated); " +
            "a trailing newline terminates the last line, so never end a hunk with an extra empty " +
            "context line for the file's final newline. Hunks are matched against the file as it " +
            "exists when this call runs — if earlier edits in the same turn changed the file, " +
            "re-read it and rebuild the patch instead of reusing a pre-computed diff."
    override val parametersSchema = Schema.obj(
        mapOf(
            "patch" to Schema.string("The complete unified diff text."),
        ),
        required = listOf("patch"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val patch = args["patch"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: patch")

            val files = parsePatch(patch)
            if (files.isEmpty()) throw ToolFailure("No file sections found in the patch. Did you use --- a/path / +++ b/path headers?")

            val results = mutableListOf<String>()
            for (filePatch in files) {
                when {
                    filePatch.isNewFile -> {
                        val content = filePatch.hunks.flatMap { it.added }.joinToString("\n")
                        val final = if (content.isNotEmpty() && filePatch.newFileHasNewline) "$content\n" else content
                        ctx.workspace.resolve(filePatch.path).writeText(final)
                        results += "created ${filePatch.path}"
                    }

                    filePatch.isDelete -> {
                        val node = ctx.workspace.resolve(filePatch.path)
                        if (!node.exists) throw ToolFailure("${filePatch.path} does not exist (cannot delete)")
                        if (node.isDirectory) throw ToolFailure(
                            "${filePatch.path} is a directory; delete it with delete_file instead",
                        )
                        node.delete()
                        results += "deleted ${filePatch.path}"
                    }

                    else -> {
                        val node = ctx.workspace.resolve(filePatch.path)
                        if (!node.exists || !node.isFile) {
                            throw ToolFailure("${filePatch.path} does not exist (create it first or use --- /dev/null)")
                        }
                        val updated = applyHunks(node.readText(), filePatch.hunks, filePatch.path)
                        node.writeText(updated)
                        results += "patched ${filePatch.path} (${filePatch.hunks.size} hunk(s))"
                    }
                }
            }
            ToolResult(true, results.joinToString("; "))
        }

    // -- parsing ---------------------------------------------------------

    private data class Hunk(
        val oldStart: Int,
        val removed: List<String>,
        val context: List<Pair<Int, String>>, // index within hunk lines
        val added: List<String>,
        val lines: List<Pair<Char, String>>, // ordered ' ', '-', '+'
    )

    private data class FilePatch(
        val path: String,
        val isNewFile: Boolean,
        val isDelete: Boolean,
        val hunks: List<Hunk>,
        val newFileHasNewline: Boolean,
    )

    private fun parsePatch(patch: String): List<FilePatch> {
        val lines = splitLines(patch)
        val files = mutableListOf<FilePatch>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("--- ")) {
                val oldPath = normalizePath(line.removePrefix("--- ").substringBefore('\t'))
                var newPath: String? = null
                if (i + 1 < lines.size && lines[i + 1].startsWith("+++ ")) {
                    newPath = normalizePath(lines[i + 1].removePrefix("+++ ").substringBefore('\t'))
                    i += 2
                } else {
                    i++
                }
                val hunks = mutableListOf<Hunk>()
                // A marker seen after the last content line means that line
                // has no trailing newline in the file the diff describes.
                var pendingNoNewline = false
                var newFileHasNewline = true
                while (i < lines.size && lines[i].startsWith("@@")) {
                    val hunkHeader = lines[i]
                    val oldStart = Regex("-(\\d+)").find(hunkHeader)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    i++
                    val hunkLines = mutableListOf<Pair<Char, String>>()
                    while (i < lines.size) {
                        val l = lines[i]
                        when {
                            l.startsWith("@@") || l.startsWith("--- ") -> break
                            l.startsWith("+") -> {
                                hunkLines += '+' to l.removePrefix("+")
                                pendingNoNewline = false
                            }
                            l.startsWith("-") -> {
                                hunkLines += '-' to l.removePrefix("-")
                                pendingNoNewline = false
                            }
                            l.startsWith(" ") -> {
                                hunkLines += ' ' to l.removePrefix(" ")
                                pendingNoNewline = false
                            }
                            l.startsWith("\\") -> pendingNoNewline = true // "\ No newline at end of file"
                            else -> {
                                hunkLines += ' ' to l
                                pendingNoNewline = false
                            }
                        }
                        i++
                    }
                    hunks += Hunk(
                        oldStart = oldStart,
                        removed = hunkLines.filter { it.first == '-' }.map { it.second },
                        context = emptyList(),
                        added = hunkLines.filter { it.first == '+' }.map { it.second },
                        lines = hunkLines,
                    )
                }
                // The marker only describes the NEW file's newline state when
                // the last content line is an addition (new-file patches).
                if (pendingNoNewline && hunks.isNotEmpty()) {
                    val lastMark = hunks.last().lines.lastOrNull()?.first
                    if (lastMark == '+') newFileHasNewline = false
                }
                val isNewFile = oldPath == "/dev/null"
                val isDelete = newPath == "/dev/null"
                val path = when {
                    isNewFile -> newPath ?: throw ToolFailure("New-file patch is missing the +++ b/path header")
                    isDelete -> oldPath
                    else -> newPath ?: oldPath
                }
                files += FilePatch(
                    path = path,
                    isNewFile = isNewFile,
                    isDelete = isDelete,
                    hunks = hunks,
                    newFileHasNewline = newFileHasNewline,
                )
            } else {
                i++
            }
        }
        return files
    }

    private fun normalizePath(p: String): String {
        val trimmed = p.trim()
        if (trimmed == "/dev/null") return trimmed
        return trimmed.removePrefix("a/").removePrefix("b/")
    }

    // -- applying --------------------------------------------------------

    private fun applyHunks(text: String, hunks: List<Hunk>, path: String): String {
        val endsWithNewline = text.endsWith("\n") || text.endsWith("\r")
        val current = splitLines(text).toMutableList()
        var shift = 0
        for ((hunkIdx, hunk) in hunks.withIndex()) {
            var hunkLines = hunk.lines
            var expectedOld = hunkLines.filter { it.first != '+' }.map { it.second }
            var position = findPosition(current, expectedOld, hunk.oldStart - 1 + shift)

            // Trailing-newline normalization: patches are sometimes written
            // with the file's final newline modeled as one extra empty
            // context line. If the hunk matches once that phantom line is
            // dropped, apply it without it (POSIX: the trailing newline
            // terminates the last line instead of starting a new one).
            if (position == null && expectedOld.size > 1 && expectedOld.last() == "") {
                val trimmedExpected = expectedOld.dropLast(1)
                val trimmedPos = findPosition(current, trimmedExpected, hunk.oldStart - 1 + shift)
                if (trimmedPos != null) {
                    position = trimmedPos
                    hunkLines = dropLastOldEntry(hunkLines)
                    expectedOld = trimmedExpected
                }
            }

            if (position == null) {
                val newlineHint = if (!endsWithNewline) {
                    " Note: $path does not end with a newline — make sure the hunk's last " +
                        "context/removed line matches the final line exactly and there is no extra " +
                        "empty context line at the end of the hunk."
                } else ""
                throw ToolFailure(
                    "Hunk ${hunkIdx + 1} of $path does not match the file contents " +
                        "(context mismatch near line ${hunk.oldStart}). Re-read the file and rebuild the patch " +
                        "against its current contents.$newlineHint"
                )
            }

            // rebuild that region following +/- ordering
            val newRegion = mutableListOf<String>()
            var oldCursor = position
            for ((mark, line) in hunkLines) {
                when (mark) {
                    ' ' -> {
                        newRegion += current[oldCursor]
                        oldCursor++
                    }
                    '-' -> oldCursor++
                    '+' -> newRegion += line
                }
            }
            val endExclusive = position + expectedOld.size
            for (k in endExclusive - 1 downTo position) current.removeAt(k)
            current.addAll(position, newRegion)
            shift += newRegion.size - expectedOld.size
        }
        if (current.isEmpty()) return ""
        return current.joinToString("\n") + (if (endsWithNewline) "\n" else "")
    }

    /** Removes the last non-'+' entry (the phantom empty context line). */
    private fun dropLastOldEntry(lines: List<Pair<Char, String>>): List<Pair<Char, String>> {
        val idx = lines.indexOfLast { it.first != '+' }
        if (idx < 0) return lines
        return lines.subList(0, idx) + lines.subList(idx + 1, lines.size)
    }

    /**
     * Finds the line where [expected] matches [lines], sliding a +-40-line
     * window around [around] (hunks are often drifted by earlier edits).
     * Falls back to whitespace-tolerant comparison so CRLF/trailing-space/
     * indentation drift still anchors; the region rebuild keeps the file's
     * own context lines, so tolerated whitespace is preserved, not rewritten.
     */
    private fun findPosition(lines: List<String>, expected: List<String>, around: Int): Int? {
        if (expected.isEmpty()) return around.coerceIn(0, lines.size)
        val searchWindow = 40
        for (level in listOf(
            FuzzyEdit.Level.EXACT,
            FuzzyEdit.Level.LINE_ENDINGS,
            FuzzyEdit.Level.INDENTATION,
        )) {
            for (offset in 0..searchWindow) {
                for (dir in listOf(-1, 1)) {
                    val start = if (offset == 0 && dir == -1) continue else around + offset * dir
                    if (start < 0 || start + expected.size > lines.size) continue
                    var match = true
                    for (j in expected.indices) {
                        if (!FuzzyEdit.lineEquals(lines[start + j], expected[j], level)) {
                            match = false
                            break
                        }
                    }
                    if (match) return start
                    if (offset == 0) break
                }
            }
        }
        return null
    }
}
