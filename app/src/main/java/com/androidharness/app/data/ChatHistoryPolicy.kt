package com.androidharness.app.data

/** Directional cross-mode permission; existing personalization and private-agent boundaries still apply. */
object ChatHistoryPolicy {
    fun canRead(from: ConversationConfig, target: ConversationConfig, settings: AppSettings): Boolean =
        from.personalizationEnabled && ChatAgentPolicy.canReadHistory(from, target) &&
            (from.kind == target.kind || if (from.isChat) settings.chatSearchCodeChats else settings.codeSearchChatChats)
}
