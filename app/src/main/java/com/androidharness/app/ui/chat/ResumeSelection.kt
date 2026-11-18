package com.androidharness.app.ui.chat

import com.androidharness.app.llm.ProviderConfig

/** Decides whether Resume task may keep a paused run's saved provider. */
internal object ResumeSelection {
    /**
     * True when the chat's [chosen] provider and model are the same as [saved].
     * Harness models resolve to a concrete model before a run, so the resolved
     * id counts as the same choice too.
     */
    fun matches(chosen: ProviderConfig, resolvedModel: String?, saved: ProviderConfig?): Boolean =
        saved != null && saved.id == chosen.id && (saved.model == chosen.model || saved.model == resolvedModel)
}
