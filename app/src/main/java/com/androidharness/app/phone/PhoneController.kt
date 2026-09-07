package com.androidharness.app.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.core.ImageRef
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PhoneController(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val images: ImageStore,
) {
    @Volatile var service: PhoneControlService? = null
    private val mutex = Mutex()
    private val observation = PhoneObservation()
    private var observedHost: PhoneControlService? = null
    private var observedEpoch = -1L

    private suspend fun foreground(): String? {
        // Filter on the device so large virtual-display dumps cannot truncate
        // display 0's focus before it crosses the Binder output limit.
        val result = shizuku.runPrivileged(arrayOf(
            "/system/bin/sh", "-c",
            "/system/bin/dumpsys window displays | /system/bin/grep -E 'Display: mDisplayId=|mCurrentFocus='",
        ), null, null, 2_000)
        return result?.takeIf { it.exitCode == 0 && !it.timedOut }?.output?.let(PhoneInput::focusedWindow)
    }
    @Volatile private var consentDeferred: CompletableDeferred<Boolean>? = null

    fun ready() = shizuku.isGranted() && shizuku.isServiceReady()

    fun notifyConsentSuccess() {
        consentDeferred?.complete(true)
    }

    fun notifyConsentFailed() {
        consentDeferred?.complete(false)
    }

    suspend fun execute(session: String?, action: String, x: Int, y: Int, x2: Int, y2: Int, text: String, imageWidth: Int = -1, imageHeight: Int = -1): ToolResult = mutex.withLock {
        if (session.isNullOrBlank()) {
            return@withLock ToolResult(false, "No active session ID for phone control.")
        }

        var host = service
        if (host == null) {
            if (!ready()) {
                return@withLock ToolResult(false, "Shizuku is not running or not authorized. Ensure Shizuku is running first.")
            }
            val deferred = CompletableDeferred<Boolean>()
            consentDeferred = deferred
            withContext(Dispatchers.Main) {
                val intent = Intent(context, PhoneConsentActivity::class.java).apply {
                    putExtra("session", session)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            val granted = try {
                deferred.await()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                consentDeferred = null
                throw ce
            } finally {
                consentDeferred = null
            }
            if (!granted) {
                return@withLock ToolResult(false, "Phone control was not started. The user may have cancelled, or capture setup failed. Do not retry consent without the user's request.")
            }
            host = service ?: return@withLock ToolResult(false, "Phone control service failed to start.")
        }

        if (!PhoneInput.sessionAllowed(session, host.sessionId, host.paused)) return@withLock ToolResult(false, "Phone control belongs to another chat or is paused.")
        if (!ready()) {
            withContext(Dispatchers.Main) { host.stopSelf() }
            return@withLock ToolResult(false, "Shizuku connection lost.")
        }
        if (action == "status") return@withLock ToolResult(true, "Phone control active: ${host.screenWidth}x${host.screenHeight}. Use physical screen coordinates. Mouse actions affect other apps. Secure content cannot be captured.")
        if (action == "screenshot") {
            observation.clear()
            val before = foreground()
            val bitmap = host.snapshot() ?: return@withLock ToolResult(false, "No fresh frame. Check capture consent or unlock the phone.")
            val name = "phone-${UUID.randomUUID()}.jpg"
            try { withContext(Dispatchers.IO) { File(images.imagesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } } }
            finally { bitmap.recycle() }
            val after = foreground()
            if (before != null && before == after && service === host && !host.paused) {
                observation.record(after, SystemClock.elapsedRealtime())
                observedHost = host
                observedEpoch = host.observationEpoch
            }
            return@withLock ToolResult(true,
                "Screen ${host.screenWidth}x${host.screenHeight}. Coordinates use these full-screen pixels. " +
                    if (before == null || before != after) "Focus changed or is unknown; take another screenshot before input."
                    else "Inspect this image before ONE input action. Foreground: $after",
                ImageRef(name, "image/jpeg"))

        }
        val point = try { PhoneInput.scalePoint(x, y, imageWidth, imageHeight, host.screenWidth, host.screenHeight) }
        catch (e: IllegalArgumentException) { return@withLock ToolResult(false, e.message ?: "Invalid image dimensions") }
        val end = try { PhoneInput.scalePoint(x2, y2, imageWidth, imageHeight, host.screenWidth, host.screenHeight) }
        catch (e: IllegalArgumentException) { return@withLock ToolResult(false, e.message ?: "Invalid image dimensions") }
        val px = point.first; val py = point.second
        val ex = end.first; val ey = end.second
        val command = try { PhoneInput.command(action, px, py, ex, ey, text, host.screenWidth, host.screenHeight) }
        catch (e: IllegalArgumentException) { return@withLock ToolResult(false, e.message ?: "Invalid input") }
        val blocked = withContext(Dispatchers.Main) {
            action in PhoneControlService.POINTER_ACTIONS &&
                (host.coversControls(px, py) || (action == "drag" && host.coversControls(ex, ey)))
        }
        if (blocked) return@withLock ToolResult(false, "Target overlaps the floating controls. Ask the user to move the panel.")
        val current = foreground()
        val ownApp = current?.let { PhoneInput.focusedPackage("mCurrentFocus=$it") } == context.packageName
        if (service !== host || host.paused || observedHost !== host || observedEpoch != host.observationEpoch ||
            ownApp || !observation.consume(current, SystemClock.elapsedRealtime(), consume = action != "move")) {
            observation.clear()
            return@withLock ToolResult(false, "Input blocked: take a new screenshot of the target app. Focus changed, observation expired, or phone control paused. No input sent.")
        }
        // Moving the drawn pointer is not an input event and must not consume
        // the observation needed by the subsequent click.
        if (action == "move") {
            withContext(Dispatchers.Main) { host.showAction(action, px, py) }
            return@withLock ToolResult(true, "Pointer moved. The same screenshot can be used for the next click.")
        }
        val result = shizuku.runPrivileged(arrayOf("/system/bin/input", *command.toTypedArray()), null, null, 3_000)
        if (result != null && result.exitCode == 0 && !result.timedOut) {
            host.noteInputCompleted()
            withContext(Dispatchers.Main) { host.showAction(action, px, py) }
        }
        ToolResult(result != null && result.exitCode == 0 && !result.timedOut,
            if (result == null) "Shizuku unavailable" else if (result.exitCode != 0 || result.timedOut) "Mouse command failed: ${result.output} ${result.stderr}" else "$action completed. Take a fresh screenshot before the next decision.")
    }
}

object PhoneInput {
    fun sessionAllowed(request: String?, owner: String, paused: Boolean) =
        !request.isNullOrBlank() && request == owner && !paused

    fun command(action: String, x: Int, y: Int, x2: Int, y2: Int, text: String, width: Int, height: Int): List<String> {
        fun point(a: Int, b: Int) { require(a in 0 until width && b in 0 until height) { "Coordinates outside ${width}x$height" } }
        if (action in setOf("move", "click", "drag", "scroll")) point(x, y)
        return when (action) {
            "move" -> listOf("mouse", "motionevent", "MOVE", "$x", "$y")
            "click" -> listOf("touchscreen", "tap", "$x", "$y")
            "drag" -> { point(x2, y2); listOf("touchscreen", "swipe", "$x", "$y", "$x2", "$y2", "350") }
            "scroll" -> { val amount = text.toIntOrNull(); require(amount != null && amount in -10..10 && amount != 0) { "Scroll text must be a nonzero integer from -10 to 10" }; listOf("mouse", "scroll", "$x", "$y", "--axis", "VSCROLL,$amount") }
            "key" -> { require(text in setOf("BACK", "HOME", "ENTER", "DEL", "TAB", "APP_SWITCH")) { "Unsupported key" }; listOf("keyevent", "KEYCODE_$text") }
            "type" -> { require(text.length in 1..500 && text.all { it.code in 32..126 } && "%s" !in text) { "Typing supports 1-500 printable ASCII characters, excluding literal %s. Use the phone keyboard for other text." }; listOf("text", text.replace(" ", "%s")) }
            else -> throw IllegalArgumentException("Unknown phone action")
        }
    }

    fun scalePoint(x: Int, y: Int, imageWidth: Int, imageHeight: Int, width: Int, height: Int): Pair<Int, Int> {
        if (imageWidth == -1 && imageHeight == -1) return x to y
        require(imageWidth > 0 && imageHeight > 0) { "Provide both screenshot_width and screenshot_height." }
        require(kotlin.math.abs(imageWidth.toDouble() / imageHeight - width.toDouble() / height) < 0.01) {
            "Screenshot aspect ratio differs from the screen. Use an uncropped screenshot."
        }
        fun scale(value: Int, source: Int, target: Int): Int =
            if (value < 0) value else if (value >= source) target else (value.toLong() * target / source).toInt()
        return scale(x, imageWidth, width) to scale(y, imageHeight, height)
    }

    fun focusedWindow(dumpsys: String): String? {
        val header = Regex("Display: mDisplayId=([0-9]+)")
        val hasDisplays = header.containsMatchIn(dumpsys)
        var display: Int? = null
        for (line in dumpsys.lineSequence()) {
            header.find(line)?.let { display = it.groupValues[1].toIntOrNull() }
            if ((!hasDisplays || display == 0) && line.trimStart().startsWith("mCurrentFocus=")) {
                // A null focus on the physical display is a real transition.
                // Never substitute another display's app or a stale focused activity.
                return line.substringAfter("mCurrentFocus=").trim().takeIf {
                    it.startsWith("Window{") && Regex("([A-Za-z0-9_.]+)/").containsMatchIn(it)
                }
            }
        }
        return null
    }

    fun focusedPackage(dumpsys: String): String? =
        focusedWindow(dumpsys)?.let {
            Regex("([A-Za-z0-9_.]+)/").find(it)?.groupValues?.get(1)
        }
}

/** A screenshot authorizes one input in the same focused window, for a bounded time. */
class PhoneObservation {
    private var window: String? = null
    private var at = 0L

    fun record(window: String, now: Long) { this.window = window; at = now }
    fun clear() { window = null }
    fun consume(current: String?, now: Long, consume: Boolean = true): Boolean {
        val allowed = window != null && current == window && now - at in 0..30_000
        if (consume || !allowed) clear()
        return allowed
    }
}
