package com.androidharness.app.phone

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity

class PhoneControlService : Service() {
    var sessionId = ""; private set
    @Volatile var paused = false; private set
    var screenWidth = 0; private set
    var screenHeight = 0; private set
    private val handler = Handler(Looper.getMainLooper())
    private val container get() = (application as HarnessApp).container
    private lateinit var windows: WindowManager
    private var panel: LinearLayout? = null
    private var cursor: TextView? = null
    private var label: TextView? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frame: Bitmap? = null
    private var frameAt = 0L
    private val monitor = object : Runnable {
        override fun run() {
            if (!container.phone.ready() || getSystemService(KeyguardManager::class.java).isKeyguardLocked || !android.provider.Settings.canDrawOverlays(this@PhoneControlService)) { stopSelf(); return }
            val bounds = windows.maximumWindowMetrics.bounds
            if (bounds.width() != screenWidth || bounds.height() != screenHeight) { stopSelf(); return }
            handler.postDelayed(this, 500)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        try {
            sessionId = intent?.getStringExtra("session") ?: error("Missing chat")
            check(container.phone.ready() && android.provider.Settings.canDrawOverlays(this))
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("phone_control", "Phone control", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 70, Intent(this, PhoneControlService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "phone_control").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Phone control active").setContentText("Screen may be shared with your selected AI provider")
                .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            startForeground(70, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            windows = getSystemService(WindowManager::class.java)
            val bounds = windows.maximumWindowMetrics.bounds
            screenWidth = bounds.width(); screenHeight = bounds.height()
            val consent = intent?.getParcelableExtra<Intent>("consent") ?: error("Missing capture consent")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    if (paused || SystemClock.elapsedRealtime() - frameAt < 250) return@use
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val next = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (next !== padded) padded.recycle()
                    synchronized(this) { frame?.recycle(); frame = next; frameAt = SystemClock.elapsedRealtime() }
                }
            }, handler)
            display = projection!!.createVirtualDisplay("Harness phone", screenWidth, screenHeight, resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            createOverlay()
            container.phone.service = this
            handler.post(monitor)
        } catch (_: Exception) { Toast.makeText(this, "Phone control could not start. Check capture, overlay and Shizuku permissions.", Toast.LENGTH_LONG).show(); stopSelf() }
        return START_NOT_STICKY
    }
    @Synchronized fun snapshot(): Bitmap? = if (!paused && SystemClock.elapsedRealtime() - frameAt < 2_000) frame?.copy(Bitmap.Config.ARGB_8888, false) else null
    private fun params(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; alpha = 0.75f }
    private fun createOverlay() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 8, 10, 8); setBackgroundColor(Color.rgb(24, 28, 36)) }
        label = TextView(this).apply { text = "Phone control · drag to move"; setTextColor(Color.WHITE); textSize = 13f }
        box.addView(label)
        val row = LinearLayout(this)
        fun button(title: String, action: (Button) -> Unit) { row.addView(Button(this).apply { text = title; textSize = 11f; setOnClickListener { action(this) } }) }
        button("Pause") { paused = !paused; it.text = if (paused) "Resume" else "Pause"; label?.text = if (paused) "Paused" else "Phone control active" }
        button("Chat") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); container.pendingSessionId.tryEmit(sessionId) }
        button("Stop") { stopSelf() }
        box.addView(row)
        val layout = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply { x = 12; y = 110 }
        var dx = 0f; var dy = 0f
        label!!.setOnTouchListener { _, e -> when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = layout.x - e.rawX; dy = layout.y - e.rawY; true }
            MotionEvent.ACTION_MOVE -> { layout.x = (e.rawX + dx).toInt().coerceIn(0, (screenWidth - box.width).coerceAtLeast(0)); layout.y = (e.rawY + dy).toInt().coerceIn(0, (screenHeight - box.height).coerceAtLeast(0)); windows.updateViewLayout(box, layout); true }
            else -> false
        } }
        windows.addView(box, layout); panel = box
        cursor = TextView(this).apply { text = "➤"; textSize = 28f; setTextColor(Color.CYAN); rotation = -35f }
        windows.addView(cursor, params(60, 60, false))
    }
    fun coversControls(x: Int, y: Int): Boolean {
        val view = panel ?: return false
        val p = view.layoutParams as WindowManager.LayoutParams
        return x in p.x until p.x + view.width && y in p.y until p.y + view.height
    }
    fun showAction(action: String, x: Int, y: Int) {
        label?.text = "Agent: $action"
        if (action in setOf("move", "click", "drag", "scroll")) cursor?.let { view ->
            val p = view.layoutParams as WindowManager.LayoutParams; p.x = x; p.y = y; windows.updateViewLayout(view, p)
        }
    }
    override fun onDestroy() {
        if (container.phone.service === this) container.phone.service = null
        if (sessionId.isNotBlank()) container.runManager.stop(sessionId)
        handler.removeCallbacksAndMessages(null)
        display?.release(); reader?.close(); projection?.stop()
        synchronized(this) { frame?.recycle(); frame = null }
        panel?.let { runCatching { windows.removeView(it) } }; cursor?.let { runCatching { windows.removeView(it) } }
        super.onDestroy()
    }
}
