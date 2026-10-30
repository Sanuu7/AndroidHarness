package com.androidharness.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.androidharness.app.ui.common.BiometricAuth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ScreenshotPolicy
import com.androidharness.app.data.update.UpdateIntents
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.update.UpdateDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var container: AppContainer
    private var isUnlocked by mutableStateOf(false)
    private var promptShownThisResume by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as HarnessApp).container

        lifecycleScope.launch {
            if (container.settings.settings.first().notificationsEnabled && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Open the chat a run-result notification points at, and catch the
        // MCP OAuth redirect when this activity is freshly created for it.
        handleSessionIntent(intent)
        handleMcpOAuth(intent)
        if (savedInstanceState == null) handleShareIntent(intent)

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = null)
            val currentSettings = settings ?: return@setContent

            HarnessTheme(
                themeMode = currentSettings.themeMode,
                dynamicColor = currentSettings.dynamicColor,
                progressBarStyle = currentSettings.progressBarStyle,
                colorPalette = currentSettings.colorPalette,
                customPalette = currentSettings.customPalettes.firstOrNull { it.id == currentSettings.activeCustomPaletteId }
                    .takeUnless { currentSettings.dynamicColor || currentSettings.harnessCanvas.adaptColors },
                canvasSeed = currentSettings.harnessCanvas.takeIf { it.adaptColors && com.androidharness.app.data.HarnessCanvasStore.image(this, it.imageId) != null }?.seed,
                glassEnabled = currentSettings.harnessGlassEnabled,
                liquidGlass = currentSettings.harnessLiquidGlass,
                glassTransparency = currentSettings.harnessGlassTransparency,
            ) {
                // Auto update check shortly after launch, once per process.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(4_000)
                    container.updates.check(manual = false)
                }

                // Biometric lock prompt on start / when enabled.
                LaunchedEffect(currentSettings.biometricLockEnabled) {
                    if (!currentSettings.biometricLockEnabled) {
                        isUnlocked = true
                    } else if (!isUnlocked) {
                        promptBiometricUnlock()
                    }
                }

                // Screenshots and the recents preview are blocked app wide unless
                // the user allows them, and always blocked while a key or token
                // is on screen (SecureScreenEffect / SecureDialogEffect).
                val credentialScreen by container.screenshotPolicy.credentialScreenVisible
                    .collectAsStateWithLifecycle()
                LaunchedEffect(currentSettings.allowScreenshots, credentialScreen) {
                    if (ScreenshotPolicy.blocked(currentSettings.allowScreenshots, credentialScreen)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                if (currentSettings.biometricLockEnabled && !isUnlocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = "App Locked",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                getString(R.string.ui_androidharness_is_locked),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                getString(R.string.ui_unlock_with_biometric_or_device_credentials_to),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { promptBiometricUnlock() }) {
                                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(getString(R.string.ui_unlock))
                            }
                        }
                    }
                } else {
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        val host = window.decorView as android.view.ViewGroup
                        container.browser.bindReadCaptureHost(host)
                        onDispose { container.browser.unbindReadCaptureHost(host) }
                    }
                    AppNav(container)
                }

                // The one global update dialog, above everything else.
                val step by container.updates.step.collectAsStateWithLifecycle()
                UpdateDialog(
                    step = step,
                    onDismiss = { container.updates.dismiss() },
                    onUpdate = {
                        val s = container.updates.step.value
                        val release = when (s) {
                            is com.androidharness.app.data.update.UpdateManager.Step.Available -> s.release
                            is com.androidharness.app.data.update.UpdateManager.Step.Error -> s.release
                            else -> null
                        }
                        if (release != null) {
                            kotlinx.coroutines.MainScope().launch {
                                container.updates.startUpdate(
                                    release,
                                    onOpenSystemInstaller = { apk ->
                                        UpdateIntents.installApk(this@MainActivity, apk)
                                    },
                                    onOpenUnknownSourcesSettings = {
                                        UpdateIntents.openUnknownSourcesSettings(this@MainActivity)
                                    },
                                )
                            }
                        } else {
                            // Error without release context = check failure; retry the check.
                            kotlinx.coroutines.MainScope().launch { container.updates.check(manual = true) }
                        }
                    },
                    onOpenSystemInstaller = { apk: File -> UpdateIntents.installApk(this, apk) },
                    onOpenUnknownSources = { UpdateIntents.openUnknownSourcesSettings(this) },
                )
            }
        }
    }

    private var lastPausedTimestamp: Long = 0L

    override fun onStart() {
        super.onStart()
        container.nineRouter.onAppOpen()
    }

    override fun onPause() {
        container.browser.unbindReadCaptureHost(window.decorView as android.view.ViewGroup)
        super.onPause()
        lastPausedTimestamp = System.currentTimeMillis()
    }

    /**
     * Compose draws at whatever rate the window asks for, and a window that
     * states no preference is often pinned to 60 Hz by the vendor's refresh
     * policy. Ask for the fastest mode that keeps the current resolution.
     */
    private fun requestHighRefreshRate() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.refreshRate <= current.refreshRate && window.attributes.preferredDisplayModeId == best.modeId) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
        com.androidharness.app.data.env.adb.WirelessAdbService.resume(this)
        container.shizuku.refresh()
        container.dhizuku.refresh()
        promptShownThisResume = false
        if (isUnlocked && lastPausedTimestamp > 0L) {
            val settings = runCatching {
                kotlinx.coroutines.runBlocking { container.settings.settings.first() }
            }.getOrNull()
            if (settings != null && settings.biometricLockEnabled) {
                val timeoutMillis = settings.biometricLockTimeoutMinutes * 60 * 1000L
                val elapsed = System.currentTimeMillis() - lastPausedTimestamp
                if (elapsed >= timeoutMillis) {
                    isUnlocked = false
                    promptBiometricUnlock()
                }
            }
        }
        if (isUnlocked) container.browser.bindReadCaptureHost(window.decorView as android.view.ViewGroup)
    }

    private fun promptBiometricUnlock() {
        promptShownThisResume = true
        BiometricAuth.prompt(
            activity = this,
            title = getString(R.string.ui_unlock_androidharness),
            subtitle = getString(R.string.ui_confirm_your_fingerprint_face_or_pin),
            onSuccess = { isUnlocked = true },
            onError = {
                // If biometrics not enrolled or unavailable, fallback unlock so user is not permanently locked out
                if (!BiometricAuth.canAuthenticate(this)) {
                    isUnlocked = true
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSessionIntent(intent)
        handleMcpOAuth(intent)
        handleShareIntent(intent)
    }

    private fun handleSessionIntent(intent: Intent?) {
        if (intent?.getStringExtra(com.androidharness.app.telegram.TelegramService.EXTRA_SETTINGS) == "telegram") {
            container.pendingSettingsScroll.value = "telegram"
        }
        val sessionId = intent?.getStringExtra(AgentService.EXTRA_SESSION_ID) ?: return
        intent.getStringExtra(AgentService.EXTRA_SUBAGENT_CALL_ID)?.let { callId ->
            container.pendingSubagentChat.value = sessionId to callId
            return
        }
        // AppNav observes this to deep-link into the session's chat.
        container.pendingSessionId.tryEmit(sessionId)
    }

    /** Completes the MCP OAuth browser round-trip (androidharness://mcp/oauth?code=…). */
    private fun handleMcpOAuth(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "androidharness" || data.host != "mcp") return
        kotlinx.coroutines.MainScope().launch {
            container.mcp.completeAuthentication(
                stateParam = data.getQueryParameter("state"),
                code = data.getQueryParameter("code"),
            ).fold(
                onSuccess = { name -> android.util.Log.i("McpOAuth", "authenticated '$name'") },
                onFailure = { e -> android.util.Log.w("McpOAuth", "auth failed: ${e.message}") },
            )
        }
    }

    /** Incoming shares are routed by AppNav into a user-selected regular Chat. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        try {
            com.androidharness.app.data.PendingShare.fromIntent(intent)?.let { container.pendingShare.value = it }
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.chat_share_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
