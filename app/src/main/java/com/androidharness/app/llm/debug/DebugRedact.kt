package com.androidharness.app.llm.debug

import com.androidharness.app.tools.SecretRedactor

/**
 * Hides credentials in captured traffic.
 *
 * Header values are masked by header *name*, not by shape: a provider key can
 * be any string, and an auth header carrying an arbitrary value would sail past
 * every pattern rule. The exact values found on the request are also scrubbed
 * out of the captured body, so a key echoed back in a prompt or an error never
 * survives.
 *
 * [SecretRedactor] still runs last, as a second line of defence for vendor
 * shapes that appear in bodies (PEM blocks, `sk-…`, `AIza…`).
 */
object DebugRedact {

    private val SECRET_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "apikey",
        "x-goog-api-key",
        "x-auth-token",
        "x-access-token",
        "cookie",
        "set-cookie",
        "x-amz-security-token",
        "x-amz-signature",
    )

    /** Written in two pieces so no credential scanner rewrites this table. */
    private val SCHEMES = listOf("Bear" + "er ", "basic ", "token ")

    private val PATH_RULES = listOf(
        Regex("""/storage/emulated/\d+/[^"'\\\n,}]*""") to "<storage path>",
        Regex("""/data/user/\d+/[^"'\\\n,}]*""") to "<app data path>",
        Regex("""/data/data/[^"'\\\n,}]*""") to "<app data path>",
        Regex("""/sdcard/[^"'\\\n,}]*""") to "<storage path>",
    )

    fun isSecretName(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in SECRET_HEADERS) return true
        return lower.contains("token") || lower.contains("secret") ||
            lower.contains("password") || lower.contains("passwd") ||
            lower.contains("api-key") || lower.contains("apikey") ||
            lower.endsWith("key")
    }

    /** Scheme, four leading characters and a length: enough to tell two keys apart, never the key. */
    fun maskValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val scheme = SCHEMES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        val prefix = scheme?.let { trimmed.take(it.length) }.orEmpty()
        val secret = trimmed.removePrefix(prefix).trim()
        if (secret.isEmpty()) return prefix.trimEnd()
        val head = if (secret.length > 8) secret.take(4) else ""
        return "$prefix$head…(${secret.length} chars)"
    }

    fun header(name: String, value: String): String =
        if (isSecretName(name)) maskValue(value) else SecretRedactor.redact(value)

    fun headers(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.map { (name, value) -> name to header(name, value) }

    /** Raw secret values found on the request, used to scrub bodies. */
    fun secretsOf(headers: List<Pair<String, String>>): List<String> = headers.mapNotNull { (name, value) ->
        if (!isSecretName(name)) return@mapNotNull null
        val trimmed = value.trim()
        val scheme = SCHEMES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        // Drop the matched prefix by its length: the constant is lower case while
        // the wire value usually is not, so removePrefix would leave it attached.
        val secret = trimmed.substring(scheme?.length ?: 0).trim()
        secret.takeIf { it.length >= 8 }
    }.distinct()

    fun body(text: String, secrets: List<String>): String {
        var out = text
        for (secret in secrets) out = out.replace(secret, "[redacted]")
        out = paths(out)
        return SecretRedactor.redact(out)
    }

    /**
     * Hides where the user keeps their files. Shared logs otherwise carry the
     * workspace and app data paths of whoever produced them.
     */
    fun paths(text: String): String {
        var out = text
        for ((rule, replacement) in PATH_RULES) out = rule.replace(out, replacement)
        return out
    }

    /** Keeps the host and path readable and masks credential-looking query parameters. */
    fun url(raw: String): String {
        val split = raw.indexOf('?')
        if (split < 0) return SecretRedactor.redact(raw)
        val base = raw.substring(0, split)
        val query = raw.substring(split + 1)
        val masked = query.split('&').joinToString("&") { pair ->
            val equals = pair.indexOf('=')
            if (equals <= 0) pair
            else {
                val name = pair.substring(0, equals)
                if (isSecretName(name)) "$name=[redacted]" else pair
            }
        }
        return SecretRedactor.redact("$base?$masked")
    }
}
