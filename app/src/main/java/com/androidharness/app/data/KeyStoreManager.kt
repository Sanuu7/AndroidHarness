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
}
