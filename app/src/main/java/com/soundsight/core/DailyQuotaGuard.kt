package com.soundsight.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuotaExhaustedException(message: String) : Exception(message)

object DailyQuotaGuard {
    const val DEFAULT_BUILTIN_LIMIT = 480
    const val DEFAULT_USER_LIMIT = 1500

    // REST fallback path only (SoundClassifier). Confirmed via the Google
    // Cloud rate-limits dashboard on 16/08/2026: gemini-3.5-flash-lite has
    // RPD=500 on this project. 480 leaves a 20-call safety margin in case
    // Google's reset clock doesn't align exactly with device-local midnight.
    // When a user provides their own key, this guard tracks THEIR budget
    // (configurable, defaulting to 1,500 calls/day).
    var dailyLimit: Int = DEFAULT_BUILTIN_LIMIT

    private const val PREFS_NAME = "soundsight_quota"
    private const val KEY_DATE = "quota_date"
    private const val KEY_COUNT = "quota_count"

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Resolves the active daily quota limit:
     * - If the user has entered their own API key: returns their configured budget (default 1,500).
     * - Otherwise: returns the built-in APK key limit (480).
     */
    fun getDailyLimit(context: Context): Int {
        return try {
            val store = EncryptedCredentialStore.getInstance(context)
            if (store.hasUserKey()) {
                store.getUserDailyLimit() ?: DEFAULT_USER_LIMIT
            } else {
                dailyLimit
            }
        } catch (_: Exception) {
            dailyLimit
        }
    }

    /**
     * Sets a custom daily quota limit for the user key.
     */
    fun setUserDailyLimit(context: Context, limit: Int) {
        try {
            EncryptedCredentialStore.getInstance(context).setUserDailyLimit(limit)
        } catch (_: Exception) {
            dailyLimit = limit
        }
    }

    /**
     * Resets today's consumption count (e.g. when changing or clearing credentials).
     */
    @Synchronized
    fun resetTodayCount(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_COUNT, 0).apply()
    }

    @Synchronized
    fun tryConsume(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = getTodayDateString()
        val storedDate = prefs.getString(KEY_DATE, null)
        var count = prefs.getInt(KEY_COUNT, 0)

        if (storedDate != today) {
            count = 0
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_COUNT, 0)
                .apply()
        }

        val activeLimit = getDailyLimit(context)
        if (count >= activeLimit) {
            return false
        }

        count++
        prefs.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_COUNT, count)
            .apply()
        return true
    }

    @Synchronized
    fun remainingToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = getTodayDateString()
        val storedDate = prefs.getString(KEY_DATE, null)
        val count = if (storedDate == today) prefs.getInt(KEY_COUNT, 0) else 0
        val activeLimit = getDailyLimit(context)
        return (activeLimit - count).coerceAtLeast(0)
    }
}
