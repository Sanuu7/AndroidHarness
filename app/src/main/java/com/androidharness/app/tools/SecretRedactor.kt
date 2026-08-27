package com.androidharness.app.tools

/** Scrub common secret shapes from tool output before it hits the model or DB. */
object SecretRedactor {

    private val PATTERNS = listOf(
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        Regex("""\bsk-[A-Za-z0-9]{10,}"""),
        Regex("""\bAKIA[0-9A-Z]{16}"""),
        Regex("""\bAIza[0-9A-Za-z\-_]{20,}"""),
        Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"""),
        Regex("""(?i)\bBearer\s+\S+"""),
        Regex("""(?i)\bpassword\s*[:=]\s*\S+"""),
        // GitHub tokens anywhere (classic PAT / OAuth / fine-grained), including
        // the token-embedded clone/push URLs git prints on GIT_CURL_VERBOSE.
        Regex("""\bgh[posr]_[A-Za-z0-9]{16,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        // user:password@ userinfo inside an https URL — only the credentials
        // are replaced, so the host and path stay readable.
        Regex("""(?<=https://)[^/\s@]+:[^/\s@]{6,}(?=@)"""),
        // token-shaped values behind credential-ish keys (config dumps, URLs)
        Regex("""(?i)\b(access_?token|auth_?token|session_?token|api_?key|secret_?key)\s*[=:]\s*["']?[A-Za-z0-9+/_\-]{20,}"""),
    )

    fun redact(text: String): String {
        var out = text
        for (p in PATTERNS) {
            out = p.replace(out, "[redacted]")
        }
        return out
    }
}
