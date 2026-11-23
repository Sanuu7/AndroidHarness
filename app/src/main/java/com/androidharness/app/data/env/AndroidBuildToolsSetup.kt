package com.androidharness.app.data.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * What an on-device Android build needs, shared by the `android_sdk_install` agent tool and
 * the one-tap setup in Terminal settings so both install exactly the same things.
 */
class AndroidBuildToolsSetup(private val linuxEnv: LinuxEnvironmentManager) {

    data class Status(val linuxReady: Boolean, val missing: List<String>, val platform: Boolean) {
        val ready: Boolean get() = linuxReady && missing.isEmpty() && platform
    }

    val sdkRoot: File get() = File(linuxEnv.prefix, SDK_DIR)

    fun packages(gradle: Boolean): List<String> = if (gradle) BASE_PACKAGES + GRADLE else BASE_PACKAGES

    fun platformInstalled(api: Int): Boolean =
        AndroidSdkRepository.androidJar(sdkRoot, api).let { it.isFile && it.length() > 1_000_000L }

    fun status(api: Int = AndroidSdkRepository.DEFAULT_API, gradle: Boolean = false): Status {
        if (!linuxEnv.isReady) return Status(false, packages(gradle), platformInstalled(api))
        val installed = linuxEnv.installedPackages().toSet()
        return Status(true, packages(gradle).filter { it !in installed }, platformInstalled(api))
    }

    fun gradleInstalled(): Boolean = linuxEnv.isReady && linuxEnv.installedContains(GRADLE)

    /** Installs the packages, then android.jar. Progress covers both as one list. */
    suspend fun install(
        api: Int,
        gradle: Boolean,
        owner: String,
        onProgress: suspend (PackageInstallStatus) -> Unit,
        onPlatformProgress: (List<String>, String, Long, Long?) -> Unit,
    ): File {
        check(linuxEnv.isReady) { "Linux environment is not ready or not installed. The environment must be installed first." }
        val packages = packages(gradle)
        linuxEnv.install(packages, owner, onProgress).getOrThrow()
        check(linuxEnv.isReady && packages.all(linuxEnv::installedContains)) {
            "Package installation did not complete. Check the Linux environment status."
        }
        val label = platformLabel(api)
        val installer = AndroidSdkInstaller(sdkRoot, File(linuxEnv.prefix, "tmp/android-sdk"))
        val jar = runInterruptible(Dispatchers.IO) {
            installer.installPlatform(api) { done, total -> onPlatformProgress(packages, label, done, total) }
        }
        linuxEnv.invalidateExternalToolDeploy()
        return jar
    }

    /** What the one-tap setup in Settings is doing. Lives past the screen so leaving Settings doesn't cancel it. */
    sealed interface Run {
        data object Idle : Run
        data class Working(val label: String, val doneBytes: Long, val totalBytes: Long?) : Run
        data object Done : Run
        data class Failed(val message: String) : Run
    }

    companion object {
        private val runScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val _run = kotlinx.coroutines.flow.MutableStateFlow<Run>(Run.Idle)
        val run: kotlinx.coroutines.flow.StateFlow<Run> = _run

        /** Starts the Settings install unless one is already going. */
        fun startFromSettings(linuxEnv: LinuxEnvironmentManager, gradle: Boolean, api: Int = AndroidSdkRepository.DEFAULT_API) {
            if (_run.value is Run.Working) return
            _run.value = Run.Working("", 0, null)
            runScope.launch {
                _run.value = try {
                    AndroidBuildToolsSetup(linuxEnv).install(api, gradle, "settings",
                        onProgress = { s -> _run.value = Run.Working(s.currentPackage ?: s.phase.name.lowercase(), s.downloadedBytes, s.totalBytes) },
                        onPlatformProgress = { _, label, done, total -> _run.value = Run.Working(label, done, total) })
                    Run.Done
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Run.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }

        const val SDK_DIR_IN_HOME = "android-sdk"
        const val SDK_DIR = "home/$SDK_DIR_IN_HOME"
        const val GRADLE = "gradle"
        val BASE_PACKAGES = listOf("openjdk-21", "d8", "apksigner", "aapt2")
        fun platformLabel(api: Int) = "Android SDK platform $api"
    }
}
