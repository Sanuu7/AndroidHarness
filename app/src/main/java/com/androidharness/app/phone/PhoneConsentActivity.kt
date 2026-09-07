package com.androidharness.app.phone

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.theme.HarnessTheme

/**
 * Consent flow for phone control, one Material dialog instead of a settings
 * scavenger hunt: explain, overlay permission, then the system capture dialog.
 */
class PhoneConsentActivity : ComponentActivity() {

    private var showOverlayError by mutableStateOf(false)
    private var serviceLaunchRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val container = (application as HarnessApp).container
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android.widget.Toast.makeText(this, "Phone control needs Android 11 or newer.", android.widget.Toast.LENGTH_LONG).show()
            container.phone.notifyConsentFailed()
            finish(); return
        }
        if (container.phone.service != null) {
            container.phone.notifyConsentSuccess()
            finish(); return
        }
        val session = intent.getStringExtra("session")
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            HarnessTheme(
                themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings?.dynamicColor ?: true,
            ) {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    if (showOverlayError) {
                        OverlayErrorDialog(onClose = { finish() })
                    } else {
                        ConsentDialog(
                            onContinue = {
                                if (Settings.canDrawOverlays(this)) requestCapture()
                                else startActivityForResult(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_OVERLAY,
                                )
                            },
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun requestCapture() {
        if (!(application as HarnessApp).container.phone.ready()) {
            showOverlayError = true; return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        startActivityForResult(request, REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestCapture()
                else showOverlayError = true
            }
            requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null && !session.isNullOrBlank() -> {
                serviceLaunchRequested = true
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, PhoneControlService::class.java)
                        .putExtra("session", session ?: return)
                        .putExtra("consent", data),
                )
                finish()
            }
            else -> {
                (application as HarnessApp).container.phone.notifyConsentFailed()
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (!serviceLaunchRequested) {
            (application as HarnessApp).container.phone.notifyConsentFailed()
        }
        super.onDestroy()
    }

    private val session: String? get() = intent.getStringExtra("session")

    companion object {
        private const val REQUEST_OVERLAY = 1
        private const val REQUEST_CAPTURE = 2
    }
}

@androidx.compose.runtime.Composable
private fun ConsentDialog(onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Mouse, contentDescription = null) },
        title = { Text("Agent is asking to control your phone") },
        text = {
            Text(
                "The agent requested permission to control your phone with a visible pointer and screen capture. " +
                    "Screen snapshots may be sent to your selected AI provider. " +
                    "A floating panel lets you pause or stop anytime.\n\n" +
                    "Do you want to allow phone control?",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { Button(onClick = onContinue) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Deny") } },
    )
}

@androidx.compose.runtime.Composable
private fun OverlayErrorDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Not ready yet") },
        text = { Text("Enable \"Display over other apps\" for Harness and make sure Shizuku is connected, then try again.") },
        confirmButton = { Button(onClick = onClose) { Text("Close") } },
    )
}
