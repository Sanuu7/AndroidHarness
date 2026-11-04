package com.androidharness.app.ui.chat.components

import android.content.Context

/** Device-wide placement shared by every Code chat, independent of chat lifetime. */
internal class SubagentBubblePositionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("subagent-bubble-position", Context.MODE_PRIVATE)
    fun read() = SubagentBubblePosition(preferences.getFloat("x", 1f), preferences.getFloat("y", 1f)).normalized()
    fun save(position: SubagentBubblePosition) {
        val safe = position.normalized()
        preferences.edit().putFloat("x", safe.x).putFloat("y", safe.y).apply()
    }
}
