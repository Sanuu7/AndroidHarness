package com.androidharness.app.ui.whatsnew

import android.content.Context

/** Shown once per install after a store update, then never again. */
internal object WhatsNewStore {
    private const val NAME = "whats-new"
    private const val KEY = "seen"

    fun seen(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markSeen(context: Context) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
    }
}
