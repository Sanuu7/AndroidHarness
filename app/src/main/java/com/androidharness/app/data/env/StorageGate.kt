package com.androidharness.app.data.env

import com.androidharness.app.tools.ToolFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Which paths are shared storage that the app user can only use with the storage grant. */
object SharedStorage {
    private val roots = listOf("/sdcard", "/storage", "/mnt/sdcard", "/mnt/user", "/mnt/media_rw", "/data/media")

    /** Android/data, Android/media and Android/obb folders of [packageName] need no grant. */
    fun needsGrant(path: String, packageName: String): Boolean {
        val normal = normalize(path)
        if (roots.none { normal == it || normal.startsWith("$it/") }) return false
        if (packageName.isEmpty()) return true
        return Regex("/Android/(data|media|obb)/${Regex.escape(packageName)}(/|$)").find(normal) == null
    }

    /** The first shared-storage path a command line names, outside this app's own folders. */
    fun mentionedIn(command: String, packageName: String): String? =
        Regex("""(?<![\w.~/-])(/sdcard|/storage/emulated/\d+|/storage/self/primary|/mnt/sdcard)(/[^\s'"`;|&<>()]*)?""")
            .findAll(command).map { it.value }.firstOrNull { needsGrant(it, packageName) }

    private fun normalize(path: String): String =
        runCatching { java.nio.file.Paths.get(path).normalize().toString() }.getOrDefault(path).trimEnd('/').ifEmpty { "/" }
}

/** Shown by the app as a dialog: a tool was stopped because [path] needs the storage grant. */
data class StorageAccessRequest(val path: String)

/**
 * The one place that decides whether a tool may touch shared storage.
 *
 * Without the storage grant Android does not fail cleanly there: on Android 11+
 * the app user sees a filtered tree (other apps' files are simply missing) and
 * can still create some files. A tool would report wrong results instead of an
 * error, so file tools and app-user commands are stopped before they run, the
 * user is asked in the app, and the call continues only once access exists.
 *
 * Until [install] runs (unit tests, previews) every path is allowed.
 */
object StorageGate {
    private const val WAIT_MS = 180_000L
    private const val QUIET_MS = 10 * 60_000L

    @Volatile private var granted: () -> Boolean = { true }
    @Volatile private var packageName = ""
    @Volatile private var privileged: suspend (File) -> Boolean = { false }
    @Volatile private var now: () -> Long = System::currentTimeMillis
    @Volatile private var quietUntil = 0L

    private val pending = MutableStateFlow<StorageAccessRequest?>(null)
    val request: StateFlow<StorageAccessRequest?> = pending.asStateFlow()

    fun install(packageName: String, granted: () -> Boolean, now: () -> Long = System::currentTimeMillis) {
        this.packageName = packageName
        this.granted = granted
        this.now = now
        quietUntil = 0L
        pending.value = null
    }

    /** True when commands in a folder run through Shizuku, Wireless ADB or root, which need no grant. */
    fun installPrivilegedRoute(check: suspend (File) -> Boolean) { privileged = check }

    internal fun reset() {
        install("", { true })
        privileged = { false }
    }

    fun needsGrant(path: String): Boolean = SharedStorage.needsGrant(path, packageName) && !granted()

    /** File tools always run as the app user. Asks the user, then fails the call. */
    fun requireFiles(what: String, file: File) {
        if (!needsGrant(file.path)) return
        throw blocked(what, file.path, ask(file.path))
    }

    /** Suspends while the dialog is on screen, so a grant lets the same call continue. */
    suspend fun awaitFiles(what: String, file: File) {
        if (!needsGrant(file.path)) return
        if (!awaitGrant(file.path)) throw blocked(what, file.path, asked = now() >= quietUntil)
    }

    /** Commands are fine when a privileged shell runs them; otherwise the same rule as files. */
    suspend fun awaitCommand(what: String, cwd: File) {
        if (!needsGrant(cwd.path) || privileged(cwd)) return
        if (!awaitGrant(cwd.path)) throw blocked(what, cwd.path, asked = now() >= quietUntil, command = true)
    }

    /** Explains odd results when an app-user command names shared storage it cannot fully see. */
    fun commandNote(command: String, failed: Boolean): String? {
        if (granted()) return null
        val path = SharedStorage.mentionedIn(command, packageName) ?: return null
        if (failed) ask(path)
        return "[note: this command names shared storage ($path) and ran as the app user without storage access. " +
            "Files there can be missing or unreadable. Do not treat that as real. Ask the user to grant " +
            "Settings → Storage access, or copy what you need into the workspace.]"
    }

    /** The user closed the dialog without granting. Stay quiet for a while instead of nagging. */
    fun decline() {
        quietUntil = now() + QUIET_MS
        pending.value = null
    }

    /** Call when the app returns to the foreground or the grant state was re-read. */
    fun refresh() {
        if (granted()) {
            quietUntil = 0L
            pending.value = null
        }
    }

    private fun ask(path: String): Boolean {
        if (now() < quietUntil) return false
        pending.compareAndSet(null, StorageAccessRequest(path))
        return true
    }

    private suspend fun awaitGrant(path: String): Boolean {
        if (!ask(path)) return false
        // Nobody can answer while the app is not on screen; fail now and leave the dialog for later.
        if (pending.subscriptionCount.value == 0) return false
        withTimeoutOrNull(WAIT_MS) { pending.first { it == null } }
        return !needsGrant(path)
    }

    private fun blocked(what: String, path: String, asked: Boolean, command: Boolean = false) = ToolFailure(
        "Blocked: $what needs shared storage ($path) and this app does not have storage access. " +
            (if (asked) "The app is asking the user to grant it. "
            else "The user chose not to grant it for now. ") +
            "Do not retry and do not work around this with another tool or path. Tell the user this step needs " +
            "storage access (Settings → Storage access)" +
            (if (command) ", or a privileged shell (Settings → Terminal)" else "") +
            ", or continue in the app workspace or a folder the user picks. Retry only after the user says access is granted.",
    )
}
