package com.androidharness.app.tools

import java.io.File

/**
 * Shell is not one permission. "Always allow" remembers a command *signature*
 * (first two tokens), and a small denylist + sandbox containment always wins —
 * including full auto.
 */
object ShellPolicy {

    fun denyReason(command: String, workspaceRoot: File? = null, cwd: File? = null): String? {
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

        val effectiveCwd = cwd ?: workspaceRoot
        return checkSandbox(cmd, workspaceRoot, effectiveCwd)
    }

    private fun checkSandbox(cmd: String, workspaceRoot: File?, cwd: File?): String? {
        val tokens = extractTokens(cmd)
        if (tokens.isEmpty()) return null

        val isSymlinkCmd = isSymlinkCreation(tokens)

        for (i in tokens.indices) {
            val token = tokens[i]
            val isRedirection = i > 0 && isRedirectionOp(tokens[i - 1])

            // Check symlink creation
            if (isSymlinkCmd) {
                if (cwd != null && isSharedStorage(cwd)) {
                    return "Blocked: symlinks are not supported on Android shared storage (/storage/emulated/0). (symlink not supported/allowed)"
                }
            }

            // Check absolute paths
            if (token.startsWith("/")) {
                val canon = try { File(token).canonicalPath } catch (_: Exception) { token }
                val inWorkspace = workspaceRoot != null &&
                    (canon == workspaceRoot.canonicalPath || canon.startsWith(workspaceRoot.canonicalPath + "/"))
                if (!inWorkspace && !isAllowedSystemPath(canon)) {
                    if (isSymlinkCmd) {
                        return "Blocked: symlink target is outside the workspace sandbox: $token (symlink not supported/allowed)"
                    }
                    return if (isRedirection) {
                        "Blocked: redirection target is outside the workspace sandbox: $token"
                    } else {
                        "Blocked: accessing path outside workspace is not allowed: $token"
                    }
                }
            }

            // Check relative traversal (..)
            if (token == ".." || token.startsWith("../") || token.contains("/..")) {
                if (workspaceRoot != null) {
                    val base = cwd ?: workspaceRoot
                    val resolved = try { File(base, token).canonicalFile } catch (_: Exception) { null }
                    val rootPath = workspaceRoot.canonicalPath
                    if (resolved == null || (!resolved.path.startsWith("$rootPath/") && resolved.path != rootPath)) {
                        if (isSymlinkCmd) {
                            return "Blocked: symlink target is outside the workspace sandbox: $token (symlink not supported/allowed)"
                        }
                        return "Blocked: path is outside the workspace sandbox: $token"
                    }
                } else if (token == ".." || token.startsWith("..") || token.contains("/..")) {
                    if (isSymlinkCmd) {
                        return "Blocked: symlink target is outside the workspace sandbox: $token (symlink not supported/allowed)"
                    }
                    return "Blocked: path is outside the workspace sandbox: $token"
                }
            }
        }
        return null
    }

    private fun isSymlinkCreation(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val first = tokens[0].substringAfterLast('/')
        if (first != "ln") return false
        return tokens.any { it == "-s" || it == "-sf" || it == "-fs" || it == "--symbolic" || (it.startsWith("-") && it.contains("s")) }
    }

    private fun isRedirectionOp(op: String): Boolean =
        op == ">" || op == ">>" || op == "<" || op == "1>" || op == "2>" || op == "1>>" || op == "2>>" || op == "tee"

    private fun isAllowedSystemPath(path: String): Boolean {
        val allowedPrefixes = listOf(
            "/system",
            "/vendor",
            "/apex",
            "/bin",
            "/usr/bin",
            "/usr/lib",
            "/sbin",
            "/system_ext",
            "/odm",
            "/product",
            "/dev",
            "/proc",
            "/sys",
            "/tmp",
            "/data/local/tmp/androidharness",
            "/data/data/com.androidharness",
            "/data/user/0/com.androidharness",
        )
        return allowedPrefixes.any { path == it || path.startsWith("$it/") }
    }

    private fun isSharedStorage(file: File): Boolean {
        val p = file.absolutePath
        return p == "/storage/emulated/0" || p.startsWith("/storage/emulated/0/") || p == "/sdcard" || p.startsWith("/sdcard/")
    }

    fun extractTokens(command: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val n = command.length
        while (i < n) {
            while (i < n && command[i].isWhitespace()) i++
            if (i >= n) break

            val c = command[i]
            if (c == '>' || c == '<' || c == '|' || c == '&' || c == ';') {
                var op = c.toString()
                i++
                if (i < n && (command[i] == '>' || command[i] == '&' || command[i] == '|')) {
                    op += command[i]
                    i++
                }
                tokens += op
                continue
            }

            val sb = StringBuilder()
            while (i < n && !command[i].isWhitespace() && command[i] != ';' && command[i] != '|' && command[i] != '&') {
                if (command[i] == '\'' || command[i] == '"') {
                    val quote = command[i]
                    i++
                    while (i < n && command[i] != quote) {
                        if (command[i] == '\\' && i + 1 < n) {
                            i++
                            sb.append(command[i])
                        } else {
                            sb.append(command[i])
                        }
                        i++
                    }
                    if (i < n && command[i] == quote) i++
                } else if (command[i] == '>' || command[i] == '<') {
                    break
                } else {
                    sb.append(command[i])
                    i++
                }
            }
            if (sb.isNotEmpty()) {
                tokens += sb.toString()
            }
        }
        return tokens
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
