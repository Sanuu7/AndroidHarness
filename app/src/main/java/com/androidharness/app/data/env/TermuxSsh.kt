package com.androidharness.app.data.env

import com.androidharness.app.data.KeyStoreManager
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class TermuxSshConfig(
    val enabled: Boolean = false,
    val username: String = "",
    val port: Int = 8022,
    val password: String = "",
    val fingerprint: String = "",
) {
    fun validate() {
        require(username.matches(Regex("[a-zA-Z0-9_][a-zA-Z0-9_-]*"))) { "Enter the username shown by whoami in Termux" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(password.isNotEmpty()) { "Enter the password set with passwd in Termux" }
        require(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))) { "Enter the SHA256 host fingerprint from Termux" }
    }
}

internal fun termuxSharedPath(path: String): String {
    // The same physical files must be visible to both apps. Never map an app
    // private workspace onto a different repo in Termux's home directory.
    val normalized = java.nio.file.Paths.get(path).normalize().toString()
    val shared = when {
        normalized == "/sdcard" || normalized.startsWith("/sdcard/") ->
            "/storage/emulated/0" + normalized.removePrefix("/sdcard")
        normalized == "/storage/self/primary" || normalized.startsWith("/storage/self/primary/") ->
            "/storage/emulated/0" + normalized.removePrefix("/storage/self/primary")
        else -> normalized
    }
    require(shared == "/storage/emulated/0" || shared.startsWith("/storage/emulated/0/")) {
        "Termux SSH needs a shared device folder, such as /storage/emulated/0/Projects. " +
            "Select that folder as your workspace and run termux-setup-storage in Termux. App-private folders cannot be shared."
    }
    require(!shared.startsWith("/storage/emulated/0/Android/")) { "Choose a shared folder outside Android/data and Android/obb" }
    return shared
}

internal fun termuxCommand(command: String, cwd: String): String {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    return "cd -- ${quote(termuxSharedPath(cwd))} && exec bash -c ${quote(command)}"
}

internal fun sshFingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

internal class FingerprintRepository(private val fingerprint: String) : HostKeyRepository {
    override fun check(host: String?, key: ByteArray): Int =
        if (MessageDigest.isEqual(sshFingerprint(key).toByteArray(), fingerprint.toByteArray())) HostKeyRepository.OK
        else HostKeyRepository.CHANGED
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "Termux pinned host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

internal class LimitedSshOutput(private val limit: Int) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    private var truncated = false
    @Synchronized override fun write(b: Int) {
        if (bytes.size() < limit) bytes.write(b) else truncated = true
    }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        val count = minOf(len, (limit - bytes.size()).coerceAtLeast(0))
        bytes.write(b, off, count)
        if (count < len) truncated = true
    }
    @Synchronized fun text() = bytes.toString("UTF-8") + if (truncated) "\n[output truncated]" else ""
}

/** SSH is loopback-only: no project syncing or commands on an unrelated host. */
class TermuxSsh(private val keys: KeyStoreManager) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _config = MutableStateFlow(runCatching {
        keys.getKey(KEY)?.let { json.decodeFromString<TermuxSshConfig>(it) }
    }.getOrNull() ?: TermuxSshConfig())
    val config = _config.asStateFlow()
    val enabled: Boolean get() = config.value.enabled

    fun save(value: TermuxSshConfig) {
        if (value.enabled) value.validate()
        keys.putKey(KEY, json.encodeToString(TermuxSshConfig.serializer(), value))
        _config.value = value
    }

    suspend fun run(command: String, cwd: File, timeoutMs: Int, maxOutput: Int,
                    connection: TermuxSshConfig = config.value): ShellRunResult = withContext(Dispatchers.IO) {
        val out = LimitedSshOutput(maxOutput.coerceAtLeast(0))
        val err = LimitedSshOutput(maxOutput.coerceAtLeast(0))
        var session: com.jcraft.jsch.Session? = null
        var channel: ChannelExec? = null
        var timedOut = false
        try {
            connection.validate()
            val script = termuxCommand(command, cwd.path)
            val client = JSch().apply { setHostKeyRepository(FingerprintRepository(connection.fingerprint)) }
            session = client.getSession(connection.username, "127.0.0.1", connection.port).apply {
                setPassword(connection.password.toByteArray(Charsets.UTF_8))
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "password")
                // Match the ECDSA host key shown in the setup instructions.
                setConfig("server_host_key", "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521")
                setServerAliveInterval(15_000)
                setServerAliveCountMax(2)
            }
            // Bound connect/auth separately; command timeout starts after connect.
            session.connect(minOf(timeoutMs, 15_000).coerceAtLeast(1))
            channel = (session.openChannel("exec") as ChannelExec).apply {
                setCommand(script)
                setInputStream(null)
                setOutputStream(out)
                setErrStream(err)
                connect(minOf(timeoutMs, 10_000).coerceAtLeast(1))
            }
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
            while (!channel.isClosed) {
                if (System.nanoTime() >= deadline) { timedOut = true; break }
                delay(25)
            }
            ShellRunResult(if (timedOut) -1 else channel.exitStatus, timedOut, out.text(), err.text(),
                ExecutionTier.TERMUX_SSH, "[Termux SSH: uses Termux Git identity and credentials]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellRunResult(-1, false, out.text(), err.text() + "\nTermux SSH: ${e.message}",
                ExecutionTier.TERMUX_SSH, "Check sshd, password, host fingerprint and shared-folder access in Terminal → SSH.")
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }

    private companion object { const val KEY = "termux_ssh_connection" }
}
