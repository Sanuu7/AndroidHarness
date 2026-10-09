package com.androidharness.app.data.env

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * GitHub auth that survives toolchain reinstalls (stress-test C1/C2/M6).
 *
 * The token's master copy lives in the app's EncryptedSharedPreferences; the
 * toolchain only receives disposable copies that are re-materialized on every
 * app start and every deploy, so wiping/redeploying the Linux prefix can no
 * longer sever the GitHub connection:
 *
 *  - `<prefix>/home/.gh-token` (0600): the documented file agents can read
 *    (HOME points at `<prefix>/home` in both shell tiers).
 *  - `<prefix>/etc/gitconfig` carries a git identity plus an insteadOf rewrite
 *    that injects the token into every https://github.com URL. URL rewriting
 *    works in both execution tiers without an interactive credential helper.
 *    Git's compiled-in SHELL_PATH is patched to /system/bin/sh at extract time
 *    (see TermuxShellPath), and termux-exec handles native child programs.
 */
object GitHubProvision {

    /** Token file relative to the toolchain prefix. */
    const val TOKEN_FILE = "home/.gh-token"

    /** gh CLI auth file relative to the toolchain prefix (HOME-scoped). */
    const val GH_HOSTS_FILE = "home/.config/gh/hosts.yml"

    fun hasToken(token: String?): Boolean = !token.isNullOrBlank()

    /**
     * Full global git config body: safe.directory, a default commit identity
     * (fresh clones can commit without "Please tell me who you are"), main as
     * the default branch for `git init`, and the token rewrite. With no token,
     * everything except the rewrite is still written, so HTTPS clones of
     * public repos keep working anonymously.
     */
    fun gitConfigBody(token: String?): String = buildString {
        append("[safe]\n\tdirectory = *\n")
        append("[user]\n\tname = Android Harness\n\temail = harness@android.local\n")
        append("[init]\n\tdefaultBranch = main\n")
        if (hasToken(token)) {
            append("[url \"https://x-access-token:").append(token).append("@github.com/\"]\n")
            append("\tinsteadOf = https://github.com/\n")
        }
    }

    /** Writes (or removes) the token file inside the toolchain HOME. Idempotent. */
    fun materializeTokenFile(prefix: File, token: String?) {
        runCatching {
            val f = File(prefix, TOKEN_FILE)
            if (hasToken(token)) {
                f.parentFile?.mkdirs()
                writePrivate(f, token!!.trim() + "\n")
            } else {
                f.delete()
            }
        }
    }

    /**
     * Minimal gh hosts.yml that authenticates the gh CLI with the same token,
     * the file `gh auth login --with-token` would have produced, without the
     * interactive login. Null when there is no token (the file is removed).
     */
    fun ghHostsYaml(token: String?): String? =
        if (!hasToken(token)) null
        else "github.com:\n    git_protocol: https\n    oauth_token: ${token!!.trim()}\n"

    /** Writes (or removes) the gh CLI auth file inside the toolchain HOME. */
    fun materializeGhHosts(prefix: File, token: String?) {
        runCatching {
            val f = File(prefix, GH_HOSTS_FILE)
            val body = ghHostsYaml(token)
            if (body == null) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                writePrivate(f, body)
            }
        }
    }

    fun checkMaterialized(prefix: File, token: String?) {
        val tokenFile = File(prefix, TOKEN_FILE)
        val hostsFile = File(prefix, GH_HOSTS_FILE)
        check(if (hasToken(token)) tokenFile.isFile && tokenFile.readText().trim() == token!!.trim()
            else !tokenFile.exists()) { "GitHub credentials could not be applied to the local toolchain. Check storage and retry." }
        check(if (hasToken(token)) hostsFile.isFile && hostsFile.readText() == ghHostsYaml(token)
            else !hostsFile.exists()) { "GitHub CLI credentials could not be updated. Check storage and retry." }
    }

    /**
     * Creates (or rewrites) [f] with mode 0600 from the first byte: writing
     * first and chmodding after leaves the file world-readable in the window
     * between the two calls, and a crash in between would leave it readable
     * forever. The trailing chmod repairs copies an older build created 0755.
     */
    private fun writePrivate(f: File, text: String) {
        val fd = Os.open(
            f.absolutePath,
            OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC,
            0x180 /* 0600 */,
        )
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            Os.write(fd, bytes, 0, bytes.size)
        } finally {
            Os.close(fd)
        }
        runCatching { Os.chmod(f.absolutePath, 0x180) }
    }
}
