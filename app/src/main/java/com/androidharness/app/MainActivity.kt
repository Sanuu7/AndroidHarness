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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ScreenshotPolicy
import com.androidharness.app.data.update.UpdateIntents
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.update.UpdateDialog
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : FragmentActivity() {

    private lateinit var container: AppContainer
    private var isUnlocked by mutableStateOf(false)
    private var promptShownThisResume by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as HarnessApp).container

        // The foreground service + run-result notifications need this on 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Open the chat a run-result notification points at, and catch the
        // MCP OAuth redirect when this activity is freshly created for it.
        handleSessionIntent(intent)
        handleMcpOAuth(intent)
        handleShareIntent(intent)

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            HarnessTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                // Auto update check shortly after launch, once per process.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(4_000)
                    container.updates.check(manual = false)
                }

                // Biometric lock prompt on start / when enabled.
                LaunchedEffect(settings.biometricLockEnabled) {
                    if (!settings.biometricLockEnabled) {
                        isUnlocked = true
                    } else if (!isUnlocked && !promptShownThisResume) {
                        promptBiometricUnlock()
                    }
                }

                // Screenshots and the recents preview are blocked app wide unless
                // the user allows them, and always blocked while a key or token
                // is on screen (SecureScreenEffect / SecureDialogEffect).
                val credentialScreen by container.screenshotPolicy.credentialScreenVisible
                    .collectAsStateWithLifecycle()
                LaunchedEffect(settings.allowScreenshots, credentialScreen) {
                    if (ScreenshotPolicy.blocked(settings.allowScreenshots, credentialScreen)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                if (settings.biometricLockEnabled && !isUnlocked) {
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
                                "AndroidHarness is Locked",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Unlock with biometric or device credentials to access your workspaces and chats.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { promptBiometricUnlock() }) {
                                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Unlock")
                            }
                        }
                    }
                } else {
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

    override fun onResume() {
        super.onResume()
        promptShownThisResume = false
    }

    private fun promptBiometricUnlock() {
        promptShownThisResume = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isUnlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AndroidHarness")
            .setSubtitle("Confirm your fingerprint, face, or PIN")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Fallback unlock if device lacks biometric hardware / lock screen is none
            isUnlocked = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSessionIntent(intent)
        handleMcpOAuth(intent)
        handleShareIntent(intent)
    }

    private fun handleSessionIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(AgentService.EXTRA_SESSION_ID) ?: return
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

    /**
     * Receives shares from other apps (text, links, images): the open chat's
     * composer consumes [container.pendingShare], prefilling the input or
     * attaching the image.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val mime = intent.type ?: return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream: android.net.Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (text.isNullOrBlank() && stream == null) return
        container.pendingShare.value =
            com.androidharness.app.data.PendingShare(mime = mime, text = text, stream = stream)
    }
}
