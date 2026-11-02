package com.androidharness.app.data.env

/** A read-only preview. Only dispatch verifies identity and consumes a root allowance. */
internal data class AgentDeviceEnvironment(
    val preferences: AccessPreferences,
    val rootReady: Boolean,
    val rootState: String,
    val rootAllowed: Boolean,
    val shizukuState: ShizukuState,
    val shizukuService: UserServiceState,
    val shizukuReady: Boolean,
    val shizukuUid: Int?,
    val paused: Boolean,
    /** A wireless ADB pairing exists, even when no shell is connected right now. */
    val wirelessPaired: Boolean = false,
    /** The built-in wireless ADB shell runner is live. */
    val wirelessConnected: Boolean = false,
    /** The live privileged route is the built-in ADB shell rather than the Shizuku app. */
    val wirelessActive: Boolean = false,
) {
    /**
     * [appWorkspace] is true when ordinary commands here are pinned to the app
     * user; [appOwned] says whether the folder belongs to the app at all.
     */
    fun describe(appWorkspace: Boolean, linuxReady: Boolean, appOwned: Boolean = appWorkspace): String = buildString {
        val shizukuPresent = shizukuState != ShizukuState.NOT_INSTALLED || shizukuService != UserServiceState.NOT_BOUND
        val wirelessPresent = wirelessPaired || wirelessConnected
        append("Access mode: ${preferences.mode.label}; fallback: ${preferences.mode == AccessMode.AUTO || preferences.autoFallback}; privileged commands: ${if (paused) "paused" else "enabled"}.\n")
        append("Root allowance policy: ${if (preferences.temporaryRoot) "temporary, ${preferences.rootUse.label}" else "unlimited while connected and unpaused"}.\n")
        append("Direct root: $rootState; connected UID 0 runner: $rootReady; root allowed for this project/chat: $rootAllowed.\n")
        if (shizukuPresent) {
            append("Shizuku: ${shizukuState.name}; runner: ${shizukuService.name}; callable: $shizukuReady; last verified UID: ${shizukuUid ?: "not checked"}.\n")
        }
        if (wirelessPresent) {
            append("Wireless ADB: built into this app, no Shizuku app required; ")
            append(if (wirelessConnected) "connected" else "paired but not connected (it connects on the first privileged command)")
            append("; UID: ${shizukuUid ?: "checked before the first command"}.\n")
            append("The Wireless ADB shell is this app's own ADB transport and uses the same privileged route as Shizuku. ")
            append("Commands run as the ADB shell user (normally UID 2000), never as root and never as the app UID. ")
            append("Use shell access=\"shizuku\" to pin Android system commands to it; /system/bin/am, pm, cmd, settings, dumpsys and appops work there, su and sudo do not. ")
            append("The user must keep Android's Wireless debugging toggle on; pairing survives a reboot, the toggle does not. Settings > Coding > Terminal > Wireless ADB shows the live state and can reconnect or forget the pairing.\n")
        }
        val decision = DeviceAccessPolicy.choose(preferences.mode, preferences.autoFallback,
            rootReady, shizukuUid.takeIf { shizukuReady }, rootAllowed, paused, appWorkspace)
        val awaitingIdentity = !appWorkspace && shizukuReady && shizukuUid == null && !paused &&
            preferences.mode != AccessMode.NORMAL && (preferences.mode == AccessMode.BOTH || preferences.mode == AccessMode.SHIZUKU || decision.backend != DeviceBackend.ROOT)
        append("Default command route: ")
        append(if (awaitingIdentity) {
            if (wirelessPresent && !shizukuService.isReady) "pending Wireless ADB UID verification at dispatch"
            else "pending Shizuku UID verification at dispatch"
        } else when (decision.backend) {
            DeviceBackend.NORMAL -> if (linuxReady) "app UID with the installed Linux userspace" else "app UID with Android toybox sh; Linux packages are not ready"
            DeviceBackend.ROOT -> "direct root, UID 0"
            DeviceBackend.DHIZUKU -> "Dhizuku Device Owner app"
            DeviceBackend.SHIZUKU -> if (wirelessActive) "built-in Wireless ADB shell, UID ${decision.uid}" else "Shizuku, UID ${decision.uid}"
            null -> "unavailable: ${decision.reason}"
        })
        append(". This is a snapshot, not an execution receipt; identity and permission are rechecked before each action.\n")
        if (appWorkspace) append("The app-owned workspace uses the app UID for ordinary development even when root, Shizuku or Wireless ADB is connected. The user can change this with Settings > Environment > Advanced device access > Privileged shell in app workspace.\n")
        else if (appOwned) append("The user turned on Privileged shell in app workspace, so ordinary commands in this app-owned workspace follow the access mode above instead of the app UID.\n")
        append("Use shell access=\"shizuku\" to pin Android system commands to the privileged shell (Shizuku or built-in Wireless ADB); it never falls back to root or the app UID. Use access=\"root\" only for a task that needs UID 0 and an allowed root grant. Never invoke su/sudo to bypass access routing. A root-started Shizuku server can itself have UID 0; that does not grant direct-root permission.\n")
        if (!rootReady && !shizukuReady) append("Neither privileged runner is ready. Ordinary app-UID work remains available where Android storage permissions permit; privilege is not required for every coding task.\n")
    }
}
