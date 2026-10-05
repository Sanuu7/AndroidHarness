package com.androidharness.app.agent

import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.effectiveApiKey

/** Credentials travel with the selected provider and must never be logged. */
class SubagentConnection(val config: ProviderConfig, val apiKey: String)

object SubagentConfiguration {
    fun configured(
        settings: AppSettings,
        providers: List<ProviderConfig>,
        apiKey: (String) -> String?,
    ): SubagentConnection? {
        val id = settings.subagentProviderId ?: return null
        val provider = providers.firstOrNull { it.id == id }
            ?: error("Subagent provider is unavailable. Choose another model in Sub-Agent settings.")
        val key = provider.effectiveApiKey(apiKey(id)).takeIf { it.isNotBlank() }
            ?: error("Subagent provider needs authentication. Reconnect it in Settings.")
        val model = settings.subagentModel?.takeIf { it.isNotBlank() } ?: provider.model
        return SubagentConnection(provider.copy(model = model), key)
    }

    suspend fun forTask(
        parent: ProviderConfig,
        parentKey: String,
        requestedModel: String?,
        configured: suspend () -> SubagentConnection?,
        resolver: (suspend (String) -> SubagentModelResolution)?,
    ): SubagentConnection {
        // An explicit user choice wins over the task tool's optional model argument.
        configured()?.let { return it }
        val requested = requestedModel?.takeIf { it.isNotBlank() }
            ?: return SubagentConnection(parent, parentKey)
        val resolve = resolver ?: error("task `model` overrides are not available in this run. Retry the task without `model`.")
        val model = when (val outcome = resolve(requested)) {
            is SubagentModelResolution.Resolved -> outcome.modelId
            is SubagentModelResolution.Unknown -> {
                val listing = if (outcome.available.isEmpty()) {
                    "the provider's catalog is empty or does not support listing models"
                } else {
                    outcome.available.take(25).joinToString() +
                        if (outcome.available.size > 25) " … (+${outcome.available.size - 25} more)" else ""
                }
                error("Unknown task model '$requested': $listing. Retry with a listed id, or omit `model` to use ${parent.model}.")
            }
            is SubagentModelResolution.Failed -> error(
                "Could not verify task model '$requested': ${outcome.message}. Retry the task, or omit `model` to use ${parent.model}.",
            )
        }
        return SubagentConnection(parent.copy(model = model), parentKey)
    }
}
