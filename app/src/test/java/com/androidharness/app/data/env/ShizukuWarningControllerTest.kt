package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuWarningControllerTest {
    private class Preferences {
        var enabled = false
        var suppressed = false
        var enabledWrites = 0
        fun controller() = ShizukuWarningController(
            enabled, suppressed,
            saveEnabled = { enabled = true; enabledWrites++ },
            saveSuppressed = { suppressed = true },
        )
    }

    @Test
    fun neverEnabledUsersAreNotWarned() {
        val controller = Preferences().controller()
        for (state in ShizukuState.entries.filter { it != ShizukuState.GRANTED }) {
            controller.checkWorkspace(state)
            assertFalse(controller.visible.value)
        }
    }

    @Test
    fun authorizationIsRememberedAcrossControllerRecreation() {
        val preferences = Preferences()
        preferences.controller().onStatus(ShizukuState.GRANTED)
        val afterRestart = preferences.controller()
        afterRestart.checkWorkspace(ShizukuState.NOT_RUNNING)
        assertTrue(afterRestart.visible.value)
    }

    @Test
    fun serverDeathAndRevokedAccessAreBothWarned() {
        val preferences = Preferences().apply { enabled = true }
        for (state in ShizukuState.entries.filter { it != ShizukuState.GRANTED }) {
            val controller = preferences.controller()
            controller.checkWorkspace(state)
            assertTrue("Missing state $state", controller.visible.value)
        }
    }

    @Test
    fun statusChangeAloneDoesNotInterruptOtherScreens() {
        val controller = Preferences().apply { enabled = true }.controller()
        controller.onStatus(ShizukuState.NOT_RUNNING)
        assertFalse(controller.visible.value)
    }

    @Test
    fun recoveryClosesWarningAndOnlyRecordsAuthorizationOnce() {
        val preferences = Preferences()
        val controller = preferences.controller()
        controller.onStatus(ShizukuState.GRANTED)
        controller.checkWorkspace(ShizukuState.NOT_RUNNING)
        controller.onStatus(ShizukuState.GRANTED)
        controller.checkWorkspace(ShizukuState.GRANTED)
        assertFalse(controller.visible.value)
        assertEquals(1, preferences.enabledWrites)
    }

    @Test
    fun normalDismissalAllowsWarningOnNextWorkspaceEntry() {
        val controller = Preferences().apply { enabled = true }.controller()
        controller.checkWorkspace(ShizukuState.NOT_RUNNING)
        controller.dismiss(neverShowAgain = false)
        assertFalse(controller.visible.value)
        controller.checkWorkspace(ShizukuState.NOT_RUNNING)
        assertTrue(controller.visible.value)
    }

    @Test
    fun permanentOptOutSurvivesRestartAndAnotherServerDeath() {
        val preferences = Preferences().apply { enabled = true }
        preferences.controller().apply {
            checkWorkspace(ShizukuState.NOT_RUNNING)
            dismiss(neverShowAgain = true)
        }
        val afterRestart = preferences.controller()
        afterRestart.onStatus(ShizukuState.GRANTED)
        afterRestart.checkWorkspace(ShizukuState.NOT_RUNNING)
        assertFalse(afterRestart.visible.value)
        assertTrue(preferences.suppressed)
    }

    @Test
    fun duplicateWorkspaceRequestsShareOneWarning() {
        val controller = Preferences().apply { enabled = true }.controller()
        repeat(3) { controller.checkWorkspace(ShizukuState.NOT_RUNNING) }
        controller.dismiss(neverShowAgain = false)
        assertFalse(controller.visible.value)
    }
}
