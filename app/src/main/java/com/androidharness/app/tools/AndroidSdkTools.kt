package com.androidharness.app.tools

import com.androidharness.app.data.env.AndroidSdkInstaller
import com.androidharness.app.data.env.AndroidSdkRepository
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.PackageInstallPhase
import com.androidharness.app.data.env.PackageInstallStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * One approval and one progress card for everything an on-device Android build needs:
 * the Linux packages plus android.jar from Google's SDK repository. The packages come
 * from the same repository `pkg_install` uses, so this behaves the same in every build.
 */
class AndroidSdkInstallTool(
    private val isReady: () -> Boolean,
    private val installedPackages: () -> List<String>,
    private val installPackages: suspend (List<String>, String, suspend (PackageInstallStatus) -> Unit) -> Unit,
    private val installPlatform: suspend (Int, (Long, Long?) -> Unit) -> File,
    private val platformInstalled: (Int) -> Boolean = { false },
    private val afterInstall: () -> Unit = {},
) : Tool {

    constructor(linuxEnv: LinuxEnvironmentManager) : this(
        isReady = { linuxEnv.isReady },
        installedPackages = { linuxEnv.installedPackages() },
        installPackages = { packages, owner, progress ->
            linuxEnv.install(packages, owner, progress).getOrThrow()
            check(linuxEnv.isReady && packages.all(linuxEnv::installedContains)) { "Package installation did not complete. Check the Linux environment status." }
        },
        installPlatform = { api, progress ->
            val installer = AndroidSdkInstaller(File(linuxEnv.prefix, SDK_DIR), File(linuxEnv.prefix, "tmp/android-sdk"))
            runInterruptible(Dispatchers.IO) { installer.installPlatform(api, progress) }
        },
        platformInstalled = { api -> AndroidSdkRepository.androidJar(File(linuxEnv.prefix, SDK_DIR), api).let { it.isFile && it.length() > 1_000_000L } },
        afterInstall = { linuxEnv.invalidateExternalToolDeploy() },
    )

    override val name = NAME
    override val description =
        "Set up everything needed to build Android apps on this phone in one step: OpenJDK, D8, aapt2 and apksigner " +
        "(plus Gradle when gradle=true) from the Linux package repository, and android.jar for an Android API level " +
        "from Google's SDK repository. Shows one install card and requires explicit user approval. " +
        "Skips anything already installed. Load the android-build skill for the build steps afterwards."

    override val parametersSchema = Schema.obj(
        mapOf(
            "platform" to Schema.integer("Android API level to compile against, ${AndroidSdkRepository.SUPPORTED_API.first}-${AndroidSdkRepository.SUPPORTED_API.last}. Default ${AndroidSdkRepository.DEFAULT_API}."),
            "gradle" to Schema.boolean("Also install Gradle, for projects that ship a build.gradle. Default false."),
            "reason" to Schema.string("Short explanation of why this setup is needed for the current task."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.workspace is com.androidharness.app.workspace.SshFs) {
            return ToolResult(false, "Android SDK setup installs onto this phone's Linux environment, not onto an SSH host.")
        }
        if (!isReady()) {
            return ToolResult(false, "Linux environment is not ready or not installed. The environment must be installed first.")
        }
        val plan = try { plan(args) } catch (e: IllegalArgumentException) { return ToolResult(false, e.message.orEmpty()) }

        return try {
            val before = installedPackages().toSet()
            installPackages(plan.packages, ctx.workspace.displayPath, ctx.onPackageProgress)

            val label = "Android SDK platform ${plan.api}"
            val alreadyHadPlatform = platformInstalled(plan.api)
            val jar = installPlatform(plan.api) { downloaded, total ->
                runBlocking {
                    ctx.onPackageProgress(PackageInstallStatus(
                        requested = plan.packages + label, phase = PackageInstallPhase.DOWNLOADING,
                        packages = plan.packages + label, completed = plan.packages,
                        currentPackage = label, downloadedBytes = downloaded, totalBytes = total,
                    ))
                }
            }
            afterInstall()

            val added = plan.packages.filter { it !in before }
            ToolResult(true, buildList {
                if (added.isNotEmpty()) add("Installed packages: ${added.joinToString(", ")}.")
                else add("Packages already installed: ${plan.packages.joinToString(", ")}.")
                add(if (alreadyHadPlatform) "android.jar for API ${plan.api} was already present." else "Downloaded android.jar for API ${plan.api} from Google's SDK repository.")
                add("ANDROID_JAR=\$HOME/$SDK_DIR_IN_HOME/platforms/android-${plan.api}/android.jar (${jar.length() / 1_048_576} MB).")
                add("Next: load the android-build skill, then run its build-apk.sh${if (plan.gradle) " or ./gradlew" else ""}.")
            }.joinToString(" "))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Android SDK setup failed: ${e.message}")
        }
    }

    internal data class Plan(val api: Int, val gradle: Boolean, val packages: List<String>)

    companion object {
        const val NAME = "android_sdk_install"
        private const val SDK_DIR_IN_HOME = "android-sdk"
        private const val SDK_DIR = "home/$SDK_DIR_IN_HOME"
        private val BASE_PACKAGES = listOf("openjdk-21", "d8", "apksigner", "aapt2")

        internal fun plan(args: JsonObject): Plan {
            val api = (args["platform"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                ?: AndroidSdkRepository.DEFAULT_API
            require(api in AndroidSdkRepository.SUPPORTED_API) {
                "Android platform $api is not supported. Use ${AndroidSdkRepository.SUPPORTED_API.first}-${AndroidSdkRepository.SUPPORTED_API.last}."
            }
            val gradle = (args["gradle"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
            return Plan(api, gradle, if (gradle) BASE_PACKAGES + "gradle" else BASE_PACKAGES)
        }

        /** Shown on the approval card. Mentions the SDK license because the download is Google's. */
        fun preview(argumentsJson: String): String? = runCatching {
            val args = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as JsonObject
            val plan = plan(args)
            val reason = (args["reason"] as? JsonPrimitive)?.contentOrNull
            buildString {
                append("Linux packages to install:\n")
                plan.packages.forEach { append("  • ").append(it).append('\n') }
                append("\nFrom Google's SDK repository (dl.google.com):\n")
                append("  • Android SDK platform ${plan.api} (android.jar only, about 27 MB after a 64 MB download)\n")
                if (!reason.isNullOrBlank()) append("\nReason: ").append(reason).append('\n')
                append("\nThe Android SDK is provided under Google's Android Software Development Kit License Agreement.")
                append("\nNote: this downloads data and modifies your Linux environment.")
            }.trim()
        }.getOrNull()
    }
}
