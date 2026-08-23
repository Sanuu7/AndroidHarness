package com.androidharness.app.agent

/** Reasoning effort levels, mapped per-provider (reasoning_effort / thinking budget). */
enum class ThinkingLevel(val label: String) {
    OFF("Off"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    MAX("Max"),
    ;

    /** Budget in tokens for providers that take an explicit thinking budget. */
    fun budgetTokens(maxOutputTokens: Int): Int = when (this) {
        OFF -> 0
        LOW -> 1_024
        MEDIUM -> 4_096
        HIGH -> 16_384
        MAX -> 32_768
    }.coerceAtMost((maxOutputTokens - 4_096).coerceAtLeast(0))

    /** OpenAI-compatible reasoning_effort string, or null to omit. */
    val reasoningEffort: String?
        get() = when (this) {
            OFF -> null
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH, MAX -> "high"
        }
}
