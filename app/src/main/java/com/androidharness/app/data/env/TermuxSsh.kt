package com.androidharness.app.data.env

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
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

