package com.androidharness.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ScreenshotPolicy
import com.androidharness.app.data.update.UpdateIntents
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.update.UpdateDialog
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

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
                // Screenshots and the recents preview are blocked app wide unless
                // the user allows them, and always blocked on credential screens.
                val credentialScreen by container.screenshotPolicy.credentialScreenVisible
                    .collectAsStateWithLifecycle()
                LaunchedEffect(settings.allowScreenshots, credentialScreen) {
                    if (ScreenshotPolicy.blocked(settings.allowScreenshots, credentialScreen)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
                AppNav(container)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSessionIntent(intent)
        handleMcpOAuth(intent)
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
}
