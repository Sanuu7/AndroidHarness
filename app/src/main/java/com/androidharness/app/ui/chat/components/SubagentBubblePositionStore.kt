package com.androidharness.app.ui.chat.components

import android.content.Context

/** Device-wide placement shared by every Code chat, independent of chat lifetime. */
internal class SubagentBubblePositionStore(
    context: Context,
    name: String = "subagent-bubble-position",
    private val default: SubagentBubblePosition = SubagentBubblePosition(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun read() = SubagentBubblePosition(preferences.getFloat("x", default.x), preferences.getFloat("y", default.y)).normalized()
    fun save(position: SubagentBubblePosition) {
        val safe = position.normalized()
        preferences.edit().putFloat("x", safe.x).putFloat("y", safe.y).apply()
    }
}
