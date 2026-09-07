package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.llm.HarnessProvider
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
        val saved = prefs[listKey]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val harnessCustom = prefs[customModelsKey(HarnessProvider.ID)]?.let { raw ->
            runCatching {
                json.decodeFromString<List<ModelEntry>>(raw)
            }.getOrDefault(emptyList())
        }?.map { it.id }?.toSet().orEmpty()
        listOf(HarnessProvider.config.copy(model = HarnessProvider.sanitize(saved.firstOrNull { it.id == HarnessProvider.ID }?.model, harnessCustom))) + saved.filterNot { it.id == HarnessProvider.ID }
    }

    /**
     * Fetched model catalogs per provider id, persisted so the pickers show
     * every model a provider offers without refetching on each open. Custom
     * models added by the user merge at the top of each list.
     */
    val catalogs: Flow<Map<String, List<ModelEntry>>> = context.providerStore.data.map { prefs ->
        val catalogMap = prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(CATALOG_PREFIX) }
            .mapNotNull { (key, value) ->
                val providerId = key.name.removePrefix(CATALOG_PREFIX)
                runCatching {
                    json.decodeFromString(ListSerializer(ModelEntry.serializer()), value as String)
                }.getOrNull()?.let { providerId to it }
            }
            .toMap().toMutableMap()

        if (catalogMap[HarnessProvider.ID].isNullOrEmpty()) {
            catalogMap[HarnessProvider.ID] = HarnessProvider.fallbackModels
        }

        prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(CUSTOM_MODELS_PREFIX) }
            .forEach { (key, value) ->
                val providerId = key.name.removePrefix(CUSTOM_MODELS_PREFIX)
                val customList = runCatching {
                    json.decodeFromString(ListSerializer(ModelEntry.serializer()), value as String)
                }.getOrDefault(emptyList())
                if (customList.isNotEmpty()) {
                    val base = catalogMap[providerId].orEmpty().filterNot { baseEntry ->
                        customList.any { it.id == baseEntry.id }
                    }
                    catalogMap[providerId] = customList + base
                }
            }

        catalogMap
    }

    suspend fun catalog(providerId: String): List<ModelEntry> =
        catalogs.first()[providerId].orEmpty()

    suspend fun customModels(providerId: String): List<ModelEntry> {
        val raw = context.providerStore.data.first()[customModelsKey(providerId)] ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ModelEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    suspend fun addCustomModel(providerId: String, modelId: String, reasoning: Boolean? = null) {
        val clean = modelId.trim()
        if (clean.isBlank()) return
        context.providerStore.edit { prefs ->
            val key = customModelsKey(providerId)
            val raw = prefs[key]
            val existing = if (raw == null) emptyList() else runCatching {
                json.decodeFromString<List<ModelEntry>>(raw)
            }.getOrDefault(emptyList())
            val updated = listOf(ModelEntry(clean, reasoning = reasoning, custom = true)) +
                existing.filterNot { it.id == clean }
            prefs[key] = json.encodeToString(ListSerializer(ModelEntry.serializer()), updated)
        }
    }

    suspend fun removeCustomModel(providerId: String, modelId: String) {
        context.providerStore.edit { prefs ->
            val key = customModelsKey(providerId)
            val raw = prefs[key]
            val existing = if (raw == null) emptyList() else runCatching {
                json.decodeFromString<List<ModelEntry>>(raw)
            }.getOrDefault(emptyList())
            val updated = existing.filterNot { it.id == modelId }
            if (updated.isEmpty()) prefs.remove(key)
            else prefs[key] = json.encodeToString(ListSerializer(ModelEntry.serializer()), updated)
        }
    }

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
        if (config.id == HarnessProvider.ID) {
            val custom = customModels(HarnessProvider.ID).map { it.id }.toSet()
            save(current().map { if (it.id == config.id) HarnessProvider.config.copy(model = HarnessProvider.sanitize(config.model, custom)) else it })
            return
        }
        if (apiKey != null) {
            if (apiKey.isBlank()) keys.removeKey(config.id) else keys.putKey(config.id, apiKey)
        }
        save(current().map { if (it.id == config.id) config else it })
    }

    suspend fun delete(id: String) {
        if (id == HarnessProvider.ID) return
        keys.removeKey(id)
        context.providerStore.edit { it.remove(catalogKey(id)) }
        save(current().filterNot { it.id == id })
    }

    fun apiKey(providerId: String): String? = if (providerId == HarnessProvider.ID) HarnessProvider.KEYLESS else keys.getKey(providerId)

    /**
     * Learned wire protocol per Harness model. The first chat request for a
     * model probes chat/completions vs /messages; the winner is stored here
     * so every later request routes directly. Keyed by bare model id so the
     * pin survives catalog refetches.
     */
    val harnessWires: Flow<Map<String, String>> = context.providerStore.data.map { prefs ->
        prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(WIRE_PREFIX) }
            .mapNotNull { (key, value) ->
                val model = key.name.removePrefix(WIRE_PREFIX)
                (value as? String)?.takeIf { it.isNotEmpty() }?.let { model to it }
            }
            .toMap()
    }

    suspend fun wire(model: String): String? = harnessWires.first()[model]

    suspend fun pinWire(model: String, wire: String) {
        context.providerStore.edit { it[stringPreferencesKey(WIRE_PREFIX + model)] = wire }
    }

    private fun catalogKey(providerId: String) = stringPreferencesKey(CATALOG_PREFIX + providerId)
    private fun customModelsKey(providerId: String) = stringPreferencesKey(CUSTOM_MODELS_PREFIX + providerId)

    private suspend fun current(): List<ProviderConfig> = providers.first()

    private suspend fun save(list: List<ProviderConfig>) {
        val raw = json.encodeToString(ListSerializer(ProviderConfig.serializer()), list)
        context.providerStore.edit { it[listKey] = raw }
    }

    private companion object {
        const val CATALOG_PREFIX = "catalog_"
        const val CUSTOM_MODELS_PREFIX = "custom_models_"
        const val WIRE_PREFIX = "wire_harness_"
    }
}
