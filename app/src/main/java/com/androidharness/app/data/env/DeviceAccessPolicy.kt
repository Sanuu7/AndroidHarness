package com.androidharness.app.data.env

import kotlinx.serialization.Serializable

@Serializable
enum class AccessMode(val label: String) {
    AUTO("Auto"), NORMAL("Normal"), SHIZUKU("Shizuku"), ROOT("Root"),
    /** Keeps both privileged transports connected: Shizuku for ordinary work, root for root requests. */
    BOTH("Both"),
}
@Serializable
enum class RootUse(val label: String) { SESSION("App session"), CHAT("Current chat"), NEXT_ACTION("Next action") }
@Serializable
enum class DeviceBackend { NORMAL, SHIZUKU, ROOT, DHIZUKU }

@Serializable
data class ExecutionReceipt(
    val backend: DeviceBackend,
    val uid: Int? = null,
    val fallbackReason: String? = null,
    /** Concrete transport when more than one provides [backend], for example Wireless ADB. */
    val transport: String? = null,
) {
    // The badge names the transport. Keep the verified UID separately for diagnostics.
    val label: String get() = transport ?: when (backend) {
        DeviceBackend.NORMAL -> "Normal"
        DeviceBackend.ROOT -> "Root"
        DeviceBackend.DHIZUKU -> "Dhizuku"
        DeviceBackend.SHIZUKU -> "Shizuku"
    }
}

data class AccessPreferences(
    val mode: AccessMode = AccessMode.AUTO,
    val autoFallback: Boolean = true,
    val rootUse: RootUse = RootUse.SESSION,
    val rootProjects: Set<String> = emptySet(),
    /** When false, an active root connection may be used everywhere without a per-session grant. */
    val temporaryRoot: Boolean = true,
    /** When true, app-owned workspaces follow [mode] instead of always running as the app user. */
    val appWorkspacePrivileged: Boolean = false,
)

data class AccessDecision(val backend: DeviceBackend?, val reason: String? = null, val uid: Int? = null)

/** Pure selection rules; readiness is verified by the executor immediately before dispatch. */
object DeviceAccessPolicy {
    /** Unlimited root still needs the connection and the paused gate; it skips only the per-use limits. */
    fun rootPermitted(temporaryMode: Boolean, manual: Boolean, projectAllowed: Boolean, allowanceActive: Boolean): Boolean =
        !temporaryMode || ((manual || projectAllowed) && allowanceActive)

    fun choose(
        mode: AccessMode, fallback: Boolean, rootReady: Boolean, shizukuUid: Int?,
        rootAllowed: Boolean, paused: Boolean = false, appWorkspace: Boolean = false,
        requireRoot: Boolean = false, requireShizuku: Boolean = false,
    ): AccessDecision {
        if (requireShizuku) return when {
            requireRoot -> AccessDecision(null, "Choose either Shizuku or direct root for this command")
            mode == AccessMode.NORMAL -> AccessDecision(null, "Shizuku is disabled in Normal mode. Select Shizuku, Both or Auto in device access settings.")
            paused -> AccessDecision(null, "Privileged access is paused")
            shizukuUid == null -> AccessDecision(null, "Shizuku is unavailable; reconnect it before retrying. This command was not run as another user.")
            else -> AccessDecision(DeviceBackend.SHIZUKU, uid = shizukuUid)
        }
        if (appWorkspace && !requireRoot) return AccessDecision(DeviceBackend.NORMAL)
        val order = when (mode) {
            AccessMode.NORMAL -> listOf(DeviceBackend.NORMAL)
            AccessMode.SHIZUKU -> if (fallback) listOf(DeviceBackend.SHIZUKU, DeviceBackend.NORMAL) else listOf(DeviceBackend.SHIZUKU)
            AccessMode.ROOT -> if (fallback) listOf(DeviceBackend.ROOT, DeviceBackend.SHIZUKU, DeviceBackend.NORMAL) else listOf(DeviceBackend.ROOT)
            AccessMode.AUTO -> buildList {
                if (rootAllowed || requireRoot) add(DeviceBackend.ROOT)
                add(DeviceBackend.SHIZUKU)
                add(DeviceBackend.NORMAL)
            }
            // Normal commands stay on the ADB shell while both connections are
            // live; explicit root requests still use the direct root service.
            AccessMode.BOTH -> when {
                requireRoot -> listOf(DeviceBackend.ROOT, DeviceBackend.SHIZUKU)
                fallback -> listOf(DeviceBackend.SHIZUKU, DeviceBackend.ROOT, DeviceBackend.NORMAL)
                else -> listOf(DeviceBackend.SHIZUKU, DeviceBackend.ROOT)
            }
        }
        val unavailable = mutableListOf<String>()
        for (backend in order) {
            val uid = when (backend) { DeviceBackend.DHIZUKU -> null; DeviceBackend.NORMAL -> null; DeviceBackend.ROOT -> if (rootReady) 0 else null; DeviceBackend.SHIZUKU -> shizukuUid }
            val issue = when {
                backend != DeviceBackend.NORMAL && paused -> "Privileged access is paused"
                backend == DeviceBackend.ROOT && !rootAllowed -> "Root is not allowed for this project or its temporary allowance has ended"
                backend == DeviceBackend.ROOT && !rootReady -> "Root is unavailable"
                backend == DeviceBackend.SHIZUKU && shizukuUid == null -> "Shizuku is unavailable"
                // Shizuku authorization belongs to its own connection, including
                // root-started servers. Explicit root actions still need a grant.
                requireRoot && !rootAllowed -> "Root is not allowed for this project or its temporary allowance has ended"
                requireRoot && uid != 0 -> "This action requires root"
                else -> null
            }
            if (issue == null) return AccessDecision(backend, unavailable.distinct().joinToString("; ").ifBlank { null }, uid)
            unavailable += issue
        }
        return AccessDecision(null, unavailable.distinct().joinToString("; "))
    }
}

/** A lease belongs to one tool action, including its internal shell calls. */
internal class RootLease { internal var generation = -1L }

/** Temporary grants are deliberately memory-only and invalidated on revoke/regrant. */
internal class TemporaryRootAllowance {
    private var generation = 0L
    private var use: RootUse? = null
    private var chat: String? = null
    private var next = false

    @Synchronized fun grant(scope: RootUse, sessionId: String?) {
        require(scope != RootUse.CHAT || sessionId != null)
        generation++; use = scope; chat = sessionId; next = true
    }
    @Synchronized fun revoke() { generation++; use = null; next = false }
    @Synchronized fun allows(sessionId: String?, lease: RootLease): Boolean =
        when (use) {
            RootUse.SESSION -> true
            RootUse.CHAT -> chat != null && chat == sessionId
            RootUse.NEXT_ACTION -> next || lease.generation == generation
            null -> false
        }
    /** UI-facing check without a tool lease. */
    @Synchronized fun activeFor(sessionId: String?): Boolean =
        when (use) {
            RootUse.SESSION -> true
            RootUse.CHAT -> chat != null && chat == sessionId
            RootUse.NEXT_ACTION -> next
            null -> false
        }
    @Synchronized fun claim(sessionId: String?, lease: RootLease): Boolean {
        if (!allows(sessionId, lease)) return false
        if (use == RootUse.NEXT_ACTION) next = false
        lease.generation = generation
        return true
    }
}
