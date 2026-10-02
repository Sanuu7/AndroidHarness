package com.androidharness.app.local

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalModelManager(private val context: Context) {
    val consent = LocalModelConsent()
    private fun resourceKey(model: LocalModelSpec, value: LocalModelLimits, downloading: Boolean, profile: LocalDeviceProfile) =
        "${model.id}:${value.context}:${value.input}:${value.output}:${value.threads}:$downloading:${profile.lowMemory}:${model.projector?.url}"
    suspend fun acknowledgeResources(model: LocalModelSpec, value: LocalModelLimits, downloading: Boolean) {
        consent.acknowledge(resourceKey(model, value, downloading, device()))
    }
    suspend fun confirmResources(model: LocalModelSpec, value: LocalModelLimits = limits(model.id), downloading: Boolean = false) {
        val profile = device()
        consent.confirm(resourceKey(model, value, downloading, profile),
            "Continue with ${model.title}?", localModelWarnings(model, profile, value, downloading))
    }
    private val root = File(context.noBackupFilesDir, "local-models")
    private val custom = CustomModelRegistry(root)
    private fun catalogModels() = (LocalModelCatalog.models + custom.models).associateBy { it.id }.values.toList()
    private val _models = MutableStateFlow(catalogModels())
    val models = _models.asStateFlow()
    val catalogError get() = custom.loadError
    fun find(id: String) = models.value.firstOrNull { it.id == id }
    private val store = LocalModelStore(root, ::find)
    private val resolver = CustomModelResolver()

    suspend fun resolveLink(link: String, format: LocalModelFormat = LocalModelFormat.GGUF) = withContext(Dispatchers.IO) { resolver.resolve(link, format) }
    suspend fun inspectCustom(model: LocalModelSpec) = withContext(Dispatchers.IO) { resolver.inspect(model) }
    suspend fun downloadCustom(model: LocalModelSpec) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            // Once installed, never replace its spec with metadata from a later URL probe.
            if (find(model.id)?.let(store::installed) != true) {
                custom.add(model)
                _models.value = catalogModels()
            }
            download(model.id)
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val lifecycle = Mutex()
    private val inference = Mutex()
    private val gate = Any()
    private val cancelled = AtomicBoolean(false)
    private var downloadCall: Call? = null
    private var handle = 0L
    private var activeId: String? = null
    private val blocked = mutableSetOf<String>()
    private val _installed = MutableStateFlow(models.value.filter(store::installed).map { it.id }.toSet())
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
                models.value.forEach { model ->
                    runCatching {
                        store.clearPartial(model.id)
                        if (store.file(model.id).exists() && !store.installed(model)) store.remove(model.id)
                    }.onFailure { setStatus(model.id, it.message ?: "Storage cleanup failed") }
                }
                root.listFiles()?.filter { it.name.startsWith("file-import-") }?.forEach { it.deleteRecursively() }
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

    fun configs(ids: Set<String> = installed.value) = models.value.filter { it.id in ids }.map {
        ProviderConfig(LocalModelCatalog.PROVIDER_PREFIX + it.id, "Local · ${it.title}", ProviderType.OPENAI_COMPAT, "local://${it.id}", LocalModelCatalog.PROVIDER_PREFIX + it.id)
    }

    fun limits(id: String): LocalModelLimits {
        val model = requireNotNull(find(id))
        return runCatching {
            json.decodeFromString<LocalModelLimits>(File(root, "$id.json").readText()).also { it.validate() }
        }.getOrElse {
            LocalModelLimits(context = model.defaultContext, input = (model.defaultContext * 3 / 4).coerceAtLeast(1),
                output = (model.defaultContext / 4).coerceAtLeast(1), threads = device().cores.coerceIn(1, 4))
        }
    }

    suspend fun saveLimits(id: String, limits: LocalModelLimits) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val model = requireNotNull(find(id))
            check(store.installed(model)) { "Model was removed." }
            limits.validate()
            confirmResources(model, limits)
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
        require(find(id) != null)
        synchronized(gate) { blocked.remove(id) }
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalModelDownloadWorker>().setInputData(workDataOf("modelId" to id))
                .addTag("local-model-download").addTag("model:$id").build())
    }

    internal suspend fun install(id: String, stopped: () -> Boolean, progress: suspend (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                val model = requireNotNull(find(id))
                if (store.installed(model)) { publishInstalled(); return@withLock }
                if (model.format == LocalModelFormat.SAFETENSORS) {
                    installSafetensors(model, stopped, progress)
                    return@withLock
                }
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
                        check(response.isSuccessful) { "Model server returned HTTP ${response.code}. Retry later." }
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
            if (activeId == id) { cancelled.set(true); downloadCall?.cancel(); if (handle != 0L && running.value == id) LocalNative.cancel(handle) }
        }
    }

    private suspend fun transferAsset(modelId: String, asset: LocalModelAsset, target: File, stopped: () -> Boolean,
        progress: (Long) -> Unit) {
        val call = client.newCall(Request.Builder().url(asset.url).build())
        synchronized(gate) {
            check(modelId !in blocked && !stopped() && !cancelled.get()) { "Download cancelled." }
            downloadCall = call; activeId = modelId
        }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Model source returned HTTP ${response.code}." }
                val body = checkNotNull(response.body)
                check(body.contentLength() < 0 || body.contentLength() == asset.bytes) { "Model source changed file size." }
                body.byteStream().use { source -> store.installAsset(asset, source, target, { cancelled.get() || stopped() }, progress) }
            }
        } finally { synchronized(gate) { downloadCall = null } }
    }

    private suspend fun installSafetensors(model: LocalModelSpec, stopped: () -> Boolean, progress: suspend (Long, Long) -> Unit) {
        synchronized(gate) {
            check(model.id !in blocked && !stopped()) { "Download cancelled." }
            cancelled.set(false); activeId = model.id
        }
        val source = store.sourceDirectory(model.id)
        val target = store.partial(model.id)
        try {
            source.mkdirs()
            var completed = 0L
            val total = model.assets.sumOf { it.bytes }
            model.assets.forEach { asset ->
                setStatus(model.id, "Downloading ${asset.filename}")
                var lastUpdate = 0L
                transferAsset(model.id, asset, File(source, asset.filename), stopped) { count ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 500 || count == asset.bytes) {
                        lastUpdate = now
                        setStatus(model.id, "Downloading ${(completed + count) * 100 / total}%")
                        kotlinx.coroutines.runBlocking { progress(completed + count, total) }
                    }
                }
                completed += asset.bytes
            }
            inference.withLock {
                synchronized(gate) {
                    check(model.id !in blocked && !stopped() && !cancelled.get()) { "Conversion cancelled." }
                    handle = LocalNative.create(); _running.value = model.id
                }
                try {
                    setStatus(model.id, "Converting safetensors to Q4 GGUF")
                    LocalNative.convertSafetensors(handle, source.absolutePath.toByteArray(), target.absolutePath.toByteArray(), limits(model.id).threads)
                    check(!cancelled.get() && !stopped()) { "Conversion cancelled." }
                } finally { synchronized(gate) { LocalNative.destroy(handle); handle = 0; _running.value = null } }
            }
            val metadata = target.inputStream().use { GgufMetadata.inspect(readPrefix(it, GgufMetadata.PROBE_BYTES)) }
            val converted = model.copy(bytes = target.length(), maxContext = metadata.maxContext, kvBytesPerToken = metadata.kvBytesPerToken)
            custom.add(converted)
            _models.value = catalogModels()
            check(target.renameTo(store.file(model.id))) { "Could not finalize converted model." }
            publishInstalled(); setStatus(model.id, "Installed · converted to Q4 GGUF")
            progress(total, total)
        } catch (e: Exception) { setStatus(model.id, e.message ?: "Conversion failed"); throw e }
        finally {
            store.clearPartial(model.id)
            synchronized(gate) { activeId = null; downloadCall = null }
        }
    }

    suspend fun installProjector(id: String, link: String) = withContext(Dispatchers.IO) {
        val asset = resolver.resolveProjector(link)
        val model = requireNotNull(find(id))
        require(model.custom) { "Add a custom vision model first." }
        confirmResources(model.copy(projector = asset), limits(id), downloading = true)
        lifecycle.withLock {
            inference.withLock {
                check(store.installed(model)) { "Install the model before adding a projector." }
                synchronized(gate) { blocked.remove(id); cancelled.set(false) }
                val temporary = File(root, "${store.projectorFile(id).name}.part")
                try {
                    setStatus(id, "Downloading vision projector")
                    transferAsset(id, asset, temporary, { false }) {}
                    temporary.inputStream().use { GgufMetadata.inspect(readPrefix(it, GgufMetadata.PROBE_BYTES)) }
                    check(temporary.renameTo(store.projectorFile(id))) { "Could not finalize projector download." }
                    custom.add(model.copy(projector = asset)); _models.value = catalogModels()
                    publishInstalled(); setStatus(id, "Vision projector installed")
                } finally { temporary.delete(); synchronized(gate) { activeId = null; downloadCall = null } }
            }
        }
    }

    /** Copy a file/folder into app storage; the source and its permissions are not retained. */
    suspend fun importFromFiles(uri: Uri, format: LocalModelFormat, progress: (String) -> Unit): LocalModelSpec = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val documents = LocalModelDocuments(context.contentResolver)
            val files = if (format == LocalModelFormat.GGUF) listOf(documents.file(uri)) else documents.folder(uri)
            if (format == LocalModelFormat.GGUF) require(files.single().name.endsWith(".gguf", true) &&
                !files.single().name.startsWith("mmproj", true)) { "Choose a GGUF model file. Add vision files through Image support." }
            val title = if (format == LocalModelFormat.GGUF) files.single().name.substringBeforeLast('.')
                else documents.file(DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))).name
            val id = "custom-" + MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
            val metadata = if (format == LocalModelFormat.GGUF) documents.open(files.single()).use { GgufMetadata.inspect(readPrefix(it, GgufMetadata.PROBE_BYTES)) }
                else GgufMetadata.Estimate()
            var model = LocalModelSpec(id, title, "", "", files.first().name,
                files.sumOf { it.bytes ?: 0 }.coerceAtLeast(24), "", 1, metadata.kvBytesPerToken,
                "Imported from your files.", license = "See original model license", custom = true, maxContext = metadata.maxContext,
                format = format, localFile = true, downloadUrl = "", sourcePage = "",
                assets = if (format == LocalModelFormat.SAFETENSORS) files.map { LocalModelAsset(it.name, "", it.bytes ?: 1) } else emptyList())
            val limits = LocalModelLimits(context = model.defaultContext, input = (model.defaultContext * 3 / 4).coerceAtLeast(1),
                output = (model.defaultContext / 4).coerceAtLeast(1), threads = device().cores.coerceIn(1, 4))
            if (files.all { it.bytes != null }) confirmResources(model, limits, downloading = true)
            else consent.confirm("$id:unknown-size", "Import model?", listOf("The file manager did not report all file sizes. Copying may use more storage than expected."))
            val staging = File(root, "file-import-${id.removePrefix("custom-")}")
            check(staging.mkdirs()) { "Cannot create import storage." }
            val target = File(staging, "imported.gguf.part")
            var registered = false
            var committed = false
            try {
                val jobContext = currentCoroutineContext()
                var completed = 0L
                val total = files.sumOf { it.bytes ?: 0 }
                val assets = files.map { document ->
                    val output = if (format == LocalModelFormat.GGUF) target else File(staging, document.name)
                    var lastUpdate = 0L
                    val copied = documents.open(document).use { input ->
                        LocalModelImportFiles.copy(input, output, document.bytes, { jobContext.ensureActive() }) { count ->
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 500 || count == document.bytes) {
                                lastUpdate = now
                                progress(if (files.all { it.bytes != null }) "Copying ${(completed + count) * 100 / total.coerceAtLeast(1)}%"
                                    else "Copying ${document.name} · ${(completed + count) / 1_000_000} MB")
                            }
                        }
                    }
                    completed += copied.first
                    LocalModelAsset(document.name, "", copied.first, copied.second)
                }
                model = model.copy(bytes = completed, assets = if (format == LocalModelFormat.SAFETENSORS) assets else emptyList())
                if (files.any { it.bytes == null }) confirmResources(model, limits, downloading = true)
                if (format == LocalModelFormat.SAFETENSORS) {
                    LocalModelImportFiles.verifyIndex(staging)
                    coroutineScope {
                        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() }
                            finally { synchronized(gate) { if (handle != 0L && _running.value == id) LocalNative.cancel(handle) } }
                        }
                        try {
                            inference.withLock {
                                currentCoroutineContext().ensureActive()
                                synchronized(gate) { handle = LocalNative.create(); _running.value = id }
                                try {
                                    progress("Converting safetensors to GGUF")
                                    LocalNative.convertSafetensors(handle, staging.absolutePath.toByteArray(), target.absolutePath.toByteArray(), limits.threads)
                                    currentCoroutineContext().ensureActive()
                                } finally { synchronized(gate) { LocalNative.destroy(handle); handle = 0; _running.value = null } }
                            }
                        } finally { watcher.cancel() }
                    }
                }
                currentCoroutineContext().ensureActive()
                val estimate = target.inputStream().use { GgufMetadata.inspect(readPrefix(it, GgufMetadata.PROBE_BYTES)) }
                model = model.copy(bytes = target.length(), filename = if (format == LocalModelFormat.SAFETENSORS) "imported.gguf" else model.filename,
                    sha256 = if (format == LocalModelFormat.GGUF) assets.single().sha256 else "", kvBytesPerToken = estimate.kvBytesPerToken, maxContext = estimate.maxContext)
                custom.add(model); registered = true; _models.value = catalogModels()
                check(target.renameTo(store.file(id))) { "Cannot finish model import." }
                committed = true
                publishInstalled(); setStatus(id, "Imported from files")
                model
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("The local engine is unavailable on this device.", e)
            } finally {
                staging.deleteRecursively()
                if (registered && !committed) { custom.remove(id); _models.value = catalogModels() }
            }
        }
    }

    fun cancelDownload(id: String) {
        synchronized(gate) {
            blocked.add(id)
            if (activeId == id) { cancelled.set(true); downloadCall?.cancel(); if (handle != 0L && running.value == id) LocalNative.cancel(handle) }
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        require(find(id) != null)
        cancelDownload(id)
        stop(id)
        setStatus(id, "Stopping and removing")
        lifecycle.withLock {
            inference.withLock {
                try {
                    store.remove(id)
                    if (find(id)?.custom == true) {
                        custom.remove(id)
                        _models.value = catalogModels()
                    }
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
        val model = requireNotNull(find(id)) { "Unknown local model." }
        val limits = withContext(Dispatchers.IO) { limits(id) }
        limits.validate()
        confirmResources(model, limits)
        synchronized(gate) {
            check(id !in blocked && store.installed(model)) { "Local model is not installed. Download it in Settings > Local models." }
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
                            limits.threads, object : LocalNative.Callback {
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

    suspend fun generateChat(
        id: String, request: ByteArray, images: Array<ByteArray>, outputCap: Int,
        emitBytes: suspend (ByteArray) -> Unit,
    ): kotlinx.serialization.json.JsonObject = inference.withLock {
        val model = requireNotNull(find(id)) { "Unknown local model." }
        val limits = withContext(Dispatchers.IO) { limits(id) }
        limits.validate()
        confirmResources(model, limits)
        synchronized(gate) {
            check(id !in blocked && store.installed(model)) { "Local model is not installed. Download it in Settings > Local models." }
            handle = LocalNative.create()
            _running.value = id
        }
        try {
            coroutineScope {
                val bytes = Channel<ByteArray>(32)
                var result: ByteArray? = null
                val worker = launch(Dispatchers.IO) {
                    val workerContext = coroutineContext
                    try {
                        result = LocalNative.generateChat(handle, store.file(id).absolutePath.toByteArray(Charsets.UTF_8),
                            (if (model.projector != null) store.projectorFile(id).absolutePath else "").toByteArray(Charsets.UTF_8),
                            request, images, limits.context, limits.input, minOf(limits.output, outputCap.coerceAtLeast(1)),
                            limits.threads, object : LocalNative.ChatCallback {
                                override fun onEvent(data: ByteArray): Boolean = kotlinx.coroutines.runBlocking {
                                    try {
                                        val event = kotlinx.serialization.json.Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
                                        (event["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { setStatus(id, it) }
                                        bytes.send(data); true
                                    } catch (_: CancellationException) { false }
                                }
                                override fun onWarning(data: ByteArray): Boolean = kotlinx.coroutines.runBlocking(workerContext) {
                                    try {
                                        consent.confirm("$id:prompt:${data.contentHashCode()}", "Continue with these settings?",
                                            listOf(data.toString(Charsets.UTF_8)))
                                        true
                                    } catch (_: CancellationException) { false }
                                }
                            })
                    } finally { bytes.close() }
                }
                try {
                    for (data in bytes) emitBytes(data)
                    worker.join()
                    val response = kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(result).toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
                    setStatus(id, "Ready")
                    response
                } finally {
                    stop(id)
                    bytes.cancel()
                    withContext(NonCancellable) { worker.join() }
                }
            }
        } catch (e: Exception) {
            setStatus(id, if (e is CancellationException) "Stopped" else e.message ?: "Inference failed")
            throw e
        } finally {
            synchronized(gate) {
                LocalNative.destroy(handle)
                handle = 0
                _running.value = null
            }
        }
    }

    private fun publishInstalled() { _installed.value = models.value.filter(store::installed).map { it.id }.toSet() }
    private fun setStatus(id: String, text: String) { _status.update { it + (id to text) } }
    private fun workName(id: String) = "local-model-$id"
}
