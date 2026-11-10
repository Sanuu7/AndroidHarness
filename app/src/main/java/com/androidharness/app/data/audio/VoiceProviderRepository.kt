package com.androidharness.app.data.audio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.data.KeyStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.voiceStore by preferencesDataStore(name = "voice_providers")

/**
 * Persists the voice providers the user can choose from.
 *
 * The built-in presets in [VoicePresets] are always available; edits to them
 * are saved as overrides, so a preset the user never touched still picks up
 * future model ids without a migration. Keys live in [KeyStoreManager], so
 * this list is safe to export in a settings backup.
 */
class VoiceProviderRepository(
    private val context: Context,
    private val keys: KeyStoreManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listKey = stringPreferencesKey("voice_provider_list")
    private val serializer = ListSerializer(VoiceProviderConfig.serializer())

    init {
        // Upgrade path from the single-key Groq era: the old key becomes the
        // Groq provider's key on first launch after the update.
        runCatching { keys.migrateGroqKeyToVoiceProviders() }
    }

    /** Presets merged with user edits and custom endpoints, in display order. */
    val providers: Flow<List<VoiceProviderConfig>> = context.voiceStore.data.map { prefs ->
        val saved = prefs[listKey]?.let { raw ->
            runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        } ?: emptyList()
        merge(saved)
    }

    suspend fun current(): List<VoiceProviderConfig> = providers.first()

    suspend fun byId(id: String?): VoiceProviderConfig? =
        id?.let { wanted -> current().firstOrNull { it.id == wanted } }

    suspend fun save(provider: VoiceProviderConfig) {
        val saved = storedOverrides()
        val existing = saved.indexOfFirst { it.id == provider.id }
        val updated = if (existing >= 0) saved.toMutableList().also { it[existing] = provider } else saved + provider
        write(updated)
    }

    /** Removes an override for a preset or deletes a custom endpoint. */
    suspend fun delete(id: String) {
        write(storedOverrides().filterNot { it.id == id })
        keys.removeVoiceApiKey(id)
    }

    /** True when this provider still has an unsaved identity (a preset, untouched). */
    fun isPreset(id: String): Boolean = VoicePresets.byId(id) != null

    fun newCustomProvider(): VoiceProviderConfig = VoiceProviderConfig(
        id = "voice_${UUID.randomUUID().toString().take(8)}",
        name = "",
        protocol = VoiceProtocol.OPENAI_AUDIO,
        baseUrl = "",
        sttModel = "",
        ttsModel = "",
        ttsVoice = "",
        custom = true,
    )

    fun apiKeyFor(id: String): String? = keys.voiceApiKey(id)

    fun saveApiKey(id: String, key: String) = keys.putVoiceApiKey(id, key)

    fun removeApiKey(id: String) = keys.removeVoiceApiKey(id)

    /** What a freshly installed app offers before the user changes anything. */
    /**
     * What the app offers first. Edge needs no setup at all, so it is the
     * friendliest cloud default; the device engine stays the saved default
     * until the user picks something else.
     */
    fun defaultProviderId(forTts: Boolean): String =
        if (forTts) VoicePresets.EDGE else VoicePresets.GROQ

    /** Provider usable for the requested direction, falling back to a preset. */
    fun resolve(list: List<VoiceProviderConfig>, id: String?, forTts: Boolean): VoiceProviderConfig? {
        val chosen = id?.let { wanted -> list.firstOrNull { it.id == wanted } }
        if (chosen != null && (if (forTts) chosen.supportsTts else chosen.supportsStt)) return chosen
        return list.firstOrNull { if (forTts) it.supportsTts else it.supportsStt }
    }

    fun supportsDirection(provider: VoiceProviderConfig, forTts: Boolean): Boolean =
        if (forTts) provider.supportsTts else provider.supportsStt

    private fun merge(saved: List<VoiceProviderConfig>): List<VoiceProviderConfig> {
        val overrides = saved.associateBy { it.id }
        val presets = VoicePresets.presets.map { preset -> overrides[preset.id] ?: preset }
        val customs = saved.filter { entry -> entry.custom && overrides[entry.id] == entry }
        // A saved edit of a preset keeps its place in the preset order; custom
        // endpoints are appended in creation order.
        return presets + customs.filterNot { custom -> presets.any { it.id == custom.id } }
    }

    private suspend fun storedOverrides(): List<VoiceProviderConfig> =
        context.voiceStore.data.first()[listKey]?.let { raw ->
            runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    private suspend fun write(list: List<VoiceProviderConfig>) {
        val encoded = json.encodeToString(serializer, list)
        context.voiceStore.edit { it[listKey] = encoded }
    }
}
