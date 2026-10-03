package com.androidharness.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidharness.app.data.env.StorageAccess

data class StorageAccessState(
    val granted: Boolean,
    val request: () -> Unit,
    val refresh: () -> Unit,
)

/** Shared by setup, Settings and the folder browser; refreshes on return from OS settings. */
@Composable
fun rememberStorageAccess(): StorageAccessState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember(context) { mutableStateOf(StorageAccess.isGranted(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val refresh = { granted = StorageAccess.isGranted(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return StorageAccessState(granted, request = {
        if (Build.VERSION.SDK_INT >= 30 || StorageAccess.isGranted(context)) {
            SystemGrants.openAllFilesAccess(context)
        } else {
            val activity = context.storageActivity()
            val missing = StorageAccess.runtimePermissions().filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty() || requested && activity != null && missing.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }) {
                // A permanently denied permission cannot show another runtime dialog.
                SystemGrants.openAllFilesAccess(context)
            } else {
                requested = true
                launcher.launch(StorageAccess.runtimePermissions())
            }
        }
    }, refresh = refresh)
}

private tailrec fun Context.storageActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.storageActivity()
    else -> null
}
