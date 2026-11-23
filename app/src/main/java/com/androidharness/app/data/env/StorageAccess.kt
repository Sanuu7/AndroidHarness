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

    /**
     * The everyday storage dialog. Up to Android 10 it is the same read and write pair as full
     * access. Android 11 and 12 can still ask to read shared storage, and Android 13 split that
     * into photos and videos plus music, with partial photo access on 14 and later.
     */
    fun mediaPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdkInt < 30 -> runtimePermissions(sdkInt)
        sdkInt < 33 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        sdkInt < 34 -> arrayOf(MEDIA_IMAGES, MEDIA_VIDEO, MEDIA_AUDIO)
        else -> arrayOf(MEDIA_IMAGES, MEDIA_VIDEO, MEDIA_AUDIO, MEDIA_USER_SELECTED)
    }

    fun isMediaGranted(context: Context): Boolean = isMediaGranted(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Before Android 11 both permissions are needed; after that any part of the media grant counts. */
    internal fun isMediaGranted(sdkInt: Int, permissionGranted: (String) -> Boolean): Boolean =
        if (sdkInt < 30) runtimePermissions(sdkInt).all(permissionGranted)
        else mediaPermissions(sdkInt).any(permissionGranted)

    // Spelled out so the checks compile against any SDK level and stay readable in tests.
    internal const val MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    internal const val MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    internal const val MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    internal const val MEDIA_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
}
