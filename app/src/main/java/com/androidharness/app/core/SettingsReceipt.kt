package com.androidharness.app.core

import kotlinx.serialization.Serializable

/** What an app_settings_change call did, shown as a card under the turn. */
@Serializable
data class SettingsReceipt(
    val changes: List<SettingChange> = emptyList(),
    /** Things only the user can do, each with the settings page that does it. */
    val needsUser: List<SettingTodo> = emptyList(),
    /** Changes that were refused, with the reason, so the card can be honest about them. */
    val skipped: List<SettingTodo> = emptyList(),
)

@Serializable
data class SettingChange(val key: String, val before: String, val after: String, val label: String? = null)

/** [page] is a settings page name; [key] optionally names one setting on it. */
@Serializable
data class SettingTodo(val page: String, val note: String, val key: String? = null)
