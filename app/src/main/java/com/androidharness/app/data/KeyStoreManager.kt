package com.androidharness.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys are stored in EncryptedSharedPreferences backed by the Android Keystore. */
class KeyStoreManager(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "api_keys",
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

    /** GitHub PAT used for push/PR/private-repo access from the toolchain. */
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

    private object McpServerNameSanitizer {
        fun sanitize(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_")
                .trim('_').takeIf { it.isNotEmpty() } ?: "x"
    }

    private companion object {
        const val KEY_GITHUB = "github_pat"
        const val KEY_GITHUB_LOGIN = "github_login"
        const val KEY_SEARCH_API = "search_api_key"
        const val KEY_MCP_OAUTH = "mcp_oauth"
    }
}
