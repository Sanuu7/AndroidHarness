package com.androidharness.app.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
    @Volatile private var consentDeferred: CompletableDeferred<Boolean>? = null

    fun ready() = shizuku.isGranted() && shizuku.isServiceReady()

    fun notifyConsentSuccess() {
        consentDeferred?.complete(true)
    }

    fun notifyConsentFailed() {
        consentDeferred?.complete(false)
    }

    suspend fun execute(session: String?, action: String, x: Int, y: Int, x2: Int, y2: Int, text: String): ToolResult = mutex.withLock {
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
            if (!granted || service == null) {
                return@withLock ToolResult(false, "The user declined or cancelled phone control.")
            }
            host = service ?: return@withLock ToolResult(false, "Phone control service failed to start.")
        }

        if (host.sessionId != session && !host.paused) {
            host.sessionId = session
        }
        if (!PhoneInput.sessionAllowed(session, host.sessionId, host.paused)) return@withLock ToolResult(false, "Phone control belongs to another chat or is paused.")
        if (!ready()) {
            withContext(Dispatchers.Main) { host.stopSelf() }
            return@withLock ToolResult(false, "Shizuku connection lost.")
        }
        if (action == "status") return@withLock ToolResult(true, "Phone control active: ${host.screenWidth}x${host.screenHeight}. Use physical screen coordinates. Mouse actions affect other apps. Secure content cannot be captured.")
        if (action == "screenshot") {
            val bitmap = host.snapshot() ?: return@withLock ToolResult(false, "No fresh frame. Check capture consent or unlock the phone.")
            val name = "phone-${UUID.randomUUID()}.jpg"
            try { withContext(Dispatchers.IO) { File(images.imagesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } } }
            finally { bitmap.recycle() }
            return@withLock ToolResult(true, "Screen ${host.screenWidth}x${host.screenHeight}", ImageRef(name, "image/jpeg"))
        }
        val command = try { PhoneInput.command(action, x, y, x2, y2, text, host.screenWidth, host.screenHeight) }
        catch (e: IllegalArgumentException) { return@withLock ToolResult(false, e.message ?: "Invalid input") }
        val blocked = withContext(Dispatchers.Main) {
            host.coversControls(x, y) || (action == "drag" && host.coversControls(x2, y2))
        }
        if (blocked) return@withLock ToolResult(false, "Target overlaps the floating controls. Ask the user to move the panel.")
        if (action in PhoneControlService.POINTER_ACTIONS) {
            val focused = shizuku.runPrivileged(arrayOf("/system/bin/dumpsys", "window", "windows"), null, null, 2_000)
            val focusedPackage = focused?.takeIf { it.exitCode == 0 && !it.timedOut }?.output?.let(PhoneInput::focusedPackage)
            if (focusedPackage == context.packageName) {
                return@withLock ToolResult(false, "AndroidHarness is currently in front. Re-open the target app before using phone control.")
            }
        }
        withContext(Dispatchers.Main) { host.showAction(action, x, y) }
        if (service !== host || host.paused) return@withLock ToolResult(false, "Phone control stopped or paused.")
        val result = shizuku.runPrivileged(arrayOf("/system/bin/input", *command.toTypedArray()), null, null, 3_000)
        if (result != null && result.exitCode == 0 && !result.timedOut && action != "move") host.noteInputCompleted()
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

    fun focusedPackage(dumpsys: String): String? {
        val line = dumpsys.lineSequence().firstOrNull {
            it.contains("mCurrentFocus=") || it.contains("mFocusedApp=")
        } ?: return null
        return Regex("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+")
            .find(line)?.groupValues?.getOrNull(1)
    }
}
