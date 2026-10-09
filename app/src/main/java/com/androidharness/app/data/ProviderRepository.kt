package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
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
    private val localModels: com.androidharness.app.local.LocalModelManager,
    private val chatGpt: com.androidharness.app.chatgpt.ChatGptAccounts,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listKey = stringPreferencesKey("provider_list")

    val providers: Flow<List<ProviderConfig>> = kotlinx.coroutines.flow.combine(context.providerStore.data, localModels.installed, chatGpt.state) { prefs, installed, accounts ->
        HarnessProvider.restoreModels(harnessModels(prefs))
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
        HarnessProvider.customModelIds = harnessCustom
        accounts.accounts.filter { it.connected && it.models.isNotEmpty() }.map { it.config() } +
            listOf(HarnessProvider.config.copy(model = HarnessProvider.sanitize(saved.firstOrNull { it.id == HarnessProvider.ID }?.model, harnessCustom))) + saved.filterNot { it.id == HarnessProvider.ID || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) || com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) || it.baseUrl.startsWith("local://") } + localModels.configs(installed)
    }

    /**
     * Fetched model catalogs per provider id, persisted so the pickers show
     * every model a provider offers without refetching on each open. Custom
     * models added by the user merge at the top of each list.
     */
    val catalogs: Flow<Map<String, List<ModelEntry>>> = kotlinx.coroutines.flow.combine(context.providerStore.data, localModels.installed, chatGpt.state) { prefs, installed, accounts ->
        val catalogMap = prefs.asMap().asSequence()
            .filter { it.key.name.startsWith(CATALOG_PREFIX) }
            .mapNotNull { (key, value) ->
                val providerId = key.name.removePrefix(CATALOG_PREFIX)
                runCatching {
                    json.decodeFromString(ListSerializer(ModelEntry.serializer()), value as String)
                }.getOrNull()?.let { providerId to it }
            }
            .toMap().toMutableMap()

        catalogMap[HarnessProvider.ID] = harnessModels(prefs)

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

        localModels.configs(installed).forEach { config ->
            catalogMap[config.id] = listOf(ModelEntry(config.model, reasoning = false, note = "On-device text chat"))
        }
        catalogMap.keys.filter { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it) }.forEach { catalogMap.remove(it) }
        accounts.accounts.filter { it.connected }.forEach { catalogMap[it.providerId] = it.models }
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

    suspend fun resolveHarnessConfig(config: ProviderConfig): ProviderConfig {
        if (config.id != HarnessProvider.ID) return config
        providers.first() // A background resume can arrive before the UI starts collecting.
        val custom = customModels(HarnessProvider.ID).map { it.id }.toSet()
        return config.copy(model = HarnessProvider.sanitize(config.model, custom))
    }

    suspend fun addCustomModel(providerId: String, modelId: String, reasoning: Boolean? = null) {
        if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(providerId)) return
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(providerId)) return
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
            if (providerId == HarnessProvider.ID) prefs[harnessCatalogSource] = HarnessProvider.CATALOG_SOURCE
        }
        if (providerId == HarnessProvider.ID) HarnessProvider.restoreModels(entries)
    }

    // Old catalog_harness snapshots came from Zen or the hardcoded pool, not Kilo.
    private val harnessCatalogSource = stringPreferencesKey("harness_catalog_source")
    private fun harnessModels(prefs: Preferences): List<ModelEntry> = HarnessProvider.cachedModels(
        prefs[catalogKey(HarnessProvider.ID)], prefs[harnessCatalogSource],
    )

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
        if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id)) return
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(config.id)) return
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
        if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(id)) return
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(id)) {
            localModels.remove(id.removePrefix(com.androidharness.app.local.LocalModelCatalog.PROVIDER_PREFIX))
            return
        }
        if (id == HarnessProvider.ID) return
        keys.removeKey(id)
        context.providerStore.edit { it.remove(catalogKey(id)) }
        save(current().filterNot { it.id == id })
    }

    fun apiKey(providerId: String): String? = when {
        com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(providerId) ->
            if (chatGpt.state.value.accounts.any { it.providerId == providerId && it.connected }) "managed-oauth" else null
        com.androidharness.app.local.LocalModelCatalog.isLocal(providerId) -> "local"
        providerId == HarnessProvider.ID -> HarnessProvider.KEYLESS
        else -> keys.getKey(providerId)
    }

    /**
     * Zen ended anonymous access to its free models (keyless calls get a 403
     * FreeTierError), so the built-in provider rides on the key saved for any
     * OpenCode-branded provider. Falls back to the keyless sentinel.
     */
    suspend fun harnessApiKey(): String = harnessApiKey(current())

    fun harnessApiKey(candidates: List<ProviderConfig>): String =
        keys.getKey(HarnessProvider.ID)?.takeIf { it.isNotBlank() }
            ?: candidates.firstOrNull { it.id != HarnessProvider.ID && HarnessProvider.isOpenCode(it) }
                ?.let { keys.getKey(it.id) }?.takeIf { it.isNotBlank() }
            ?: HarnessProvider.KEYLESS

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
        val raw = json.encodeToString(ListSerializer(ProviderConfig.serializer()), list.filterNot { com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) })
        context.providerStore.edit { it[listKey] = raw }
    }

    private companion object {
        const val CATALOG_PREFIX = "catalog_"
        const val CUSTOM_MODELS_PREFIX = "custom_models_"
        const val WIRE_PREFIX = "wire_harness_"
    }
}
