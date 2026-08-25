package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
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

    /**
     * Fetched model catalogs per provider id, persisted so the pickers show
     * every model a provider offers without refetching on each open.
     */
    val catalogs: Flow<Map<String, List<ModelEntry>>> = context.providerStore.data.map { prefs ->
        prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(CATALOG_PREFIX) }
            .mapNotNull { (key, value) ->
                val providerId = key.name.removePrefix(CATALOG_PREFIX)
                runCatching {
                    json.decodeFromString(ListSerializer(ModelEntry.serializer()), value as String)
                }.getOrNull()?.let { providerId to it }
            }
            .toMap()
    }

    suspend fun catalog(providerId: String): List<ModelEntry> =
        catalogs.first()[providerId].orEmpty()

    suspend fun saveCatalog(providerId: String, entries: List<ModelEntry>) {
        context.providerStore.edit { prefs ->
            prefs[catalogKey(providerId)] =
                json.encodeToString(ListSerializer(ModelEntry.serializer()), entries)
        }
    }

    suspend fun add(name: String, type: ProviderType, baseUrl: String, model: String, apiKey: String): ProviderConfig {
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
        context.providerStore.edit { it.remove(catalogKey(id)) }
        save(current().filterNot { it.id == id })
    }

    fun apiKey(providerId: String): String? = keys.getKey(providerId)

    private fun catalogKey(providerId: String) = stringPreferencesKey(CATALOG_PREFIX + providerId)

    private suspend fun current(): List<ProviderConfig> = providers.first()

    private suspend fun save(list: List<ProviderConfig>) {
        val raw = json.encodeToString(ListSerializer(ProviderConfig.serializer()), list)
        context.providerStore.edit { it[listKey] = raw }
    }

    private companion object {
        const val CATALOG_PREFIX = "catalog_"
    }
}
