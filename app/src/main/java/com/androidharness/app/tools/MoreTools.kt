package com.androidharness.app.tools

import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.TodoStore
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class CreateDirTool : Tool {
    override val name = "create_dir"
    override val description =
        "Create a directory (including parents) in the workspace. Fails if the path exists and is a file."
    override val parametersSchema = Schema.obj(
        mapOf("path" to Schema.string("Directory path relative to the workspace root.")),
        required = listOf("path"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val node = ctx.workspace.resolve(path)
            if (node.exists && node.isFile) {
                throw ToolFailure("Cannot create directory: $path already exists and is a file")
            }
            if (node.exists && node.isDirectory) {
                return@withContext ToolResult(true, "Directory already exists at $path")
            }
            node.mkdirs()
            if (!node.exists || !node.isDirectory) {
                throw ToolFailure("Failed to create directory $path")
            }
            val warn = caseCollisionWarning(ctx.workspace, node)
            ToolResult(
                true,
                buildString {
                    append("Created directory $path")
                    if (warn != null) append('\n').append(warn)
                },
            )
        }
}

class DeleteFileTool : Tool {
    override val name = "delete_file"
    override val description =
        "Delete a file or directory in the workspace. Deleting a directory that contains anything " +
            "requires recursive=true. This cannot be undone. The workspace root can never be deleted."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("Path relative to the workspace root. Must not be the workspace root."),
            "recursive" to Schema.boolean(
                "Required (true) to delete a directory that has files or subdirectories inside it.",
            ),
        ),
        required = listOf("path"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: path")
            val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
            if (path.isBlank() || path.trim() == ".") {
                throw ToolFailure(
                    "Refusing to delete the workspace root. Delete the contents individually if that is intended.",
                )
            }
            val node = ctx.workspace.resolve(path)
            if (node.relPath == "." || node.relPath.isBlank()) {
                throw ToolFailure(
                    "Refusing to delete the workspace root. Delete the contents individually if that is intended.",
                )
            }
            if (!node.exists) throw ToolFailure("Path does not exist: $path")
            if (node.isDirectory) {
                val childCount = node.list().size
                if (childCount > 0 && !recursive) {
                    throw ToolFailure(
                        "$path is a directory containing $childCount entr${if (childCount == 1) "y" else "ies"}. " +
                            "Pass recursive=true to delete it with everything inside, or delete the contents individually.",
                    )
                }
            }
            val wasDirectory = node.isDirectory
            if (node.delete()) {
                val what = if (wasDirectory) "directory" else "file"
                ToolResult(true, "Deleted $what $path")
            } else {
                ToolResult(false, "Failed to delete $path")
            }
        }
}

class MoveFileTool : Tool {
    override val name = "move_file"
    override val description = "Move or rename a file within the workspace."
    override val parametersSchema = Schema.obj(
        mapOf(
            "source" to Schema.string("Current path relative to the workspace root."),
            "destination" to Schema.string("New path relative to the workspace root."),
        ),
        required = listOf("source", "destination"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val source = args["source"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: source")
            val destination = args["destination"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: destination")

            try {
                val from = ctx.workspace.resolve(source)
                if (!from.exists) {
                    if (com.androidharness.app.workspace.isNameTooLong(null, source)) {
                        throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(source))
                    }
                    throw ToolFailure("Source does not exist: $source")
                }
                if (com.androidharness.app.workspace.isNameTooLong(null, destination)) {
                    throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(destination))
                }
                val to = ctx.workspace.resolve(destination)

                val srcParts = source.trim('/').replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                val dstParts = destination.trim('/').replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (srcParts.isEmpty()) throw ToolFailure("Cannot move workspace root.")
                if (dstParts.isEmpty()) throw ToolFailure("Destination cannot be workspace root.")
                if (srcParts == dstParts) throw ToolFailure("Source and destination are the same: $source")

                if (dstParts.size > srcParts.size && dstParts.subList(0, srcParts.size) == srcParts) {
                    throw ToolFailure("Cannot move '$source' into itself or a subdirectory: '$destination'")
                }

                if (from.isDirectory && srcParts.size > dstParts.size && srcParts.subList(0, dstParts.size) == dstParts) {
                    throw ToolFailure("Cannot move directory '$source' into its ancestor '$destination'")
                }

                if (to.exists && to.isDirectory) {
                    throw ToolFailure("Destination already exists and is a directory: $destination")
                }

                if (from.isDirectory && to.exists) {
                    throw ToolFailure("Destination already exists: $destination")
                }

                val srcParent = if (srcParts.size > 1) srcParts.dropLast(1).joinToString("/") else ""
                val dstParent = if (dstParts.size > 1) dstParts.dropLast(1).joinToString("/") else ""
                val sameDir = srcParent == dstParent
                val warn = caseCollisionWarning(ctx.workspace, to)
                if (sameDir && from.renameTo(to.name)) {
                    return@withContext ToolResult(
                        true,
                        buildString {
                            append("Moved $source → $destination")
                            if (warn != null) append('\n').append(warn)
                        },
                    )
                }
                // Slow path: copy content + delete (files only)
                if (!from.isFile) {
                    throw ToolFailure("Moving directories across folders is not supported; move the files individually.")
                }
                to.writeText(from.readText())
                from.delete()
                ToolResult(
                    true,
                    buildString {
                        append("Moved $source → $destination")
                        if (warn != null) append('\n').append(warn)
                    },
                )
            } catch (e: Exception) {
                if (e is ToolFailure) throw e
                if (com.androidharness.app.workspace.isNameTooLong(e, source)) {
                    throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(source))
                }
                if (com.androidharness.app.workspace.isNameTooLong(e, destination)) {
                    throw ToolFailure(com.androidharness.app.workspace.longFilenameGuidance(destination))
                }
                throw e
            }
        }
}

class WebFetchTool(
    private val client: OkHttpClient,
) : Tool {
    override val name = "web_fetch"
    override val description =
        "Fetch a URL and return its text content (HTML is stripped to text). Useful for reading docs."
    override val parametersSchema = Schema.obj(
        mapOf("url" to Schema.string("The http(s) URL to fetch.")),
        required = listOf("url"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw ToolFailure("Only http(s) URLs are supported.")
            }
            val fetchClient = client.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            try {
                fetchClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ToolResult(false, "HTTP ${resp.code} fetching $url")
                    }
                    val body = resp.body
                    val mediaType = body?.contentType()
                    if (HttpBinaryDetector.isBinaryMediaType(mediaType)) {
                        val mime = mediaType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                        val len = if ((body?.contentLength() ?: -1L) >= 0L) {
                            body!!.contentLength()
                        } else {
                            body?.bytes()?.size?.toLong() ?: 0L
                        }
                        return@withContext ToolResult(true, "[binary content: $mime, $len bytes]")
                    }
                    val bytes = body?.bytes() ?: ByteArray(0)
                    if (mediaType == null && bytes.isNotEmpty() && com.androidharness.app.workspace.isBinaryStream(java.io.ByteArrayInputStream(bytes))) {
                        return@withContext ToolResult(true, "[binary content: application/octet-stream, ${bytes.size} bytes]")
                    }
                    val raw = bytes.toString(mediaType?.charset() ?: Charsets.UTF_8)
                    ToolResult(true, htmlToText(raw).take(20_000))
                }
            } catch (e: Exception) {
                ToolResult(false, "Fetch failed: ${e.message}")
            }
        }

    private fun htmlToText(raw: String): String {
        if (!raw.contains("<html", ignoreCase = true) && !raw.contains("<body", ignoreCase = true)) {
            return raw
        }
        return raw
            .replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</(p|div|h[1-6]|li|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

class TodoWriteTool(
    private val store: TodoStore,
) : Tool {
    override val name = "todo_write"
    override val description =
        "Maintain a task list for this session. Use it to plan multi-step work: mark tasks " +
        "in_progress when you start them and completed when done. Replaces the whole list. " +
        "The todos parameter must be a JSON array like " +
        "[{\"content\": \"Do X\", \"status\": \"pending\"}], with statuses: pending, in_progress, completed."
    override val parametersSchema = Schema.obj(
        mapOf(
            "todos" to Schema.string(
                "JSON array of {\"content\": string, \"status\": \"pending\"|\"in_progress\"|\"completed\"}.",
            ),
        ),
        required = listOf("todos"),
    )
    override val isReadOnly = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val element = args["todos"] ?: throw ToolFailure("Missing required argument: todos")
        val raw = when (element) {
            is kotlinx.serialization.json.JsonArray -> element.toString()
            else -> element.jsonPrimitive.content
        }
        val items = parseTodos(raw)
            ?: throw ToolFailure("Invalid todos JSON: expected an array of {content, status} objects.")
        store.setAll(items)
        val done = items.count { it.status == TodoItem.Status.COMPLETED }
        return ToolResult(true, "Task list updated (${done}/${items.size} completed).")
    }

    /**
     * Tolerant parser: models frequently emit lowercase/alias statuses or
     * slightly malformed JSON (missing commas). Try strict parse first, then
     * a regex extraction that pairs up content/status values in order.
     */
    private fun parseTodos(raw: String): List<TodoItem>? {
        // 1) strict JSON array
        runCatching {
            val elements = json.parseToJsonElement(raw)
            if (elements is kotlinx.serialization.json.JsonArray) {
                return elements.mapNotNull { el ->
                    val obj = el.jsonObject
                    val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    TodoItem(content, parseStatus(obj["status"]?.jsonPrimitive?.contentOrNull))
                }
            }
        }

        // 2) regex fallback for malformed JSON: pair content/status in order
        val contentRegex = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val statusRegex = Regex("\"status\"\\s*:\\s*\"([a-zA-Z_\\- ]+)\"")
        val contents = contentRegex.findAll(raw).map { m ->
            m.groupValues[1]
                .replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }.toList()
        if (contents.isEmpty()) return null
        val statuses = statusRegex.findAll(raw).map { parseStatus(it.groupValues[1]) }.toList()
        return contents.mapIndexed { idx, content ->
            TodoItem(content, statuses.getOrElse(idx) { TodoItem.Status.PENDING })
        }
    }

    private fun parseStatus(raw: String?): TodoItem.Status =
        when (raw?.trim()?.lowercase()) {
            "completed", "complete", "done", "finished" -> TodoItem.Status.COMPLETED
            "in_progress", "inprogress", "in-progress", "active", "started", "working", "doing" ->
                TodoItem.Status.IN_PROGRESS
            else -> TodoItem.Status.PENDING // pending, waiting, todo, …
        }
}
