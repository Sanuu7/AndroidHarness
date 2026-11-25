package com.androidharness.app.local

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** The build channel is explicit; installer identity cannot enable remote code in a Play build. */
enum class GpuInstallPhase { CHECKING, NOT_INSTALLED, WAITING, DOWNLOADING, INSTALLING, CONFIRMATION, READY, FAILED, CANCELLED, UNSUPPORTED }
data class GpuInstallState(
    val phase: GpuInstallPhase = GpuInstallPhase.CHECKING,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val libraryPath: String? = null,
) {
    val busy get() = phase in setOf(GpuInstallPhase.CHECKING, GpuInstallPhase.WAITING, GpuInstallPhase.DOWNLOADING, GpuInstallPhase.INSTALLING)
    val progress get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}
interface GpuDelivery {
    val state: StateFlow<GpuInstallState>
    fun install()
    fun cancel()
    fun confirm(activity: Activity)
    fun prepareForLoad() = Unit
    /** Reads the install state again, for screens that come back to the foreground. */
    fun refresh() = Unit
}
class GpuSupport(context: Context) {
    private val delivery = GpuDeliveryFactory.create(context.applicationContext)
    val state get() = delivery.state
    @Volatile private var loaded: String? = null
    @Volatile private var loadedDevice: String? = null
    fun install() = delivery.install()
    fun cancel() = delivery.cancel()
    fun refresh() = delivery.refresh()
    fun confirm(activity: Activity) = delivery.confirm(activity)

    /** The GPU name from an earlier [device] call, while the same runtime is still installed. Needs no lock. */
    internal fun cachedDevice(): String? {
        val installed = state.value
        if (installed.phase != GpuInstallPhase.READY) return ""
        return loadedDevice?.takeIf { loaded != null && loaded == installed.libraryPath }
    }

    // Called only while LocalModelManager owns its inference mutex. Never unload
    // a registered backend: its worker threads and function pointers can outlive a request.
    internal fun device(): String {
        val installed = state.value
        if (installed.phase != GpuInstallPhase.READY) return ""
        val path = installed.libraryPath ?: return ""
        if (loaded != path) {
            delivery.prepareForLoad()
            check(LocalNative.loadBackend(path)) { "GPU backend could not load." }
            loaded = path
        }
        return LocalNative.gpuDevice().also { loadedDevice = it }
    }
}
