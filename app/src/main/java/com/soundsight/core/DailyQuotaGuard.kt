package com.soundsight.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuotaExhaustedException(message: String) : Exception(message)

object DailyQuotaGuard {
    var dailyLimit: Int = 15 // safety margin below the observed 20/day free-tier
                             // cap — Google's actual reset clock is not known to
                             // match device-local midnight, so leave headroom.

    private const val PREFS_NAME = "soundsight_quota"
    private const val KEY_DATE = "quota_date"
    private const val KEY_COUNT = "quota_count"

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
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

        if (count >= dailyLimit) {
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
        return (dailyLimit - count).coerceAtLeast(0)
    }
}
