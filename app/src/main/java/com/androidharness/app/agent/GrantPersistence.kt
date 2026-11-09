package com.androidharness.app.agent

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where "Always" approvals live between runs: workspace path to the keys approved there. */
internal interface GrantPersistence {
    fun load(): Map<String, Set<String>>
    fun save(grants: Map<String, Set<String>>)
}

/** Plain app-private preferences. Grants are not secrets, and they stay out of settings backups on purpose. */
internal class PrefsGrantPersistence(private val context: Context) : GrantPersistence {
    private val serializer = MapSerializer(String.serializer(), SetSerializer(String.serializer()))
    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Map<String, Set<String>> {
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return emptyMap()
        return runCatching { Json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    override fun save(grants: Map<String, Set<String>>) {
        runCatching { prefs.edit().putString(KEY, Json.encodeToString(serializer, grants)).apply() }
    }

    private companion object {
        const val FILE = "remembered_grants"
        const val KEY = "grants"
    }
}

/**
 * Decides which approvals may outlive the run that granted them. Anything that
 * installs software, publishes work, or hands the agent an open-ended command
 * has to be approved again, so "Always" never turns into a blank check.
 */
internal object GrantPolicy {
    private val neverSaved = setOf(
        "pkg_install", "android_sdk_install", "device_owner_shell", "github_create_pull_request", "git_push",
    )

    /** First words that can run anything, or that are destructive no matter what follows. */
    private val openEnded = setOf(
        "sh", "bash", "zsh", "dash", "ash", "su", "sudo", "doas", "eval", "exec", "env", "xargs", "nohup", "time",
        "timeout", "busybox", "python", "python3", "node", "nodejs", "perl", "ruby", "php", "lua",
        "curl", "wget", "ssh", "scp", "sftp", "nc", "ncat", "adb", "rm", "dd", "chmod", "chown", "kill", "pkill",
        "killall", "mkfs", "mount",
    )

    private val workspacePrefix = Regex("^workspace:[^:]+:")

    fun canSave(key: String): Boolean {
        if (key.startsWith("config:") || key.startsWith("app_settings:")) return false
        val base = key.replace(workspacePrefix, "")
        if (base.isBlank() || base in neverSaved) return false
        val hash = base.indexOf('#')
        if (hash < 0) return true
        val tool = base.substring(0, hash)
        val first = base.substring(hash + 1).trim().substringBefore(' ').substringAfterLast('/').lowercase()
        return tool !in neverSaved && first.isNotEmpty() && first !in openEnded
    }
}
