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
    )

    fun redact(text: String): String {
        var out = text
        for (p in PATTERNS) {
            out = p.replace(out, "[redacted]")
        }
        return out
    }
}
