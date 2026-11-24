package com.androidharness.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import com.androidharness.app.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class AppRuntimeState(
    val ready: Boolean = false,
    val visible: Boolean = false,
    val runInBackground: Boolean = false,
    val notificationsEnabled: Boolean = false,
) {
    val canRun: Boolean get() = ready && (visible || runInBackground)
}

/** Tracks the whole app, including secondary activities, rather than an individual chat. */
internal class AppRuntimePolicy(private val context: Context, private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = MutableStateFlow(false)
    private val mutable = MutableStateFlow(AppRuntimeState())
    val state = mutable.asStateFlow()
    private val started = mutableSetOf<Activity>()
    private var leaving: Job? = null

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started += activity
                leaving?.cancel()
                visible.value = true
            }
            override fun onActivityStopped(activity: Activity) {
                started -= activity
                leaveIfHidden()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** The assistant panel over other apps counts as the app being on screen. Main thread only. */
    private var overlayShown = false

    fun setOverlayShown(shown: Boolean) {
        overlayShown = shown
        if (shown) { leaving?.cancel(); visible.value = true } else leaveIfHidden()
    }

    private fun leaveIfHidden() {
        if (started.isNotEmpty() || overlayShown) return
        // Activity replacement and rotation must not interrupt an active agent.
        leaving?.cancel()
        leaving = scope.launch { delay(700); if (started.isEmpty() && !overlayShown) visible.value = false }
    }

    suspend fun canRun(): Boolean =settings.settings.first().runInBackground || visible.value

    suspend fun <T> whileAllowed(block: suspend () -> T): T = coroutineScope {
        if (!canRun()) throw BackgroundExecutionDisabled()
        val guard = launch {
            combine(settings.settings, visible) { saved, shown -> saved.runInBackground || shown }.first { !it }
            throw BackgroundExecutionDisabled()
        }
        try { block() } finally { guard.cancel() }
    }

    fun start(onBackgroundDisabled: suspend () -> Unit, onAvailable: suspend () -> Unit) {
        scope.launch {
            combine(settings.settings, visible) { saved, shown ->
                AppRuntimeState(true, shown, saved.runInBackground, saved.notificationsEnabled)
            }.distinctUntilChanged().collect { next ->
                val previous = mutable.value
                mutable.value = next
                AppNotifications.applyPreference(context, next.notificationsEnabled)
                if (!next.canRun) {
                    withContext(Dispatchers.IO) { onBackgroundDisabled() }
                } else {
                    if (!previous.canRun || next.visible && !previous.visible) onAvailable()
                    if (next.runInBackground && (!previous.runInBackground || !previous.ready)) {
                        runCatching {
                            if (RuntimeNotifier.active.value) AgentService.acquireKeepalive(context)
                            else if (next.visible) AgentService.startPersistent(context)
                        }
                    } else if (!next.runInBackground) AgentService.stop(context)
                }
            }
        }
    }
}

/** One gate for all optional app alerts; foreground-service notices remain owned by Android. */
internal object AppNotifications {
    private val lock = Any()
    fun enabled(context: Context): Boolean =
        (context.applicationContext as? HarnessApp)?.container?.runtimePolicy?.state?.value?.notificationsEnabled == true

    fun post(context: Context, id: Int, notification: Notification, tag: String? = null) = synchronized(lock) {
        if (enabled(context)) context.getSystemService(NotificationManager::class.java)?.notify(tag, id, notification)
    }

    fun applyPreference(context: Context, enabled: Boolean) = synchronized(lock) {
        if (!enabled) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@synchronized
            manager.activeNotifications.filter { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0 }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}

internal class BackgroundExecutionDisabled : IllegalStateException("Background running is off. Open AndroidHarness to continue.")
