package com.androidharness.app.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.androidharness.app.HarnessApp

class PhoneConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (android.os.Build.VERSION.SDK_INT < 30) {
            android.widget.Toast.makeText(this, "Phone control requires Android 11 or newer.", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        if ((application as HarnessApp).container.phone.service != null) {
            android.widget.Toast.makeText(this, "Stop the existing phone session first.", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        AlertDialog.Builder(this).setTitle("Enable phone control?")
            .setMessage("The agent can control other apps through Shizuku. Screen snapshots can be sent to your selected AI provider and saved in chat. Avoid passwords, banking and private content. A floating panel provides Pause and Stop. Enable display over other apps, then approve entire-screen capture. Rotation or locking stops control.")
            .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }
            .setPositiveButton("Continue") { _, _ ->
                if (!Settings.canDrawOverlays(this)) startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 1) else capture()
            }.show()
    }
    private fun capture() {
        if (!Settings.canDrawOverlays(this) || !(application as HarnessApp).container.phone.ready()) {
            AlertDialog.Builder(this).setMessage("Enable overlay permission and connect Shizuku first.").setPositiveButton("Close") { _, _ -> finish() }.show(); return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        startActivityForResult(request, 2)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) { capture(); return }
        if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, PhoneControlService::class.java).putExtra("session", intent.getStringExtra("session")).putExtra("consent", data))
        }
        finish()
    }
}
