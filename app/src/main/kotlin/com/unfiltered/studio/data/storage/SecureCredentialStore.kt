package com.unfiltered.studio.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores API tokens/passwords encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (non-exportable). Ciphertexts are bound to their storage key via AAD, so values can't be swapped
 * between entries. Values are never logged; failures only log the fact that decryption failed.
 *
 * Backups/device transfer of this file are excluded (see data_extraction_rules.xml) — the Keystore
 * key never leaves the device, so a restored ciphertext would be undecryptable anyway.
 */
@Singleton
class SecureCredentialStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
    )

    private val cache = ConcurrentHashMap<String, String>()

    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(key, value)
        dataStore.edit { it[stringPreferencesKey(key)] = encrypted }
        cache[key] = value
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        cache[key]?.let { return@withContext it }
        val stored = dataStore.data.first()[stringPreferencesKey(key)] ?: return@withContext null
        val value = try {
            decrypt(key, stored)
        } catch (e: Exception) {
            // Typical after restoring app data on a new device or a Keystore reset.
            Log.w(TAG, "A stored credential could not be decrypted and was discarded (${e.javaClass.simpleName}).")
            dataStore.edit { it.remove(stringPreferencesKey(key)) }
            null
        }
        value?.also { cache[key] = it }
    }

    suspend fun remove(key: String) {
        cache.remove(key)
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun removeWithPrefix(prefix: String) {
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { prefs.remove(it) }
        }
    }

    suspend fun contains(key: String): Boolean = dataStore.data.first().contains(stringPreferencesKey(key))

    fun observeContains(key: String): Flow<Boolean> =
        dataStore.data.map { it.contains(stringPreferencesKey(key)) }.distinctUntilChanged()

    private fun secretKey(): SecretKey = synchronized(this) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return@synchronized existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun encrypt(key: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf(FORMAT_VERSION, b64(cipher.iv), b64(ciphertext)).joinToString(":")
    }

    private fun decrypt(key: String, stored: String): String {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unknown credential format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, unb64(parts[1])))
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(unb64(parts[2])), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val TAG = "SecureCredentialStore"
        const val FILE_NAME = "secure_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "unfiltered_studio_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = "v1"

        fun providerToken(providerId: String) = "provider:$providerId:token"
        fun providerHeader(providerId: String, header: String) = "provider:$providerId:header:${header.lowercase()}"
        fun providerPrefix(providerId: String) = "provider:$providerId:"
        const val HF_TOKEN = "account:hf_token"
        const val CIVITAI_KEY = "account:civitai_key"
    }
}
