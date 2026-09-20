package com.androidharness.app.workspace

import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.env.FingerprintRepository
import com.androidharness.app.data.env.LimitedSshOutput
import com.androidharness.app.data.env.ShellRunResult
import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.sshFingerprint
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SshConnection(
    val id: String = UUID.randomUUID().toString(),
    val host: String = "127.0.0.1",
    val port: Int = 8022,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val fingerprint: String = "",
    val termux: Boolean = true,
) {
    override fun toString() = "SshConnection(id=$id, host=$host, port=$port, credentials=<redacted>)"
    fun validate(requirePin: Boolean = true) {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }) { "Enter a host name or IP address" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(username.isNotBlank() && '\u0000' !in username) { "Enter your SSH username" }
        require(password.isNotEmpty() || privateKey.isNotBlank()) { "Enter a password or SSH private key" }
        require(!requirePin || fingerprint.startsWith("SHA256:")) { "Verify this host's fingerprint first" }
    }
}

@Serializable
data class SshLocation(val connectionId: String, val root: String)
data class SshStatus(val connected: Boolean = false, val message: String = "Disconnected", val uncertain: Boolean = false)
internal fun sshQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** Credentials stay in encrypted preferences, separate from project metadata and backups. */
class SshConnections(private val keys: KeyStoreManager) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    val statuses = MutableStateFlow<Map<String, SshStatus>>(emptyMap())
    fun load(id: String): SshConnection = keys.getKey("ssh_workspace_$id")?.let {
        json.decodeFromString<SshConnection>(it)
    } ?: error("SSH credentials are missing. Open Connection settings to reconnect.")
    fun save(config: SshConnection) {
        config.validate()
        disconnect(config.id)
        keys.putKey("ssh_workspace_${config.id}", json.encodeToString(SshConnection.serializer(), config))
        paused.remove(config.id)
    }
    fun forget(id: String) { disconnect(id); keys.removeKey("ssh_workspace_$id") }
    fun disconnect(id: String) {
        paused.add(id)
        sessions.remove(id)?.disconnect()
        statuses.update { it + (id to SshStatus(message = "Disconnected. Reconnect to continue.")) }
    }
    fun acknowledge(id: String) { statuses.update { it + (id to (it[id] ?: SshStatus()).copy(uncertain = false)) } }
    fun reconnect(id: String) {
        paused.remove(id)
        session(id)
    }
    private fun connect(config: SshConnection, repository: HostKeyRepository): Session {
        val client = JSch().apply {
            setHostKeyRepository(repository)
            if (config.privateKey.isNotBlank()) addIdentity("workspace", config.privateKey.toByteArray(), null,
                config.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
        }
        val session = client.getSession(config.username, config.host, config.port)
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            if (config.privateKey.isNotBlank()) session.setConfig("PreferredAuthentications", "publickey")
            else {
                session.setPassword(config.password.toByteArray())
                session.setConfig("PreferredAuthentications", "password")
            }
            session.setServerAliveInterval(15_000)
            session.setServerAliveCountMax(2)
            session.setTimeout(15_000)
            session.connect(15_000)
            return session
        } catch (e: Exception) { session.disconnect(); throw e }
    }
    /** Reject the key before authentication; never send credentials to an unverified host. */
    fun probe(config: SshConnection): String {
        config.validate(requirePin = false)
        var observed: String? = null
        val repository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray): Int {
                observed = sshFingerprint(key)
                return HostKeyRepository.CHANGED
            }
            override fun add(key: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "Untrusted host"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        try { connect(config, repository).disconnect() }
        catch (e: Exception) { if (observed == null) throw e }
        return requireNotNull(observed) { "Server did not provide a host key" }
    }
    fun <T> preview(config: SshConnection, block: (ChannelSftp) -> T): T {
        config.validate()
        val session = connect(config, FingerprintRepository(config.fingerprint))
        try {
            val sftp = session.openChannel("sftp") as ChannelSftp
            try { sftp.connect(10_000); return block(sftp) } finally { sftp.disconnect() }
        } finally { session.disconnect() }
    }
    @Synchronized private fun session(id: String): Session {
        check(id !in paused) { "SSH disconnected. Tap Reconnect before continuing." }
        sessions[id]?.takeIf { it.isConnected }?.let { existing ->
            statuses.update { it + (id to SshStatus(true, "Connected", it[id]?.uncertain == true)) }
            return existing
        }
        try {
            val config = load(id)
            val s = connect(config, FingerprintRepository(config.fingerprint))
            sessions[id] = s
            statuses.update { it + (id to SshStatus(true, "Connected", it[id]?.uncertain == true)) }
            return s
        } catch (e: Exception) { failed(id, e.message.orEmpty(), false); throw e }
    }
    fun failed(id: String, message: String, uncertain: Boolean) {
        statuses.update { it + (id to SshStatus(false, message, uncertain || it[id]?.uncertain == true)) }
    }
    fun <T> sftp(id: String, block: (ChannelSftp) -> T): T {
        val s = session(id)
        var channel: ChannelSftp? = null
        try {
            channel = s.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            return block(channel)
        } catch (e: Exception) {
            if (!s.isConnected || e is JSchException || e is java.io.IOException ||
                (e is SftpException && (e.id == ChannelSftp.SSH_FX_CONNECTION_LOST || e.id == ChannelSftp.SSH_FX_NO_CONNECTION))) {
                failed(id, "Connection lost. Check remote files before retrying changes.", true)
            }
            throw e
        } finally { channel?.disconnect() }
    }
    fun process(id: String, command: String, cwd: String): Process {
        val channel = session(id).openChannel("exec") as ChannelExec
        channel.setCommand("cd -- ${sshQuote(cwd)} && exec sh -c ${sshQuote(command)}")
        val input = channel.inputStream
        val output = channel.outputStream
        // MCP stderr is drained by JSch rather than sharing the protocol pipe.
        channel.setErrStream(LimitedSshOutput(16_000))
        try { channel.connect(10_000) } catch (e: Exception) { channel.disconnect(); throw e }
        return object : Process() {
            override fun getInputStream() = input
            override fun getOutputStream() = output
            override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(byteArrayOf())
            override fun isAlive() = channel.isConnected && !channel.isClosed
            override fun waitFor(): Int { while (isAlive()) Thread.sleep(25); return channel.exitStatus }
            override fun exitValue(): Int {
                if (isAlive()) throw IllegalThreadStateException("SSH process still running")
                return channel.exitStatus
            }
            override fun destroy() { channel.disconnect() }
            override fun destroyForcibly(): Process { destroy(); return this }
        }
    }

    suspend fun run(id: String, command: String, cwd: String, timeoutMs: Int, maxOutput: Int): ShellRunResult = withContext(Dispatchers.IO) {
        val out = LimitedSshOutput(maxOutput)
        val err = LimitedSshOutput(maxOutput)
        var channel: ChannelExec? = null
        var submitted = false
        try {
            val s = session(id)
            channel = s.openChannel("exec") as ChannelExec
            channel.setCommand("cd -- ${sshQuote(cwd)} && exec sh -c ${sshQuote(command)}")
            channel.setInputStream(null)
            channel.setOutputStream(out)
            channel.setErrStream(err)
            submitted = true
            channel.connect(10_000)
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
            while (!channel.isClosed && s.isConnected) {
                if (System.nanoTime() >= deadline) {
                    failed(id, "Command timed out. Check command status before retrying.", true)
                    return@withContext ShellRunResult(-1, true, out.text(), err.text(), ExecutionTier.TERMUX_SSH, "Remote command outcome is uncertain; do not repeat it automatically.")
                }
                delay(25)
            }
            check(channel.exitStatus >= 0) { "Connection ended without an exit status. Check command status before retrying." }
            ShellRunResult(channel.exitStatus, false, out.text(), err.text(), ExecutionTier.TERMUX_SSH, "SSH workspace; remote Git identity and credentials")
        } catch (e: CancellationException) {
            if (submitted) failed(id, "Command interrupted. Check command status before retrying.", true)
            throw e
        } catch (e: Exception) {
            failed(id, e.message ?: "Connection failed", submitted)
            ShellRunResult(-1, false, out.text(), err.text() + "\n" + e.message, ExecutionTier.TERMUX_SSH,
                if (submitted) "Remote outcome uncertain. Reconnect and inspect before retrying." else "Reconnect in workspace Connection settings.")
        } finally { channel?.disconnect() }
    }
}
