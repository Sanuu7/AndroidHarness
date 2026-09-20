package com.androidharness.app.workspace

import com.androidharness.app.tools.ToolResult

/** Jobs and logs live with their remote workspace, including across app restarts. */
object SshJobs {
    suspend fun start(fs: SshFs, command: String): ToolResult {
        val id = (java.util.UUID.randomUUID().hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        val dir = ".harness/ssh-jobs/$id"
        val body = "echo \$\$ > ${sshQuote("$dir/pid")}\n( $command\n)\nec=\$?\nprintf '%s\\n' \"\$ec\" > ${sshQuote("$dir/exit")}\nexit \"\$ec\"\n"
        val script = "command -v setsid >/dev/null && command -v nohup >/dev/null || { echo 'Install setsid and nohup on the SSH host first'; exit 1; }; " +
            "mkdir -p .harness/ssh-jobs && mkdir ${sshQuote(dir)} && " +
            "printf '%s' ${sshQuote(body)} > ${sshQuote("$dir/run.sh")} && " +
            "{ nohup setsid sh ${sshQuote("$dir/run.sh")} > ${sshQuote("$dir/output.log")} 2>&1 < /dev/null & }; " +
            "echo 'Remote job $id started; inspect with bg_list. Log: $dir/output.log'"
        val r = fs.run(script, timeoutMs = 15_000, maxOutput = 4_000)
        return ToolResult(r.exitCode == 0, r.rawOutput + r.rawStderr + "\n" + r.note.orEmpty())
    }
    suspend fun list(fs: SshFs): ToolResult {
        val script = "for d in .harness/ssh-jobs/[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "printf '[%s] ' \"\${d##*/}\"; " +
            "if [ -f \"\$d/exit\" ]; then printf 'exited '; cat \"\$d/exit\"; " +
            "elif [ -f \"\$d/pid\" ] && kill -0 \"\$(cat \"\$d/pid\")\" 2>/dev/null; then echo running; " +
            "else echo 'unknown or stopped'; fi; tail -c 2000 \"\$d/output.log\" 2>/dev/null; printf '\\n'; done"
        val r = fs.run(script, timeoutMs = 15_000, maxOutput = 16_000)
        return ToolResult(r.exitCode == 0, (r.rawOutput + r.rawStderr).ifBlank { "No remote jobs." })
    }
    suspend fun stop(fs: SshFs, id: Int): ToolResult {
        require(id > 0)
        val dir = ".harness/ssh-jobs/$id"
        // Validate the tracked shell's command line before signaling its group, preventing PID reuse accidents.
        val script = "[ -f '$dir/pid' ] || { echo 'Unknown remote job'; exit 1; }; " +
            "[ ! -f '$dir/exit' ] || { echo 'Already exited'; exit 0; }; " +
            "p=\$(cat '$dir/pid'); case \"\$p\" in ''|*[!0-9]*) exit 1;; esac; " +
            "ps -p \"\$p\" -o args= | grep -F '$dir/run.sh' >/dev/null || { echo 'Job identity changed or process exited; no signal sent'; exit 1; }; " +
            "kill -TERM \"-\$p\" && echo 'Stop signal sent; inspect with bg_list'"
        val r = fs.run(script, timeoutMs = 15_000, maxOutput = 4_000)
        return ToolResult(r.exitCode == 0, r.rawOutput + r.rawStderr)
    }
}
