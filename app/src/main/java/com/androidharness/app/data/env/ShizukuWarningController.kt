package com.androidharness.app.data.env

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remembers prior authorization and the user's permanent opt-out. */
internal class ShizukuWarningController(
    previouslyEnabled: Boolean,
    suppressed: Boolean,
    private val saveEnabled: () -> Unit,
    private val saveSuppressed: () -> Unit,
) {
    private var previouslyEnabled = previouslyEnabled
    private var suppressed = suppressed
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()

    @Synchronized
    fun onStatus(state: ShizukuState) {
        if (state == ShizukuState.GRANTED) {
            if (!previouslyEnabled) {
                previouslyEnabled = true
                saveEnabled()
            }
            _visible.value = false
        }
    }

    @Synchronized
    fun checkWorkspace(state: ShizukuState) {
        onStatus(state)
        _visible.value = previouslyEnabled && !suppressed && state != ShizukuState.GRANTED
    }

    @Synchronized
    fun dismiss(neverShowAgain: Boolean) {
        if (neverShowAgain && !suppressed) {
            suppressed = true
            saveSuppressed()
        }
        _visible.value = false
    }
}
