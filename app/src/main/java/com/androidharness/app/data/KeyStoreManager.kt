package com.androidharness.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

/** API keys are stored in EncryptedSharedPreferences backed by the Android Keystore. */
class KeyStoreManager(context: Context) {

    private val prefs = openPrefs(context)

    /**
     * The prefs file is wrapped by an AndroidKeyStore master key, so keystore
     * loss (credential reset, backup restore onto another install) makes it
     * undecryptable. Creating it would then throw from Application.onCreate
     * and crash-loop the app forever; reset the file once and start over
     * empty instead. The stored secrets are gone either way at that point.
     */
    private fun openPrefs(context: Context): android.content.SharedPreferences =
        try {
            createPrefs(context)
        } catch (e: GeneralSecurityException) {
            Log.w("KeyStoreManager", "Encrypted prefs are undecryptable (keystore reset?); resetting them", e)
            context.deleteSharedPreferences(PREFS_NAME)
            createPrefs(context)
        }

    private fun createPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun putKey(providerId: String, apiKey: String) {
        prefs.edit().putString(providerId, apiKey).apply()
    }

    fun getKey(providerId: String): String? = prefs.getString(providerId, null)

    fun removeKey(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }

    /** GitHub access token used for push/PR/private-repo access from the toolchain. */
    fun putGitHubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB, token.trim()).apply()
    }

    fun githubToken(): String? = prefs.getString(KEY_GITHUB, null)?.trim()?.ifBlank { null }

    fun removeGitHubToken() {
        prefs.edit().remove(KEY_GITHUB).apply()
    }

    /** GitHub login name captured when a token was verified against /user. */
    fun putGitHubLogin(login: String) {
        prefs.edit().putString(KEY_GITHUB_LOGIN, login.trim()).apply()
    }

    fun githubLogin(): String? = prefs.getString(KEY_GITHUB_LOGIN, null)?.trim()?.ifBlank { null }

    fun removeGitHubLogin() {
        prefs.edit().remove(KEY_GITHUB_LOGIN).apply()
    }

    /**
     * Search API keys used by the web_search tool, one slot per provider
     * (brave/tavily) so switching providers keeps both keys. The pre-split
     * single key lives in [KEY_SEARCH_API] until [migrateLegacySearchKey].
     */
    fun putSearchApiKey(provider: String, key: String) {
        prefs.edit().putString(searchKeySlot(provider), key.trim()).apply()
    }

    fun searchApiKey(provider: String): String? =
        prefs.getString(searchKeySlot(provider), null)?.trim()?.ifBlank { null }

    fun removeSearchApiKey(provider: String) {
        prefs.edit().remove(searchKeySlot(provider)).apply()
    }

    /** One-time: attribute the pre-split single key to [provider]'s slot. */
    fun migrateLegacySearchKey(provider: String) {
        val legacy = prefs.getString(KEY_SEARCH_API, null)?.trim()?.ifBlank { null } ?: return
        prefs.edit()
            .putString(searchKeySlot(provider), legacy)
            .remove(KEY_SEARCH_API)
            .apply()
    }

    private fun searchKeySlot(provider: String) = "${KEY_SEARCH_API}_$provider"

    /**
     * Per-server MCP OAuth state (discovered endpoints + tokens) as a JSON
     * blob, kept in the app's KeyStore-encrypted prefs like every other
     * credential.
     */
    fun putMcpOAuthState(server: String, stateJson: String) {
        prefs.edit().putString(mcpOAuthSlot(server), stateJson).apply()
    }

    fun mcpOAuthState(server: String): String? =
        prefs.getString(mcpOAuthSlot(server), null)?.trim()?.ifBlank { null }

    fun removeMcpOAuthState(server: String) {
        prefs.edit().remove(mcpOAuthSlot(server)).apply()
    }

    private fun mcpOAuthSlot(server: String) =
        "${KEY_MCP_OAUTH}_${McpServerNameSanitizer.sanitize(server)}"

    /** Groq API key used for high-speed Whisper speech-to-text transcriptions. */
    fun putGroqApiKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API, key.trim()).apply()
    }

    fun groqApiKey(): String? = prefs.getString(KEY_GROQ_API, null)?.trim()?.ifBlank { null }

    fun removeGroqApiKey() {
        prefs.edit().remove(KEY_GROQ_API).apply()
    }

    private object McpServerNameSanitizer {
        fun sanitize(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_")
                .trim('_').takeIf { it.isNotEmpty() } ?: "x"
    }

    private companion object {
        const val PREFS_NAME = "api_keys"
        const val KEY_GITHUB = "github_pat"
        const val KEY_GITHUB_LOGIN = "github_login"
        const val KEY_SEARCH_API = "search_api_key"
        const val KEY_MCP_OAUTH = "mcp_oauth"
        const val KEY_GROQ_API = "groq_api_key"
    }
}
