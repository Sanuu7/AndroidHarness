package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.llm.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.providerStore by preferencesDataStore(name = "providers")

class ProviderRepository(
    private val context: Context,
    private val keys: KeyStoreManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listKey = stringPreferencesKey("provider_list")

    val providers: Flow<List<ProviderConfig>> = context.providerStore.data.map { prefs ->
        prefs[listKey]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun add(name: String, type: com.androidharness.app.llm.ProviderType, baseUrl: String, model: String, apiKey: String): ProviderConfig {
        val config = ProviderConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            baseUrl = baseUrl.ifBlank { type.defaultBaseUrl },
            model = model,
        )
        if (apiKey.isNotBlank()) keys.putKey(config.id, apiKey)
        save(current() + config)
        return config
    }

    suspend fun update(config: ProviderConfig, apiKey: String?) {
        if (apiKey != null) {
            if (apiKey.isBlank()) keys.removeKey(config.id) else keys.putKey(config.id, apiKey)
        }
        save(current().map { if (it.id == config.id) config else it })
    }

    suspend fun delete(id: String) {
        keys.removeKey(id)
        save(current().filterNot { it.id == id })
    }

    fun apiKey(providerId: String): String? = keys.getKey(providerId)

    private suspend fun current(): List<ProviderConfig> = providers.first()

    private suspend fun save(list: List<ProviderConfig>) {
        val raw = json.encodeToString(ListSerializer(ProviderConfig.serializer()), list)
        context.providerStore.edit { it[listKey] = raw }
    }
}
