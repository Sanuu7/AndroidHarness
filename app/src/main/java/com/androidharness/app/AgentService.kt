package com.androidharness.app

import com.androidharness.app.ui.common.localizedOptionLabel
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.androidharness.app.agent.NotificationActionReceiver
import com.androidharness.app.agent.NotificationActionReceiver.Companion.ACTION_APPROVE
import com.androidharness.app.agent.NotificationActionReceiver.Companion.ACTION_APPROVE_ALWAYS
import com.androidharness.app.agent.NotificationActionReceiver.Companion.ACTION_DENY
import com.androidharness.app.agent.NotificationActionReceiver.Companion.ACTION_ENV_INSTALL
import com.androidharness.app.agent.NotificationActionReceiver.Companion.ACTION_ENV_SKIP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Pushes the agent's live status for the notification to display. */
object RuntimeNotifier {
    const val IDLE_STATUS = "Running in background"
    private val _status = MutableStateFlow(IDLE_STATUS)
    val status: StateFlow<String> = _status

    /** True while active task work holds keepalive; passive services are not promoted. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /** Heads-up events posted when a run ends while the app may be backgrounded. */
    private val _results = MutableSharedFlow<RunResultNotification>(extraBufferCapacity = 8)
    val results: MutableSharedFlow<RunResultNotification> = _results

    /**
     * Blocking prompts that can be answered straight from a notification,
     * keyed per session so parallel runs never clobber each other's alerts.
     */
    private val promptLock = Any()
    private val promptsBySession = mutableMapOf<String, List<PendingPrompt>>()
    private val _pendingPrompts = MutableStateFlow<List<PendingPrompt>>(emptyList())
    val pendingPrompts: StateFlow<List<PendingPrompt>> = _pendingPrompts

    fun update(text: String) {
        _status.value = text
    }

    /** Replaces the waiting prompts of one session and republishes the union. */
    fun setSessionPrompts(sessionId: String, prompts: List<PendingPrompt>) {
        synchronized(promptLock) {
            if (prompts.isEmpty()) promptsBySession.remove(sessionId)
            else promptsBySession[sessionId] = prompts
            _pendingPrompts.value = promptsBySession.values.flatten()
        }
    }

    fun notifyResult(result: RunResultNotification) {
        _results.tryEmit(result)
    }
}

/**
 * Live Update chips have room for a glance, not a sentence. Known run states
 * get a short localized label; tool actions fall back to a truncated line.
 */
internal fun Context.liveUpdateShortText(status: String): String =
    liveUpdateShortLabel(status)?.let(::getString) ?: liveUpdateFallbackText(status)

/** Resource for a known run state, or null when [status] is free-form tool text. */
internal fun liveUpdateShortLabel(status: String): Int? {
    val line = status.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        line.startsWith("Waiting for your approval") -> R.string.ui_live_short_approval
        line.startsWith("Waiting for your answer") -> R.string.ui_live_short_answer
        line.startsWith("Installing Linux environment") -> R.string.ui_live_short_installing
        line.startsWith("Linux environment needs") -> R.string.ui_live_short_attention
        line.startsWith("Retrying") -> R.string.ui_live_short_retrying
        line.startsWith("Thinking") -> R.string.ui_live_short_thinking
        line.startsWith("Writing response") -> R.string.ui_live_short_writing
        line.startsWith("Generating code") -> R.string.ui_live_short_generating
        line.startsWith("Working") -> R.string.ui_live_short_working
        line.startsWith("Running in background") || line.isEmpty() -> R.string.ui_live_short_running
        else -> null
    }
}

internal fun liveUpdateFallbackText(status: String): String =
    status.lineSequence().firstOrNull()?.trim().orEmpty().take(24)

/**
 * One blocking prompt (tool approval, ask_user question, environment install)
 * rendered as an answerable notification while the run waits.
 */
data class PendingPrompt(
    val sessionId: String,
    val kind: Kind,
    /** Chat/session title, shown as context on the notification. */
    val sessionTitle: String,
    /** One-line summary: tool name + description, the question, or the env summary. */
    val headline: String,
    /** Optional expanded body, e.g. the diff preview for file edits. */
    val detail: String? = null,
    /** Predefined answer buttons for [Kind.QUESTION]. */
    val options: List<String> = emptyList(),
) {
    enum class Kind { APPROVAL, QUESTION, ENVIRONMENT }
}

/** A completed-run notification. */
data class RunResultNotification(
    val sessionId: String,
    val title: String,
    val ok: Boolean,
    val summary: String,
    val notificationTitle: String? = null,
    val subagentCallId: String? = null,
)

/** Posts completed-run notifications even when [AgentService] has already stopped. */
object RunResultNotifications {
    fun post(context: Context, result: RunResultNotification) {
        if (!AppNotifications.enabled(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannels(context, manager)
        AppNotifications.post(context, notificationId(result.subagentCallId?.let { "${result.sessionId}:$it" } ?: result.sessionId), build(context, result))
    }

    /**
     * Finished and failed runs use separate channels so people can give each
     * its own sound in the system notification settings.
     */
    fun createChannels(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                AgentService.RESULTS_CHANNEL_ID,
                context.getString(R.string.ui_run_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.ui_notified_when_a_background_agent_run_finishes) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                AgentService.ERRORS_CHANNEL_ID,
                context.getString(R.string.ui_run_errors),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.ui_notified_when_a_background_agent_run_fails) },
        )
    }

    internal fun channelFor(result: RunResultNotification): String =
        if (result.ok) AgentService.RESULTS_CHANNEL_ID else AgentService.ERRORS_CHANNEL_ID

    private fun build(context: Context, result: RunResultNotification): Notification {
        val preview = result.summary.trim().ifBlank { context.getString(R.string.ui_run_finished) }
        val compactPreview = preview.replace('\n', ' ').take(180)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AgentService.EXTRA_SESSION_ID, result.sessionId)
            .putExtra(AgentService.EXTRA_SUBAGENT_CALL_ID, result.subagentCallId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            (result.subagentCallId?.let { "${result.sessionId}:$it" } ?: result.sessionId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channelFor(result))
            .setContentTitle(
                result.notificationTitle ?: if (result.ok) context.getString(R.string.ui_run_finished) else context.getString(R.string.ui_run_needs_attention),
            )
            .setContentText(compactPreview)
            .setSubText(result.title)
            .setStyle(Notification.BigTextStyle().bigText(preview.take(4000)))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    internal fun notificationId(sessionId: String): Int =
        AgentService.RESULT_NOTIFICATION_ID + (sessionId.hashCode() and 0x0fffffff)
}

/**
 * Foreground service that keeps the process alive while an agent run (or an
 * interactive terminal) is active and mirrors RuntimeNotifier into the
 * notification. Holds a partial wake lock so minimizing/screen-off doesn't
 * freeze long-running shells.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var liveUpdatesEnabled = true

    override fun onCreate() {
        super.onCreate()
        current = this
        createChannels()
        startForeground(NOTIFICATION_ID, buildNotification(RuntimeNotifier.status.value))
        scope.launch {
            RuntimeNotifier.status.collect { text ->
                runCatching {
                    AppNotifications.post(this@AgentService, NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
        scope.launch {
            RuntimeNotifier.active.collect {
                runCatching {
                    AppNotifications.post(this@AgentService, NOTIFICATION_ID, buildNotification(RuntimeNotifier.status.value))
                }
            }
        }
        scope.launch {
            RuntimeNotifier.results.collect { result ->
                runCatching {
                    RunResultNotifications.post(this@AgentService, result)
                }
            }
        }
        scope.launch {
            RuntimeNotifier.pendingPrompts.collect { prompts ->
                runCatching { refreshPromptAlerts(prompts) }
                runCatching {
                    AppNotifications.post(this@AgentService, NOTIFICATION_ID, buildNotification(RuntimeNotifier.status.value))
                }
            }
        }
        scope.launch {
            val container = (applicationContext as HarnessApp).container
            combine(container.runtimePolicy.state, container.settings.settings) { policy, settings -> policy to settings.liveUpdatesEnabled }.collect { (policy, liveUpdates) ->
                if (!policy.ready) return@collect
                if (!policy.runInBackground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                liveUpdatesEnabled = liveUpdates && policy.notificationsEnabled
                // A running foreground service must retain its Android-owned notice.
                // Disabling alerts removes task details/actions and Live Update promotion.
                startForeground(NOTIFICATION_ID, buildNotification(RuntimeNotifier.status.value))
                refreshPromptAlerts(RuntimeNotifier.pendingPrompts.value)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((applicationContext as HarnessApp).container.runtimePolicy.state.value.let { it.ready && !it.runInBackground }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_ACQUIRE_WAKELOCK -> acquireWakeLock()
            ACTION_RELEASE_WAKELOCK -> releaseWakeLock()
            ACTION_STOP -> {
                stopFromNotification()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harness:agent").apply {
                // generous ceiling so a long run is never silently killed at 10 min
                acquire(12L * 60 * 60 * 1000)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ui_agent_runs),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.ui_live_progress_while_the_coding_agent_is) },
        )
        RunResultNotifications.createChannels(this, nm)
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.ui_action_needed),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.ui_agent_prompts_you_can_answer_right_from)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun contentIntent(sessionId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, sessionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(text: String): Notification {
        val alertsEnabled = AppNotifications.enabled(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidHarness")
            .setContentText(if (alertsEnabled) localizedOptionLabel(text) else getString(R.string.background_service_notice))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(appPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    0, getString(R.string.settings_action_stop), stopPendingIntent(),
                ).build(),
            )
        // The live card only surfaces actions on this ongoing notification, so a
        // blocked run must expose its answer buttons here too.
        RuntimeNotifier.pendingPrompts.value.firstOrNull()?.takeIf { alertsEnabled }?.let { prompt ->
            when (prompt.kind) {
                PendingPrompt.Kind.APPROVAL -> {
                    // Keep the live card to the two answers the chip promises;
                    // "Always allow" stays on the in-shade alert notification.
                    builder.addAction(compatButton(ACTION_APPROVE, getString(R.string.ui_approve), prompt.sessionId))
                    builder.addAction(compatButton(ACTION_DENY, getString(R.string.ui_deny), prompt.sessionId))
                }
                PendingPrompt.Kind.ENVIRONMENT -> {
                    builder.addAction(compatButton(ACTION_ENV_SKIP, getString(R.string.ui_skip), prompt.sessionId))
                    builder.addAction(compatButton(ACTION_ENV_INSTALL, getString(R.string.settings_action_install), prompt.sessionId))
                }
                PendingPrompt.Kind.QUESTION -> Unit
            }
        }
        if (alertsEnabled && liveUpdatesEnabled && RuntimeNotifier.active.value &&
            text.isNotBlank() && text != RuntimeNotifier.IDLE_STATUS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Android 16 promotes this ongoing notification to a Live Update, which
            // the OEM surfaces in its status bar, lock screen and live activity area
            // (Now Bar on One UI, Live updates on Pixel and near-stock builds).
            builder
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(liveUpdateShortText(text))
                .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
        }
        return builder.build()
    }

    private fun appPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        STOP_REQUEST_CODE,
        Intent(this, AgentService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopFromNotification() {
        scope.launch(Dispatchers.IO) {
            val container = (applicationContext as? HarnessApp)?.container
            runCatching { container?.telegram?.stopAndPause() }
            runCatching { container?.runManager?.stopAllAndJoin() }
            runCatching { container?.terminal?.stopTerminal() }
            runCatching { container?.backgroundProcesses?.killAll() }
            releaseWakeLock()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            Process.killProcess(Process.myPid())
        }
    }

    // ------------------------------------------------------------------
    // Answerable "action needed" alerts
    // ------------------------------------------------------------------

    /** Tags of alerts currently posted, so removed prompts get cancelled. */
    private var shownAlertTags: Set<String> = emptySet()

    private fun refreshPromptAlerts(prompts: List<PendingPrompt>) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val visiblePrompts = prompts.takeIf { AppNotifications.enabled(this) }.orEmpty()
        val tags = visiblePrompts.map { it.sessionId }.toSet()
        (shownAlertTags - tags).forEach { nm.cancel(it, ALERT_NOTIFICATION_ID) }
        visiblePrompts.forEach { AppNotifications.post(this, ALERT_NOTIFICATION_ID, buildActionNeeded(it), it.sessionId) }
        shownAlertTags = tags
    }

    private fun buildActionNeeded(p: PendingPrompt): Notification {
        val body = buildString {
            append(p.headline)
            p.detail?.let { append("\n\n").append(it) }
        }
        val builder = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(
                when (p.kind) {
                    PendingPrompt.Kind.APPROVAL -> getString(R.string.ui_approval_needed)
                    PendingPrompt.Kind.QUESTION -> getString(R.string.ui_agent_asks)
                    PendingPrompt.Kind.ENVIRONMENT -> getString(R.string.settings_search_linux_title)
                },
            )
            .setContentText("${p.sessionTitle}: ${p.headline}")
            .setStyle(Notification.BigTextStyle().bigText("${p.sessionTitle}\n\n$body"))
            .setContentIntent(contentIntent(p.sessionId))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)

        when (p.kind) {
            PendingPrompt.Kind.APPROVAL -> {
                builder.addAction(button(ACTION_DENY, getString(R.string.ui_deny), p.sessionId))
                builder.addAction(button(ACTION_APPROVE, getString(R.string.ui_approve), p.sessionId))
                builder.addAction(button(ACTION_APPROVE_ALWAYS, getString(R.string.ui_always_allow), p.sessionId))
            }

            PendingPrompt.Kind.ENVIRONMENT -> {
                builder.addAction(button(ACTION_ENV_SKIP, getString(R.string.ui_skip), p.sessionId))
                builder.addAction(button(ACTION_ENV_INSTALL, getString(R.string.settings_action_install), p.sessionId))
            }

            PendingPrompt.Kind.QUESTION -> {
                // Predefined options become buttons; a free-text quick-reply
                // field covers everything else (and longer option lists).
                p.options.take(MAX_OPTION_BUTTONS).forEachIndexed { index, option ->
                    builder.addAction(
                        Notification.Action.Builder(
                            null as Icon?, option,
                            answerPendingIntent(p.sessionId, index, option),
                        ).build(),
                    )
                }
                val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
                    .setLabel(getString(R.string.ui_your_answer))
                    .build()
                val replyAction = Notification.Action.Builder(
                    null as Icon?, "Reply…",
                    answerPendingIntent(p.sessionId, MAX_OPTION_BUTTONS, null),
                ).addRemoteInput(remoteInput).build()
                builder.addAction(replyAction)
            }
        }
        return builder.build()
    }

    /**
     * Broadcast [PendingIntent] for one notification button. Request codes are
     * keyed per session+slot because extras are not part of PendingIntent
     * identity, two sessions' identical actions would otherwise overwrite
     * each other.
     */
    private fun button(action: String, label: String, sessionId: String): Notification.Action =
        Notification.Action.Builder(null as Icon?, label, actionPendingIntent(action, sessionId)).build()

    private fun compatButton(action: String, label: String, sessionId: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, label, actionPendingIntent(action, sessionId)).build()

    private fun actionPendingIntent(action: String, sessionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode(sessionId, action.hashCode()),
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * [PendingIntent] delivering one ask_user answer: either a predefined
     * [optionText] carried on the intent, or (when null) a RemoteInput
     * quick-reply, which is why this one must stay FLAG_MUTABLE.
     */
    private fun answerPendingIntent(sessionId: String, slot: Int, optionText: String?): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode(sessionId, slot),
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_ANSWER)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .apply { optionText?.let { putExtra(NotificationActionReceiver.EXTRA_ANSWER_TEXT, it) } },
            if (optionText == null) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            },
        )

    private fun requestCode(sessionId: String, slot: Int): Int =
        31 * sessionId.hashCode() + slot

    companion object {
        const val CHANNEL_ID = "agent_runs"
        const val RESULTS_CHANNEL_ID = "run_results"
        const val ERRORS_CHANNEL_ID = "run_errors"
        const val ALERT_CHANNEL_ID = "action_needed"
        const val NOTIFICATION_ID = 9101
        const val RESULT_NOTIFICATION_ID = 9102

        /**
         * Alert id shared by all sessions, notifications are keyed by the
         * session id as TAG + this id, so parallel runs don't clobber each other.
         */
        const val ALERT_NOTIFICATION_ID = 9103

        /** ask_user option buttons shown before falling back to free-text reply. */
        const val MAX_OPTION_BUTTONS = 3

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SUBAGENT_CALL_ID = "subagent_call_id"

        private const val ACTION_START = "com.androidharness.app.action.START_BACKGROUND"
        private const val ACTION_ACQUIRE_WAKELOCK = "com.androidharness.app.action.ACQUIRE_WAKELOCK"
        private const val ACTION_RELEASE_WAKELOCK = "com.androidharness.app.action.RELEASE_WAKELOCK"
        private const val ACTION_STOP = "com.androidharness.app.action.STOP_APP"
        private const val OPEN_APP_REQUEST_CODE = 9104
        private const val STOP_REQUEST_CODE = 9105

        @Volatile private var current: AgentService? = null

        fun stop(context: Context) { context.stopService(Intent(context, AgentService::class.java)) }

        fun startPersistent(context: Context) {
            if ((context.applicationContext as? HarnessApp)?.container?.runtimePolicy?.state?.value?.runInBackground != true) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java).setAction(ACTION_START),
            )
        }

        fun acquireKeepalive(context: Context) {
            if ((context.applicationContext as? HarnessApp)?.container?.runtimePolicy?.state?.value?.runInBackground != true) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java).setAction(ACTION_ACQUIRE_WAKELOCK),
            )
        }

        fun releaseKeepalive(context: Context) {
            // Releasing the last task must never resurrect a service stopped by the user.
            current?.releaseWakeLock()
        }
    }
}
