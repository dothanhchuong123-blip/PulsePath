package com.soundsight.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What a rider chooses to show on screen after a crash.
 *
 * Held in its own DataStore file, separate from the onboarding preferences, because it is the only
 * personal data this app stores. Keeping it in one identifiable place is what makes "delete
 * everything" a single operation the rider can actually trust, rather than a promise spread across
 * a shared preferences file.
 *
 * **It never leaves the device.** No field here may be logged, sent to Gemini, or included in
 * telemetry. The crash screen's actions hand a pre-filled intent to the phone's own dialer or
 * messaging app; nothing in this app transmits any of it.
 *
 * [isUsable] gates the whole feature. With no contact number saved there is nothing the
 * post-collision screen could usefully do, so it is not shown at all and a collision behaves
 * exactly as it does today.
 */
data class EmergencyProfile(
    val enabled: Boolean = false,
    val riderName: String = "",
    val bloodType: String = "",
    val medicalNotes: String = "",
    val contact1Name: String = "",
    val contact1Phone: String = "",
    val contact2Name: String = "",
    val contact2Phone: String = ""
) {
    /**
     * Whether the post-collision screen has anything worth showing.
     *
     * Requires a first contact number specifically, not merely `enabled`: the screen's primary
     * purpose is the Call button, and a screen that shows medical notes with no way to act on them
     * is a worse outcome than the plain takeover, because it costs the rider seconds to read.
     */
    val isUsable: Boolean
        get() = enabled && contact1Phone.isNotBlank()

    val hasSecondContact: Boolean
        get() = enabled && contact2Phone.isNotBlank()

    /** True when the rider has typed anything at all, used to offer "delete everything". */
    val hasAnyData: Boolean
        get() = riderName.isNotBlank() || bloodType.isNotBlank() || medicalNotes.isNotBlank() ||
            contact1Name.isNotBlank() || contact1Phone.isNotBlank() ||
            contact2Name.isNotBlank() || contact2Phone.isNotBlank()

    /**
     * Resolves the profile to display in the post-collision preview: the rider's own saved data if
     * any was entered (armed with enabled=true so contacts and medical notes are visible in the preview),
     * or the demo placeholder when no data has been saved yet.
     */
    fun forPreview(strings: Strings): EmergencyProfile =
        if (hasAnyData) copy(enabled = true) else demoPlaceholder(strings)


    companion object {
        /** Blood type is free text, but these are the options the picker offers. */
        val BLOOD_TYPES = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")

        /**
         * An obviously-fake profile, so the crash screen can be previewed before any real data
         * has been entered.
         *
         * The whole point of the preview is to see this screen *before* committing a family
         * member's phone number to it, which means [isUsable] cannot be the thing that decides
         * whether it renders. This fills the same shape with sample values instead.
         *
         * **The phone number stays blank on purpose.** `CrashResponseScreen.dial` returns early on
         * a blank number, so the dial and SMS paths are inert here without needing a second guard
         * that could be lost later. Do not "fix" this by inventing a dialable number - a preview
         * that can place a real call to a made-up stranger is worse than a button that does
         * nothing.
         */
        fun demoPlaceholder(strings: Strings): EmergencyProfile = EmergencyProfile(
            enabled = true,
            riderName = strings.crashSampleName,
            bloodType = "O+",
            medicalNotes = strings.crashSampleNotes,
            contact1Name = strings.crashSampleContact,
            contact1Phone = ""
        )

        /** Guard rails on stored text, so one pasted essay cannot make the crash screen unreadable. */
        const val MAX_NAME_LENGTH = 60
        const val MAX_NOTES_LENGTH = 240
        const val MAX_PHONE_LENGTH = 24
    }
}

private val Context.emergencyDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulsepath_emergency")

object EmergencyStore {
    fun flow(context: Context): Flow<EmergencyProfile> = profileFlow(context)

    private val ENABLED = booleanPreferencesKey("emergency_enabled")
    private val RIDER_NAME = stringPreferencesKey("rider_name")
    private val BLOOD_TYPE = stringPreferencesKey("blood_type")
    private val MEDICAL_NOTES = stringPreferencesKey("medical_notes")
    private val CONTACT1_NAME = stringPreferencesKey("contact1_name")
    private val CONTACT1_PHONE = stringPreferencesKey("contact1_phone")
    private val CONTACT2_NAME = stringPreferencesKey("contact2_name")
    private val CONTACT2_PHONE = stringPreferencesKey("contact2_phone")

    fun profileFlow(context: Context): Flow<EmergencyProfile> =
        context.emergencyDataStore.data.map { p ->
            EmergencyProfile(
                enabled = p[ENABLED] ?: false,
                riderName = p[RIDER_NAME] ?: "",
                bloodType = p[BLOOD_TYPE] ?: "",
                medicalNotes = p[MEDICAL_NOTES] ?: "",
                contact1Name = p[CONTACT1_NAME] ?: "",
                contact1Phone = p[CONTACT1_PHONE] ?: "",
                contact2Name = p[CONTACT2_NAME] ?: "",
                contact2Phone = p[CONTACT2_PHONE] ?: ""
            )
        }

    suspend fun save(context: Context, profile: EmergencyProfile) {
        context.emergencyDataStore.edit { p ->
            p[ENABLED] = profile.enabled
            p[RIDER_NAME] = profile.riderName.trim().take(EmergencyProfile.MAX_NAME_LENGTH)
            p[BLOOD_TYPE] = profile.bloodType.trim().take(8)
            p[MEDICAL_NOTES] = profile.medicalNotes.trim().take(EmergencyProfile.MAX_NOTES_LENGTH)
            p[CONTACT1_NAME] = profile.contact1Name.trim().take(EmergencyProfile.MAX_NAME_LENGTH)
            p[CONTACT1_PHONE] = profile.contact1Phone.trim().take(EmergencyProfile.MAX_PHONE_LENGTH)
            p[CONTACT2_NAME] = profile.contact2Name.trim().take(EmergencyProfile.MAX_NAME_LENGTH)
            p[CONTACT2_PHONE] = profile.contact2Phone.trim().take(EmergencyProfile.MAX_PHONE_LENGTH)
        }
    }

    /** Wipes every stored field. Offered in the UI as a single explicit action. */
    suspend fun clear(context: Context) {
        context.emergencyDataStore.edit { it.clear() }
    }
}

typealias EmergencyProfileStore = EmergencyStore

