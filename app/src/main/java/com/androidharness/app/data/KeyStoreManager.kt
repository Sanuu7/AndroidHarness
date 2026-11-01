package com.androidharness.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

/** API keys are stored in EncryptedSharedPreferences backed by the Android Keystore. */
class KeyStoreManager(context: Context) {

    private val prefs = openPrefs(context)

    fun chatGptCredentials(): String? = prefs.getString("chatgpt_accounts", null)

    fun putChatGptCredentials(value: String) {
        check(prefs.edit().putString("chatgpt_accounts", value).commit()) { "Could not securely save ChatGPT connection." }
    }

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

    @Synchronized fun putKey(providerId: String, apiKey: String) {
        putProviderControls(providerId, providerKeyPool(providerId).replaceDefault(apiKey.trim()), providerProxy(providerId))
    }

    fun getKey(providerId: String): String? = providerKeyPool(providerId).keys.firstOrNull { it.enabled }?.value

    /** Structured device credentials are separate from provider API-key pools. */
    fun localSecret(slot: String): String? = prefs.getString("local_secret:$slot", null) ?: prefs.getString(slot, null)
    @Synchronized fun putLocalSecret(slot: String, value: String) {
        check(prefs.edit().putString("local_secret:$slot", value).remove(slot).remove("provider_keys:$slot").commit()) { "Could not save connection credentials." }
    }
    @Synchronized fun removeLocalSecret(slot: String) {
        check(prefs.edit().remove("local_secret:$slot").remove(slot).remove("provider_keys:$slot").commit()) { "Could not remove connection credentials." }
    }

    fun providerKeyPool(providerId: String): com.androidharness.app.llm.ProviderKeyPool {
        val raw = prefs.getString("provider_keys:$providerId", null)
        if (raw != null) return try {
            kotlinx.serialization.json.Json.decodeFromString<com.androidharness.app.llm.ProviderKeyPool>(raw).also {
                check(it.error() == null)
            }
        } catch (_: Exception) { throw IllegalStateException("Saved provider API keys could not be read. Reopen connection controls.") }
        val legacy = prefs.getString(providerId, null)?.takeIf { it.isNotBlank() }
        return com.androidharness.app.llm.ProviderKeyPool(legacy?.let {
            listOf(com.androidharness.app.llm.ProviderApiKey(id = "primary", value = it))
        }.orEmpty())
    }

    fun providerProxy(providerId: String?): com.androidharness.app.llm.ProviderProxy =
        prefs.getString(if (providerId == null) "global_provider_proxy" else "provider_proxy:$providerId", null)?.let {
            try { kotlinx.serialization.json.Json.decodeFromString<com.androidharness.app.llm.ProviderProxy>(it).also { proxy -> check(proxy.error() == null) } }
            catch (_: Exception) { throw IllegalStateException("Saved provider proxy could not be read. Reopen connection controls.") }
        } ?: com.androidharness.app.llm.ProviderProxy()

    @Synchronized fun putProviderControls(providerId: String, pool: com.androidharness.app.llm.ProviderKeyPool?, proxy: com.androidharness.app.llm.ProviderProxy) {
        require(pool?.error() == null) { pool?.error().orEmpty() }
        require(proxy.error() == null) { proxy.error().orEmpty() }
        val edit = prefs.edit()
        if (pool != null) {
            edit.putString("provider_keys:$providerId", kotlinx.serialization.json.Json.encodeToString(com.androidharness.app.llm.ProviderKeyPool.serializer(), pool))
            val first = pool.keys.firstOrNull { it.enabled }?.value
            if (first == null) edit.remove(providerId) else edit.putString(providerId, first)
        }
        if (proxy.mode == com.androidharness.app.llm.ProviderProxyMode.INHERIT) edit.remove("provider_proxy:$providerId")
        else edit.putString("provider_proxy:$providerId", kotlinx.serialization.json.Json.encodeToString(com.androidharness.app.llm.ProviderProxy.serializer(), proxy))
        check(edit.commit()) { "Could not securely save provider controls." }
    }

    @Synchronized fun putGlobalProviderProxy(proxy: com.androidharness.app.llm.ProviderProxy) {
        require(proxy.error() == null) { proxy.error().orEmpty() }
        val edit = prefs.edit()
        if (proxy.mode == com.androidharness.app.llm.ProviderProxyMode.INHERIT) edit.remove("global_provider_proxy")
        else edit.putString("global_provider_proxy", kotlinx.serialization.json.Json.encodeToString(com.androidharness.app.llm.ProviderProxy.serializer(), proxy))
        check(edit.commit()) { "Could not securely save global provider proxy." }
    }

    fun providerHeaders(providerId: String): List<com.androidharness.app.llm.ProviderHeader> =
        prefs.getString("provider_headers:$providerId", null)?.let {
            kotlinx.serialization.json.Json.decodeFromString<List<com.androidharness.app.llm.ProviderHeader>>(it)
        }.orEmpty()

    fun putProviderHeaders(providerId: String, headers: List<com.androidharness.app.llm.ProviderHeader>) {
        val clean = com.androidharness.app.llm.ProviderHeaders.normalized(headers)
        val editor = prefs.edit()
        if (clean.isEmpty()) editor.remove("provider_headers:$providerId")
        else editor.putString("provider_headers:$providerId", kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.androidharness.app.llm.ProviderHeader.serializer()), clean))
        check(editor.commit()) { "Could not securely save provider headers." }
    }

    fun removeKey(providerId: String) {
        check(prefs.edit().remove(providerId).remove("provider_keys:$providerId").remove("provider_proxy:$providerId").commit()) { "Could not securely remove provider credentials." }
    }

    /** GitHub access token used for push/PR/private-repo access from the toolchain. */
    fun putGitHubToken(token: String) {
        prefs.edit().remove("github_connection").putString(KEY_GITHUB, token.trim()).apply()
    }

    fun githubToken(): String? = prefs.getString(KEY_GITHUB, null)?.trim()?.ifBlank { null }

    fun removeGitHubToken() {
        prefs.edit().remove("github_connection").remove(KEY_GITHUB).apply()
    }

    /** GitHub login name captured when a token was verified against /user. */
    fun putGitHubLogin(login: String) {
        prefs.edit().putString(KEY_GITHUB_LOGIN, login.trim()).apply()
    }

    fun githubLogin(): String? = prefs.getString(KEY_GITHUB_LOGIN, null)?.trim()?.ifBlank { null }

    fun githubCredential(): com.androidharness.app.github.GitHubCredential? {
        val saved = prefs.getString("github_connection", null)
        if (saved != null) runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<com.androidharness.app.github.GitHubCredential>(saved)
        }.getOrNull()?.let { return it }
        return githubToken()?.let { com.androidharness.app.github.GitHubCredential(it, githubLogin().orEmpty()) }
    }

    fun putGitHubCredential(value: com.androidharness.app.github.GitHubCredential?) {
        val editor = prefs.edit()
        if (value == null) editor.remove("github_connection").remove(KEY_GITHUB).remove(KEY_GITHUB_LOGIN)
        else editor.putString("github_connection", kotlinx.serialization.json.Json.encodeToString(
            com.androidharness.app.github.GitHubCredential.serializer(), value))
            .putString(KEY_GITHUB, value.token).putString(KEY_GITHUB_LOGIN, value.login)
        check(editor.commit()) { "Could not securely save the GitHub connection." }
    }

    fun removeGitHubLogin() {
        prefs.edit().remove(KEY_GITHUB_LOGIN).apply()
    }

    /**
     * Search API keys used by the web_search tool, one slot per provider
     * (brave/tavily) so switching providers keeps both keys. The pre-split
     * single key lives in [KEY_SEARCH_API] until [migrateLegacySearchKey].
     */
    @Synchronized fun putSearchApiKey(provider: String, key: String) {
        putSearchKeyPool(provider, searchKeyPool(provider).replaceDefault(key.trim()))
    }

    /** The first enabled key, which is also what older readers of the single slot see. */
    fun searchApiKey(provider: String): String? =
        prefs.getString(searchKeySlot(provider), null)?.trim()?.ifBlank { null }

    @Synchronized fun removeSearchApiKey(provider: String) {
        check(prefs.edit().remove(searchKeySlot(provider)).remove(searchPoolSlot(provider)).commit()) { "Could not remove the search API key." }
    }

    /**
     * Up to [com.androidharness.app.llm.ProviderKeyPool.MAX_KEYS] keys per search
     * provider. Before pools existed the provider had one key in its single slot,
     * which reads back as a one-key pool.
     */
    fun searchKeyPool(provider: String): com.androidharness.app.llm.ProviderKeyPool {
        val raw = prefs.getString(searchPoolSlot(provider), null)
        if (raw != null) return try {
            kotlinx.serialization.json.Json.decodeFromString<com.androidharness.app.llm.ProviderKeyPool>(raw).also {
                check(it.error() == null)
            }
        } catch (_: Exception) { throw IllegalStateException("Saved search API keys could not be read. Reopen Web search settings.") }
        return com.androidharness.app.llm.ProviderKeyPool(searchApiKey(provider)?.let {
            listOf(com.androidharness.app.llm.ProviderApiKey(id = "primary", value = it))
        }.orEmpty())
    }

    @Synchronized fun putSearchKeyPool(provider: String, pool: com.androidharness.app.llm.ProviderKeyPool) {
        require(pool.error() == null) { pool.error().orEmpty() }
        val edit = prefs.edit()
        if (pool.keys.isEmpty()) edit.remove(searchPoolSlot(provider)).remove(searchKeySlot(provider))
        else {
            edit.putString(searchPoolSlot(provider), kotlinx.serialization.json.Json.encodeToString(com.androidharness.app.llm.ProviderKeyPool.serializer(), pool))
            val first = pool.keys.firstOrNull { it.enabled }?.value
            if (first == null) edit.remove(searchKeySlot(provider)) else edit.putString(searchKeySlot(provider), first)
        }
        check(edit.commit()) { "Could not save the search API keys." }
    }

    /** One-time: attribute the pre-split single key to [provider]'s slot. */
    @Synchronized fun migrateLegacySearchKey(provider: String) {
        val legacy = prefs.getString(KEY_SEARCH_API, null)?.trim()?.ifBlank { null } ?: return
        val target = if (legacy.startsWith("tvly-")) "tavily" else provider.takeIf { it == "brave" || it == "tavily" } ?: return
        val slot = searchKeySlot(target)
        val edit = prefs.edit()
            .apply { if (prefs.getString(slot, null).isNullOrBlank()) putString(slot, legacy) }
            .remove(KEY_SEARCH_API)
        check(edit.commit()) { "Could not migrate the saved search API key." }
    }

    private fun searchKeySlot(provider: String) = "${KEY_SEARCH_API}_$provider"
    private fun searchPoolSlot(provider: String) = "search_keys:$provider"

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

    /** Telegram setup and bot token share one encrypted record; excluded from portable backups. */
    fun telegramConnection(): String? = prefs.getString("telegram_connection", null)

    fun putTelegramConnection(value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove("telegram_connection") else editor.putString("telegram_connection", value)
        check(editor.commit()) { "Could not securely save Telegram setup." }
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
