package com.androidharness.app.data.env

/**
 * How much a privileged command can do to the phone, which decides whether the
 * agent has to ask first.
 *
 * - [READ] only looks at device state (battery, properties, installed apps,
 *   system files under /proc or /system). It runs without a card.
 * - [CONTROL] acts on the phone: taps, opening apps, changing settings, reading
 *   the screen or personal files. One Allow covers the rest of the chat.
 * - [DESTRUCTIVE] is hard or impossible to undo: uninstalling apps, wiping app
 *   data, deleting files, rebooting. It asks every time, even in an allowed chat.
 *
 * Anything the classifier does not recognise is [CONTROL], so a command only
 * skips the card when every part of it is known to be read-only.
 */
enum class PhoneCommandRisk {
    READ, CONTROL, DESTRUCTIVE;

    companion object {
        fun of(command: String?): PhoneCommandRisk {
            // 2>&1, >/dev/null and 2>/dev/null only redirect output; drop them before splitting on & and |.
            val text = command?.trim().orEmpty()
                .replace(Regex("""\d?>&\d"""), " ").replace(Regex("""\d?>>?\s*/dev/null"""), " ")
            if (text.isBlank()) return CONTROL
            val segments = splitSegments(text)
            if (segments.any { destructive(words(it)) }) return DESTRUCTIVE
            // Substitutions and writes to files can hide anything, so they never count as reads.
            if ("$(" in text || '`' in text || '>' in text) return CONTROL
            return if (segments.isNotEmpty() && segments.all { read(words(it)) }) READ else CONTROL
        }

        private fun splitSegments(text: String): List<String> =
            text.split(Regex("""\|\||&&|[|;\n&]""")).map { it.trim() }.filter { it.isNotEmpty() }

        /** Words with quotes stripped and leading env assignments dropped; the program is reduced to its file name. */
        private fun words(segment: String): List<String> {
            val raw = Regex("""'[^']*'|"[^"]*"|\S+""").findAll(segment).map { it.value.trim('\'', '"') }.toList()
            val rest = raw.dropWhile { Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""").matches(it) }
            return rest.mapIndexed { i, w -> if (i == 0) w.substringAfterLast('/') else w }
        }

        /** The subcommand: `pm list` and `cmd package list` both give "list". */
        private fun sub(w: List<String>): String? = if (w.firstOrNull() == "cmd") w.getOrNull(2) else w.getOrNull(1)

        private fun isPackageManager(w: List<String>) = w.firstOrNull() == "pm" || (w.firstOrNull() == "cmd" && w.getOrNull(1) == "package")

        private val destructivePackage = setOf(
            "uninstall", "clear", "disable", "disable-user", "disable-until-used", "hide", "suspend",
            "uninstall-system-updates", "remove-user", "trim-caches", "reset-permissions",
        )
        private val destructiveCommands = setOf("rm", "rmdir", "reboot", "wipe", "mkfs", "dd", "shred", "truncate", "format", "recovery", "fastboot")

        /** Programs that run another command; what they run is what counts. */
        private val wrappers = setOf("xargs", "busybox", "toybox", "nohup", "timeout", "env", "nice", "ionice", "su", "sudo", "time", "exec", "command")
        private val shells = setOf("sh", "bash", "ash", "mksh", "zsh", "dash")

        private fun destructive(w: List<String>): Boolean {
            val program = w.firstOrNull() ?: return false
            if (program in shells) {
                val script = w.windowed(2).firstOrNull { it[0] == "-c" }?.get(1) ?: return false
                return of(script) == DESTRUCTIVE
            }
            if (program in wrappers) {
                // Skip the wrapper's own options and numbers (timeout 10, nice -n 5) to reach the command it runs.
                val inner = w.drop(1).dropWhile { it.startsWith("-") || it.toDoubleOrNull() != null || it.matches(Regex("""\d+[smhd]""")) }
                return inner.isNotEmpty() && destructive(listOf(inner[0].substringAfterLast('/')) + inner.drop(1))
            }
            return when {
                program in destructiveCommands -> true
                isPackageManager(w) -> sub(w) in destructivePackage
                program == "svc" && w.getOrNull(1) == "power" && w.getOrNull(2) in setOf("shutdown", "reboot") -> true
                program == "settings" && w.getOrNull(1) in setOf("delete", "reset") -> true
                program == "am" && (w.any { "FACTORY_RESET" in it || "MASTER_CLEAR" in it } || w.getOrNull(1) == "kill-all") -> true
                program == "locksettings" && w.getOrNull(1) in setOf("clear", "set-pin", "set-password", "set-pattern") -> true
                program == "dpm" && w.getOrNull(1)?.startsWith("remove") == true -> true
                program == "find" && ("-delete" in w || w.windowed(2).any { it[0] == "-exec" && it[1].substringAfterLast('/') == "rm" }) -> true
                else -> false
            }
        }

        /** Programs that only print what they are given or what they find. */
        private val plainReaders = setOf(
            "getprop", "id", "whoami", "uname", "uptime", "date", "echo", "printf", "pwd", "which", "true",
            "df", "free", "nproc", "ps", "pidof", "sort", "uniq", "cut", "tr", "wc", "head", "tail", "cat",
            "grep", "egrep", "fgrep", "ls", "stat", "file", "du", "basename", "dirname",
        )
        private val readOnlyPackage = setOf(
            "list", "path", "dump", "has-feature", "resolve-activity", "query-activities", "query-services",
            "query-receivers", "get-app-links", "get-install-location", "get-max-users", "get-max-running-users",
        )
        private val dumpsysChanges = setOf("set", "reset", "unplug", "force-idle", "unforce", "enable", "disable", "whitelist",
            "tempwhitelist", "kill", "clear", "put", "delete", "add", "remove", "step", "--reset")

        /** File reads are free only for system state, never personal files or other apps' data. */
        private val readablePaths = listOf("/proc/", "/sys/", "/system/", "/vendor/", "/product/", "/odm/", "/apex/", "/dev/null")

        private fun pathsAreSystem(w: List<String>) = w.drop(1).filter { it.startsWith("/") }
            .all { path -> readablePaths.any { path.startsWith(it) || "$path/" == it } }

        private fun read(w: List<String>): Boolean {
            val program = w.firstOrNull() ?: return false
            return when {
                // Disk usage of any mount point says nothing about the files on it.
                program == "df" -> true
                program == "dumpsys" -> w.drop(1).none { it in dumpsysChanges }
                isPackageManager(w) -> sub(w) in readOnlyPackage
                program == "settings" -> w.getOrNull(1) in setOf("get", "list")
                program == "service" -> w.getOrNull(1) in setOf("list", "check")
                program == "wm" -> w.getOrNull(1) in setOf("size", "density") && w.size == 2
                program == "logcat" -> "-d" in w && "-c" !in w && "--clear" !in w && "-f" !in w
                program == "top" -> "-n" in w || w.any { it.startsWith("-bn") }
                program == "sed" -> w.none { it.startsWith("-i") } && pathsAreSystem(w)
                program == "find" -> w.none { it in setOf("-exec", "-execdir", "-ok", "-delete", "-fprint", "-fprintf", "-fls") } && pathsAreSystem(w)
                program in plainReaders -> pathsAreSystem(w)
                else -> false
            }
        }
    }
}
