package com.soundsight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmergencyProfile.isUsable] is the gate for the entire post-collision feature, so these tests
 * are really about one question: can the app ever show a crash screen the rider did not ask for,
 * or one that cannot do anything?
 */
class EmergencyProfileTest {

    // 1. Default state: the feature is off and shows nothing. A fresh install must behave exactly
    // as it did before this feature existed.
    @Test
    fun defaultProfile_isNotUsable() {
        assertFalse(EmergencyProfile().isUsable)
        assertFalse(EmergencyProfile().hasSecondContact)
        assertFalse(EmergencyProfile().hasAnyData)
    }

    // 2. Filling the form in but never flipping the switch must not arm the feature. Someone who
    // typed their blood type and changed their mind has not consented to the screen.
    @Test
    fun profileWithContactButDisabled_isNotUsable() {
        val profile = EmergencyProfile(
            enabled = false,
            riderName = "Mai",
            contact1Phone = "0900000000"
        )

        assertFalse(profile.isUsable)
        assertTrue(profile.hasAnyData)
    }

    // 3. Enabled but with no number to call: the screen would have nothing to offer, so it is
    // still not shown. This is the case the settings warning tells the rider about.
    @Test
    fun enabledWithoutContactPhone_isNotUsable() {
        val profile = EmergencyProfile(
            enabled = true,
            riderName = "Mai",
            bloodType = "O+",
            contact1Name = "Linh"
        )

        assertFalse(profile.isUsable)
    }

    // 4. The one combination that arms it.
    @Test
    fun enabledWithContactPhone_isUsable() {
        val profile = EmergencyProfile(
            enabled = true,
            contact1Phone = "0900000000"
        )

        assertTrue(profile.isUsable)
    }

    // 5. Whitespace is not a phone number.
    @Test
    fun blankPhoneIsNotAContact() {
        assertFalse(EmergencyProfile(enabled = true, contact1Phone = "   ").isUsable)
        assertFalse(
            EmergencyProfile(
                enabled = true,
                contact1Phone = "0900000000",
                contact2Phone = "  "
            ).hasSecondContact
        )
    }

    // 6. The second call button appears only when there is a second number, and never when the
    // whole feature is off.
    @Test
    fun secondContactRequiresBothNumberAndEnabled() {
        val enabled = EmergencyProfile(
            enabled = true,
            contact1Phone = "0900000000",
            contact2Phone = "0911111111"
        )
        assertTrue(enabled.hasSecondContact)

        assertFalse(enabled.copy(enabled = false).hasSecondContact)
    }

    // 7. The length caps exist so one pasted essay cannot push the call buttons off a crash
    // screen that a bystander is trying to use.
    @Test
    fun lengthCapsAreSaneAndOrdered() {
        assertTrue(EmergencyProfile.MAX_NOTES_LENGTH > EmergencyProfile.MAX_NAME_LENGTH)
        assertTrue(EmergencyProfile.MAX_NAME_LENGTH > EmergencyProfile.MAX_PHONE_LENGTH)
        assertEquals(8, EmergencyProfile.BLOOD_TYPES.size)
        assertEquals(
            EmergencyProfile.BLOOD_TYPES.distinct().size,
            EmergencyProfile.BLOOD_TYPES.size
        )
    }
}
