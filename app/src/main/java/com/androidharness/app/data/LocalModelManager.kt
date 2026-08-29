package com.androidharness.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.llm.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Local models: llama.cpp (llama-server) running inside the app-tier shell,
 * serving an OpenAI-compatible endpoint on 127.0.0.1, with models downloaded
 * straight from Hugging Face. The engine comes from llama.cpp's official
 * GitHub releases (nightly tags carry an android-arm64 tarball), resolved at
 * install time so updates ride the upstream release train.
 */
class LocalModelManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val providers: ProviderRepository,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // --- Device specs -----------------------------------------------------------

    data class DeviceSpecs(val soc: String, val cores: Int, val totalRamBytes: Long, val freeRamBytes: Long)

    fun specs(): DeviceSpecs {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.HARDWARE
        } else Build.HARDWARE
        return DeviceSpecs(soc, Runtime.getRuntime().availableProcessors(), mem.totalMem, mem.availMem)
    }

    /** A GGUF needs its weights plus KV cache plus overhead; 60% of total RAM is safe. */
    fun isCompatible(sizeBytes: Long): Boolean = sizeBytes < specs().totalRamBytes * 0.6

    // --- Engine -----------------------------------------------------------------

    sealed interface EngineState {
        data object NotInstalled : EngineState
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : EngineState
        data class Ready(val tag: String) : EngineState
        data class Failed(val error: String) : EngineState
    }

    private val llamaDir = File(context.filesDir, "llama")

    private val _engine = MutableStateFlow<EngineState>(
        if (findBinary() != null) EngineState.Ready(engineTag()) else EngineState.NotInstalled,
    )
    val engine: StateFlow<EngineState> = _engine

    private fun engineTagFile() = File(llamaDir, "engine.tag")

    private fun engineTag(): String =
        runCatching { engineTagFile().readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            // Tarballs extract into a llama-bNNNNN directory; use that when the
            // marker is missing (pre-fix installs that failed on detection).
            ?: llamaDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("llama-b") }?.name
            ?: "llama.cpp"

    /**
     * The server binary: newer releases ship a unified `llama` launcher (the
     * server is `llama server`, logic lives in the bundled .so files, so the
     * launcher itself is small), older ones a standalone `llama-server`.
     */
    private fun findBinary(): File? = runCatching {
        llamaDir.walkTopDown()
            .filter { it.isFile && (it.name == "llama-server" || it.name == "llama") }
            .firstOrNull()
    }.getOrNull()

    /**
     * Fetches the latest llama.cpp android-arm64 build: the "latest" release
     * only points at a nightly tag, so resolve that, then pull the tarball
     * from the tag's assets and extract it with the shell toolchain.
     */
    suspend fun installEngine() = withContext(Dispatchers.IO) {
        try {
            // Already extracted (an earlier attempt may have failed after the
            // download): finish the marker steps instead of re-downloading.
            val existing = findBinary()
            if (existing != null) {
                existing.setExecutable(true, false)
                if (!engineTagFile().exists()) engineTagFile().writeText(engineTag())
                _engine.value = EngineState.Ready(engineTag())
                return@withContext
            }
            if (linuxEnv.bashExecutable() == null) {
                throw IllegalStateException(
                    "The llama.cpp engine needs the Linux environment. Install it in Settings, Terminal and environment first.",
                )
            }
            llamaDir.mkdirs()
            val latest = http.newCall(
                Request.Builder()
                    .url("https://api.github.com/repos/ggml-org/llama.cpp/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("GitHub API error ${resp.code}")
                json.parseToJsonElement(resp.body!!.string()).jsonObject
            }
            val nightlyUrl = latest["assets"]?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "nightly-tag.txt" }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("Could not find the nightly pointer on llama.cpp releases.")
            val tag = http.newCall(Request.Builder().url(nightlyUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Could not read the nightly tag (${resp.code})")
                resp.body!!.string().trim()
            }
            val release = http.newCall(
                Request.Builder()
                    .url("https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/$tag")
                    .header("Accept", "application/vnd.github+json")
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("GitHub API error ${resp.code} for $tag")
                json.parseToJsonElement(resp.body!!.string()).jsonObject
            }
            val asset = release["assets"]?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.firstOrNull {
                    it["name"]?.jsonPrimitive?.contentOrNull?.endsWith("bin-android-arm64.tar.gz") == true
                }
                ?: throw IllegalStateException("No android-arm64 build in llama.cpp $tag.")
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("Asset has no download URL.")
            val total = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val tgz = File(llamaDir, "engine.tar.gz")
            downloadTo(url, tgz, total) { done, all ->
                _engine.value = EngineState.Downloading(done, all)
            }.getOrThrow()
            _engine.value = EngineState.Downloading(total, total)
            if (!extractTarball(tgz, llamaDir)) {
                throw IllegalStateException("Extraction failed (tar and python both unavailable).")
            }
            tgz.delete()
            val bin = findBinary() ?: throw IllegalStateException("No llama-server binary found in the tarball.")
            bin.setExecutable(true, false)
            engineTagFile().writeText(tag)
            _engine.value = EngineState.Ready(tag)
        } catch (e: Exception) {
            _engine.value = EngineState.Failed(e.message ?: "Engine install failed.")
        }
    }

    /** tar from the toolchain first, python's stdlib tarfile as the fallback. */
    private fun extractTarball(tgz: File, dir: File): Boolean {
        val viaTar = runCatching {
            linuxEnv.shellProcessBuilder("tar -xzf '${tgz.absolutePath}' -C '${dir.absolutePath}'")
                .start().waitFor() == 0
        }.getOrDefault(false)
        if (viaTar) return true
        return runCatching {
            linuxEnv.shellProcessBuilder(
                "python3 -c \"import tarfile; tarfile.open('${tgz.absolutePath}').extractall('${dir.absolutePath}')\"",
            ).start().waitFor() == 0
        }.getOrDefault(false)
    }

    // --- Hugging Face catalog -----------------------------------------------------

    data class HfRepo(val id: String, val downloads: Long)
    data class HfFile(val path: String, val sizeBytes: Long)

    /** Repos the app suggests out of the box: small, ungated, chat-tuned GGUFs. */
    val curatedRepos: List<Triple<String, String, String>> = listOf(
        Triple("Qwen3 0.6B", "Qwen/Qwen3-0.6B-GGUF", "Fast small chat model"),
        Triple("Qwen2.5 Coder 1.5B", "bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF", "Tuned for code"),
        Triple("Llama 3.2 1B", "bartowski/Llama-3.2-1B-Instruct-GGUF", "General chat"),
        Triple("Qwen2.5 1.5B", "bartowski/Qwen2.5-1.5B-Instruct-GGUF", "General chat"),
        Triple("Gemma 2 2B", "bartowski/gemma-2-2b-it-GGUF", "General chat"),
        Triple("Qwen2.5 3B", "bartowski/Qwen2.5-3B-Instruct-GGUF", "Stronger, needs RAM"),
        Triple("Llama 3.2 3B", "bartowski/Llama-3.2-3B-Instruct-GGUF", "Stronger, needs RAM"),
        Triple("Phi 3.5 mini", "bartowski/Phi-3.5-mini-instruct-GGUF", "3.8B reasoning"),
    )

    suspend fun searchRepos(query: String): Result<List<HfRepo>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode("$query gguf", "UTF-8")
            val url = "https://huggingface.co/api/models?search=$q&sort=downloads&direction=-1&limit=20"
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Hugging Face search failed (${resp.code})")
                val arr = json.parseToJsonElement(resp.body!!.string()).jsonArray
                arr.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    HfRepo(id, o["downloads"]?.jsonPrimitive?.longOrNull ?: 0L)
                }
            }
        }
    }

    /** GGUF files inside a repo, with real sizes from the LFS metadata. */
    suspend fun listGgufFiles(repoId: String): Result<List<HfFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/models/${repoId}/tree/main"
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Could not list files for $repoId (${resp.code})")
                val arr = json.parseToJsonElement(resp.body!!.string()).jsonArray
                arr.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!path.endsWith(".gguf")) return@mapNotNull null
                    val size = o["lfs"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
                        ?: o["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    HfFile(path, size)
                }.sortedBy { it.sizeBytes }
            }
        }
    }

    // --- Model downloads ----------------------------------------------------------

    data class DownloadState(val downloadedBytes: Long, val totalBytes: Long)

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    private val _localFileNames = MutableStateFlow(localFileNames())
    val localFileNames: StateFlow<List<String>> = _localFileNames

    private fun localFileNames(): List<String> =
        modelsDir.listFiles()?.filter { it.name.endsWith(".gguf") }?.map { it.name }?.sorted().orEmpty()

    fun localFileByName(name: String): File? = File(modelsDir, name).takeIf { it.isFile }

    suspend fun downloadModel(repoId: String, file: HfFile): Result<File> = withContext(Dispatchers.IO) {
        val dest = File(modelsDir, file.path.substringAfterLast('/'))
        try {
            downloadTo(
                "https://huggingface.co/$repoId/resolve/main/${file.path}",
                dest,
                file.sizeBytes,
            ) { done, total ->
                _downloads.value = _downloads.value + (dest.name to DownloadState(done, total))
            }.getOrThrow()
            _downloads.value = _downloads.value - dest.name
            _localFileNames.value = localFileNames()
            Result.success(dest)
        } catch (e: Exception) {
            _downloads.value = _downloads.value - dest.name
            runCatching { File(modelsDir, dest.name + ".part").delete() }
            Result.failure(e)
        }
    }

    fun deleteModel(name: String) {
        if (server.value is ServerState.Running && (server.value as ServerState.Running).model == name) stop()
        localFileByName(name)?.delete()
        _localFileNames.value = localFileNames()
    }

    private suspend fun downloadTo(
        url: String,
        dest: File,
        total: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): Result<File> {
        val part = File(dest.absolutePath + ".part")
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download failed (${resp.code})")
                val expected = if (total > 0) total else resp.body!!.contentLength()
                resp.body!!.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var sinceUpdate = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            sinceUpdate += n
                            if (sinceUpdate > 512 * 1024) {
                                sinceUpdate = 0
                                onProgress(done, expected)
                            }
                        }
                        output.flush()
                    }
                }
                if (expected > 0 && part.length() < expected) {
                    throw IllegalStateException("Download incomplete (${part.length()} of $expected bytes).")
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                dest
            }
        }.onFailure { runCatching { part.delete() } }
    }

    // --- Server -------------------------------------------------------------------

    sealed interface ServerState {
        data object Stopped : ServerState
        data class Starting(val model: String) : ServerState
        data class Running(val model: String, val port: Int) : ServerState
        data class Failed(val error: String) : ServerState
    }

    private val _server = MutableStateFlow<ServerState>(ServerState.Stopped)
    val server: StateFlow<ServerState> = _server

    @Volatile private var process: Process? = null

    fun serverLog(): File = File(context.cacheDir, "llama-server.log")

    /**
     * Serves one downloaded model on 127.0.0.1. Any previous server (ours or a
     * leftover from a killed app, same port pattern) is stopped first. The
     * provider registration is idempotent so the model shows up in the chat
     * model picker right away.
     */
    suspend fun start(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val model = localFileByName(fileName)
                ?: return@withContext Result.failure(IllegalStateException("Model file '$fileName' not found on disk."))
            if (linuxEnv.bashExecutable() == null) {
                return@withContext Result.failure(IllegalStateException(
                    "The local model server needs the Linux environment. Install it in Settings, Terminal and environment first.",
                ))
            }
            val bin = findBinary()
                ?: return@withContext Result.failure(IllegalStateException(
                    "The llama.cpp engine is not installed. Use Install engine above.",
                ))
            stop(quiet = true)
            // A leftover server from a killed app holds RAM; clear the port first.
            runCatching {
                linuxEnv.shellProcessBuilder("pkill -f 'llama.*--port $PORT' 2>/dev/null; true").start().waitFor()
            }

            _server.value = ServerState.Starting(fileName)
            val unified = bin.name == "llama"
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            val cmd = buildString {
                append("'${bin.absolutePath}'")
                if (unified) append(" server")
                append(" -m '${model.absolutePath}'")
                append(" --host 127.0.0.1 --port $PORT --ctx-size 8192 --threads $threads")
            }
            val pb = linuxEnv.shellProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] =
                bin.parent + ":" + (pb.environment()["LD_LIBRARY_PATH"] ?: "")
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(serverLog()))
            process = pb.start()

            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                val p = process
                if (p == null || !p.isAlive) {
                    val tail = runCatching { serverLog().readText().takeLast(400) }.getOrDefault("")
                    _server.value = ServerState.Failed("llama-server exited. Log tail: $tail")
                    return@withContext Result.failure(IllegalStateException("llama-server exited at startup."))
                }
                val healthy = runCatching {
                    http.newCall(Request.Builder().url("http://127.0.0.1:$PORT/health").build())
                        .execute().use { it.code == 200 }
                }.getOrDefault(false)
                if (healthy) {
                    _server.value = ServerState.Running(fileName, PORT)
                    registerProvider(fileName)
                    return@withContext Result.success(Unit)
                }
                delay(700)
            }
            stop(quiet = true)
            _server.value = ServerState.Failed("The server did not become healthy within 3 minutes.")
            Result.failure(IllegalStateException("llama-server health check timed out."))
        } catch (e: Exception) {
            _server.value = ServerState.Failed(e.message ?: "Could not start the local server.")
            Result.failure(e)
        }
    }

    fun stop(quiet: Boolean = false) {
        runCatching { process?.destroy() }
        runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
        runCatching { process?.destroyForcibly() }
        process = null
        if (!quiet) _server.value = ServerState.Stopped
    }

    private suspend fun registerProvider(modelFileName: String) {
        val stem = modelFileName.removeSuffix(".gguf")
        val existing = providers.providers.firstOrNull()?.firstOrNull { it.baseUrl == BASE_URL }
        if (existing == null) {
            providers.add("Local llama.cpp", ProviderType.OPENAI_COMPAT, BASE_URL, stem, "local")
        } else if (existing.model != stem) {
            providers.update(existing.copy(model = stem), null)
        }
    }

    /**
     * Called before a run whose provider is localhost: returns null when the
     * requested model is being served (starting it if needed), else an error
     * message for the chat.
     */
    suspend fun ensureRunningFor(modelStem: String?): String? {
        if (engine.value is EngineState.NotInstalled && findBinary() == null) {
            return "The local model engine is not installed. Settings, Local models, Install engine."
        }
        val target = localFileNames().firstOrNull { it.removeSuffix(".gguf") == modelStem }
            ?: localFileNames().firstOrNull()
            ?: return "No local model is downloaded yet. Settings, Local models has the list."
        when (val s = _server.value) {
            is ServerState.Running -> if (s.model == target) return null
            is ServerState.Starting -> if (s.model == target) {
                // Wait for the in-flight start to finish rather than double-spawning.
                val waited = _server.firstOrNull {
                    it is ServerState.Running || it is ServerState.Failed || it is ServerState.Stopped
                }
                return if (waited is ServerState.Running && waited.model == target) null
                else "The local model server is still starting; try again in a moment."
            }
            else -> {}
        }
        return start(target).fold(onSuccess = { null }, onFailure = { it.message })
    }

    companion object {
        const val PORT = 8901
        const val BASE_URL = "http://127.0.0.1:$PORT/v1"
    }
}
