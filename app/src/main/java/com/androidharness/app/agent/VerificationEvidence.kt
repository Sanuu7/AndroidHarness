package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class CheckStatus { PASSED, FAILED, UNKNOWN, STALE }
internal data class VerificationCheck(val command: String, val status: CheckStatus)

internal object VerificationEvidence {
    private val checkCommand = Regex("^(?:(?:\\./)?gradlew?|mvnw?|npm|pnpm|yarn|bun|pytest|python3?|cargo|go|make|npx)\\s+", RegexOption.IGNORE_CASE)
    private val checkTask = Regex("(?:^|[\\s:])(?:test\\w*|check\\w*|lint\\w*|build|assemble\\w*|verify|typecheck|tsc|pytest)(?:$|[\\s:])", RegexOption.IGNORE_CASE)
    private val edits = setOf("write_file", "edit_file", "multi_edit", "apply_patch", "move_file", "delete_file", "shell_background", "task", "git_checkout", "git_pull", "create_dir")
    private val exit = Regex("(?m)^exit code: (-?\\d+)\\s*$")

    fun collect(messages: List<ChatMessage>): List<VerificationCheck> {
        val calls = messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }
            .flatMap { it.toolCalls }.associateBy { it.id }
        val checks = linkedMapOf<String, VerificationCheck>()
        fun invalidate() { checks.replaceAll { _, check -> if (check.status == CheckStatus.PASSED) check.copy(status = CheckStatus.STALE) else check } }
        for (message in messages) {
            if (message.role != Role.TOOL) continue
            val call = calls[message.toolCallId] ?: continue
            if (call.name in edits && !message.isError) invalidate()
            if (call.name != "shell") continue
            val command = runCatching {
                Json.parseToJsonElement(call.argumentsJson).jsonObject["command"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim() ?: continue
            val simple = command.substringAfterLast("&&").trim().replace(Regex("""['"]([A-Za-z0-9:_-]+)['"]"""), "$1")
            val isCheck = !command.contains(Regex("[|;`\\n\\r]|\\$\\(")) &&
                !command.contains(Regex("(?:^|\\s)(?:echo|printf)(?:\\s|$)")) &&
                checkCommand.containsMatchIn(simple) && checkTask.containsMatchIn(simple)
            if (!isCheck) { if (!message.isError) invalidate(); continue }
            val code = exit.find(message.text)?.groupValues?.get(1)?.toIntOrNull()
            val status = when {
                message.isError || (code != null && code != 0) -> CheckStatus.FAILED
                code == 0 -> CheckStatus.PASSED
                else -> CheckStatus.UNKNOWN
            }
            checks[command] = VerificationCheck(command, status)
        }
        return checks.values.toList()
    }
}
