package com.androidharness.app.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalModelManager(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "local-models")
    private val store = LocalModelStore(root)
    private val json = Json { ignoreUnknownKeys = true }
    private val lifecycle = Mutex()
    private val inference = Mutex()
    private val gate = Any()
    private val cancelled = AtomicBoolean(false)
    private var downloadCall: Call? = null
    private var handle = 0L
    private var activeId: String? = null
    private val blocked = mutableSetOf<String>()
    private val _installed = MutableStateFlow(LocalModelCatalog.models.filter(store::installed).map { it.id }.toSet())
    val installed = _installed.asStateFlow()
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = _status.asStateFlow()
    private val _running = MutableStateFlow<String?>(null)
    val running = _running.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            lifecycle.withLock {
                LocalModelCatalog.models.forEach { model ->
                    runCatching {
                        store.clearPartial(model.id)
                        if (store.file(model.id).exists() && !store.installed(model)) store.remove(model.id)
                    }.onFailure { setStatus(model.id, it.message ?: "Storage cleanup failed") }
                }
                publishInstalled()
            }
        }
    }

    fun device(): LocalDeviceProfile {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return LocalDeviceProfile(info.totalMem, info.availMem, StatFs(root.path).availableBytes,
            if (Process.is64Bit()) Build.SUPPORTED_ABIS.firstOrNull().orEmpty() else "32-bit",
            Runtime.getRuntime().availableProcessors(), info.lowMemory)
    }

    fun configs(ids: Set<String> = installed.value) = LocalModelCatalog.models.filter { it.id in ids }.map {
        ProviderConfig(LocalModelCatalog.PROVIDER_PREFIX + it.id, "Local · ${it.title}", ProviderType.OPENAI_COMPAT, "local://${it.id}", LocalModelCatalog.PROVIDER_PREFIX + it.id)
    }

    fun limits(id: String): LocalModelLimits = runCatching {
        json.decodeFromString<LocalModelLimits>(File(root, "$id.json").readText()).also { it.validate() }
    }.getOrElse { LocalModelLimits(threads = device().cores.coerceIn(1, 4)) }

    suspend fun saveLimits(id: String, limits: LocalModelLimits) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val model = requireNotNull(LocalModelCatalog.find(id))
            check(store.installed(model)) { "Model was removed." }
            limits.validate()
            require(device().fits(model, limits.context)) { "These limits exceed this device's estimated memory budget." }
            val target = android.util.AtomicFile(File(root, "$id.json"))
            val output = target.startWrite()
            try {
                output.write(json.encodeToString(LocalModelLimits.serializer(), limits).toByteArray())
                target.finishWrite(output)
            } catch (e: Exception) {
                target.failWrite(output)
                throw e
            }
        }
    }

    fun download(id: String) {
        require(LocalModelCatalog.find(id) != null)
        synchronized(gate) { blocked.remove(id) }
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalModelDownloadWorker>().setInputData(workDataOf("modelId" to id))
                .addTag("local-model-download").addTag("model:$id").build())
    }

    internal suspend fun install(id: String, stopped: () -> Boolean, progress: suspend (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                val model = requireNotNull(LocalModelCatalog.find(id))
                if (store.installed(model)) { publishInstalled(); return@withLock }
                check(device().fits(model)) { "This model exceeds the device's estimated memory budget." }
                check(device().freeStorage >= model.bytes + 256L * 1024 * 1024) { "Not enough free storage for this model." }
                synchronized(gate) {
                    check(id !in blocked && !stopped()) { "Download cancelled." }
                    cancelled.set(false)
                }
                setStatus(id, "Connecting")
                val call = client.newCall(Request.Builder().url(model.url).build())
                synchronized(gate) {
                    check(id !in blocked && !stopped() && !cancelled.get()) { "Download cancelled." }
                    downloadCall = call
                    activeId = id
                }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "Hugging Face returned HTTP ${response.code}. Retry later." }
                        val body = checkNotNull(response.body) { "Empty download." }
                        check(body.contentLength() < 0 || body.contentLength() == model.bytes) { "Unexpected model size." }
                        var lastUpdate = 0L
                        body.byteStream().use { input ->
                            store.install(model, input, { cancelled.get() || stopped() }) { count ->
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= 500 || count == model.bytes) {
                                    lastUpdate = now
                                    setStatus(id, if (count == model.bytes) "Verifying" else "Downloading ${count * 100 / model.bytes}%")
                                    kotlinx.coroutines.runBlocking { progress(count, model.bytes) }
                                }
                            }
                        }
                    }
                    publishInstalled()
                    progress(model.bytes, model.bytes)
                    setStatus(id, "Installed")
                } catch (e: Exception) {
                    setStatus(id, if (cancelled.get() || stopped()) "Cancelled" else e.message ?: "Download failed")
                    throw e
                } finally {
                    synchronized(gate) { if (activeId == id) { downloadCall = null; activeId = null } }
                }
            }
        }

    internal fun interruptDownload(id: String) {
        synchronized(gate) {
            if (activeId == id) { cancelled.set(true); downloadCall?.cancel() }
        }
    }

    fun cancelDownload(id: String) {
        synchronized(gate) {
            blocked.add(id)
            if (activeId == id) { cancelled.set(true); downloadCall?.cancel() }
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        require(LocalModelCatalog.find(id) != null)
        cancelDownload(id)
        stop(id)
        setStatus(id, "Stopping and removing")
        lifecycle.withLock {
            inference.withLock {
                try {
                    store.remove(id)
                    publishInstalled()
                    setStatus(id, "Removed")
                } catch (e: Exception) {
                    publishInstalled()
                    setStatus(id, e.message ?: "Removal failed")
                    throw e
                }
            }
        }
    }

    fun stop(id: String? = null) {
        synchronized(gate) {
            if (handle != 0L && (id == null || running.value == id)) LocalNative.cancel(handle)
        }
    }

    suspend fun generate(
        id: String, roles: Array<String>, contents: Array<ByteArray>, outputCap: Int,
        emitBytes: suspend (ByteArray) -> Unit,
    ): IntArray = inference.withLock {
        val model = requireNotNull(LocalModelCatalog.find(id)) { "Unknown local model." }
        val limits = withContext(Dispatchers.IO) { limits(id) }
        limits.validate()
        synchronized(gate) {
            check(id !in blocked && store.installed(model)) { "Local model is not installed. Download it in Settings > Local models." }
            check(device().canLoad(model, limits.context)) { "Not enough available RAM. Close other apps or lower context in Local models." }
            handle = LocalNative.create()
            _running.value = id
        }
        try {
            coroutineScope {
                val bytes = Channel<ByteArray>(32)
                var result: IntArray? = null
                val worker = launch(Dispatchers.IO) {
                    try {
                        result = LocalNative.generate(handle, store.file(id).absolutePath.toByteArray(Charsets.UTF_8), roles, contents,
                            limits.context, limits.input, minOf(limits.output, outputCap.coerceAtLeast(1)),
                            minOf(limits.threads, device().cores.coerceAtLeast(1)), object : LocalNative.Callback {
                                override fun onToken(data: ByteArray): Boolean = kotlinx.coroutines.runBlocking {
                                    try { bytes.send(data); true } catch (_: CancellationException) { false }
                                }
                            })
                    } finally { bytes.close() }
                }
                try {
                    for (data in bytes) emitBytes(data)
                    worker.join()
                    checkNotNull(result)
                } finally {
                    stop(id)
                    bytes.cancel()
                    withContext(NonCancellable) { worker.join() }
                }
            }
        } finally {
            synchronized(gate) {
                LocalNative.destroy(handle)
                handle = 0
                _running.value = null
            }
        }
    }

    private fun publishInstalled() { _installed.value = LocalModelCatalog.models.filter(store::installed).map { it.id }.toSet() }
    private fun setStatus(id: String, text: String) { _status.update { it + (id to text) } }
    private fun workName(id: String) = "local-model-$id"
}
