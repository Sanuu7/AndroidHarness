package com.androidharness.app.data.env

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Present while an agent runs a tool. When "Ask before controlling the phone"
 * is on, [DeviceAccessManager] uses [ask] to show one approval card per chat
 * before the agent reaches Shizuku, root or Device Owner. The user's own
 * terminal and app features never carry a gate, so they are never asked.
 */
class PhoneControlGate(
    val sessionId: String,
    /** One run of the agent; a refusal holds for the rest of it. */
    val turnId: String,
    val ask: suspend (backend: DeviceBackend, command: String?, risk: PhoneCommandRisk) -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PhoneControlGate>
}

/** Plain-language summary of what a privileged command will do, for the approval card. */
object PhoneControlPreview {
    fun describe(backend: DeviceBackend, command: String?, risk: PhoneCommandRisk = PhoneCommandRisk.CONTROL): String {
        val via = when (backend) {
            DeviceBackend.ROOT -> "root"
            DeviceBackend.DHIZUKU -> "Device Owner (Dhizuku)"
            else -> "Shizuku"
        }
        val action = command?.let(::actionOf)
        if (risk == PhoneCommandRisk.DESTRUCTIVE) return buildString {
            append("The agent wants to use $via to ")
            append(action ?: "run a command that can't easily be undone")
            append(".\n\nThis can remove apps, erase data or restart the phone. Allowing it runs only this command; ")
            append("the agent asks again before the next one like it.")
            command?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n\nCommand:\n").append(it.take(400)) }
        }
        return buildString {
            append("The agent wants to use $via to work on your phone")
            append(action?.let { ": $it." } ?: ".")
            append("\n\nIf you allow it, the agent can read and change phone files, install apps, ")
            append("tap, type and swipe on screen, and change settings for the rest of this chat without asking again. ")
            append("Uninstalling apps, wiping data and restarting the phone still ask every time.")
            command?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n\nFirst command:\n").append(it.take(400)) }
        }
    }

    private fun actionOf(command: String): String? {
        val words = command.trim().split(Regex("\\s+")).map { it.substringAfterLast('/') }
        fun after(vararg w: String) = words.windowed(w.size + 1).firstOrNull { it.take(w.size) == w.toList() }?.last()
        return when {
            "input" in words -> when (after("input")) {
                "tap" -> "tap the screen"
                "swipe" -> "swipe on the screen"
                "text" -> "type text"
                "keyevent" -> "press a key"
                else -> "send touch or key input"
            }
            "uiautomator" in words -> "read what is on screen"
            "monkey" in words -> "open an app"
            "getprop" in words -> "read device state"
            "screencap" in words || "screenrecord" in words -> "capture the screen"
            words.contains("pm") || after("cmd") == "package" -> when (after("pm") ?: after("package")) {
                "install", "install-create", "install-existing" -> "install an app"
                "uninstall" -> "uninstall an app"
                "clear" -> "clear an app's data"
                "grant", "revoke" -> "change an app's permissions"
                "disable", "disable-user", "enable" -> "turn an app on or off"
                "list" -> "list installed apps"
                else -> "manage apps"
            }
            words.contains("am") || after("cmd") == "activity" -> when (after("am") ?: after("activity")) {
                "start", "start-activity" -> "open an app"
                "force-stop", "kill" -> "stop an app"
                "broadcast" -> "send a system broadcast"
                else -> "control running apps"
            }
            "settings" in words -> if (after("settings") == "put") "change a system setting" else "read system settings"
            "reboot" in words -> "restart the phone"
            "rm" in words || "rmdir" in words -> "delete files"
            command.contains("FACTORY_RESET") || command.contains("MASTER_CLEAR") -> "factory reset the phone"
            "svc" in words -> "change Wi-Fi, data or power settings"
            "dumpsys" in words -> "read system state"
            "logcat" in words -> "read system logs"
            "/sdcard" in command || "/storage/" in command || "/data/" in command -> "work with phone files"
            else -> null
        }
    }
}
