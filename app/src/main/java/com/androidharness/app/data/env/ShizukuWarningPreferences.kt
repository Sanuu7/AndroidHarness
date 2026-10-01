package com.androidharness.app.data.env

import android.content.SharedPreferences

/** Device-local notice preferences; prior authorization is not portable. */
internal fun shizukuWarningController(preferences: SharedPreferences): ShizukuWarningController =
    ShizukuWarningController(
        previouslyEnabled = preferences.getBoolean("previously_enabled", false),
        suppressed = preferences.getBoolean("never_show_again", false),
        saveEnabled = { preferences.edit().putBoolean("previously_enabled", true).apply() },
        saveSuppressed = { preferences.edit().putBoolean("never_show_again", true).apply() },
    )
