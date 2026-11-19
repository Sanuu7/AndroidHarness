package com.androidharness.app.tools

/**
 * Scrub common secret shapes from tool output before it hits the model, the
 * chat DB, or the screen.
 *
 * Two rules of the road:
 *  - Match on a vendor prefix or a credential-ish name, never on entropy. A
 *    "long random-looking string" rule would also swallow commit SHAs, hashes
 *    and base64 blobs, and the agent has to feed those back into later
 *    commands.
 *  - Nothing here may backtrack super-linearly. Tool output runs to 100k chars
 *    and this sits on every result, so the name/value split is a linear regex
 *    plus a plain-code predicate instead of one clever pattern.
 */
object SecretRedactor {

    /** A regex plus what to leave behind when it fires. */
    private class Rule(val regex: Regex, val replacement: String)

    private val PREFIX_RULES = listOf(
        // PEM key blocks (RSA / EC / OPENSSH / PGP).
        Rule(
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
            "[redacted]",
        ),

        // OpenAI: the legacy sk-<48 alnum> shape, then the hyphenated project,
        // service-account, admin and org keys, plus OpenRouter's sk-or-v1-<64>.
        // The hyphen is what made the old single sk- rule miss all of these.
        Rule(Regex("""\bsk-[A-Za-z0-9]{10,}"""), "[redacted]"),
        Rule(Regex("""\bsk-(?:proj|svcacct|admin|org|or-v1)-[A-Za-z0-9_\-]{12,}"""), "[redacted]"),
        // Anthropic: sk-ant-api03-<95>, sk-ant-admin01-<...>
        Rule(Regex("""\bsk-ant-[A-Za-z0-9_\-]{12,}"""), "[redacted]"),

        // Vendors that put a distinctive marker up front.
        Rule(Regex("""\bgsk_[A-Za-z0-9]{16,}"""), "[redacted]"),        // Groq
        Rule(Regex("""\bxai-[A-Za-z0-9]{16,}"""), "[redacted]"),        // xAI
        Rule(Regex("""\bpplx-[A-Za-z0-9]{16,}"""), "[redacted]"),       // Perplexity
        Rule(Regex("""\bfw_[A-Za-z0-9]{16,}"""), "[redacted]"),         // Fireworks
        Rule(Regex("""\br8_[A-Za-z0-9]{16,}"""), "[redacted]"),         // Replicate
        Rule(Regex("""\bhf_[A-Za-z0-9]{16,}"""), "[redacted]"),         // Hugging Face
        Rule(Regex("""\btgp_v1_[A-Za-z0-9_\-]{12,}"""), "[redacted]"),  // Together
        Rule(Regex("""\btvly-[A-Za-z0-9_\-]{12,}"""), "[redacted]"),    // Tavily
        Rule(Regex("""\bfc-[A-Za-z0-9]{24,}"""), "[redacted]"),         // Firecrawl
        Rule(Regex("""\bjina_[A-Za-z0-9_\-]{24,}"""), "[redacted]"),    // Jina

        // Cloud and infra.
        Rule(Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""), "[redacted]"),  // AWS access key id
        Rule(Regex("""\bAIza[0-9A-Za-z\-_]{20,}"""), "[redacted]"),      // Google API key
        Rule(Regex("""\bya29\.[A-Za-z0-9_\-]{20,}"""), "[redacted]"),    // Google OAuth
        Rule(Regex("""\bdop_v1_[0-9a-f]{64}"""), "[redacted]"),          // DigitalOcean
        Rule(Regex("""\bsbp_[A-Za-z0-9]{24,}"""), "[redacted]"),         // Supabase PAT
        Rule(Regex("""\bsb_(?:publishable|secret)_[A-Za-z0-9_\-]{12,}"""), "[redacted]"),

        // Code hosts and package registries.
        Rule(Regex("""\bgh[posru]_[A-Za-z0-9]{16,}"""), "[redacted]"),    // GitHub PAT / OAuth / app
        Rule(Regex("""github_pat_[A-Za-z0-9_]{20,}"""), "[redacted]"),    // GitHub fine-grained
        Rule(Regex("""\bglpat-[A-Za-z0-9_\-]{16,}"""), "[redacted]"),     // GitLab
        Rule(Regex("""\bnpm_[A-Za-z0-9]{32,}"""), "[redacted]"),          // npm
        Rule(Regex("""\bdckr_pat_[A-Za-z0-9_\-]{16,}"""), "[redacted]"),  // Docker Hub
        Rule(Regex("""\bnapi-[A-Za-z0-9]{32,}"""), "[redacted]"),         // Notion
        Rule(Regex("""\blin_api_[A-Za-z0-9]{24,}"""), "[redacted]"),      // Linear

        // Payments and messaging.
        Rule(Regex("""\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}"""), "[redacted]"),          // Stripe
        Rule(Regex("""\bwhsec_[A-Za-z0-9]{16,}"""), "[redacted]"),                        // Stripe webhook
        Rule(Regex("""\bSG\.[A-Za-z0-9_\-]{16,}\.[A-Za-z0-9_\-]{16,}"""), "[redacted]"),  // SendGrid
        Rule(Regex("""\b(?:AC|SK)[0-9a-fA-F]{32}\b"""), "[redacted]"),                    // Twilio SID
        Rule(Regex("""\bxox[abpros]-[A-Za-z0-9\-]{10,}"""), "[redacted]"),                // Slack
        Rule(Regex("""\b\d{8,10}:[A-Za-z0-9_\-]{33,}"""), "[redacted]"),                  // Telegram bot

        // Tokens that carry their own signature.
        Rule(Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"""), "[redacted]"),
        Rule(Regex("""(?i)\bBearer\s+\S+"""), "[redacted]"),
        Rule(Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]{16,}"""), "[redacted]"),

        // user:password@ userinfo in any URL scheme; the scheme and host stay
        // readable so the model can still tell what it was pointed at.
        Rule(
            Regex("""([a-zA-Z][A-Za-z0-9+.\-]{1,20}://)[^\s/@:]{1,64}:[^\s/@]{6,}@"""),
            "$1[redacted]@",
        ),
    )

    /**
     * `NAME = value` / `NAME: value`, the value taken as one unbroken token.
     * Deliberately blind to what NAME means, that call is made in code below.
     */
    private val ASSIGNMENT = Regex(
        "(?m)(?<![A-Za-z0-9])([A-Za-z0-9_.\\-]{1,64})" +
            "([\"']?[^\\S\\r\\n]{0,8}[=:][^\\S\\r\\n]{0,8}[\"']?)" +
            "([A-Za-z0-9+/_\\-]{16,})(?![A-Za-z0-9_(])",
    )

    /** Same shape, but passwords carry no prefix so the value may be short. */
    private val PASSWORD = Regex(
        "(?m)(?<![A-Za-z0-9])([A-Za-z0-9_.\\-]{1,64})" +
            "([\"']?[^\\S\\r\\n]{0,8}[=:][^\\S\\r\\n]{0,8}[\"']?)" +
            "([^\\s\"']{3,})",
    )

    /**
     * Words that make an identifier a credential holder. Matched per `_`/`-`/`.`
     * segment, so `ANTHROPIC_API_KEY`, `AWS_SECRET_ACCESS_KEY` and
     * `SECRET_KEY_BASE` all qualify while `monkey`, `keyboard` and `tokenizer`
     * do not.
     */
    private val CREDENTIAL_WORDS = setOf(
        "key", "keys", "apikey", "secret", "secrets", "secretkey",
        "token", "tokens", "accesstoken", "credential", "credentials",
        "password", "passwd", "passphrase", "passcode",
        "authkey", "accesskey", "privatekey", "signingkey", "sessionkey",
    )

    /** `pwd` is excluded on purpose: PWD= in env output is a directory. */
    private val PASSWORD_WORDS = setOf("password", "passwd", "passphrase", "passcode")

    private fun segments(name: String) = name.lowercase().split('_', '-', '.')

    fun redact(text: String): String {
        var out = text
        for (rule in PREFIX_RULES) {
            out = rule.regex.replace(out, rule.replacement)
        }
        out = ASSIGNMENT.replace(out) { m ->
            val name = m.groupValues[1]
            if (segments(name).any { it in CREDENTIAL_WORDS }) {
                name + m.groupValues[2] + "[redacted]"
            } else {
                m.value
            }
        }
        out = PASSWORD.replace(out) { m ->
            val name = m.groupValues[1]
            if (segments(name).any { it in PASSWORD_WORDS }) {
                name + m.groupValues[2] + "[redacted]"
            } else {
                m.value
            }
        }
        return out
    }
}