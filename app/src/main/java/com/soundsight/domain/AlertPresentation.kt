package com.soundsight.domain

data class Presentation(
    val tier: Tier,
    val holdMs: Long,
    val haptic: HapticPattern,
    val glyph: GlyphId
)

enum class HapticPattern {
    NONE,
    PRE_ALERT,
    WARNING,
    CRITICAL
}

enum class GlyphId {
    SHIELD,
    PERSON_HAIL,
    MEGAPHONE,
    BELL,
    GAUGE,
    ARROW_DOWN,
    TRUCK,
    SIREN,
    TRAIN,
    BURST,
    QUESTION
}

object AlertPresentation {

    /**
     * Labels and captions for a fall the motion sensors detected ([EventSource.MOTION]). The
     * soundId is COLLISION_IMPACT, whose label "COLLISION NEARBY" describes a crash *heard*
     * somewhere near the rider; a fall is the rider's own bike on the ground, and the words must
     * say so - to the rider, and to whoever picks the phone up.
     */
    const val FALL_LABEL_VI = "NGÃ XE"
    const val FALL_LABEL_EN = "FALL DETECTED"
    const val FALL_CAPTION_VI = "Cảm biến chuyển động ghi nhận va đập mạnh, rồi xe nằm nghiêng bất động"
    const val FALL_CAPTION_EN = "Motion sensors recorded a hard impact, then the bike lying still on its side"

    /** The label for one event: a motion-detected fall reads as a fall, everything else by soundId. */
    fun getLabelVi(event: SoundEvent): String =
        if (event.source == EventSource.MOTION && event.soundId == SoundId.COLLISION_IMPACT) FALL_LABEL_VI
        else getLabelVi(event.soundId)

    fun getLabelEn(event: SoundEvent): String =
        if (event.source == EventSource.MOTION && event.soundId == SoundId.COLLISION_IMPACT) FALL_LABEL_EN
        else getLabelEn(event.soundId)

    fun getHoldMs(tier: Tier): Long {
        return when (tier) {
            Tier.CRITICAL -> 5000L
            Tier.WARNING -> 3000L
            Tier.SAFE -> 0L
        }
    }

    fun getHapticPattern(tier: Tier): HapticPattern {
        return when (tier) {
            Tier.CRITICAL -> HapticPattern.CRITICAL
            Tier.WARNING -> HapticPattern.WARNING
            Tier.SAFE -> HapticPattern.NONE
        }
    }

    fun getHapticTimings(pattern: HapticPattern): LongArray {
        return when (pattern) {
            HapticPattern.NONE -> longArrayOf()
            // 60ms was inaudible-to-the-body on a bar-mounted phone: too short for the motor to
            // reach full amplitude and spin down, so through a glove and a mount it registered as
            // nothing at all. 150ms is past the ~30-50ms spin-up of a typical LRA/ERM and is the
            // shortest pulse that reads as a distinct "tap" rather than a click. Still clearly
            // shorter and single-pulse against WARNING's 180-80-180, so the escalation ladder
            // (pre-alert < warning < critical) is preserved by both length and rhythm.
            // NOT MEASURED against a specific handset; tune here if the demo device disagrees.
            HapticPattern.PRE_ALERT -> longArrayOf(0L, 150L)
            HapticPattern.WARNING -> longArrayOf(0L, 180L, 80L, 180L)
            HapticPattern.CRITICAL -> longArrayOf(0L, 400L, 100L, 400L, 100L, 400L, 200L, 800L, 100L, 800L)
        }
    }

    /**
     * Cycle count for the vibration pattern.
     * Note: This is a CYCLE COUNT (number of times the full pattern should be experienced),
     * NOT an Android VibrationEffect repeat index.
     */
    fun getHapticCycles(pattern: HapticPattern): Int {
        return when (pattern) {
            HapticPattern.CRITICAL -> 2
            HapticPattern.WARNING -> 1
            HapticPattern.PRE_ALERT -> 1
            HapticPattern.NONE -> 0
        }
    }

    /**
     * Repeat index for VibrationEffect.createWaveform.
     *
     * VibrationEffect.createWaveform treats its second argument as the index in the timings
     * array to loop back to when repeating indefinitely. Therefore, -1 (play once without looping)
     * is the only safe value here. Repeated cycles must be produced by expanding the timings
     * array instead.
     */
    fun waveformRepeatIndex(pattern: HapticPattern): Int = -1

    fun getGlyph(soundId: SoundId): GlyphId {
        return when (soundId) {
            SoundId.NORMAL_TRAFFIC -> GlyphId.SHIELD
            // A person calling out to you, not a loudspeaker. This is the only alert in the set
            // caused by a human trying to communicate with the rider directly - usually from
            // behind, usually to get them to move - so it must not look like the vehicle alerts.
            SoundId.VOICE_SHOUT -> GlyphId.PERSON_HAIL
            SoundId.MOTORCYCLE_HORN -> GlyphId.BELL
            SoundId.CAR_HORN -> GlyphId.BELL
            SoundId.SUDDEN_ACCELERATION -> GlyphId.GAUGE
            SoundId.REVERSING_BEEP -> GlyphId.ARROW_DOWN
            SoundId.TRUCK_AIR_HORN -> GlyphId.TRUCK
            SoundId.EMERGENCY_SIREN -> GlyphId.SIREN
            SoundId.TRAIN_HORN -> GlyphId.TRAIN
            SoundId.COLLISION_IMPACT -> GlyphId.BURST
            SoundId.UNKNOWN -> GlyphId.QUESTION
        }
    }

    fun getLabelVi(soundId: SoundId): String {
        return when (soundId) {
            SoundId.NORMAL_TRAFFIC -> "Bình thường"
            SoundId.VOICE_SHOUT -> "Có người gọi"
            SoundId.MOTORCYCLE_HORN -> "Còi xe máy"
            SoundId.CAR_HORN -> "Còi ô tô"
            SoundId.SUDDEN_ACCELERATION -> "Xe rồ ga gần"
            SoundId.REVERSING_BEEP -> "Xe đang lùi"
            SoundId.TRUCK_AIR_HORN -> "CÒI HƠI XE TẢI"
            SoundId.EMERGENCY_SIREN -> "XE ƯU TIÊN"
            SoundId.TRAIN_HORN -> "TÀU HỎA"
            SoundId.COLLISION_IMPACT -> "VA CHẠM GẦN"
            SoundId.UNKNOWN -> "Không xác định"
        }
    }

    fun getLabelEn(soundId: SoundId): String {
        return when (soundId) {
            SoundId.NORMAL_TRAFFIC -> "All clear"
            SoundId.VOICE_SHOUT -> "Someone calling"
            SoundId.MOTORCYCLE_HORN -> "Motorbike horn"
            SoundId.CAR_HORN -> "Car horn"
            SoundId.SUDDEN_ACCELERATION -> "Vehicle accelerating"
            SoundId.REVERSING_BEEP -> "Vehicle reversing"
            SoundId.TRUCK_AIR_HORN -> "TRUCK AIR HORN"
            SoundId.EMERGENCY_SIREN -> "EMERGENCY VEHICLE"
            SoundId.TRAIN_HORN -> "TRAIN CROSSING"
            SoundId.COLLISION_IMPACT -> "COLLISION NEARBY"
            SoundId.UNKNOWN -> "Unknown sound"
        }
    }

    fun forEvent(event: SoundEvent): Presentation {
        return Presentation(
            tier = event.tier,
            holdMs = getHoldMs(event.tier),
            haptic = getHapticPattern(event.tier),
            glyph = getGlyph(event.soundId)
        )
    }
}
