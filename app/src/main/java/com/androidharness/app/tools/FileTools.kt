package com.androidharness.app.tools

import com.androidharness.app.core.splitLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.FileSystems
import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs

/** Bytes scanned at most when filesystem size metadata cannot be trusted (procfs/virtual entries). */
private const val VIRTUAL_FILE_SCAN_CAP = 1024L * 1024L

/**
 * Shared-storage mounts (emulated internal storage, FAT-style SAF providers)
 * treat names differing only in case as ONE file, so creating a twin-cased
 * name silently mixes two files into one slot. App-private ext4/f2fs storage
 * is genuinely case-sensitive. There is no portable mount-flag query on
 * Android, so this is judged purely by path shape.
 */
internal object CaseCollision {
    fun insensitiveMount(shellRootPath: String?, isSaf: Boolean): Boolean {
        if (isSaf) return true
        val p = shellRootPath?.replace('\\', '/') ?: return false
        return p.startsWith("/storage/") || p.startsWith("/sdcard")
    }

    fun siblingsMatchingOnlyByCase(name: String, siblingNames: Collection<String>): List<String> =
        siblingNames.filter { it != name && it.equals(name, ignoreCase = true) }

    fun warning(newName: String, collisions: List<String>): String? =
        collisions.firstOrNull()?.let { other ->
            "[warning: \"$other\" already exists here and this filesystem treats names differing only in case as the SAME file; writing \"$newName\" will collide with it]"
        }
}

/**
 * Non-fatal aliasing check for write_file / create_dir / move_file on
 * case-insensitive mounts. Walks only the nearest EXISTING parent directory;
 * fully-new nested paths cannot collide with anything yet except through
 * their created ancestors' siblings, which this deliberately ignores.
 */
internal fun caseCollisionWarning(workspace: WorkspaceFs, target: FsNode): String? {
    if (!CaseCollision.insensitiveMount(workspace.shellRoot?.absolutePath, workspace.isSaf)) return null
    val rel = target.relPath
    if (rel.isBlank() || rel == ".") return null
    val parentRel = if ('/' in rel) rel.substringBeforeLast('/') else "."
    val parent = runCatching { workspace.resolve(parentRel) }.getOrNull() ?: return null
    if (!parent.isDirectory) return null
    val leaf = target.name.ifBlank { return null }
    if (leaf == "?" || leaf == ".") return null
    val collisions = CaseCollision.siblingsMatchingOnlyByCase(leaf, parent.list().mapNotNull { n -> n.name })
    return CaseCollision.warning(leaf, collisions)
}

private const val MAX_LIST_ENTRIES = 500
private const val MAX_READ_CHARS = 100_000
private const val MAX_SEARCH_RESULTS = 300
private const val MAX_GREP_MATCHES = 200

class ListDirTool : Tool {
    override val name = "list_dir"
    override val description =
        "List the contents of a directory in the workspace. Directories are marked with a trailing /."
    override val parametersSchema = Schema.obj(
        mapOf("path" to Schema.string("Directory path relative to the workspace root. Use \".\" for the root.")),
        required = listOf("path"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content ?: "."
            val dir = ctx.workspace.resolve(path)
            if (!dir.exists) throw ToolFailure("Directory does not exist: $path")
            if (!dir.isDirectory) throw ToolFailure("Not a directory: $path")

            val entries = dir.list()
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            if (entries.isEmpty()) return@withContext ToolResult(true, "(empty directory)")

            val sb = StringBuilder()
            entries.take(MAX_LIST_ENTRIES).forEach { node ->
                sb.append(node.name)
                if (node.isDirectory) sb.append('/')
                sb.append('\n')
            }
            if (entries.size > MAX_LIST_ENTRIES) {
                sb.append("... and ${entries.size - MAX_LIST_ENTRIES} more entries\n")
            }
            ToolResult(true, sb.toString().trimEnd())
        }
}

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description =
        "Read a text file from the workspace with line numbers. Use offset and limit for large files."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("File path relative to the workspace root."),
            "offset" to Schema.integer("1-based line number to start reading from. Defaults to 1."),
            "limit" to Schema.integer("Maximum number of lines to read. Defaults to 2000."),
        ),
        required = listOf("path"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val file = ctx.workspace.resolve(path)
            if (!file.exists) throw ToolFailure("File does not exist: $path")
            if (!file.isFile) throw ToolFailure("Not a file: $path")
            if (file.isBinary()) {
                throw ToolFailure("Cannot read $path: binary file (not text).")
            }
            if (file.length > 2_000_000 && args["offset"] == null) {
                throw ToolFailure("File is ${file.length} bytes; use offset/limit to read it in chunks.")
            }

            val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 2000).coerceIn(1, 4000)

            // A UTF-8 BOM is encoding metadata, not content, never surface it
            // to the model (it leaks into line 1 and breaks exact matching).
            val raw = file.readText().removePrefix("\uFEFF")
            if (raw.isEmpty()) return@withContext ToolResult(true, "(empty file)")
            val all = splitLines(raw)
            if (all.isEmpty()) return@withContext ToolResult(true, "(empty file)")
            val slice = all.drop(offset - 1).take(limit)
            val sb = StringBuilder()
            for ((idx, line) in slice.withIndex()) {
                sb.append(offset + idx).append('\t').append(line).append('\n')
                if (sb.length > MAX_READ_CHARS) {
                    sb.append("\n[truncated: output exceeded $MAX_READ_CHARS chars]\n")
                    break
                }
            }
            if (offset + slice.size - 1 < all.size) {
                sb.append("[showing lines $offset..${offset + slice.size - 1} of ${all.size}]\n")
            }
            ToolResult(true, sb.toString().trimEnd())
        }
}

data class FileLineInfo(
    val isEmpty: Boolean,
    val isBinary: Boolean,
    val lineCount: Long,
    val trailingNewline: String,
    /**
     * Bytes actually streamed when filesystem metadata could not be trusted
     * (procfs / virtual files report size 0). 0 for regular stat-backed files.
     */
    val measuredBytes: Long = 0L,
    /** True when the scan stopped at the cap, i.e. real content continues. */
    val sizeTruncated: Boolean = false,
)

private class StreamScan {
    var hasBytes = false
    var lineCount = 0L
    var lastByte = -1
    var measuredBytes = 0L
    var truncated = false
    var binary = false
}

/**
 * Streams [node] counting bytes and newline-terminated lines up to [byteCap].
 * With [sniffBinary], binary content is detected inline from the first chunk
 * (for zero-stat entries where node.isBinary()'s own length guard skips it).
 */
private fun scanFileStream(node: FsNode, byteCap: Long, sniffBinary: Boolean): StreamScan {
    val scan = StreamScan()
    val buf = ByteArray(64 * 1024)
    try {
        node.openInputStream()?.buffered(64 * 1024)?.use { input ->
            var total = 0L
            while (total < byteCap) {
                val want = minOf(buf.size.toLong(), byteCap - total).toInt()
                val read = input.read(buf, 0, want)
                if (read <= 0) break
                if (sniffBinary &&
                    com.androidharness.app.workspace.isBinaryStream(java.io.ByteArrayInputStream(buf, 0, minOf(read, 1024)))
                ) {
                    scan.binary = true
                    break
                }
                total += read
                for (i in 0 until read) {
                    val b = buf[i].toInt()
                    if (b == 0x0A) { // '\n'
                        scan.lineCount++
                    }
                    scan.lastByte = b
                }
            }
            if (total >= byteCap && input.read() > 0) {
                scan.truncated = true
            }
            scan.hasBytes = total > 0
            scan.measuredBytes = total
        }
    } catch (_: Exception) {
        // Unreadable stream behaves like an empty one, matching prior behavior.
    }
    return scan
}

fun inspectFileInfo(node: FsNode): FileLineInfo {
    if (!node.exists || !node.isFile) {
        return emptyTextResult()
    }
    if (node.length > 0L) {
        // Trust metadata for regular files (unchanged behavior, incl. big files).
        if (node.isBinary()) {
            return FileLineInfo(
                isEmpty = false,
                isBinary = true,
                lineCount = 0L,
                trailingNewline = "none (binary)",
            )
        }
        val scan = scanFileStream(node, byteCap = Long.MAX_VALUE, sniffBinary = false)
        if (!scan.hasBytes) return emptyTextResult()
        return finished(scan)
    }

    // Size metadata says 0, but procfs/sysfs entries report 0 no matter how
    // much they contain (/proc/self/status reads ~1KB). Decide emptiness by
    // actually reading, bounded so pathological virtual files stay cheap.
    val scan = scanFileStream(node, byteCap = VIRTUAL_FILE_SCAN_CAP, sniffBinary = true)
    if (scan.binary) {
        return FileLineInfo(
            isEmpty = false,
            isBinary = true,
            lineCount = 0L,
            trailingNewline = "none (binary)",
        )
    }
    if (!scan.hasBytes) return emptyTextResult()
    // Full cap consumed and more behind it: report what was verified and say so.
    return finished(scan, sizeTruncated = scan.truncated || scan.measuredBytes >= VIRTUAL_FILE_SCAN_CAP)
}

private fun emptyTextResult() = FileLineInfo(
    isEmpty = true,
    isBinary = false,
    lineCount = 0L,
    trailingNewline = "none (empty file)",
)

private fun finished(scan: StreamScan, sizeTruncated: Boolean = false): FileLineInfo {
    val endsWithNl = scan.lastByte == 0x0A || scan.lastByte == 0x0D
    var lineCount = scan.lineCount
    if (!endsWithNl) {
        lineCount++
    }
    return FileLineInfo(
        isEmpty = false,
        isBinary = false,
        lineCount = lineCount,
        trailingNewline = if (endsWithNl) "present" else "none",
        measuredBytes = scan.measuredBytes,
        sizeTruncated = sizeTruncated,
    )
}

class FileInfoTool : Tool {
    override val name = "file_info"
    override val description =
        "Inspect file or directory metadata: size in bytes, line count, and trailing newline status."
    override val parametersSchema = Schema.obj(
        mapOf("path" to Schema.string("Path relative to the workspace root.")),
        required = listOf("path"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val node = ctx.workspace.resolve(path)
            if (!node.exists) throw ToolFailure("Path does not exist: $path")
            val sb = StringBuilder()
            sb.append("path: ").append(path).append('\n')
            sb.append("type: ").append(if (node.isDirectory) "directory" else "file").append('\n')
            sb.append("size_bytes: ").append(node.length).append('\n')
            if (node.isFile) {
                val info = inspectFileInfo(node)
                if (info.isEmpty) {
                    sb.append("is_empty: true\n")
                    sb.append("line_count: 0\n")
                } else {
                    val measuredNote =
                        if (!info.isBinary && node.length == 0L && info.measuredBytes > 0L) {
                            // procfs-style entry: stat undercounts, streaming told the truth.
                            val measured = if (info.sizeTruncated) {
                                ">= ${info.measuredBytes} bytes (scan cap reached)"
                            } else {
                                "${info.measuredBytes} bytes"
                            }
                            "size_note: stat reports 0 bytes; streamed content measures $measured\n"
                        } else ""
                    sb.append(measuredNote)
                    sb.append("is_empty: ").append(info.isEmpty).append('\n')
                    if (info.isBinary) {
                        sb.append("is_binary: true\n")
                        sb.append("line_count: (binary file)\n")
                    } else {
                        sb.append("line_count: ").append(info.lineCount).append('\n')
                        sb.append("trailing_newline: ").append(info.trailingNewline).append('\n')
                    }
                }
            }
            ToolResult(true, sb.toString().trimEnd())
        }
}

class WriteFileTool : Tool {
    override val name = "write_file"
    override val description =
        "Create or overwrite a file in the workspace. Parent directories are created automatically. " +
            "Non-empty content that doesn't end in a newline gets one appended (POSIX convention), " +
            "so files stay patch- and grep-friendly."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("File path relative to the workspace root."),
            "content" to Schema.string("Full content to write to the file."),
        ),
        required = listOf("path", "content"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val content = args["content"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: content")
            val file = ctx.workspace.resolve(path)
            if (file.exists && file.isDirectory) {
                throw ToolFailure("Cannot write file '$path': is a directory")
            }
            val existed = file.exists
            val endsWithNewline = content.endsWith("\n") || content.endsWith("\r")
            val written = if (content.isNotEmpty() && !endsWithNewline) "$content\n" else content
            val note = if (content.isNotEmpty() && !endsWithNewline) ", trailing newline added" else ""
            val warn = if (!existed) caseCollisionWarning(ctx.workspace, file) else null
            file.writeText(written)
            ToolResult(
                true,
                buildString {
                    append("${if (existed) "Overwrote" else "Created"} $path (${written.length} chars$note)")
                    if (warn != null) append('\n').append(warn)
                },
            )
        }
}

class EditFileTool : Tool {
    override val name = "edit_file"
    override val description =
        "Replace a string in a file. Matching tolerates whitespace drift " +
            "(indentation, trailing spaces, line endings); the match must still be unique. " +
            "Fails if not found or appears more than once (unless replace_all is true)."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("File path relative to the workspace root."),
            "old_string" to Schema.string("The text to replace."),
            "new_string" to Schema.string("The replacement text."),
            "replace_all" to Schema.boolean("Replace all occurrences. Defaults to false."),
        ),
        required = listOf("path", "old_string", "new_string"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val old = args["old_string"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: old_string")
            val new = args["new_string"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: new_string")
            val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false

            val file = ctx.workspace.resolve(path)
            if (!file.exists || !file.isFile) throw ToolFailure("File does not exist: $path")

            when (val r = FuzzyEdit.replace(file.readText(), old, new, replaceAll)) {
                is FuzzyEdit.Result.Ok -> {
                    file.writeText(r.newText)
                    val note = if (r.level != FuzzyEdit.Level.EXACT) {
                        " matched with whitespace tolerance (${r.level.name.lowercase()})"
                    } else ""
                    ToolResult(
                        true,
                        "Edited $path (${r.count} replacement${if (r.count > 1) "s" else ""})$note",
                    )
                }
                is FuzzyEdit.Result.Ambiguous -> throw ToolFailure(
                    "old_string appears ${r.count} times in $path; make it more specific or set replace_all.",
                )
                is FuzzyEdit.Result.NotFound -> throw ToolFailure("${r.detail} (in $path)")
            }
        }
}

class SearchFilesTool : Tool {
    override val name = "search_files"
    override val description =
        "Find files in the workspace whose name matches a glob pattern (e.g. \"*.kt\", \"**/*.gradle\")."
    override val parametersSchema = Schema.obj(
        mapOf(
            "pattern" to Schema.string("Glob pattern matched against file names/paths."),
            "path" to Schema.string("Subdirectory to search in. Defaults to the workspace root."),
        ),
        required = listOf("pattern"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val pattern = args["pattern"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: pattern")
            val path = args["path"]?.jsonPrimitive?.content ?: "."

            // Match against the file name via a synthetic path; ** patterns
            // additionally match against the workspace-relative path.
            val nameMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val matches = mutableListOf<String>()
            ctx.workspace.walk(path).forEach { node ->
                if (matches.size >= MAX_SEARCH_RESULTS) return@forEach
                if (!node.isFile) return@forEach
                val nameMatch = runCatching {
                    nameMatcher.matches(java.nio.file.Path.of(node.name))
                }.getOrDefault(false)
                val pathMatch = runCatching {
                    nameMatcher.matches(java.nio.file.Path.of(node.relPath))
                }.getOrDefault(false)
                if (nameMatch || pathMatch) matches += node.relPath
            }
            if (matches.isEmpty()) ToolResult(true, "No files matched \"$pattern\".")
            else ToolResult(
                true,
                matches.joinToString("\n") +
                    if (matches.size >= MAX_SEARCH_RESULTS) "\n[truncated at $MAX_SEARCH_RESULTS results]" else "",
            )
        }
}

class GrepTool : Tool {
    override val name = "grep"
    override val description =
        "Search file contents in the workspace with a regular expression. Returns matching lines as path:line: text."
    override val parametersSchema = Schema.obj(
        mapOf(
            "pattern" to Schema.string("Regular expression to search for."),
            "path" to Schema.string("Subdirectory to search in. Defaults to the workspace root."),
            "include" to Schema.string("Optional glob to limit files, e.g. \"*.kt\"."),
        ),
        required = listOf("pattern"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val pattern = args["pattern"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: pattern")
            val regex = try {
                Regex(pattern)
            } catch (e: Exception) {
                throw ToolFailure("Invalid regex: ${e.message}")
            }
            val path = args["path"]?.jsonPrimitive?.content ?: "."
            val include = args["include"]?.jsonPrimitive?.content
            val includeMatcher = include?.let {
                FileSystems.getDefault().getPathMatcher("glob:$it")
            }

            val matches = mutableListOf<String>()
            for (node in ctx.workspace.walk(path)) {
                if (matches.size >= MAX_GREP_MATCHES) break
                if (!node.isFile || node.length > 2_000_000 || node.isBinary()) continue
                if (includeMatcher != null &&
                    !includeMatcher.matches(java.nio.file.Path.of(node.name))
                ) continue
                val lines = runCatching { splitLines(node.readText()) }.getOrNull() ?: continue
                lines.forEachIndexed { idx, line ->
                    if (matches.size >= MAX_GREP_MATCHES) return@forEachIndexed
                    if (regex.containsMatchIn(line)) {
                        matches += "${node.relPath}:${idx + 1}: ${line.take(300)}"
                    }
                }
            }
            if (matches.isEmpty()) ToolResult(true, "No matches for \"$pattern\".")
            else ToolResult(
                true,
                matches.joinToString("\n") +
                    if (matches.size >= MAX_GREP_MATCHES) "\n[truncated at $MAX_GREP_MATCHES matches]" else "",
            )
        }
}
