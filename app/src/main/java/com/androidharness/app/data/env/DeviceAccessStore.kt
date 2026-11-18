package com.androidharness.app.data.env

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

// Device-local authorization is deliberately separate from portable settings backups.
private val Context.accessStore by preferencesDataStore("device_access")
class DeviceAccessStore(context: Context) {
    private val store = context.applicationContext.accessStore
    private val mode = stringPreferencesKey("mode")
    private val fallback = booleanPreferencesKey("auto_fallback")
    private val rootUse = stringPreferencesKey("root_use")
    private val projects = stringSetPreferencesKey("root_projects")
    private val temporaryRoot = booleanPreferencesKey("temporary_root")
    private val appWorkspacePrivileged = booleanPreferencesKey("app_workspace_privileged")
    private val askPhoneControl = booleanPreferencesKey("ask_phone_control")
    private val phoneControlChats = stringSetPreferencesKey("phone_control_chats")
    val preferences = store.data.map { p -> AccessPreferences(
        mode = p[mode]?.let { runCatching { AccessMode.valueOf(it) }.getOrNull() } ?: AccessMode.AUTO,
        autoFallback = p[fallback] ?: true,
        rootUse = p[rootUse]?.let { runCatching { RootUse.valueOf(it) }.getOrNull() } ?: RootUse.SESSION,
        rootProjects = p[projects].orEmpty(),
        temporaryRoot = p[temporaryRoot] ?: true,
        appWorkspacePrivileged = p[appWorkspacePrivileged] ?: false,
        askPhoneControl = p[askPhoneControl] ?: true,
        phoneControlChats = p[phoneControlChats].orEmpty(),
    ) }
    suspend fun setMode(value: AccessMode) { store.edit { it[mode] = value.name } }
    suspend fun setFallback(value: Boolean) { store.edit { it[fallback] = value } }
    suspend fun setRootUse(value: RootUse) { store.edit { it[rootUse] = value.name } }
    suspend fun setTemporaryRoot(enabled: Boolean) { store.edit { it[temporaryRoot] = enabled } }
    suspend fun setAppWorkspacePrivileged(enabled: Boolean) { store.edit { it[appWorkspacePrivileged] = enabled } }
    /** Turning the question on again also forgets every chat that was allowed before. */
    suspend fun setAskPhoneControl(enabled: Boolean) { store.edit { it[askPhoneControl] = enabled; if (enabled) it[phoneControlChats] = emptySet() } }
    suspend fun allowPhoneControl(sessionId: String, allow: Boolean) {
        store.edit { it[phoneControlChats] = if (allow) it[phoneControlChats].orEmpty() + sessionId else it[phoneControlChats].orEmpty() - sessionId }
    }
    suspend fun allowProject(id: String, allow: Boolean) { store.edit { it[projects] = if (allow) it[projects].orEmpty() + id else it[projects].orEmpty() - id } }
}
