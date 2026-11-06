package com.soundsight.data.live

import android.content.Context
import com.soundsight.BuildConfig
import com.soundsight.core.EncryptedCredentialStore
import com.soundsight.core.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Source of the credential used to open the Gemini Live WebSocket and make REST classification calls.
 *
 * [UserKeyCredentialProvider] checks the encrypted credential store for a user-supplied key,
 * falling back to [BuildConfigCredentialProvider] (the APK-embedded key) if no user key is set.
 */
fun interface GeminiCredentialProvider {
    fun getApiKey(): String
}

object BuildConfigCredentialProvider : GeminiCredentialProvider {
    override fun getApiKey(): String = BuildConfig.GEMINI_API_KEY
}

class UserKeyCredentialProvider(
    private val store: SecureCredentialStore,
    private val fallback: GeminiCredentialProvider = BuildConfigCredentialProvider
) : GeminiCredentialProvider {

    override fun getApiKey(): String {
        val userKey = store.getUserApiKey()
        return if (!userKey.isNullOrBlank()) userKey else fallback.getApiKey()
    }

    fun hasUserKey(): Boolean = store.hasUserKey()

    fun getMaskedKey(): String? {
        val key = store.getUserApiKey() ?: return null
        return maskApiKey(key)
    }

    companion object {
        private const val VALIDATION_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"

        private val validationHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        @Volatile
        private var instance: UserKeyCredentialProvider? = null

        fun getInstance(context: Context): UserKeyCredentialProvider {
            return instance ?: synchronized(this) {
                instance ?: UserKeyCredentialProvider(
                    EncryptedCredentialStore.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }

        /**
         * Masks an API key for safe display in the UI (e.g. "AIza…xY4c").
         * Never exposes the full key.
         */
        fun maskApiKey(key: String): String {
            val trimmed = key.trim()
            return if (trimmed.length >= 8) {
                "${trimmed.take(4)}…${trimmed.takeLast(4)}"
            } else if (trimmed.isNotEmpty()) {
                "••••"
            } else {
                ""
            }
        }

        /**
         * Validates a candidate Gemini API key with a single cheap GET call (pageSize=1)
         * to the models endpoint. Passes the key in the x-goog-api-key header so it does
         * not appear in URL parameters.
         *
         * Consumes 0 tokens of generation quota.
         * Never logs or prints the key.
         */
        suspend fun validateCandidateKey(
            candidateKey: String,
            client: OkHttpClient = validationHttpClient
        ): Result<Unit> = withContext(Dispatchers.IO) {
            val key = candidateKey.trim()
            if (key.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key cannot be blank"))
            }

            val request = Request.Builder()
                .url(VALIDATION_ENDPOINT)
                .addHeader("x-goog-api-key", key)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        val code = response.code
                        Result.failure(
                            if (code in 400..403) {
                                IllegalArgumentException("Invalid API key (HTTP $code)")
                            } else {
                                IOException("API key validation failed (HTTP $code)")
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
