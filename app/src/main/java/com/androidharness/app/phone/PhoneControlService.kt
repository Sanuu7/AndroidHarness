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
import android.os.HandlerThread
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.json.*
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
import kotlinx.coroutines.android.asCoroutineDispatcher
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
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val container get() = (application as HarnessApp).container
    private lateinit var windows: WindowManager

    private val sessionChanges = MutableStateFlow("")
    var sessionId: String
        get() = sessionChanges.value
        set(value) { sessionChanges.value = value }
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
    private var capturedImage: android.media.Image? = null
    @Volatile private var frameSequence = 0L
    @Volatile private var captureActive = false
    @Volatile var observationEpoch = 0L; private set
    @Volatile private var lastInputAt = 0L

    class OverlayUi {
        var activity by mutableStateOf(PhoneActivity.IDLE)
        var recentActivity by mutableStateOf<PhoneActivity?>(null)
        var expanded by mutableStateOf(false)
        var capturing by mutableStateOf(false)
        var paused by mutableStateOf(false)
        var status by mutableStateOf("Idle")
        var isThinking by mutableStateOf(false)
        var pointerNote by mutableStateOf<String?>(null)
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("HarnessPhoneCapture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
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
            sessionChanges.flatMapLatest { sid ->
                if (sid.isBlank()) kotlinx.coroutines.flow.flowOf(null)
                else container.runManager.live(sid)
            }.collect { live ->
                if (live != null) {
                    val text = container.runManager.actionText(if (live.runningCalls.isEmpty()) live.copy(currentToolAction = null) else live)
                    ui.status = text ?: if (live.running) "Working…" else "Idle"
                    ui.isThinking = live.running && !live.streamingThinking.isNullOrEmpty() && live.streamingText.isNullOrEmpty()
                    val call = live.runningCalls.lastOrNull()
                    val phoneAction = if (call?.name == "phone_control") runCatching {
                        Json.parseToJsonElement(call.argumentsJson).jsonObject["action"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() else null
                    ui.activity = when {
                        live.error != null -> PhoneActivity.ERROR
                        live.pendingApproval != null || live.pendingQuestion != null ||
                            live.pendingEnvironment != null || live.pendingPlan != null || live.retryStatus != null -> PhoneActivity.WAITING
                        !live.running -> PhoneActivity.IDLE
                        phoneAction == "screenshot" -> PhoneActivity.SCREENSHOT
                        phoneAction in setOf("click", "move", "drag", "scroll") -> PhoneActivity.MOUSE
                        phoneAction in setOf("type", "key") -> PhoneActivity.TYPING
                        call != null -> PhoneActivity.WORKING
                        ui.isThinking -> PhoneActivity.THINKING
                        !live.streamingText.isNullOrEmpty() -> PhoneActivity.WRITING
                        else -> PhoneActivity.WORKING
                    }
                } else {
                    ui.status = "Idle"
                    ui.isThinking = false
                    ui.activity = PhoneActivity.IDLE
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopByUser(); return START_NOT_STICKY }
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
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { captureActive = false; stopSelf() } }, handler)

            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            captureActive = true
            reader!!.setOnImageAvailableListener({ source ->
                if (captureActive) {
                    val next = source.acquireLatestImage()
                    if (next != null) {
                        capturedImage?.close()
                        capturedImage = next
                        frameSequence++
                    }
                }
            }, captureHandler)
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

    // Retain the latest Image without throttling or converting every video frame.
    // A static display need not produce another frame after input completes.
    suspend fun snapshot(): android.graphics.Bitmap? {
        val previous = frameSequence
        try {
            withContext(Dispatchers.Main) {
                ui.capturing = true
                panelView?.visibility = View.INVISIBLE
                cursorView?.visibility = View.INVISIBLE
            }
            // Give WindowManager time to remove both overlays from the projection.
            delay(120)
            return captureSnapshot(previous)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                ui.capturing = false
                if (captureActive) {
                    panelView?.visibility = View.VISIBLE
                    cursorView?.visibility = View.VISIBLE
                    // The overlay is absent from the captured image. Briefly show
                    // the eye after it returns so the user can see the activity.
                    flashActivity(PhoneActivity.SCREENSHOT)
                }
            }
        }
    }

    private suspend fun captureSnapshot(previous: Long): android.graphics.Bitmap? {
        delay((lastInputAt + 200 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        return withContext(captureHandler.asCoroutineDispatcher()) {
            val deadline = SystemClock.elapsedRealtime() + 1_800
            while (captureActive && (capturedImage == null || frameSequence <= previous) && SystemClock.elapsedRealtime() < deadline) delay(40)
            if (!captureActive || paused || frameSequence <= previous) return@withContext null
            val image = capturedImage ?: return@withContext null
            val plane = image.planes[0]
            val padded = android.graphics.Bitmap.createBitmap(
                plane.rowStride / plane.pixelStride, image.height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            try {
                plane.buffer.rewind()
                padded.copyPixelsFromBuffer(plane.buffer)
                val result = android.graphics.Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (result !== padded) padded.recycle()
                result
            } catch (error: Exception) {
                padded.recycle()
                throw error
            }
        }
    }

    fun stopByUser() {
        if (sessionId.isNotBlank()) container.runManager.stop(sessionId)
        stopSelf()
    }

    fun noteInputCompleted() {
        lastInputAt = SystemClock.elapsedRealtime()
    }

    /** True when a model-driven point would land on the floating controls. */
    fun coversControls(x: Int, y: Int): Boolean {
        val view = panelView ?: return false
        val p = panelParams ?: return false
        return x in p.x until p.x + view.width && y in p.y until p.y + view.height
    }

    fun togglePause() {
        observationEpoch++
        ui.paused = !ui.paused
        ui.expanded = ui.paused
    }

    fun openChat() {
        observationEpoch++
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val target = container.runManager.runningSessionIds.value.firstOrNull() ?: sessionId
        container.pendingSessionId.tryEmit(target)
    }

    private val clearActivity = Runnable { ui.recentActivity = null }

    private fun flashActivity(activity: PhoneActivity) {
        ui.recentActivity = activity
        handler.removeCallbacks(clearActivity)
        handler.postDelayed(clearActivity, 900)
    }

    fun showAction(action: String, x: Int, y: Int) {
        flashActivity(if (action in setOf("key", "type")) PhoneActivity.TYPING else PhoneActivity.MOUSE)
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

    private fun panelDragModifier() = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val view = panelView ?: return@detectDragGestures
            val p = panelParams ?: return@detectDragGestures
            p.x = (p.x + dragAmount.x.roundToInt()).coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
            p.y = (p.y + dragAmount.y.roundToInt()).coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
            runCatching { windows.updateViewLayout(view, p) }
        }
    }

    @Composable
    private fun FloatingPanel() {
        androidx.compose.runtime.LaunchedEffect(ui.expanded, ui.paused) {
            if (ui.expanded && !ui.paused) {
                delay(5_000)
                ui.expanded = false
            }
        }
        val settings by container.settings.settings.collectAsState(initial = null)
        HarnessTheme(
            themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
            dynamicColor = settings?.dynamicColor ?: true,
        ) {
            val scheme = MaterialTheme.colorScheme
            val activity = when {
                ui.paused -> PhoneActivity.PAUSED
                ui.capturing -> PhoneActivity.SCREENSHOT
                ui.activity in setOf(PhoneActivity.ERROR, PhoneActivity.WAITING) -> ui.activity
                else -> ui.recentActivity ?: ui.activity
            }
            val statusLine = when {
                ui.paused -> "Paused"
                ui.capturing -> "Taking screenshot…"
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
                if (!ui.expanded) {
                    androidx.compose.material3.IconButton(
                        onClick = { ui.expanded = true },
                        modifier = Modifier.size(48.dp).then(panelDragModifier()),
                    ) {
                        Icon(activity.icon, contentDescription = "${activity.label}. Open phone controls. Drag to move.", tint = scheme.primary)
                    }
                } else Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = panelDragModifier(),
                    ) {
                        Icon(
                            activity.icon, contentDescription = activity.label,
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
                            onClick = { stopByUser() },
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
        captureActive = false
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        captureHandler.post {
            capturedImage?.close()
            capturedImage = null
            reader?.close()
            captureThread.quitSafely()
        }
        projection?.stop()
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

enum class PhoneActivity(val label: String, val icon: ImageVector) {
    SCREENSHOT("Taking screenshot", Icons.Outlined.Visibility),
    THINKING("Thinking", Icons.Outlined.Psychology),
    WORKING("Working", Icons.Outlined.Computer),
    MOUSE("Pointer action", Icons.Outlined.Mouse),
    TYPING("Typing", Icons.Outlined.Keyboard),
    WRITING("Writing response", Icons.Outlined.Edit),
    IDLE("Idle", Icons.Outlined.Bedtime),
    PAUSED("Paused", Icons.Outlined.PauseCircle),
    WAITING("Waiting", Icons.Outlined.HourglassEmpty),
    ERROR("Error", Icons.Outlined.ErrorOutline),
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
