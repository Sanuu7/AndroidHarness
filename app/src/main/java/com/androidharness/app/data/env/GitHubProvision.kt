package com.androidharness.app.data.env

import android.system.Os
import java.io.File
import java.security.MessageDigest

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
 *    that injects the token into every https://github.com URL. Credential
 *    helpers cannot work in this toolchain — git's libexec sub-binaries
 *    (git-credential-store) are not kernel-execable under the W^X shim — so
 *    URL rewriting is the only credential transport that always works.
 */
object GitHubProvision {

    /** Token file relative to the toolchain prefix. */
    const val TOKEN_FILE = "home/.gh-token"

    fun hasToken(token: String?): Boolean = !token.isNullOrBlank()

    /**
     * Full global git config body: safe.directory, a default commit identity
     * (fresh clones can commit without "Please tell me who you are"), and the
     * token rewrite. With no token, everything except the rewrite is still
     * written, so HTTPS clones of public repos keep working anonymously.
     */
    fun gitConfigBody(token: String?): String = buildString {
        append("[safe]\n\tdirectory = *\n")
        append("[user]\n\tname = Android Harness\n\temail = harness@android.local\n")
        // Explicit empty helper list: even a stray user-level credential.helper
        // cannot make git spawn a helper that exec() would kill with EACCES.
        append("[credential]\n\thelper =\n")
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
                f.writeText(token!!.trim() + "\n")
                runCatching { Os.chmod(f.absolutePath, 0x180 /* 0600 */) }
            } else {
                f.delete()
            }
        }
    }

    /**
     * Short fingerprint of the token for staging hashes: a token change must
     * bump the deploy hash so the shell-tier copy is re-staged and re-deployed.
     */
    fun fingerprint(token: String?): String =
        if (!hasToken(token)) "none"
        else MessageDigest.getInstance("SHA-256").digest(token!!.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
