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
            HapticPattern.PRE_ALERT -> longArrayOf(0L, 60L)
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
            SoundId.VOICE_SHOUT -> GlyphId.MEGAPHONE
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
