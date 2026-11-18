package com.androidharness.app.data.env

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import com.androidharness.app.data.env.adb.AdbShell
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Concurrent requests over private pipes. Never retries a command after it has been submitted. */
internal class ShizukuCompatClient private constructor(
    private val process: CompatProcess,
    private val onDied: () -> Unit,
) {
    /** The UID the runner reported in its hello. Set once the identity check passes, before [start] returns. */
    @Volatile var uid: Int = -1
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val ids = AtomicLong(0)
    private val replies = ConcurrentHashMap<Long, CompletableDeferred<CompatResponse>>()
    private val hello = CompletableDeferred<CompatResponse>()
    private val input = process.inputStream.bufferedReader()
    private val output = process.outputStream.bufferedWriter()
    @Volatile private var startupErrors = ""
    val isAlive: Boolean get() = !closed.get() && process.isAlive

    init {
        scope.launch {
            try {
                process.errorStream?.let { startupErrors = readStartupLogTail(it, 6_000) }
            } catch (_: Exception) { }
        }
        scope.launch {
            try {
                while (true) {
                    val line = input.readLine() ?: break
                    val reply = ShizukuCompatProtocol.json.decodeFromString(CompatResponse.serializer(), line)
                    if (reply.id == 0L) hello.complete(reply) else replies.remove(reply.id)?.complete(reply)
                }
                error("Compatibility runner exited. $startupErrors")
            } catch (e: Exception) {
                hello.completeExceptionally(e)
                replies.values.forEach { it.completeExceptionally(e) }
                replies.clear()
                if (!closed.get()) { close(); onDied() }
            }
        }
    }

    private fun send(request: CompatRequest) = synchronized(output) {
        check(!closed.get()) { "Compatibility runner disconnected" }
        output.write(ShizukuCompatProtocol.json.encodeToString(CompatRequest.serializer(), request))
        output.newLine()
        output.flush()
    }

    suspend fun request(request: CompatRequest): String = withContext(Dispatchers.IO) {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<CompatResponse>()
        replies[id] = reply
        try {
            send(request.copy(id = id))
            val result = withTimeout(request.timeoutMs.toLong().coerceAtLeast(1) + 5_000) { reply.await() }
            check(result.error == null) { result.error.orEmpty() }
            result.result
        } catch (e: CancellationException) {
            runCatching { send(CompatRequest(0, "cancel", target = id)) }
            throw e
        } finally { replies.remove(id) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { synchronized(output) {
            output.write(ShizukuCompatProtocol.json.encodeToString(CompatRequest.serializer(), CompatRequest(0, "close")))
            output.newLine(); output.flush()
        } }
        runCatching { output.close() }
        val error = IllegalStateException("Compatibility runner disconnected")
        hello.completeExceptionally(error)
        replies.values.forEach { it.completeExceptionally(error) }
        replies.clear()
        // Give the helper time to interrupt foreground commands and clean their
        // process groups. Detached jobs intentionally survive runner recreation.
        scope.launch {
            process.waitForTimeout(3, "SECONDS")
            process.destroy()
            runCatching { input.close() }
            runCatching { process.errorStream?.close() }
            scope.cancel()
        }
    }

    companion object {
        suspend fun startWithShizuku(apkPath: String, onDied: () -> Unit): ShizukuCompatClient = withContext(Dispatchers.IO) {
            check(Shizuku.getVersion() in 11..13) { "This Shizuku API does not support the compatibility launcher" }
            val uid = Shizuku.getUid()
            check(uid == 0 || uid == 2000) { "Shizuku did not provide shell/root privileges" }
            val server = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = requireNotNull(server.newProcess(ShizukuCompatProtocol.launchCommand(apkPath), null, null))
            start(ShizukuCompatProcess(process), setOf(uid), onDied).also { client ->
                if (!client.isAlive) { runCatching { process.destroy() }; error("Compatibility runner did not stay alive") }
            }
        }

        /** adbd normally runs as shell (2000); with `service.adb.root` or a root manager it runs as root (0). */
        private val ADB_UIDS = setOf(0, 2000)

        /**
         * Starts the same runner over a direct ADB shell stream. The runner inherits adbd's
         * UID, which is the shell user on a stock device and root where adbd itself is rooted.
         */
        suspend fun startWithAdb(shell: AdbShell, onDied: () -> Unit): ShizukuCompatClient = withContext(Dispatchers.IO) {
            start(AdbCompatProcess(shell), ADB_UIDS, onDied)
        }

        /**
         * Waits for the runner's hello and accepts it only when it reports one of [allowedUids].
         * An `id -u` run through the runner must then agree with that reported UID.
         */
        internal suspend fun start(process: CompatProcess, allowedUids: Set<Int>, onDied: () -> Unit): ShizukuCompatClient {
            val client = try { ShizukuCompatClient(process, onDied) } catch (e: Exception) {
                runCatching { process.destroy() }
                throw e
            }
            return try {
                val hello = withTimeout(10_000) { client.hello.await() }
                check(hello.protocol == 1 && hello.uid in allowedUids) { "Compatibility runner identity check failed" }
                val raw = client.request(CompatRequest(0, "exec", command = listOf("/system/bin/id", "-u"), timeoutMs = 3_000))
                check(raw.startsWith("exit=0\ntimeout=0\n${hello.uid}\n")) { "Compatibility runner execution check failed" }
                client.uid = hello.uid
                client
            } catch (e: Exception) { client.close(); throw e }
        }
    }
}
