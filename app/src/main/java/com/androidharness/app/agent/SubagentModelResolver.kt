package com.androidharness.app.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Outcome of resolving the task tool's `model` override against the
 * provider's catalog. Sealed so the engine's refusal messages stay
 * exhaustive over the cases that matter (matched / unknown / catalog down).
 */
sealed interface SubagentModelResolution {
    /** [modelId] is the exact catalog id the subagent should run on. */
    data class Resolved(val modelId: String) : SubagentModelResolution

    /**
     * No catalog entry matches. [available] lists the ids to offer in the
     * refusal — the whole catalog, or empty when the provider returned none
     * (some endpoints do not support listing at all).
     */
    data class Unknown(val available: List<String>) : SubagentModelResolution

    /** The catalog itself could not be fetched. */
    data class Failed(val message: String) : SubagentModelResolution
}

/** Pure matching rules over catalog ids — unit-testable without any I/O. */
object SubagentModels {

    fun match(requested: String, candidates: List<String>): SubagentModelResolution {
        val wanted = requested.trim()
        candidates.firstOrNull { it == wanted }?.let { return SubagentModelResolution.Resolved(it) }
        // Catalog casing wins so Usage stats and later exact matches line up.
        candidates.firstOrNull { it.equals(wanted, ignoreCase = true) }
            ?.let { return SubagentModelResolution.Resolved(it) }
        // Vendor-qualified catalogs (e.g. OpenRouter's "anthropic/claude-…"):
        // an unqualified tail that resolves to exactly one entry is accepted.
        if (!wanted.contains('/')) {
            val bySuffix = candidates.filter { it.endsWith("/$wanted") }
            if (bySuffix.size == 1) return SubagentModelResolution.Resolved(bySuffix.single())
        }
        return SubagentModelResolution.Unknown(candidates)
    }
}

/**
 * Resolves task `model` overrides against one fetch of the provider's model
 * catalog. The fetch is memoized on first success, so parallel task calls in
 * the same run share a single request; a failed fetch is not cached and is
 * retried by the next call.
 */
class SubagentModelResolver(private val fetch: suspend () -> List<String>) {

    private val mutex = Mutex()
    private var cache: List<String>? = null

    suspend fun resolve(requested: String): SubagentModelResolution {
        val candidates = try {
            cache ?: mutex.withLock { cache ?: fetch().also { cache = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SubagentModelResolution.Failed(e.message ?: "catalog unavailable")
        }
        return SubagentModels.match(requested, candidates)
    }
}
