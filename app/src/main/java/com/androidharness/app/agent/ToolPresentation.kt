package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class ToolActivityKind { READ, SEARCH, EDIT, COMMAND, VERIFY, WEB, QUESTION, SUBAGENT, MEMORY, OTHER }

data class ToolPresentation(
    val title: String,
    val action: String,
    val detail: String? = null,
    val kind: ToolActivityKind = ToolActivityKind.OTHER,
)

fun toolPresentation(call: ToolCallData, result: ChatMessage? = null): ToolPresentation {
    val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
    fun arg(name: String): String? = when (val el = args?.get(name)) {
        is JsonPrimitive -> el.content
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")
        else -> null
    }
    fun target(name: String = "path"): String = arg(name)?.trim()?.ifBlank { null } ?: "file"
    fun leaf(value: String): String = value.trimEnd('/').substringAfterLast('/').ifBlank { value }
    fun okTitle(success: String, failure: String = success): String = when {
        result == null -> success
        result.isError -> failure
        else -> success
    }
    fun countResultLines(): Int? {
        val text = result?.text ?: return null
        val count = text.lineSequence().count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("[") && trimmed != "(empty directory)"
        }
        return count.takeIf { it > 0 }
    }

    return when (call.name) {
        "write_file" -> {
            val path = target()
            ToolPresentation(
                title = if (result == null) "Create ${leaf(path)}" else okTitle("Created ${leaf(path)}", "Failed to create ${leaf(path)}"),
                action = "Creating $path…",
                detail = path,
                kind = ToolActivityKind.EDIT,
            )
        }
        "edit_file", "multi_edit" -> {
            val path = target()
            ToolPresentation(
                title = if (result == null) "Edit ${leaf(path)}" else okTitle("Edited ${leaf(path)}", "Edit failed: ${leaf(path)}"),
                action = "Editing $path…",
                detail = path,
                kind = ToolActivityKind.EDIT,
            )
        }
        "apply_patch" -> ToolPresentation(
            title = if (result == null) "Apply patch" else okTitle("Applied patch", "Patch failed"),
            action = "Applying patch…",
            kind = ToolActivityKind.EDIT,
        )
        "read_file" -> {
            val path = target()
            ToolPresentation("Read ${leaf(path)}", "Reading $path…", path, ToolActivityKind.READ)
        }
        "file_info" -> {
            val path = target()
            ToolPresentation("Inspected ${leaf(path)}", "Inspecting $path…", path, ToolActivityKind.READ)
        }
        "list_dir" -> {
            val path = arg("path") ?: "."
            ToolPresentation("Listed $path", "Listing $path…", kind = ToolActivityKind.READ)
        }
        "search_files" -> {
            val pattern = arg("pattern") ?: "files"
            val count = countResultLines()
            ToolPresentation(
                title = if (count != null) "Found $count files" else "Searched files",
                action = "Finding $pattern…",
                detail = pattern,
                kind = ToolActivityKind.SEARCH,
            )
        }
        "grep" -> {
            val pattern = arg("pattern") ?: "pattern"
            val count = countResultLines()
            ToolPresentation(
                title = if (count != null) "Searched $count matches" else "Searched code",
                action = "Searching for $pattern…",
                detail = pattern,
                kind = ToolActivityKind.SEARCH,
            )
        }
        "shell" -> {
            val command = arg("command")?.trim().orEmpty()
            val verification = isVerificationCommand(command)
            val title = if (verification) {
                when {
                    result == null -> "Run tests"
                    result.isError -> "Tests failed"
                    else -> "Tests passed"
                }
            } else {
                val short = command.lineSequence().firstOrNull()?.take(52)?.ifBlank { "command" } ?: "command"
                when {
                    result == null -> "Run $short"
                    result.isError -> "Command failed"
                    else -> "Ran $short"
                }
            }
            ToolPresentation(
                title = title,
                action = if (verification) "Running tests…" else "Running ${command.take(48).ifBlank { "command" }}…",
                detail = command.takeIf { it.isNotBlank() },
                kind = if (verification) ToolActivityKind.VERIFY else ToolActivityKind.COMMAND,
            )
        }
        "shell_background" -> {
            val command = arg("command")?.trim().orEmpty()
            ToolPresentation(
                title = if (result?.isError == true) "Background task failed" else "Started background task",
                action = "Starting ${command.take(48).ifBlank { "background task" }}…",
                detail = command.takeIf { it.isNotBlank() },
                kind = ToolActivityKind.COMMAND,
            )
        }
        "git_status" -> ToolPresentation("Checked git status", "Checking git status…", kind = ToolActivityKind.READ)
        "git_diff" -> ToolPresentation("Read git diff", "Reading git diff…", kind = ToolActivityKind.READ)
        "git_log", "git_show" -> ToolPresentation("Read git history", "Reading git history…", kind = ToolActivityKind.READ)
        "git_commit" -> ToolPresentation(if (result?.isError == true) "Commit failed" else "Committed changes", "Committing…", kind = ToolActivityKind.EDIT)
        "web_fetch" -> {
            val url = arg("url") ?: "page"
            ToolPresentation("Fetched page", "Fetching ${url.take(48)}…", url, ToolActivityKind.WEB)
        }
        "web_search" -> {
            val query = arg("query") ?: ""
            ToolPresentation("Searched web", "Searching: ${query.take(48)}…", query, ToolActivityKind.WEB)
        }
        "http_request" -> {
            val url = arg("url") ?: "API"
            ToolPresentation("Called API", "Calling ${url.take(48)}…", url, ToolActivityKind.WEB)
        }
        "ask_user" -> ToolPresentation("Asked a question", "Waiting for your answer…", kind = ToolActivityKind.QUESTION)
        "task" -> {
            val title = arg("title")?.takeIf { it.isNotBlank() } ?: "research subagent"
            ToolPresentation("Delegated $title", "Delegating: $title…", kind = ToolActivityKind.SUBAGENT)
        }
        "memory_write" -> ToolPresentation("Saved memory", "Saving to memory…", kind = ToolActivityKind.MEMORY)
        "memory_read", "memory_search" -> ToolPresentation("Read memory", "Reading memory…", kind = ToolActivityKind.MEMORY)
        "todo_write" -> ToolPresentation("Updated task list", "Updating task list…", kind = ToolActivityKind.OTHER)
        "skill_view" -> {
            val name = arg("name") ?: "skill"
            ToolPresentation("Loaded $name", "Loading skill $name…", kind = ToolActivityKind.READ)
        }
        "skills_list" -> ToolPresentation("Listed skills", "Listing skills…", kind = ToolActivityKind.READ)
        "pkg_install" -> {
            val packages = arg("packages") ?: arg("package") ?: "package"
            ToolPresentation(
                if (result?.isError == true) "Install failed" else "Installed $packages",
                "Installing $packages…",
                kind = ToolActivityKind.EDIT,
            )
        }
        "pkg_search" -> {
            val query = arg("query") ?: "packages"
            ToolPresentation("Searched packages", "Searching packages for $query…", query, ToolActivityKind.SEARCH)
        }
        "pkg_list" -> ToolPresentation("Listed packages", "Listing installed packages…", kind = ToolActivityKind.READ)
        "read_logcat" -> ToolPresentation("Read logcat", "Reading logcat…", kind = ToolActivityKind.READ)
        "read_image", "browser_screenshot" -> ToolPresentation("Inspected image", "Inspecting image…", kind = ToolActivityKind.READ)
        "browser_get_dom", "browser_get_logs", "browser_get_url" -> ToolPresentation("Inspected browser", "Inspecting browser…", kind = ToolActivityKind.READ)
        "browser_wait_for" -> ToolPresentation("Waited for page", "Waiting for page…", kind = ToolActivityKind.READ)
        else -> {
            val label = call.name.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            ToolPresentation(label, "$label…")
        }
    }
}

fun describeToolCall(call: ToolCallData): String = toolPresentation(call).action

internal fun isVerificationCommand(command: String): Boolean {
    val lower = command.lowercase()
    return listOf(
        "gradlew test", "gradle test", "testdebugunittest", "connectedandroidtest", "pytest",
        "python -m pytest", "python3 -m pytest", "npm test", "npm run test", "pnpm test",
        "yarn test", "cargo test", "go test", "mvn test", "mvnw test", "ctest",
    ).any { it in lower }
}
