package com.androidharness.app.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updater backed by GitHub Releases.
 *
 * Flow: check latest release -> if its version is NEWER than the running one,
 * surface a dialog (with clickable changelog links parsed from the release
 * body) -> download the .apk asset with progress -> install:
 *
 *  - Shizuku granted + user service connected: `pm install -r` runs silently
 *    as the shell uid (the APK is staged under the app's external files dir,
 *    which the shell uid demonstrably reads — the toolchain deploy uses the
 *    exact same path family).
 *  - No Shizuku: hand the downloaded file to the system package installer
 *    (the standard "express" sideload flow with the platform's own UI).
 */
class UpdateManager(
    private val context: Context,
    private val shizuku: com.androidharness.app.data.env.ShizukuManager,
) {

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, DONE, ERROR }

    data class LatestRelease(
        val tag: String,
        val name: String,
        /** Release body/description — may contain plain links or a linked changelog.md. */
        val body: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val apkName: String?,
        val apkBytes: Long,
    )

    sealed interface Step {
        data object Idle : Step
        data object Checking : Step
        data class UpToDate(val current: String) : Step
        data class Available(val release: LatestRelease) : Step
        data class Downloading(val release: LatestRelease, val percent: Int, val mb: Float, val totalMb: Float) : Step
        data class Installing(val release: LatestRelease, val viaShizuku: Boolean) : Step
        data class Done(val viaShizuku: Boolean) : Step
        data class Error(val message: String, val release: LatestRelease? = null) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Where downloads stage: shared external files dir (shell-uid readable). */
    private val updateDir: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "update",
        ).apply { mkdirs() }

    /** Manual flag so a Settings-triggered check always surfaces its result. */
    @Volatile private var manualCheck = false

    suspend fun check(manual: Boolean = false): Unit = withContext(Dispatchers.IO) {
        manualCheck = manual || manualCheck
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
        val alreadyRunning = when (val s = _step.value) {
            is Step.Checking, is Step.Downloading, is Step.Installing -> true
            else ->
                // Auto checks shouldn't nag again over an existing dialog.
                !manual && (s is Step.Available || s is Step.Error)
        }
        if (alreadyRunning) return@withContext
        try {
            _step.value = Step.Checking
            val release = fetchLatest()
            val newer = release != null && isNewer(release.name.ifBlank { release.tag }, current)
            when {
                release == null -> _step.value =
                    if (manualCheck) Step.Error("No published release found yet.") else Step.Idle
                newer -> _step.value = Step.Available(release)
                manualCheck -> _step.value = Step.UpToDate(current)
                else -> _step.value = Step.Idle
            }
        } catch (e: Exception) {
            if (manualCheck) {
                _step.value = Step.Error(e.message ?: "Update check failed")
            } else {
                _step.value = Step.Idle
            }
        } finally {
            manualCheck = false
        }
    }

    fun dismiss() {
        _step.value = Step.Idle
    }

    private fun fetchLatest(): LatestRelease? {
        val req = Request.Builder()
            .url("$RELEASES_API/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 404 simply means no releases published yet.
                if (resp.code == 404) return null
                throw IllegalStateException("GitHub API HTTP ${resp.code}")
            }
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val draft = root["draft"]?.jsonPrimitive?.content == "true"
            if (draft) return null
            val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
            val name = root["name"]?.jsonPrimitive?.content.orEmpty()
            val body = root["body"]?.jsonPrimitive?.content.orEmpty()
            val url = root["html_url"]?.jsonPrimitive?.content.orEmpty()
            var apkUrl: String? = null
            var apkName: String? = null
            var apkBytes = 0L
            root["assets"]?.jsonArray?.forEach { el ->
                val a = el.jsonObject
                val assetName = a["name"]?.jsonPrimitive?.content.orEmpty()
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a["browser_download_url"]?.jsonPrimitive?.content
                    apkName = assetName
                    apkBytes = a["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                }
            }
            if (tag.isBlank()) return null
            return LatestRelease(tag, name.ifBlank { tag }, body, url, apkUrl, apkName, apkBytes)
        }
    }

    /**
     * Compares like "0.3-alpha": numeric dot-parts of the version prefix;
     * anything unparsable never prompts (no nagging on odd tags).
     */
    internal fun isNewer(remote: String, current: String): Boolean {
        val r = parseParts(remote) ?: return false
        val c = parseParts(current) ?: return false
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseParts(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        val cleaned = core.filter { it.isDigit() || it == '.' }
        if (cleaned.isEmpty() || cleaned.none { it.isDigit() }) return null
        return cleaned.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** Downloads the release APK, reporting progress through [step]. */
    private fun download(release: LatestRelease): File {
        val out = File(updateDir, release.apkName ?: "harness-update.apk")
        val req = Request.Builder().url(release.apkUrl!!).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkBytes
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val mb = read / 1_048_576f
                            val totalMb = total / 1_048_576f
                            _step.value = Step.Downloading(
                                release,
                                ((read * 100) / total).toInt().coerceIn(0, 100),
                                (mb * 10).toInt() / 10f,
                                (totalMb * 10).toInt() / 10f,
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * Runs the install leg. Shizuku takes priority; system installer is the
     * fallback. Returns after handing off ([onNeedUserInstaller] opens the
     * platform flow) — the caller drives UI state around this.
     */
    suspend fun startUpdate(
        release: LatestRelease,
        onOpenSystemInstaller: (File) -> Unit,
        onOpenUnknownSourcesSettings: () -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val apk = runCatching { download(release) }.getOrElse { e ->
            _step.value = Step.Error(e.message ?: "Download failed", release)
            return@withContext
        }
        val viaShizuku = tryShizukuInstall(apk)
        if (viaShizuku) {
            _step.value = Step.Done(viaShizuku = true)
            apk.delete()
            return@withContext
        }
        _step.value = Step.Installing(release, viaShizuku = false)
        // System path requires the unknown-sources grant first.
        val canInstall = if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else true
        if (!canInstall) {
            _step.value = Step.Error(NEED_INSTALL_PERMISSION, release)
            onOpenUnknownSourcesSettings()
            return@withContext
        }
        onOpenSystemInstaller(apk)
    }

    /** Called from the Activity once the user granted unknown-sources. */
    suspend fun continueSystemInstall(release: LatestRelease, apk: File, onOpenSystemInstaller: (File) -> Unit) {
        _step.value = Step.Installing(release, viaShizuku = false)
        onOpenSystemInstaller(apk)
    }

    private suspend fun tryShizukuInstall(apk: File): Boolean {
        if (!shizuku.isGranted()) return false
        val r = shizuku.runPrivileged(
            arrayOf("/system/bin/sh", "-c", "pm install -r '${apk.absolutePath}'"),
            env = null,
            dir = null,
            timeoutMs = 120_000,
            maxBytes = 4_000,
        ) ?: return false // user service not connected → fall back
        return r.exitCode == 0 &&
            !r.output.contains("Failure", ignoreCase = true) &&
            !r.stderr.contains("Exception", ignoreCase = true)
    }

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/Sanuu7/AndroidHarness/releases"
        const val NEED_INSTALL_PERMISSION =
            "Allow installs from this source to continue. Grant it, then tap Update again."
    }
}
