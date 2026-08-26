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

        // 1. Dangerous system-level commands
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

        // Check raw command
        val rawCheck = checkSandbox(cmd, workspaceRoot, effectiveCwd)
        if (rawCheck != null) return rawCheck

        // Check expanded command ($PWD, ${PWD}, $HOME, variable assignments, $'\x2e\x2e', $(pwd), etc.)
        val expanded = expandVariables(cmd, workspaceRoot, effectiveCwd)
        if (expanded != cmd) {
            val expandedCheck = checkSandbox(expanded, workspaceRoot, effectiveCwd)
            if (expandedCheck != null) return expandedCheck
        }

        return null
    }

    private fun checkSandbox(cmd: String, workspaceRoot: File?, cwd: File?): String? {
        // 2. Recursively check command substitutions $(...) and `...`
        val subshellMatch = extractSubshells(cmd)
        for (subcmd in subshellMatch) {
            val subDeny = denyReason(subcmd, workspaceRoot, cwd)
            if (subDeny != null) {
                return subDeny
            }
        }

        // 3. Check variable assignments (e.g. D="/storage/emulated/0/Download", X=../escape)
        val varAssignments = extractVariableAssignments(cmd)
        for ((name, value) in varAssignments) {
            val trimmedVal = value.trim('\'', '"', ' ')
            if (trimmedVal.isNotEmpty()) {
                val pathDeny = checkSinglePath(trimmedVal, workspaceRoot, cwd, isSymlink = false, isRedirection = false)
                if (pathDeny != null) {
                    return "Blocked: variable $name contains out-of-workspace path: $trimmedVal"
                }
            }
        }

        // 4. Check directory changes (cd .. or cd /storage/...)
        val cdMatches = CD_REGEX.findAll(cmd)
        for (m in cdMatches) {
            val target = m.groupValues[1].trim('\'', '"', ' ')
            if (target.isNotEmpty()) {
                val pathDeny = checkSinglePath(target, workspaceRoot, cwd, isSymlink = false, isRedirection = false)
                if (pathDeny != null) {
                    return "Blocked: cd outside the workspace is not allowed: $target"
                }
            }
        }

        // 5. Check all tokens and explicit redirections
        val tokens = extractTokens(cmd)
        val isSymlinkCmd = isSymlinkCreation(tokens)

        if (isSymlinkCmd && cwd != null && isSharedStorage(cwd)) {
            return "Blocked: symlinks are not supported on Android shared storage (/storage/emulated/0). (symlink not supported/allowed)"
        }

        for (i in tokens.indices) {
            val token = tokens[i]
            val isRedirection = i > 0 && isRedirectionOp(tokens[i - 1])
            val check = checkSinglePath(token, workspaceRoot, cwd, isSymlinkCmd, isRedirection)
            if (check != null) return check
        }

        // 6. Full-text scan for absolute paths in the command (including quoted strings, arguments, scripts)
        val absolutePathMatches = ABS_PATH_REGEX.findAll(cmd)
        for (m in absolutePathMatches) {
            val p = m.value.trimEnd('/', ';', '&', '|', ')', '}', '"', '\'')
            if (p.length > 1) {
                val check = checkAbsolutePath(p, workspaceRoot, isSymlink = isSymlinkCmd, isRedirection = false)
                if (check != null) return check
            }
        }

        // 7. Full-text scan for relative traversals (../ or /..)
        if (TRAVERSAL_REGEX.containsMatchIn(cmd)) {
            val traversalMatches = TRAVERSAL_TOKEN_REGEX.findAll(cmd)
            for (m in traversalMatches) {
                val t = m.value.trim('\'', '"', '`', '(', ')', '{', '}', ';', '&', '|')
                val check = checkRelativePath(t, workspaceRoot, cwd, isSymlink = isSymlinkCmd, isRedirection = false)
                if (check != null) return check
            }
        }

        return null
    }

    private fun checkSinglePath(
        token: String,
        workspaceRoot: File?,
        cwd: File?,
        isSymlink: Boolean,
        isRedirection: Boolean,
    ): String? {
        val clean = token.trim('\'', '"', '`', '{', '}', '(', ')')
        if (clean.startsWith("/")) {
            return checkAbsolutePath(clean, workspaceRoot, isSymlink, isRedirection)
        }
        if (clean == ".." || clean.startsWith("../") || clean.contains("/..") || clean.startsWith("./..")) {
            return checkRelativePath(clean, workspaceRoot, cwd, isSymlink, isRedirection)
        }
        return null
    }

    private fun checkAbsolutePath(
        path: String,
        workspaceRoot: File?,
        isSymlink: Boolean,
        isRedirection: Boolean,
    ): String? {
        val canon = try { File(path).canonicalPath } catch (_: Exception) { path }
        if (workspaceRoot != null) {
            val rootCanon = workspaceRoot.canonicalPath
            if (canon == rootCanon || canon.startsWith("$rootCanon/")) {
                return null // In workspace
            }
        }
        if (isAllowedSystemPath(canon)) {
            return null // Allowed system/toolchain path
        }
        if (isSymlink) {
            return "Blocked: symlink target is outside the workspace sandbox: $path (symlink not supported/allowed)"
        }
        if (isRedirection) {
            return "Blocked: redirection target is outside the workspace sandbox: $path"
        }
        return "Blocked: accessing path outside workspace is not allowed: $path"
    }

    private fun checkRelativePath(
        path: String,
        workspaceRoot: File?,
        cwd: File?,
        isSymlink: Boolean,
        isRedirection: Boolean,
    ): String? {
        if (workspaceRoot != null) {
            val base = cwd ?: workspaceRoot
            val resolved = try { File(base, path).canonicalFile } catch (_: Exception) { null }
            val rootPath = workspaceRoot.canonicalPath
            if (resolved == null || (!resolved.path.startsWith("$rootPath/") && resolved.path != rootPath)) {
                if (isSymlink) {
                    return "Blocked: symlink target is outside the workspace sandbox: $path (symlink not supported/allowed)"
                }
                if (isRedirection) {
                    return "Blocked: redirection target is outside the workspace sandbox: $path"
                }
                return "Blocked: path is outside the workspace sandbox: $path"
            }
        } else if (path == ".." || path.startsWith("..") || path.contains("/..") || path.startsWith("./..")) {
            if (isSymlink) {
                return "Blocked: symlink target is outside the workspace sandbox: $path (symlink not supported/allowed)"
            }
            return "Blocked: path is outside the workspace sandbox: $path"
        }
        return null
    }

    fun expandVariables(
        cmd: String,
        workspaceRoot: File?,
        cwd: File?,
    ): String {
        val vars = mutableMapOf<String, String>()
        val effectiveCwd = cwd ?: workspaceRoot ?: File(".")
        val cwdPath = effectiveCwd.canonicalPath
        val rootPath = workspaceRoot?.canonicalPath ?: cwdPath
        vars["PWD"] = cwdPath
        vars["CWD"] = cwdPath
        vars["WORKSPACE"] = rootPath
        vars["HOME"] = "/data/local/tmp/androidharness/linux/home"

        // Extract all variable assignments in the command: VAR=value
        val assignments = extractVariableAssignments(cmd)
        for ((k, v) in assignments) {
            var resolvedV = v.trim('\'', '"')
            for ((varName, varVal) in vars) {
                resolvedV = resolvedV.replace("\$$varName", varVal).replace("\${$varName}", varVal)
            }
            vars[k] = resolvedV
        }

        var expanded = cmd
        // Unescape ANSI-C quotes: $'...'
        expanded = expanded.replace(ANSI_C_REGEX) { m ->
            unescapeAnsiC(m.groupValues[1])
        }

        // Replace $(pwd), `pwd`, etc.
        expanded = expanded.replace("\$(pwd)", cwdPath)
        expanded = expanded.replace("`pwd`", cwdPath)
        expanded = expanded.replace("\$(echo \$PWD)", cwdPath)
        expanded = expanded.replace("\$(echo \${PWD})", cwdPath)

        // Replace all variables sorted by name length descending
        val sortedVars = vars.entries.sortedByDescending { it.key.length }
        for ((k, v) in sortedVars) {
            expanded = expanded.replace("\${$k}", v)
            expanded = expanded.replace("\$$k", v)
        }

        return expanded
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
            "/data/data/com.androidharness/files/linux",
            "/data/user/0/com.androidharness/files/linux",
            "/data/data/com.androidharness.debug/files/linux",
            "/data/user/0/com.androidharness.debug/files/linux",
        )
        return allowedPrefixes.any { path == it || path.startsWith("$it/") }
    }

    private fun isSharedStorage(file: File): Boolean {
        val p = file.absolutePath
        return p == "/storage/emulated/0" || p.startsWith("/storage/emulated/0/") || p == "/sdcard" || p.startsWith("/sdcard/")
    }

    private fun extractSubshells(cmd: String): List<String> {
        val list = mutableListOf<String>()
        var idx = 0
        while (idx < cmd.length) {
            val start = cmd.indexOf("\$(", idx)
            if (start < 0) break
            var depth = 1
            var i = start + 2
            while (i < cmd.length && depth > 0) {
                if (cmd[i] == '(') depth++
                else if (cmd[i] == ')') depth--
                i++
            }
            if (depth == 0) {
                list += cmd.substring(start + 2, i - 1)
            }
            idx = i
        }
        val backtickRegex = Regex("`([^`]+)`")
        backtickRegex.findAll(cmd).forEach { m ->
            list += m.groupValues[1]
        }
        return list
    }

    private fun extractVariableAssignments(cmd: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val regex = Regex("""(?:^|[\s;&|])([A-Za-z_][A-Za-z0-9_]*)=(?:"([^"]*)"|'([^']*)'|([^\s;&|]+))""")
        regex.findAll(cmd).forEach { m ->
            val name = m.groupValues[1]
            val value = when {
                m.groupValues[2].isNotEmpty() -> m.groupValues[2]
                m.groupValues[3].isNotEmpty() -> m.groupValues[3]
                else -> m.groupValues[4]
            }
            list += name to value
        }
        return list
    }

    private fun unescapeAnsiC(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'x' -> {
                        val hex = s.substring(i + 2, minOf(i + 4, s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 2 + hex.length
                            continue
                        }
                    }
                    '0', '1', '2', '3', '4', '5', '6', '7' -> {
                        val oct = s.substring(i + 1, minOf(i + 4, s.length)).takeWhile { it in '0'..'7' }
                        val code = oct.toIntOrNull(8)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 1 + oct.length
                            continue
                        }
                    }
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    '\'' -> { sb.append('\''); i += 2; continue }
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
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

    private val CD_REGEX = Regex("""\bcd\s+([^\s;&|]+)""")
    private val ABS_PATH_REGEX = Regex("""/(?:storage|sdcard|data|etc|mnt|system|vendor|apex|dev|proc|sys|tmp)[A-Za-z0-9_.\-/]*""")
    private val TRAVERSAL_REGEX = Regex("""(?:\.\./|/\.\.|\b\.\.\b)""")
    private val TRAVERSAL_TOKEN_REGEX = Regex("""[^\s;&|'"]*\.\.[^\s;&|'"]*""")
    private val ANSI_C_REGEX = Regex("""\$'([^']*)'""")

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
