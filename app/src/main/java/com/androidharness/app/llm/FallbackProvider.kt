package com.androidharness.app.llm

import com.androidharness.app.agent.RetryPolicy
import com.androidharness.app.agent.TaskBudget
import com.androidharness.app.agent.ThinkingSpecs
import com.androidharness.app.agent.stallGuard
import com.androidharness.app.core.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.coroutineContext

internal enum class FallbackFailureKind { QUOTA, TRANSIENT, TERMINAL }

internal fun fallbackFailureKind(failure: StreamEvent.Failure, cause: Throwable? = null): FallbackFailureKind {
    if (failure.code == 401 || failure.code == 403) return FallbackFailureKind.TERMINAL
    val quotaCodes = setOf(HarnessFreeLimit.QUOTA_CODE, "insufficient_quota", "quota_exceeded", "credit_balance_too_low",
        "subscription_sharing_usage_limit_exceeded", "usage_limit_reached", "billing_hard_limit_reached")
    val quotaText = Regex("insufficient[_ ]quota|quota (?:is )?(?:exceeded|exhausted)|exceeded.{0,30}quota|" +
        "insufficient (?:credits|balance)|credit balance.{0,20}(?:low|exhausted)|usage limit (?:reached|exceeded)", RegexOption.IGNORE_CASE)
    if (failure.errorCode?.lowercase() in quotaCodes || failure.code == 402 ||
        ((failure.code == null || failure.code == 429) && quotaText.containsMatchIn(failure.message))) return FallbackFailureKind.QUOTA
    if (failure.retryable == false) return FallbackFailureKind.TERMINAL
    val transientCode = failure.code == null && failure.errorCode?.lowercase() in setOf(
        "rate_limit_exceeded", "rate_limit_error", "resource_exhausted", "overloaded_error", "unavailable", "deadline_exceeded")
    return if (transientCode || RetryPolicy.isRetryable(cause, failure.message, failure.code)) FallbackFailureKind.TRANSIENT else FallbackFailureKind.TERMINAL
}

/** Each destination uses its own credentials. Only explicitly configured routes can be attempted. */
class FallbackProvider(
    private val factory: (ProviderConfig) -> LlmProvider,
    private val resolve: suspend (FallbackTarget) -> ProviderConfig?,
    private val keyFor: suspend (ProviderConfig) -> String,
    private val destination: (ProviderConfig) -> String = { it.baseUrl.trimEnd('/').lowercase() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val stallTimeoutMs: Long = 90_000,
) : LlmProvider {
    private var primary: ProviderConfig? = null
    private var managedChanged = false
    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        val consent = coroutineContext[FallbackRun]
        if (consent == null) {
            factory(config).streamChat(config, apiKey, systemPrompt, messages, tools, options).collect { emit(it) }
            return@flow
        }
        var current = config
        val original = primary ?: config.also { primary = it }
        val tried = mutableSetOf(FallbackTarget(current.id, current.model), FallbackTarget(original.id, original.model))
        var attempts = 0
        // One group per turn keeps every retry and failover attempt together in the console.
        val debugGroup = options.debugGroup ?: java.util.UUID.randomUUID().toString()
        // Counts every send of this turn, including failover hops (attempts resets per provider).
        var debugAttempts = 0
        var lastFailure: StreamEvent.Failure? = null
        val initialPolicy = consent.policy()
        val approvedPrimary = consent.primary ?: original
        val rootDestination = destination(approvedPrimary)
        fun allowed(candidate: ProviderConfig, policy: FallbackPolicy): Boolean =
            if (policy.sameProviderOnly) candidate.id == approvedPrimary.id && destination(candidate) == rootDestination
            else (candidate.id == approvedPrimary.id && destination(candidate) == rootDestination) ||
                FallbackTarget(candidate.id, candidate.model) in policy.targets
        if (initialPolicy?.enabled == true && !allowed(current, initialPolicy)) {
            emit(StreamEvent.Failure("${current.name} is outside this chat's allowed fallback routes. Review Fallbacks or the subagent model.", retryable = false))
            return@flow
        }
        if (initialPolicy == null || !initialPolicy.enabled) {
            if (managedChanged && (current.id != original.id || current.model != original.model)) {
                emit(StreamEvent.Failure("Fallback routing changed. Review the model before continuing.", retryable = false))
                return@flow
            }
            val key = apiKey
            if (key.isBlank()) emit(StreamEvent.Failure("${current.name}: credentials are unavailable. Reconnect this provider.", retryable = false))
            else factory(current).streamChat(current, key, systemPrompt, messages, tools, options).collect { emit(it) }
            return@flow
        }
        while (true) {
            coroutineContext[TaskBudget]?.check()
            if (lastFailure != null && consent.policy()?.enabled != true) {
                emit(lastFailure.copy(retryable = false))
                return@flow
            }
            if (current.id != original.id || current.model != original.model) {
                val live = consent.policy()?.takeIf { it.enabled && it.valid() }
                if (live == null || FallbackTarget(current.id, current.model) !in live.targets ||
                    !allowed(current, live)) {
                    emit(StreamEvent.Failure("Fallback routing changed. Choose a model or resume after reviewing Fallbacks.", retryable = false))
                    return@flow
                }
            }
            var output = false
            var failure: StreamEvent.Failure? = null
            var cause: Throwable? = null
            val key = keyFor(current)
            if (key.isBlank()) {
                emit(StreamEvent.Failure("${current.name}: credentials are unavailable. Reconnect this provider.", retryable = false))
                return@flow
            }
            val beforeSend = consent.policy()?.takeIf { it.enabled && it.valid() }
            val selectedConnection = current.id == approvedPrimary.id && destination(current) == rootDestination
            if ((beforeSend == null && (!selectedConnection || lastFailure != null)) ||
                (beforeSend != null && !allowed(current, beforeSend))) {
                emit(StreamEvent.Failure("Fallback routing changed. Review Fallbacks before continuing.", retryable = false))
                return@flow
            }
            val timeout = if (com.androidharness.app.local.LocalModelCatalog.isLocal(current.id)) 600_000 else stallTimeoutMs
            flow {
                factory(current).streamChat(current, key, systemPrompt, messages, tools,
                    options.copy(thinking = ThinkingSpecs.resolvedForModel(current.model, options.thinking, ModelsDev.providerKeyFor(current.baseUrl)).level,
                        debugGroup = debugGroup, debugAttempt = ++debugAttempts))
                    .collect { emit(it) }
            }.flattenEvents().stallGuard(timeout).catch { e ->
                when (e) {
                    is TimeoutCancellationException -> {
                        coroutineContext.ensureActive()
                        emit(StreamEvent.Failure("Generation stalled (timed out)"))
                    }
                    is CancellationException -> throw e
                    is com.androidharness.app.agent.TaskPaused -> throw e
                    else -> {
                        cause = e
                        emit(StreamEvent.Failure(e.message ?: "Provider request failed", code = (e as? ApiException)?.code,
                            errorCode = (e as? ApiException)?.errorCode))
                    }
                }
            }.takeWhile { event ->
                if (event is StreamEvent.Failure) { failure = event; false }
                else {
                    if (event.hasResponseOutput()) output = true
                    emit(event)
                    true
                }
            }.collect { }
            val failed = failure ?: return@flow
            lastFailure = failed
            val kind = fallbackFailureKind(failed, cause)
            if (output || kind == FallbackFailureKind.TERMINAL) {
                emit(failed.copy(retryable = false))
                return@flow
            }
            val policy = consent.policy()?.takeIf { it.enabled && it.valid() }
            if (policy == null) { emit(failed.copy(retryable = false)); return@flow }
            if (kind == FallbackFailureKind.TRANSIENT && attempts < policy.retries) {
                attempts++
                val wait = RetryPolicy.delayMs(attempts)
                emit(StreamEvent.Retrying(attempts, wait, "${current.name}: ${failed.message.take(160)}"))
                sleep(wait)
                continue
            }
            val currentIndex = if (managedChanged) policy.targets.indexOf(FallbackTarget(current.id, current.model)) else -1
            var next: ProviderConfig? = null
            for (target in policy.targets.drop(currentIndex + 1)) {
                if (target in tried) continue
                val livePolicy = consent.policy()?.takeIf { it.enabled && it.valid() } ?: break
                if (target !in livePolicy.targets) continue
                val candidate = resolve(target) ?: continue
                if (!allowed(candidate, livePolicy)) continue
                if (keyFor(candidate).isBlank()) continue
                next = candidate
                break
            }
            val livePolicy = consent.policy()?.takeIf { it.enabled && it.valid() }
            if (next == null || livePolicy == null || FallbackTarget(next.id, next.model) !in livePolicy.targets ||
                !allowed(next, livePolicy)) {
                emit(failed.copy(message = "${current.name}: ${failed.message}\nNo allowed fallback model could continue. Review Fallbacks in the model picker.", retryable = false))
                return@flow
            }
            current = next
            managedChanged = true
            lastFailure = null
            tried += FallbackTarget(current.id, current.model)
            attempts = 0
            val reason = if (kind == FallbackFailureKind.QUOTA) "Quota exhausted" else "Temporary failure after retries"
            emit(StreamEvent.ProviderChanged(current, "$reason · switched to ${current.name} · ${current.model}", persistSelection = false))
        }
    }
}

private fun Flow<StreamEvent>.flattenEvents(): Flow<StreamEvent> = transform { event ->
    suspend fun send(value: StreamEvent) {
        if (value is StreamEvent.Batch) value.events.forEach { send(it) } else emit(value)
    }
    send(event)
}

internal fun StreamEvent.hasResponseOutput(): Boolean = when (this) {
    is StreamEvent.TextDelta -> text.isNotEmpty()
    is StreamEvent.ThinkingDelta -> text.isNotEmpty()
    is StreamEvent.ToolCallDelta, is StreamEvent.ToolCallReady, is StreamEvent.ToolCallBatch,
    is StreamEvent.Usage, is StreamEvent.Done -> true
    is StreamEvent.Batch -> events.any { it.hasResponseOutput() }
    else -> false
}
