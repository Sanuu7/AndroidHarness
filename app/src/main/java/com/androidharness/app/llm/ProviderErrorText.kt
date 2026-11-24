package com.androidharness.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns a provider's "not found" or "gone" reply into something a person can
 * act on. NVIDIA, for one, answers a model it does not serve with a bare
 * "404 page not found" and a retired one with a 410 whose reason sits in
 * `detail`, so the raw text never says the model is the problem.
 */
object ProviderErrorText {
    private val json = Json { ignoreUnknownKeys = true }
    private val bareNotFound = Regex("""(?:404\s*)?(?:page\s+)?not\s+found\.?""", RegexOption.IGNORE_CASE)

    /** The provider's own reason from the usual error shapes, including RFC 7807 `detail`. */
    fun reason(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error")
        fun text(key: String, from: JsonObject?) = (from?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            error is JsonObject -> text("message", error)
            error is JsonPrimitive -> error.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            root != null -> text("message", root) ?: text("detail", root) ?: text("title", root)
            else -> body.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") }
        }
    }

    /**
     * Replacement text for a 404 or 410 that does not explain itself, or null
     * when the provider's own reason already does (or the code is another one).
     */
    fun missingModel(code: Int, body: String, model: String? = null,
        /** False only for a model listing; chat and key checks always name a model, even when it is unknown here. */
        namesModel: Boolean = !model.isNullOrBlank()): String? {
        if (code != 404 && code != 410) return null
        val reason = reason(body)
        val named = model?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "this model"
        return when {
            code == 410 -> (reason ?: "The provider has retired $named.") +
                " Refresh the model list and choose another model."
            reason == null || bareNotFound.matches(reason) ->
                if (!namesModel) "The provider has nothing at this address. Check the base URL."
                else "The provider does not serve $named here. It may have been retired or the ID may be " +
                    "mistyped. Refresh the model list and choose another model, or check the base URL."
            else -> null
        }
    }
}
