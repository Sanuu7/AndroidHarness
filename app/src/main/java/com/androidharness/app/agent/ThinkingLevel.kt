package com.androidharness.app.agent

/**
 * Canonical reasoning-effort ladder, ordered low→high like Hermes'
 * EFFORT_LADDER. This ONE enum is the vocabulary every surface speaks; what a
 * given provider/model actually accepts is decided by ThinkingSpecs and
 * clamped at request time, never invented per call site.
 */
enum class ThinkingLevel(val label: String) {
    OFF("Off"),
    MINIMAL("Minimal"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    XHIGH("X-High"),
    MAX("Max"),
    ULTRA("Ultra"),
    ;

    /** Position on the global ladder, basis for nearest-weaker clamping. */
    val rank: Int get() = ordinal

    /**
     * Budget in tokens for providers that take an explicit thinking budget.
     * Budget families (Claude/Gemini natively) take every ladder rung;
     * MINIMAL gets a tiny budget, ULTRA rides with MAX.
     */
    fun budgetTokens(maxOutputTokens: Int): Int = when (this) {
        OFF -> 0
        MINIMAL -> 512
        LOW -> 1_024
        MEDIUM -> 4_096
        HIGH -> 16_384
        XHIGH -> 24_576
        MAX, ULTRA -> 32_768
    }.coerceAtMost((maxOutputTokens - 4_096).coerceAtLeast(0))

    /**
     * OpenAI-compatible reasoning_effort string, or null to omit. Top-tier
     * rungs fold onto "xhigh": whether the endpoint accepts the extended
     * value (or prefers a native "max") is decided at request time from the
     * model id (see OpenAiCompatProvider.supportsExtendedEffort).
     */
    val reasoningEffort: String?
        get() = when (this) {
            OFF -> null
            MINIMAL -> "minimal"
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            XHIGH, MAX, ULTRA -> "xhigh"
        }
}
