package com.androidharness.app.llm.debug

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Field level encryption for the payloads kept in the request log database.
 *
 * The log database is plain SQLite, so bodies are sealed here with a keystore
 * key instead: the metadata a list needs (time, provider, status, duration)
 * stays queryable, while prompt text never lands on disk in the clear. A reset
 * keystore makes old bodies unreadable, and [decrypt] returns null rather than
 * throwing, so the rows still list.
 */
object DebugCrypto {

    private const val KEY_ALIAS = "harness_debug_bodies"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    private fun key(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(text: String?): ByteArray? {
        if (text == null) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.iv + cipher.doFinal(text.toByteArray())
        } catch (e: Exception) {
            Log.w("DebugCrypto", "Could not encrypt a captured body", e)
            null
        }
    }

    fun decrypt(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            Log.w("DebugCrypto", "A captured body is no longer decryptable", e)
            null
        }
    }
}
