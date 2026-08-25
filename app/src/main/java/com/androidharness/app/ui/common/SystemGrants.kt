package com.androidharness.app.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime permission / system-toggle checks and request intents shared by the
 * first-run setup screen and Settings.
 */
object SystemGrants {

    fun isPostNotificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    /** Fires the direct exemption dialog, falling back to the settings list. */
    fun requestBatteryExemption(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
        }
    }

    /**
     * Opens the OS "All files access" page for this app. Samsung/OEM builds often
     * ignore the plain ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION + package URI, so
     * we walk a few known intents and fall back to the app details screen.
     */
    fun openAllFilesAccess(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
        }
    }
}
