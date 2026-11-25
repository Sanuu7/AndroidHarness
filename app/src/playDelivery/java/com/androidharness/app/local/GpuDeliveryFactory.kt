package com.androidharness.app.local

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Process
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.*
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object GpuDeliveryFactory {
    fun create(context: Context): GpuDelivery = PlayGpuDelivery(context)
    fun attach(context: Context) { SplitCompat.install(context) }
}
internal class PlayGpuDelivery(private val context: Context) : GpuDelivery {
    private val manager = SplitInstallManagerFactory.create(context)
    private val mutable = MutableStateFlow(GpuInstallState())
    override val state = mutable.asStateFlow()
    private var session: SplitInstallSessionState? = null
    private var sessionId: Int? = null
    private var cancelRequested = false
    private val supported = Process.is64Bit() && Build.SUPPORTED_ABIS.firstOrNull() in setOf("arm64-v8a", "x86_64")
    private val listener = SplitInstallStateUpdatedListener { value ->
        if (MODULE in value.moduleNames()) update(value)
    }
    init {
        manager.registerListener(listener)
        if (!supported) mutable.value = GpuInstallState(GpuInstallPhase.UNSUPPORTED)
        else if (MODULE in manager.installedModules) installed()
        else {
            mutable.value = GpuInstallState(GpuInstallPhase.NOT_INSTALLED)
            manager.sessionStates.addOnSuccessListener { sessions ->
                sessions.lastOrNull { MODULE in it.moduleNames() && it.status() in setOf(
                    SplitInstallSessionStatus.PENDING, SplitInstallSessionStatus.DOWNLOADING,
                    SplitInstallSessionStatus.DOWNLOADED, SplitInstallSessionStatus.INSTALLING,
                    SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION, SplitInstallSessionStatus.CANCELING)
                }?.let(::update)
            }
        }
    }
    private fun installed() {
        try {
            SplitCompat.install(context)
            mutable.value = GpuInstallState(GpuInstallPhase.READY, libraryPath = "libggml-vulkan.so")
        } catch (e: Exception) { fail(e.message) }
    }
    private fun update(value: SplitInstallSessionState) {
        session = value
        sessionId = value.sessionId()
        if (value.status() == SplitInstallSessionStatus.INSTALLED) { installed(); return }
        val phase = when (value.status()) {
            SplitInstallSessionStatus.PENDING, SplitInstallSessionStatus.CANCELING -> GpuInstallPhase.WAITING
            SplitInstallSessionStatus.DOWNLOADING -> GpuInstallPhase.DOWNLOADING
            SplitInstallSessionStatus.DOWNLOADED, SplitInstallSessionStatus.INSTALLING -> GpuInstallPhase.INSTALLING
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> GpuInstallPhase.CONFIRMATION
            SplitInstallSessionStatus.CANCELED -> GpuInstallPhase.CANCELLED
            SplitInstallSessionStatus.FAILED -> GpuInstallPhase.FAILED
            else -> return
        }
        mutable.value = GpuInstallState(phase, value.bytesDownloaded(), value.totalBytesToDownload(),
            error = if (phase == GpuInstallPhase.FAILED) "Google Play could not install GPU support (${value.errorCode()}). Please retry." else null)
    }
    override fun install() {
        if (!supported || state.value.busy || state.value.phase == GpuInstallPhase.READY) return
        mutable.value = GpuInstallState(GpuInstallPhase.WAITING)
        cancelRequested = false
        manager.startInstall(SplitInstallRequest.newBuilder().addModule(MODULE).build())
            // Play answers with session 0 and sends no updates when the module is already on the device.
            .addOnSuccessListener { id -> sessionId = id; if (cancelRequested) manager.cancelInstall(id); if (id == 0) refresh() }
            .addOnFailureListener { fail(it.message) }
    }
    override fun refresh() {
        if (!supported) return
        if (MODULE in manager.installedModules) { if (state.value.phase != GpuInstallPhase.READY) installed(); return }
        if (!state.value.busy) return
        manager.sessionStates.addOnSuccessListener { sessions ->
            val current = sessions.lastOrNull { MODULE in it.moduleNames() }
            if (current != null) update(current)
            else if (MODULE in manager.installedModules) installed()
        }
    }
    override fun cancel() {
        cancelRequested = true
        sessionId?.let { manager.cancelInstall(it).addOnFailureListener { error -> fail(error.message) } }
    }
    override fun confirm(activity: Activity) {
        session?.let { manager.startConfirmationDialogForResult(it, activity, 5804) }
    }
    override fun prepareForLoad() { SplitInstallHelper.loadLibrary(context, "ggml-vulkan") }
    private fun fail(message: String?) { mutable.value = GpuInstallState(GpuInstallPhase.FAILED, error = message) }
    companion object { const val MODULE = "local_gpu" }
}
