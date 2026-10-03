package com.androidharness.app.data.env

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/** Broad read/write access to shared storage, using the permission available on the device. */
object StorageAccess {
    fun isGranted(context: Context): Boolean = isGranted(
        sdkInt = Build.VERSION.SDK_INT,
        allFilesAccess = { Environment.isExternalStorageManager() },
        legacyStorage = { Environment.isExternalStorageLegacy() },
        permissionGranted = {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        },
    )

    // Keep platform calls lazy: neither storage API exists on Android 8 or 9.
    internal fun isGranted(
        sdkInt: Int,
        allFilesAccess: () -> Boolean,
        legacyStorage: () -> Boolean,
        permissionGranted: (String) -> Boolean,
    ): Boolean = when {
        sdkInt >= 30 -> allFilesAccess()
        sdkInt == 29 && !legacyStorage() -> false
        else -> runtimePermissions(sdkInt).all(permissionGranted)
    }

    fun runtimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= 30) emptyArray() else arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
}
