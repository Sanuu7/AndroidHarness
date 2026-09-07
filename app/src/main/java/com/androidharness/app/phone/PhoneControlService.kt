package com.androidharness.app.phone

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.KeyguardManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

/**
 * Owns one phone-control session: the screen-capture foreground service, the
 * floating Material panel (drag, pause, chat, stop) and the drawn pointer the
 * model moves before every click. Everything dies with the session; nothing
 * survives onDestroy.
 */
class PhoneControlService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateController = androidx.savedstate.SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val handler = Handler(Looper.getMainLooper())
    private val container get() = (application as HarnessApp).container
    private lateinit var windows: WindowManager

    var sessionId = ""
    var panelView: ComposeView? = null
        private set
    private var panelParams: WindowManager.LayoutParams? = null
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    var ui = OverlayUi()
        private set
    val paused: Boolean get() = ui.paused

    var screenWidth = 0; private set
    var screenHeight = 0; private set

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frame: android.graphics.Bitmap? = null
    private var frameAt = 0L

    class OverlayUi {
        var paused by mutableStateOf(false)
        var status by mutableStateOf("Idle")
        var isThinking by mutableStateOf(false)
        var pointerNote by mutableStateOf<String?>(null)
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        // Restore must happen while the lifecycle is still INITIALIZED;
        // SavedStateRegistry attach rejects anything past that stage.
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        startRunStateObserver()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startRunStateObserver() {
        serviceScope.launch {
            val sessionFlow = container.runManager.runningSessionIds.map { running ->
                running.firstOrNull() ?: sessionId
            }.distinctUntilChanged()

            sessionFlow.flatMapLatest { sid ->
                if (sid.isBlank()) kotlinx.coroutines.flow.flowOf(null)
                else container.runManager.live(sid)
            }.collect { live ->
                if (live != null) {
                    val text = container.runManager.actionText(live)
                    ui.status = text ?: if (live.running) "Working…" else "Idle"
                    ui.isThinking = (live.streamingThinking != null && live.streamingText.isNullOrEmpty())
                } else {
                    ui.status = "Idle"
                    ui.isThinking = false
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val targetSession = intent?.getStringExtra("session")
        if (!targetSession.isNullOrBlank()) {
            sessionId = targetSession
        }
        if (projection != null) return START_NOT_STICKY
        try {
            sessionId = targetSession ?: error("Missing chat")
            check(container.phone.ready() && android.provider.Settings.canDrawOverlays(this))

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Phone control", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 70, Intent(this, PhoneControlService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Phone control active")
                .setContentText("Screen snapshots may be shared with your selected AI provider")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            windows = getSystemService(WindowManager::class.java)
            val bounds = windows.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()

            val consent = intent.getParcelableExtra<Intent>("consent") ?: error("Missing capture consent")
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(android.app.Activity.RESULT_OK, consent)
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)

            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    if (paused || SystemClock.elapsedRealtime() - frameAt < 250) return@use
                    val plane = image.planes[0]
                    val padded = android.graphics.Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, android.graphics.Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val next = android.graphics.Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (next !== padded) padded.recycle()
                    synchronized(this) { frame?.recycle(); frame = next; frameAt = SystemClock.elapsedRealtime() }
                }
            }, handler)
            display = projection!!.createVirtualDisplay(
                "Harness phone control", screenWidth, screenHeight,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler,
            )

            createOverlay()
            container.phone.service = this
            container.phone.notifyConsentSuccess()
            handler.post(monitor)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Phone control could not start. Check overlay, capture and Shizuku permissions.", android.widget.Toast.LENGTH_LONG).show()
            container.phone.notifyConsentFailed()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Auto-teardown watch: Shizuku loss, lock screen or a display-size change ends the session. */
    private val monitor = object : Runnable {
        override fun run() {
            if (!container.phone.ready() || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                !android.provider.Settings.canDrawOverlays(this@PhoneControlService)
            ) { stopSelf(); return }
            val bounds = windows.maximumWindowMetrics.bounds
            if (bounds.width() != screenWidth || bounds.height() != screenHeight) { stopSelf(); return }
            handler.postDelayed(this, 500)
        }
    }

    @Synchronized
    fun snapshot(): android.graphics.Bitmap? =
        if (!paused && SystemClock.elapsedRealtime() - frameAt < 2_000) frame?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else null

    /** True when a model-driven point would land on the floating controls. */
    fun coversControls(x: Int, y: Int): Boolean {
        val view = panelView ?: return false
        val p = panelParams ?: return false
        return x in p.x until p.x + view.width && y in p.y until p.y + view.height
    }

    fun togglePause() {
        ui.paused = !ui.paused
    }

    fun openChat() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val target = container.runManager.runningSessionIds.value.firstOrNull() ?: sessionId
        container.pendingSessionId.tryEmit(target)
    }

    fun showAction(action: String, x: Int, y: Int) {
        if (action in POINTER_ACTIONS) {
            cursorParams?.let { p ->
                p.x = x; p.y = y
                runCatching { windows.updateViewLayout(cursorView, p) }
            }
            cursorView?.pulse()
            ui.pointerNote = if (action == "move") "Agent: moving to $x, $y" else "Agent: $action at $x, $y"
            handler.removeCallbacks(clearNote)
            handler.postDelayed(clearNote, 1_500)
        }
    }

    private val clearNote = Runnable { ui.pointerNote = null }

    private fun overlayFlags(touchable: Boolean) =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    private fun createOverlay() {
        val panel = ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setViewTreeLifecycleOwner(this@PhoneControlService)
            setViewTreeSavedStateRegistryOwner(this@PhoneControlService)
            setContent { FloatingPanel() }
        }
        val layout = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlayFlags(touchable = true), PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 24; y = 140 }
        // Rounded Material surface; the view background must stay transparent.
        panel.background = null
        windows.addView(panel, layout)
        panelView = panel
        panelParams = layout

        val cursor = CursorView(this)
        val cursorLayout = WindowManager.LayoutParams(
            (28 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(touchable = false) or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = screenWidth / 2; y = screenHeight / 2 }
        windows.addView(cursor, cursorLayout)
        cursorView = cursor
        cursorParams = cursorLayout
    }

    @Composable
    private fun FloatingPanel() {
        val settings by container.settings.settings.collectAsState(initial = null)
        HarnessTheme(
            themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
            dynamicColor = settings?.dynamicColor ?: true,
        ) {
            val scheme = MaterialTheme.colorScheme
            val statusLine = when {
                ui.paused -> "Paused"
                ui.pointerNote != null -> ui.pointerNote!!
                else -> ui.status
            }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainer,
                contentColor = scheme.onSurface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val view = panelView ?: return@detectDragGestures
                                val p = panelParams ?: return@detectDragGestures
                                p.x = (p.x + dragAmount.x.roundToInt()).coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
                                p.y = (p.y + dragAmount.y.roundToInt()).coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
                                runCatching { windows.updateViewLayout(view, p) }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Mouse, contentDescription = null,
                            tint = scheme.primary, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Phone control", style = MaterialTheme.typography.labelLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(6.dp).background(
                                        color = when {
                                            ui.paused -> scheme.outline
                                            ui.isThinking -> scheme.tertiary
                                            else -> scheme.primary
                                        },
                                        shape = CircleShape,
                                    )
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    statusLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 210.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(onClick = { togglePause() }, contentPadding = CompactPadding) {
                            Text(if (ui.paused) "Resume" else "Pause", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(onClick = { openChat() }, contentPadding = CompactPadding) {
                            Text("Chat", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { stopSelf() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.errorContainer,
                                contentColor = scheme.onErrorContainer,
                            ),
                            contentPadding = CompactPadding,
                        ) { Text("Stop", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }

    private val CompactPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)

    override fun onDestroy() {
        if (container.phone.service === this) container.phone.service = null
        if (sessionId.isNotBlank()) container.runManager.stop(sessionId)
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        synchronized(this) { frame?.recycle(); frame = null }
        panelView?.let { runCatching { windows.removeView(it) } }
        cursorView?.let { runCatching { windows.removeView(it) } }
        panelView = null; cursorView = null
        store.clear()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        val POINTER_ACTIONS = setOf("move", "click", "drag", "scroll")
        private const val CHANNEL_ID = "phone_control"
        private const val NOTIFICATION_ID = 70
    }
}

/**
 * The agent's pointer: a classic arrow drawn on canvas, white with an outline
 * and a soft shadow, with a springy pulse when the model interacts.
 */
private class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(10f, 0f, 3f, 0x59000000)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 28, 30, 34)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private var pulse = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 420
        interpolator = OvershootInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, fill)
    }

    fun pulse() {
        runCatching { animator.start() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Classic pointer arrow, tip anchored at the view origin.
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, h * 0.72f)
            lineTo(w * 0.28f, h * 0.55f)
            lineTo(w * 0.44f, h * 0.95f)
            lineTo(w * 0.58f, h * 0.88f)
            lineTo(w * 0.42f, h * 0.50f)
            lineTo(w * 0.72f, h * 0.50f)
            close()
        }
        val scale = 1f + 0.25f * pulse
        canvas.save()
        canvas.scale(scale, scale, 0f, 0f)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
        canvas.restore()
        if (pulse > 0f && pulse < 1f) {
            canvas.drawCircle(0f, 0f, w * (0.4f + pulse), accent.apply { alpha = ((1f - pulse) * 140).toInt() })
        }
    }
}
