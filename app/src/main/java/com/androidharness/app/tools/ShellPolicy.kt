package com.androidharness.app.tools

/**
 * Shell is not one permission. "Always allow" remembers a command *signature*
 * (first two tokens), and a small denylist always wins — including full auto.
 */
object ShellPolicy {

    fun denyReason(command: String): String? {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        if (isRmRoot(cmd)) {
            return "Blocked: recursive delete of the filesystem root is never allowed."
        }
        if (PIPE_TO_SHELL.containsMatchIn(cmd)) {
            return "Blocked: piping a download straight into a shell is never allowed."
        }
        if (MKFS.containsMatchIn(cmd)) {
            return "Blocked: formatting a block device is never allowed."
        }
        if (DD_DEV.containsMatchIn(cmd)) {
            return "Blocked: writing directly to a block device is never allowed."
        }
        if (FORK_BOMB.containsMatchIn(cmd)) {
            return "Blocked: fork bomb is never allowed."
        }
        if (CHMOD_ROOT.containsMatchIn(cmd)) {
            return "Blocked: chmod 777 of the filesystem root is never allowed."
        }
        return null
    }

    /** First two tokens, or the only token. */
    fun signature(command: String): String {
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        if (tokens.size == 1) return tokens[0]
        return "${tokens[0]} ${tokens[1]}"
    }

    fun grantKey(tool: String, command: String?): String =
        if (tool == "shell" || tool == "shell_background") {
            "$tool#${signature(command.orEmpty())}"
        } else {
            tool
        }

    fun isGranted(tool: String, command: String?, allowed: Set<String>): Boolean {
        if (grantKey(tool, command) in allowed) return true
        // Non-shell tools may still be remembered by name. Never treat a bare
        // "shell" grant as a blank check.
        return tool != "shell" && tool != "shell_background" && tool in allowed
    }

    fun commandOf(argumentsJson: String): String? = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson).jsonObjectOrNull()
        obj?.get("command")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    private fun isRmRoot(cmd: String): Boolean {
        if (!RM.containsMatchIn(cmd)) return false
        val recursive = RECURSIVE.containsMatchIn(cmd)
        val force = FORCE.containsMatchIn(cmd)
        val noPreserve = cmd.contains("--no-preserve-root")
        val rootTarget = ROOT_PATH.containsMatchIn(cmd)
        return ((recursive && force) || noPreserve) && (rootTarget || noPreserve)
    }

    private val RM = Regex("""(^|[\s;&|])rm\b""")
    private val RECURSIVE = Regex("""(^|[\s])(-[a-zA-Z]*r[a-zA-Z]*|--recursive)\b""")
    private val FORCE = Regex("""(^|[\s])(-[a-zA-Z]*f[a-zA-Z]*|--force)\b""")
    private val ROOT_PATH = Regex("""(^|[\s])/(?:\*+)?(?:\s|$)""")
    private val PIPE_TO_SHELL = Regex("""\b(curl|wget)\b[\s\S]*\|\s*(ba)?sh\b""")
    private val MKFS = Regex("""(^|[\s;&|])mkfs(\.\w+)?\b""")
    private val DD_DEV = Regex("""(^|[\s;&|])dd\b[\s\S]*\bof=/dev/""")
    private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""")
    private val CHMOD_ROOT = Regex("""(^|[\s;&|])chmod\b[\s\S]*\b777\b[\s\S]*(^|[\s])/(\s|$)""")
}
