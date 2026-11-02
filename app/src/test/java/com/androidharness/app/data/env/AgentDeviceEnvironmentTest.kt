package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Test

class AgentDeviceEnvironmentTest {
    private fun state(mode: AccessMode = AccessMode.AUTO, root: Boolean = false,
        allowed: Boolean = false, shizuku: Boolean = false, uid: Int? = null,
        paused: Boolean = false, fallback: Boolean = true, wirelessPaired: Boolean = false,
        wirelessConnected: Boolean = false, wirelessActive: Boolean = false) = AgentDeviceEnvironment(
        AccessPreferences(mode = mode, autoFallback = fallback), root,
        if (root) "Ready" else "Not connected", allowed,
        if (shizuku) ShizukuState.GRANTED else ShizukuState.NOT_INSTALLED,
        if (shizuku) UserServiceState.BOUND_READY else UserServiceState.NOT_BOUND,
        shizukuReady = shizuku || wirelessConnected || wirelessPaired, shizukuUid = uid, paused = paused,
        wirelessPaired = wirelessPaired, wirelessConnected = wirelessConnected, wirelessActive = wirelessActive)

    @Test fun neitherRunnerStillSupportsNormalWork() {
        val text = state().describe(false, false)
        assertTrue(text.contains("Neither privileged runner is ready"))
        assertTrue(text.contains("Default command route: app UID with Android toybox"))
        assertTrue(state().describe(false, true).contains("Default command route: app UID with the installed Linux"))
    }

    @Test fun permissionAloneDoesNotClaimACallableShizukuRunner() {
        val text = state().copy(shizukuState = ShizukuState.GRANTED,
            shizukuService = UserServiceState.BINDING, shizukuUid = 2000).describe(false, true)
        assertTrue(text.contains("Shizuku: GRANTED; runner: BINDING; callable: false"))
        assertTrue(text.contains("Default command route: app UID"))
    }

    @Test fun connectedRootStillNeedsItsOwnAllowance() {
        val denied = state(root = true).describe(false, true)
        assertTrue(denied.contains("connected UID 0 runner: true; root allowed for this project/chat: false"))
        assertTrue(denied.contains("Default command route: app UID"))
        assertTrue(state(root = true, allowed = true).describe(false, true).contains("Default command route: direct root, UID 0"))
    }

    @Test fun autoAndBothFollowTheExecutorOrdering() {
        for (mode in AccessMode.entries) for (root in listOf(false, true))
            for (allowed in listOf(false, true)) for (uid in listOf(null, 0, 2000))
                for (paused in listOf(false, true)) for (fallback in listOf(false, true)) {
                    val snapshot = state(mode, root, allowed, uid != null, uid, paused, fallback)
                    val decision = DeviceAccessPolicy.choose(mode, fallback, root, uid, allowed, paused)
                    val expected = when (decision.backend) {
                        DeviceBackend.DHIZUKU -> error("Device Owner is not a shell fallback")
                        DeviceBackend.NORMAL -> "app UID"
                        DeviceBackend.SHIZUKU -> "Shizuku, UID $uid"
                        DeviceBackend.ROOT -> "direct root, UID 0"
                        null -> "unavailable: ${decision.reason}"
                    }
                    val text = snapshot.describe(false, true)
                    assertTrue("$snapshot -> $decision\n$text", text.contains("Default command route: $expected"))
                }
    }

    @Test fun unknownShizukuIdentityIsNotInventedOrReportedAsFallback() {
        for (mode in listOf(AccessMode.AUTO, AccessMode.SHIZUKU, AccessMode.BOTH)) {
            val text = state(mode, shizuku = true).describe(false, true)
            assertTrue(text.contains("last verified UID: not checked"))
            assertTrue(text.contains("Default command route: pending Shizuku UID verification"))
        }
        assertTrue(state(AccessMode.BOTH, root = true, allowed = true, shizuku = true)
            .describe(false, true).contains("Default command route: pending Shizuku UID verification"))
        assertTrue(state(root = true, allowed = true, shizuku = true)
            .describe(false, true).contains("Default command route: direct root, UID 0"))
    }

    @Test fun appWorkspaceStaysNormalAndPausedConnectionsDoNotBecomePrivileges() {
        for (mode in AccessMode.entries) {
            val text = state(mode, true, true, true, 0).describe(true, true)
            assertTrue(text.contains("Default command route: app UID"))
            assertTrue(text.contains("app-owned workspace uses the app UID"))
        }
        val paused = state(AccessMode.BOTH, true, true, true, 2000, paused = true, fallback = false).describe(false, true)
        assertTrue(paused.contains("privileged commands: paused"))
        assertTrue(paused.contains("Default command route: unavailable"))
    }

    @Test fun unpinnedAppWorkspaceFollowsTheAccessMode() {
        val text = state(AccessMode.SHIZUKU, uid = 2000, wirelessPaired = true, wirelessConnected = true,
            wirelessActive = true).describe(false, true, appOwned = true)
        assertTrue(text.contains("Default command route: built-in Wireless ADB shell, UID 2000"))
        assertTrue(text.contains("follow the access mode above instead of the app UID"))
        assertFalse(text.contains("app-owned workspace uses the app UID"))
    }

    @Test fun rootStartedShizukuDoesNotInventADirectRootGrant() {
        val text = state(shizuku = true, uid = 0).describe(false, true)
        assertTrue(text.contains("root allowed for this project/chat: false"))
        assertTrue(text.contains("Default command route: Shizuku, UID 0"))
        assertTrue(text.contains("does not grant direct-root permission"))
    }

    @Test fun wirelessAdbIsNamedWithItsUidAndHowToUseIt() {
        val text = state(uid = 2000, wirelessPaired = true, wirelessConnected = true,
            wirelessActive = true).describe(false, true)
        assertTrue(text.contains("Wireless ADB: built into this app"))
        assertTrue(text.contains("connected; UID: 2000"))
        assertTrue(text.contains("Default command route: built-in Wireless ADB shell, UID 2000"))
        assertTrue(text.contains("access=\"shizuku\""))
        assertTrue(text.contains("never as root"))
        assertTrue(text.contains("Settings > Coding > Terminal > Wireless ADB"))
        assertFalse(text.contains("Shizuku: "))
    }

    @Test fun pairedWirelessReconnectsOnDispatchAndNamesItself() {
        val text = state(uid = null, wirelessPaired = true).describe(false, true)
        assertTrue(text.contains("paired but not connected"))
        assertTrue(text.contains("Default command route: pending Wireless ADB UID verification at dispatch"))
        assertFalse(text.contains("pending Shizuku UID verification"))
    }

    @Test fun liveShizukuStaysTheActiveRouteWhenWirelessIsAlsoConnected() {
        val text = state(shizuku = true, uid = 2000, wirelessPaired = true,
            wirelessConnected = true, wirelessActive = false).describe(false, true)
        assertTrue(text.contains("Default command route: Shizuku, UID 2000"))
        assertTrue(text.contains("Wireless ADB: built into this app"))
    }

    @Test fun pausedWirelessShellIsReportedAsUnavailable() {
        val text = state(AccessMode.BOTH, uid = 2000, paused = true, fallback = false,
            wirelessPaired = true, wirelessConnected = true, wirelessActive = true).describe(false, true)
        assertTrue(text.contains("privileged commands: paused"))
        assertTrue(text.contains("Default command route: unavailable"))
    }
}
