package com.androidharness.app.router

import android.content.Context
import android.os.Build
import com.androidharness.app.data.ProviderRepository
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.llm.*
import com.androidharness.app.tools.SecretRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class NineRouterPhase { SETUP_LINUX, INSTALL, READY, INSTALLING_LINUX, INSTALLING, STARTING, RUNNING, STOPPING, UPDATING, UNINSTALLING, CONNECTING, REMOVING_PROVIDER }
data class NineRouterState(
    val phase: NineRouterPhase, val detail: String = "", val error: String? = null,
    val installedVersion: String? = null, val latestVersion: String? = null,
    val hasFiles: Boolean = false, val notice: String? = null,
    val checkingUpdate: Boolean = false, val autoStart: Boolean = false, val providerAdded: Boolean = false,
) {
    val busy get() = phase !in setOf(NineRouterPhase.SETUP_LINUX, NineRouterPhase.INSTALL, NineRouterPhase.READY, NineRouterPhase.RUNNING)
    val updateAvailable: Boolean get() {
        val current = installedVersion ?: return false
        val latest = latestVersion ?: return false
        return runCatching { NineRouterRelease.newer(latest, current) }.getOrDefault(false)
    }
}

/** Only one lifecycle mutation runs at a time. Service lifetime is independent of Settings. */
class NineRouterManager(
    context: Context, private val linux: LinuxEnvironmentManager,
    private val acquire: () -> Unit, private val release: () -> Unit,
    private val providers: ProviderRepository, private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(context.filesDir, "9router")
    private val prefs = context.getSharedPreferences("nine_router", Context.MODE_PRIVATE)
    private val server get() = File(root, "node_modules/9router/app/custom-server.js")
    private val marker = File(root, ".installed")
    private var process: Process? = null
    private var working = false
    private var stopping = false
    private var outputReader: Job? = null
    private val logState = MutableStateFlow("")
    val logs = logState.asStateFlow()
    private val mutable = MutableStateFlow(NineRouterState(idlePhase(), installedVersion = installedVersion(), autoStart = prefs.getBoolean("autoStart", false), hasFiles = root.exists(), latestVersion = prefs.getString("latestVersion", null)))
    val state = mutable.asStateFlow()
    init { scope.launch { providers.providers.collect { list -> mutable.update { it.copy(providerAdded = list.any(::isRouterProvider)) } } } }
    fun installedVersion(): String? = marker.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }
    private fun idlePhase() = when {
        process?.isAlive == true -> NineRouterPhase.RUNNING
        !linux.isReady -> NineRouterPhase.SETUP_LINUX
        !server.isFile || installedVersion() == null -> NineRouterPhase.INSTALL
        else -> NineRouterPhase.READY
    }
    fun refresh() = synchronized(this) { if (!working) mutable.update { it.copy(phase = idlePhase(), installedVersion = installedVersion(), hasFiles = root.exists()) } }
    private fun work(phase: NineRouterPhase, action: suspend () -> Unit) = synchronized(this) {
        if (working) return@synchronized
        working = true
        mutable.update { it.copy(phase = phase, detail = "", error = null, notice = null) }
        scope.launch {
            acquire()
            try { action() }
            catch (e: Exception) { mutable.update { it.copy(error = SecretRedactor.redact(e.message ?: "9Router could not complete this action.")) } }
            finally {
                synchronized(this@NineRouterManager) {
                    working = false
                    mutable.update { it.copy(phase = idlePhase(), installedVersion = installedVersion(), detail = "", hasFiles = root.exists()) }
                }
                release()
            }
        }
    }
    fun installLinux() = work(NineRouterPhase.INSTALLING_LINUX) {
        linux.install(linux.fullPackages, "9Router") { status -> mutable.update { it.copy(detail = listOfNotNull(status.phase.name.lowercase(), status.currentPackage).joinToString(" · ")) } }.getOrThrow()
    }
    fun install() = work(NineRouterPhase.INSTALLING) { check(process?.isAlive != true); installPayload(VERSION) }
    private suspend fun installPayload(version: String) {
        NineRouterRelease.validate(version)
        check(linux.isReady) { "Install the Linux toolchain first." }
        linux.install(listOf("nodejs", "npm"), "9Router").getOrThrow()
        root.mkdirs()
        val stage = File(root, ".update")
        check(!stage.exists() || stage.deleteRecursively()) { "Could not clear the previous install attempt." }
        stage.mkdirs()
        val backup = File(root, ".previous_modules")
        val modules = File(root, "node_modules")
        val quote = { text: String -> "'" + text.replace("'", "'\\''") + "'" }
        val installer = linux.shellProcessBuilder("npm install --prefix ${quote(stage.absolutePath)} --ignore-scripts --omit=optional --no-audit --no-fund 9router@${version} sql.js@1.14.1")
            .directory(stage).redirectErrorStream(true).apply { environment()["npm_config_cache"] = File(root, "cache/npm").absolutePath }.start()
        val tail = StringBuilder()
        val reader = scope.launch { drain(installer, tail) }
        try {
            check(installer.waitFor(15, TimeUnit.MINUTES)) { "Installation timed out. Check your connection and retry." }
            reader.join()
            check(installer.exitValue() == 0 && File(stage, "node_modules/9router/app/custom-server.js").isFile) { "9Router installation failed.\n${synchronized(tail) { tail.toString() }}" }
            check(!backup.exists() || backup.deleteRecursively()) { "Could not prepare the update." }
            val hadModules = modules.exists()
            if (hadModules) check(modules.renameTo(backup)) { "Could not prepare the installed files for update." }
            try {
                check(File(stage, "node_modules").renameTo(modules)) { "Could not activate the new 9Router version." }
                File(stage, "package.json").copyTo(File(root, "package.json"), overwrite = true)
                File(stage, "package-lock.json").copyTo(File(root, "package-lock.json"), overwrite = true)
                val atomic = android.util.AtomicFile(marker)
                val stream = atomic.startWrite()
                try { stream.write(version.toByteArray()); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
            } catch (e: Exception) {
                modules.deleteRecursively()
                if (hadModules) check(backup.renameTo(modules)) { "Update failed and the previous installation could not be restored." }
                throw e
            }
            backup.deleteRecursively()
        } finally { if (installer.isAlive) installer.destroyForcibly(); stage.deleteRecursively() }
    }
    fun start() = work(NineRouterPhase.STARTING) { if (process?.isAlive != true) startServer() }
    private suspend fun startServer() {
        check(server.isFile && marker.isFile && linux.isReady) { "Install 9Router first." }
        check(!portOpen()) { "Port $PORT is already in use. Stop the other service before starting 9Router." }
        linux.ensureScratchDirs(); linux.ensureShims()
        val node = File(linux.prefix, "bin/node")
        val linker = if (Build.SUPPORTED_ABIS.first().contains("64")) "/system/bin/linker64" else "/system/bin/linker"
        val args = if (Build.VERSION.SDK_INT >= 29) listOf(linker, node.absolutePath) else listOf(node.absolutePath)
        val runtime = File(root, "node-runtime.cjs").apply {
            writeText(NineRouterNodeRuntime.preload(useLinker = Build.VERSION.SDK_INT >= 29))
        }
        val lifecycle = File(root, "lifecycle.cjs").apply { writeText("const parent=process.ppid;setInterval(()=>{if(process.ppid!==parent)process.exit(0)},1500).unref();") }
        val child = ProcessBuilder(args + listOf("--require", runtime.absolutePath, "--require", lifecycle.absolutePath, "--dns-result-order=ipv4first", "--max-old-space-size=1024", server.absolutePath))
            .directory(server.parentFile!!).redirectErrorStream(true).apply {
                environment().putAll(linux.processEnv())
                environment().putAll(mapOf("PORT" to "$PORT", "HOSTNAME" to "127.0.0.1", "NODE_ENV" to "production", "NEXT_TELEMETRY_DISABLED" to "1", "NODE_PATH" to File(root, "node_modules").absolutePath, "DATA_DIR" to File(root, "data").absolutePath))
            }.start()
        process = child
        val tail = StringBuilder()
        val reader = scope.launch { drain(child, tail) }
        outputReader = reader
        try {
            withTimeout(90_000) {
                while (!portOpen()) {
                    check(child.isAlive) { "9Router exited during startup.\n${synchronized(tail) { tail.toString() }}" }; delay(300)
                }
            }
            check(child.isAlive) { "9Router stopped during startup." }
            acquire()
            scope.launch {
                try {
                    child.waitFor(); reader.join()
                    synchronized(this@NineRouterManager) {
                        if (process === child && !stopping) {
                            process = null
                            mutable.update { it.copy(phase = if (working) it.phase else idlePhase(), error = "9Router stopped unexpectedly.\n${SecretRedactor.redact(synchronized(tail) { tail.toString() })}") }
                        }
                    }
                } finally { release() }
            }
        } catch (e: Exception) { child.destroyForcibly(); process = null; throw e }
    }
    private suspend fun stopServer() {
        val child = process ?: return
        stopping = true
        try { child.destroy(); if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly(); child.waitFor(); outputReader?.join(); outputReader = null; process = null }
        finally { stopping = false }
    }
    fun stop() = work(NineRouterPhase.STOPPING) { stopServer() }
    fun restart() = work(NineRouterPhase.STARTING) { stopServer(); startServer() }
    fun setAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean("autoStart", enabled).apply()
        mutable.update { it.copy(autoStart = enabled) }
    }
    fun onAppOpen() { if (state.value.autoStart && installedVersion() != null) start(); checkUpdates(automatic = true) }
    fun checkUpdates(automatic: Boolean = false) = synchronized(this) {
        if (mutable.value.checkingUpdate || mutable.value.phase == NineRouterPhase.UNINSTALLING || installedVersion() == null) return@synchronized
        val now = System.currentTimeMillis()
        if (automatic && now - prefs.getLong("lastUpdateCheck", 0) < 86_400_000) return@synchronized
        prefs.edit().putLong("lastUpdateCheck", now).apply()
        mutable.update { it.copy(checkingUpdate = true, error = if (automatic) it.error else null) }
        scope.launch {
            try {
                val latest = NineRouterRelease.latest()
                synchronized(this@NineRouterManager) {
                    val installed = installedVersion()
                    if (installed != null && mutable.value.phase != NineRouterPhase.UNINSTALLING) {
                        prefs.edit().putString("latestVersion", latest).apply()
                        mutable.update { it.copy(latestVersion = latest,
                            notice = if (!automatic && !NineRouterRelease.newer(latest, installed)) "9Router is up to date." else it.notice) }
                    }
                }
            } catch (e: Exception) {
                if (!automatic && installedVersion() != null) mutable.update { it.copy(error = e.message ?: "Could not check for updates.") }
            } finally { mutable.update { it.copy(checkingUpdate = false) } }
        }
    }
    fun update() = work(NineRouterPhase.UPDATING) {
        val latest = NineRouterRelease.latest()
        prefs.edit().putString("latestVersion", latest).apply()
        mutable.update { it.copy(latestVersion = latest) }
        check(NineRouterRelease.newer(latest, installedVersion() ?: VERSION)) { "9Router is already up to date." }
        val wasRunning = process?.isAlive == true
        stopServer()
        try { installPayload(latest) }
        catch (e: Exception) { if (wasRunning && server.isFile) runCatching { startServer() }; throw e }
        mutable.update { it.copy(latestVersion = latest) }
        if (wasRunning) startServer()
    }
    fun addProvider() = work(NineRouterPhase.CONNECTING) {
        if (process?.isAlive != true) startServer()
        val key = NineRouterControl(File(root, "data")).integrationKey()
        val config = ProviderConfig("", "9Router", ProviderType.OPENAI_COMPAT, API, "")
        val result = ModelCatalog.listModels(config, key)
        check(result is ModelCatalog.Result.Models) { (result as? ModelCatalog.Result.Failed)?.message ?: "Could not load 9Router models." }
        result as ModelCatalog.Result.Models
        val existing = providers.providers.first().firstOrNull(::isRouterProvider)
        val selected = existing?.model?.takeIf { id -> result.models.any { it.id == id } } ?: result.models.firstOrNull()?.id.orEmpty()
        val saved = if (existing == null) providers.add("9Router", ProviderType.OPENAI_COMPAT, API, selected, key)
            else { val next = existing.copy(type = ProviderType.OPENAI_COMPAT, baseUrl = API, model = selected); providers.update(next, key); next }
        providers.setEnabled(saved.id, true)
        providers.saveCatalog(saved.id, result.models)
        prefs.edit().putString("providerId", saved.id).apply()
        mutable.update { it.copy(providerAdded = true, notice = if (result.models.isEmpty()) "9Router added. Connect a model provider in its browser dashboard, then refresh this connection."
            else "9Router added to providers. Its models are available in Chat and Code.") }
    }
    fun removeProvider() = work(NineRouterPhase.REMOVING_PROVIDER) { removeProviders() }
    private suspend fun removeProviders() {
        val owned = providers.providers.first().filter(::isRouterProvider)
        val current = settings.settings.first().activeProviderId
        owned.forEach { providers.delete(it.id); settings.setProviderEnabled(it.id, true) }
        if (owned.any { it.id == current }) settings.setActiveSelection(com.androidharness.app.llm.HarnessProvider.ID, null)
        prefs.edit().remove("providerId").apply()
        mutable.update { it.copy(providerAdded = false) }
    }
    fun uninstall() = work(NineRouterPhase.UNINSTALLING) {
        stopServer()
        setAutoStart(false)
        removeProviders()
        linux.managedTools().values.filter { "9Router" in it.requirements }.forEach { record -> linux.releasePackageRequirement(record.name, "9Router") }
        check(!root.exists() || root.deleteRecursively()) { "Some 9Router files could not be removed. Retry uninstall." }
        prefs.edit().clear().apply()
        logState.value = ""
        mutable.update { it.copy(latestVersion = null, autoStart = false, providerAdded = false, notice = "9Router was fully uninstalled.") }
    }
    private fun drain(child: Process, tail: StringBuilder) {
        try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            synchronized(tail) { tail.appendLine(line); if (tail.length > 6000) tail.delete(0, tail.length - 6000) }
            logState.update { (it + SecretRedactor.redact(line) + "\n").takeLast(24_000) }
        } } } catch (_: java.io.IOException) { }
    }
    private fun portOpen() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500) }; true }.getOrDefault(false)
    companion object {
        const val VERSION = "0.5.112"
        const val PORT = 20128
        const val DASHBOARD = "http://127.0.0.1:20128/dashboard"
        const val API = "http://127.0.0.1:20128/v1"
        private fun isRouterProvider(config: ProviderConfig): Boolean {
            val url = config.baseUrl.toHttpUrlOrNull() ?: return false
            return url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1") && url.port == PORT && url.encodedPath.trimEnd('/') == "/v1"
        }
    }
}
