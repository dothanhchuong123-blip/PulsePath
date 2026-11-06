package com.soundsight.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Storage for user-provided Gemini API credentials.
 *
 * Never stores API keys in plaintext. Uses AndroidX [EncryptedSharedPreferences] backed by
 * the Android KeyStore with AES-256 encryption.
 */
interface SecureCredentialStore {
    fun getUserApiKey(): String?
    fun setUserApiKey(key: String)
    fun clearUserApiKey()
    fun getUserDailyLimit(): Int?
    fun setUserDailyLimit(limit: Int)
    fun hasUserKey(): Boolean
    fun clearAll()
}

class EncryptedCredentialStore(
    context: Context,
    prefsOverride: SharedPreferences? = null
) : SecureCredentialStore {

    private val prefs: SharedPreferences = prefsOverride ?: createEncryptedPrefs(context)

    companion object {
        const val PREFS_FILE = "soundsight_secure_credentials"
        const val KEY_API_KEY = "user_gemini_api_key"
        const val KEY_DAILY_LIMIT = "user_daily_quota_limit"

        @Volatile
        private var instance: EncryptedCredentialStore? = null

        fun getInstance(context: Context): EncryptedCredentialStore {
            return instance ?: synchronized(this) {
                instance ?: EncryptedCredentialStore(context.applicationContext).also { instance = it }
            }
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                try {
                    context.deleteSharedPreferences(PREFS_FILE)
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_FILE,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (_: Exception) {
                    // Fallback to private prefs if KeyStore is completely unavailable (e.g. some test environments)
                    context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                }
            }
        }
    }

    override fun getUserApiKey(): String? {
        val key = prefs.getString(KEY_API_KEY, null)?.trim()
        return if (!key.isNullOrBlank()) key else null
    }

    override fun setUserApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    override fun clearUserApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    override fun getUserDailyLimit(): Int? {
        return if (prefs.contains(KEY_DAILY_LIMIT)) {
            val limit = prefs.getInt(KEY_DAILY_LIMIT, -1)
            if (limit > 0) limit else null
        } else {
            null
        }
    }

    override fun setUserDailyLimit(limit: Int) {
        prefs.edit().putInt(KEY_DAILY_LIMIT, limit).apply()
    }

    override fun hasUserKey(): Boolean {
        return getUserApiKey() != null
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}
