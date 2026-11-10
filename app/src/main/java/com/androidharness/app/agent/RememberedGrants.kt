package com.androidharness.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Approvals the user chose to repeat. A grant lasts for the current run, and
 * when [persistence] is given and [GrantPolicy] allows it, it is also saved for
 * its workspace so later runs start with it already approved.
 */
internal class RememberedGrants(private val persistence: GrantPersistence? = null) {
    /** [saved] grants outlive the run that created them; [sessionId] is blank for those. */
    data class Grant(val sessionId: String, val workspacePath: String, val key: String, val saved: Boolean = false)
    private data class Run(val workspacePath: String, val keys: MutableSet<String>)
    private val runs = mutableMapOf<String, Run>()
    private val saved = mutableMapOf<String, MutableSet<String>>().also { all ->
        persistence?.load()?.forEach { (path, keys) ->
            val valid = keys.filterTo(sortedSetOf()) { GrantPolicy.canSave(it) }
            if (valid.isNotEmpty()) all[path] = valid
        }
    }
    private val state = MutableStateFlow<List<Grant>>(emptyList())
    val grants: StateFlow<List<Grant>> = state

    init { publish() }

    /** [useSaved] false starts the run with nothing pre-approved, for Confirm everything. */
    @Synchronized fun start(sessionId: String, workspacePath: String, useSaved: Boolean = true): MutableSet<String> {
        val keys = ConcurrentHashMap.newKeySet<String>()
        if (useSaved) saved[workspacePath]?.let(keys::addAll)
        runs[sessionId] = Run(workspacePath, keys)
        publish()
        return keys
    }

    /** Returns true when the approval was also saved for later runs. */
    @Synchronized fun remember(sessionId: String, key: String): Boolean {
        val run = runs[sessionId] ?: return false
        run.keys.add(key)
        val keep = persistence != null && GrantPolicy.canSave(key)
        if (keep && saved.getOrPut(run.workspacePath) { sortedSetOf() }.add(key)) persistence?.save(snapshot())
        publish()
        return keep
    }

    @Synchronized fun revoke(grant: Grant) {
        if (grant.saved) {
            removeSaved(grant.workspacePath, grant.key)
            runs.values.filter { it.workspacePath == grant.workspacePath }.forEach { it.keys.remove(grant.key) }
        } else {
            runs[grant.sessionId]?.takeIf { it.workspacePath == grant.workspacePath }?.keys?.remove(grant.key)
        }
        publish()
    }

    /** Clears every saved and active grant of one workspace. */
    @Synchronized fun revokeAll(workspacePath: String) {
        if (saved.remove(workspacePath) != null) persistence?.save(snapshot())
        runs.values.filter { it.workspacePath == workspacePath }.forEach { it.keys.clear() }
        publish()
    }

    @Synchronized fun finish(sessionId: String) {
        runs.remove(sessionId)?.keys?.clear()
        publish()
    }

    private fun removeSaved(workspacePath: String, key: String) {
        val keys = saved[workspacePath] ?: return
        if (!keys.remove(key)) return
        if (keys.isEmpty()) saved.remove(workspacePath)
        persistence?.save(snapshot())
    }

    private fun snapshot(): Map<String, Set<String>> = saved.mapValues { it.value.toSet() }

    private fun publish() {
        val all = ArrayList<Grant>()
        saved.forEach { (path, keys) -> keys.forEach { all += Grant("", path, it, saved = true) } }
        runs.forEach { (session, run) ->
            run.keys.forEach { key -> if (saved[run.workspacePath]?.contains(key) != true) all += Grant(session, run.workspacePath, key) }
        }
        state.value = all.sortedWith(compareBy({ it.workspacePath }, { it.key }, { it.sessionId }))
    }
}
