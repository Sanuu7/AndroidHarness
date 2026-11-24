package com.androidharness.app.assistant

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.Process

/** What the phone says about letting AndroidHarness be its digital assistant. */
data class AssistantFacts(
    val active: Boolean,
    /** Android starts its voice assistant service only when the phone declares this feature. */
    val voiceFeature: Boolean,
    /** Whether the assistant role can be held on this phone; null before Android 10, which has no roles. */
    val roleAvailable: Boolean?,
    /** False inside a work profile, Secure Folder, Private Space or a second space. */
    val mainProfile: Boolean,
    val lowRam: Boolean,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val release: String,
    val sdk: Int,
)

enum class AssistantAvailability {
    ACTIVE,
    /** The phone does not let other apps be the assistant. */
    UNSUPPORTED,
    /** Installed outside the main profile, where no app can be the assistant. */
    OTHER_PROFILE,
    /** Supported and not picked yet, on a Samsung phone whose path we know. */
    PICK_SAMSUNG,
    /** Supported and not picked yet, anywhere else. */
    PICK,
}

object AssistantCheck {
    /** Android gives each user or profile its own block of app ids; the main one is user 0. */
    private const val PER_USER_RANGE = 100_000
    /** PackageManager.FEATURE_VOICE_RECOGNIZERS, which is hidden from apps. */
    private const val VOICE_RECOGNIZERS_FEATURE = "android.software.voice_recognizers"

    fun facts(context: Context, active: Boolean): AssistantFacts {
        val pm = context.packageManager
        return AssistantFacts(
            active = active,
            voiceFeature = pm.hasSystemFeature(VOICE_RECOGNIZERS_FEATURE),
            roleAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
                context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
            }.getOrNull() else null,
            mainProfile = Process.myUid() / PER_USER_RANGE == 0,
            lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            release = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
        )
    }

    fun availability(facts: AssistantFacts): AssistantAvailability = when {
        facts.active -> AssistantAvailability.ACTIVE
        // A profile cannot hold the role either, so this explains more than "unsupported" would.
        !facts.mainProfile -> AssistantAvailability.OTHER_PROFILE
        !facts.voiceFeature || facts.roleAvailable == false -> AssistantAvailability.UNSUPPORTED
        facts.manufacturer.equals("samsung", ignoreCase = true) -> AssistantAvailability.PICK_SAMSUNG
        else -> AssistantAvailability.PICK
    }

    /** Plain English on purpose: it is pasted into bug reports. */
    fun report(facts: AssistantFacts, appVersion: String): String = buildString {
        fun yesNo(value: Boolean) = if (value) "yes" else "no"
        appendLine("AndroidHarness assistant check")
        appendLine("App version: $appVersion")
        appendLine("Phone: ${facts.manufacturer} ${facts.model} (brand ${facts.brand})")
        appendLine("Android: ${facts.release} (API ${facts.sdk})")
        appendLine("Result: ${availability(facts).name.lowercase()}")
        appendLine("Set as assistant: ${yesNo(facts.active)}")
        appendLine("Voice assistant feature: ${yesNo(facts.voiceFeature)}")
        appendLine("Assistant role available: ${facts.roleAvailable?.let(::yesNo) ?: "not on this Android version"}")
        appendLine("Main profile: ${yesNo(facts.mainProfile)}")
        append("Low RAM phone: ${yesNo(facts.lowRam)}")
    }
}
