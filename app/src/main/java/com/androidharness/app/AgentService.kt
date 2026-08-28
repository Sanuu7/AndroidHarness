package com.androidharness.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
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
import kotlinx.coroutines.launch

/** Pushes the agent's live status for the notification to display. */
object RuntimeNotifier {
    private val _status = MutableStateFlow("Working…")
    val status: StateFlow<String> = _status

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
)

/**
 * Foreground service that keeps the process alive while an agent run (or an
 * interactive terminal) is active and mirrors RuntimeNotifier into the
 * notification. Holds a partial wake lock so minimizing/screen-off doesn't
 * freeze long-running shells.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, buildNotification("Working…"))
        scope.launch {
            RuntimeNotifier.status.collect { text ->
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
        scope.launch {
            RuntimeNotifier.results.collect { result ->
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(resultNotificationId(), buildResultNotification(result))
                }
            }
        }
        scope.launch {
            RuntimeNotifier.pendingPrompts.collect { prompts ->
                runCatching { refreshPromptAlerts(prompts) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
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
                "Agent runs",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Live progress while the coding agent is working" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                RESULTS_CHANNEL_ID,
                "Run results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notified when a background agent run finishes" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Action needed",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Agent prompts you can answer right from the notification"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun contentIntent(sessionId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidHarness")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun buildResultNotification(result: RunResultNotification): Notification =
        Notification.Builder(this, RESULTS_CHANNEL_ID)
            .setContentTitle(if (result.ok) "Run finished" else "Run needs attention")
            .setContentText(result.title + ": " + result.summary)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(result.sessionId))
            .build()

    private fun resultNotificationId(): Int = RESULT_NOTIFICATION_ID

    // ------------------------------------------------------------------
    // Answerable "action needed" alerts
    // ------------------------------------------------------------------

    /** Tags of alerts currently posted, so removed prompts get cancelled. */
    private var shownAlertTags: Set<String> = emptySet()

    private fun refreshPromptAlerts(prompts: List<PendingPrompt>) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val tags = prompts.map { it.sessionId }.toSet()
        (shownAlertTags - tags).forEach { nm.cancel(it, ALERT_NOTIFICATION_ID) }
        prompts.forEach { nm.notify(it.sessionId, ALERT_NOTIFICATION_ID, buildActionNeeded(it)) }
        shownAlertTags = tags
    }

    private fun buildActionNeeded(p: PendingPrompt): Notification {
        val body = buildString {
            append(p.headline)
            p.detail?.let { append("\n\n").append(it) }
        }
        val builder = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                when (p.kind) {
                    PendingPrompt.Kind.APPROVAL -> "Approval needed"
                    PendingPrompt.Kind.QUESTION -> "Agent asks"
                    PendingPrompt.Kind.ENVIRONMENT -> "Linux environment"
                },
            )
            .setContentText("${p.sessionTitle}: ${p.headline}")
            .setStyle(Notification.BigTextStyle().bigText("${p.sessionTitle}\n\n$body"))
            .setContentIntent(contentIntent(p.sessionId))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)

        when (p.kind) {
            PendingPrompt.Kind.APPROVAL -> {
                builder.addAction(button(ACTION_DENY, "Deny", p.sessionId))
                builder.addAction(button(ACTION_APPROVE, "Approve", p.sessionId))
                builder.addAction(button(ACTION_APPROVE_ALWAYS, "Always allow", p.sessionId))
            }

            PendingPrompt.Kind.ENVIRONMENT -> {
                builder.addAction(button(ACTION_ENV_SKIP, "Skip", p.sessionId))
                builder.addAction(button(ACTION_ENV_INSTALL, "Install", p.sessionId))
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
                    .setLabel("Your answer")
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
        Notification.Action.Builder(
            null as Icon?, label,
            PendingIntent.getBroadcast(
                this,
                requestCode(sessionId, action.hashCode()),
                Intent(this, NotificationActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

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
    }
}
