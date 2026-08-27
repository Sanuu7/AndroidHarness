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
            "The whole patch is validated first: if ANY hunk fails to match, nothing is written " +
            "and every failing hunk is reported by number and reason (atomic across hunks and files). " +
            "Context lines must match the CURRENT file contents (whitespace drift is tolerated); " +
            "a trailing newline terminates the last line, so never end a hunk with an extra empty " +
            "context line for the file's final newline. Set dry_run=true to validate only. " +
            "Hunks are matched against the file as it exists when this call runs; " +
            "if earlier edits in the same turn changed the file, " +
            "re-read it and rebuild the patch instead of reusing a pre-computed diff."
    override val parametersSchema = Schema.obj(
        mapOf(
            "patch" to Schema.string("The complete unified diff text."),
            "dry_run" to Schema.boolean(
                "Validate the patch against current file contents and report what would change, " +
                    "without writing anything. Defaults to false.",
            ),
        ),
        required = listOf("patch"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val patch = args["patch"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: patch")
            val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: false

            val files = parsePatch(patch)
            if (files.isEmpty()) throw ToolFailure("No file sections found in the patch. Did you use --- a/path / +++ b/path headers?")

            // Phase 1: compute every planned write against CURRENT content.
            // Nothing touches the filesystem here: if any hunk (or a whole
            // file section) fails, the whole patch is refused with a per-hunk
            // report instead of half-applying it (the old behavior let hunk 1
            // through and silently dropped the rest).
            data class Plan(
                val path: String,
                val op: String, // create | delete | patch
                val content: String? = null,
                val hunksApplied: Int = 0,
                val hunksTotal: Int = 0,
            )
            val plans = mutableListOf<Plan>()
            val failures = mutableListOf<String>()

            for (filePatch in files) {
                when {
                    filePatch.isNewFile -> {
                        val content = filePatch.hunks.flatMap { it.added }.joinToString("\n")
                        val final = if (content.isNotEmpty() && filePatch.newFileHasNewline) "$content\n" else content
                        val node = ctx.workspace.resolve(filePatch.path)
                        if (node.exists) {
                            failures += "${filePatch.path}: cannot create, it already exists (patch it instead or delete it first)"
                        } else {
                            plans += Plan(filePatch.path, "create", content = final)
                        }
                    }

                    filePatch.isDelete -> {
                        val node = ctx.workspace.resolve(filePatch.path)
                        when {
                            !node.exists ->
                                failures += "${filePatch.path}: does not exist (cannot delete)"
                            node.isDirectory ->
                                failures += "${filePatch.path}: is a directory; delete it with delete_file instead"
                            else -> plans += Plan(filePatch.path, "delete")
                        }
                    }

                    else -> {
                        val node = ctx.workspace.resolve(filePatch.path)
                        if (!node.exists || !node.isFile) {
                            failures += "${filePatch.path}: does not exist (create it first or use --- /dev/null)"
                            continue
                        }
                        try {
                            val applied = applyHunks(node.readText(), filePatch.hunks, filePatch.path)
                            plans += Plan(filePatch.path, "patch", content = applied, hunksApplied = filePatch.hunks.size, hunksTotal = filePatch.hunks.size)
                        } catch (e: ToolFailure) {
                            failures += e.message ?: "unknown hunk failure in ${filePatch.path}"
                        }
                    }
                }
            }

            if (failures.isNotEmpty()) {
                throw ToolFailure(
                    "Patch NOT applied (atomic: no file was modified). " +
                        failures.size + " of " + files.size + " file section(s) failed:\n" +
                        failures.joinToString("\n") { "- $it" },
                )
            }

            // Phase 2: commit (skipped entirely for dry runs). Only reached
            // when every section validated.
            val results = plans.map { p ->
                if (!dryRun) {
                    when (p.op) {
                        "create" -> ctx.workspace.resolve(p.path).writeText(p.content ?: "")
                        "delete" -> ctx.workspace.resolve(p.path).delete()
                        else -> ctx.workspace.resolve(p.path).writeText(p.content ?: "")
                    }
                }
                when (p.op) {
                    "create" -> "created ${p.path}"
                    "delete" -> "deleted ${p.path}"
                    else -> "patched ${p.path} (${p.hunksApplied} hunk(s) applied)"
                }
            }
            ToolResult(
                true,
                (if (dryRun) "[dry run: validated, nothing written] " else "") +
                    results.joinToString("; "),
            )
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

        // Pass 1: locate every hunk against the original text, simulating the
        // size shift each earlier hunk contributes. All failures are collected
        // so the error names EVERY bad hunk with its reason, and the caller
        // never sees a partial application (atomic across hunks).
        data class Resolved(
            val lines: List<Pair<Char, String>>,
            val expectedOld: List<String>,
            val position: Int,
        )
        val resolved = mutableListOf<Resolved>()
        val hunkFailures = mutableListOf<String>()
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
                    " Note: $path does not end with a newline, so make sure the hunk's last " +
                        "context/removed line matches the final line exactly and there is no extra " +
                        "empty context line at the end of the hunk."
                } else ""
                hunkFailures += "Hunk ${hunkIdx + 1} of $path does not match the file contents " +
                    "(context mismatch near line ${hunk.oldStart}). Re-read the file and rebuild the patch " +
                    "against its current contents.$newlineHint"
                continue
            }
            resolved += Resolved(hunkLines, expectedOld, position)
            shift += hunkLines.count { it.first == '+' } - hunkLines.count { it.first == '-' }
        }

        if (hunkFailures.isNotEmpty()) {
            throw ToolFailure(
                "Patch NOT applied to $path (${hunks.size} hunk(s), ${hunkFailures.size} failed; " +
                    "no hunk was written):\n" + hunkFailures.joinToString("\n"),
            )
        }

        // Pass 2: rebuild the regions, last hunk first so earlier positions
        // stay valid without shift bookkeeping.
        for (r in resolved.sortedByDescending { it.position }) {
            val newRegion = mutableListOf<String>()
            var oldCursor = r.position
            for ((mark, line) in r.lines) {
                when (mark) {
                    ' ' -> {
                        newRegion += current[oldCursor]
                        oldCursor++
                    }
                    '-' -> oldCursor++
                    '+' -> newRegion += line
                }
            }
            val endExclusive = r.position + r.expectedOld.size
            for (k in endExclusive - 1 downTo r.position) current.removeAt(k)
            current.addAll(r.position, newRegion)
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
