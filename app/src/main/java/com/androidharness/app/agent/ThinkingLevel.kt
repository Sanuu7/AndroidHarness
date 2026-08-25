package com.androidharness.app.agent

/** Reasoning effort levels, mapped per-provider (reasoning_effort / thinking budget). */
enum class ThinkingLevel(val label: String) {
    OFF("Off"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    XHIGH("X-High"),
    MAX("Max"),
    ;

    /** Budget in tokens for providers that take an explicit thinking budget. */
    fun budgetTokens(maxOutputTokens: Int): Int = when (this) {
        OFF -> 0
        LOW -> 1_024
        MEDIUM -> 4_096
        HIGH -> 16_384
        XHIGH -> 24_576
        MAX -> 32_768
    }.coerceAtMost((maxOutputTokens - 4_096).coerceAtLeast(0))

    /**
     * OpenAI-compatible reasoning_effort string, or null to omit. X-High and
     * Max both ask for the highest tier; whether the endpoint accepts the
     * extended "xhigh" value is decided at request time from the model id
     * (see OpenAiCompatProvider.supportsExtendedEffort).
     */
    val reasoningEffort: String?
        get() = when (this) {
            OFF -> null
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            XHIGH, MAX -> "xhigh"
        }
}
