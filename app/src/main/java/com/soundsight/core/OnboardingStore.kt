package com.soundsight.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.soundsight.audio.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "soundsight_onboarding")

object OnboardingStore {
    private val ONBOARDED_KEY = booleanPreferencesKey("onboarded")
    private val LANGUAGE_KEY = stringPreferencesKey("language")
    private val SENSITIVITY_KEY = intPreferencesKey("sensitivity")

    fun onboardedFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[ONBOARDED_KEY] ?: false
        }

    fun languageFlow(context: Context): Flow<AppLanguage> =
        context.dataStore.data.map { preferences ->
            when (preferences[LANGUAGE_KEY]) {
                "EN" -> AppLanguage.EN
                else -> AppLanguage.VI
            }
        }

    fun sensitivityFlow(context: Context): Flow<Sensitivity> =
        context.dataStore.data.map { preferences ->
            val ordinal = preferences[SENSITIVITY_KEY] ?: Sensitivity.MEDIUM.ordinal
            Sensitivity.entries.getOrNull(ordinal) ?: Sensitivity.MEDIUM
        }

    suspend fun setOnboarded(context: Context, value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDED_KEY] = value
        }
    }

    suspend fun setLanguage(context: Context, language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language.name
        }
    }

    suspend fun setSensitivity(context: Context, sensitivity: Sensitivity) {
        context.dataStore.edit { preferences ->
            preferences[SENSITIVITY_KEY] = sensitivity.ordinal
        }
    }
}

