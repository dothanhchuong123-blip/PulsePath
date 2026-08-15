package com.soundsight.domain

import com.soundsight.data.ClassificationResult

enum class Tier {
    SAFE,
    WARNING,
    CRITICAL;

    companion object {
        fun fromString(value: String): Tier {
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: Exception) {
                SAFE
            }
        }
    }
}

enum class SoundId {
    NORMAL_TRAFFIC,
    VOICE_SHOUT,
    MOTORCYCLE_HORN,
    CAR_HORN,
    SUDDEN_ACCELERATION,
    REVERSING_BEEP,
    TRUCK_AIR_HORN,
    EMERGENCY_SIREN,
    TRAIN_HORN,
    COLLISION_IMPACT,
    UNKNOWN;

    companion object {
        fun fromString(value: String): SoundId {
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: Exception) {
                UNKNOWN
            }
        }
    }
}

data class SoundEvent(
    val soundId: SoundId,
    val tier: Tier,
    val confidence: Double,
    val captionVi: String,
    val captionEn: String,
    val capturedAtMs: Long,
    val resolvedAtMs: Long,
    val repeatCount: Int = 1,
    val isDemo: Boolean = false
) {
    companion object {
        fun canonicalTier(id: SoundId): Tier {
            return when (id) {
                SoundId.NORMAL_TRAFFIC, SoundId.UNKNOWN -> Tier.SAFE
                SoundId.VOICE_SHOUT,
                SoundId.MOTORCYCLE_HORN,
                SoundId.CAR_HORN,
                SoundId.SUDDEN_ACCELERATION,
                SoundId.REVERSING_BEEP -> Tier.WARNING
                SoundId.TRUCK_AIR_HORN,
                SoundId.EMERGENCY_SIREN,
                SoundId.TRAIN_HORN,
                SoundId.COLLISION_IMPACT -> Tier.CRITICAL
            }
        }

        fun fromClassification(
            result: ClassificationResult,
            capturedAtMs: Long,
            resolvedAtMs: Long,
            repeatCount: Int = 1,
            isDemo: Boolean = false
        ): SoundEvent {
            val soundId = SoundId.fromString(result.soundId)
            val parsedTier = try {
                Tier.valueOf(result.tier.trim().uppercase())
            } catch (_: Exception) {
                canonicalTier(soundId)
            }
            return SoundEvent(
                soundId = soundId,
                tier = parsedTier,
                confidence = result.confidence,
                captionVi = result.captionVi,
                captionEn = result.captionEn,
                capturedAtMs = capturedAtMs,
                resolvedAtMs = resolvedAtMs,
                repeatCount = repeatCount,
                isDemo = isDemo
            )
        }

        fun fromStrings(
            soundIdStr: String,
            tierStr: String,
            confidence: Double,
            captionVi: String,
            captionEn: String,
            capturedAtMs: Long,
            resolvedAtMs: Long,
            repeatCount: Int = 1,
            isDemo: Boolean = false
        ): SoundEvent {
            val soundId = SoundId.fromString(soundIdStr)
            val parsedTier = try {
                Tier.valueOf(tierStr.trim().uppercase())
            } catch (_: Exception) {
                canonicalTier(soundId)
            }
            return SoundEvent(
                soundId = soundId,
                tier = parsedTier,
                confidence = confidence,
                captionVi = captionVi,
                captionEn = captionEn,
                capturedAtMs = capturedAtMs,
                resolvedAtMs = resolvedAtMs,
                repeatCount = repeatCount,
                isDemo = isDemo
            )
        }
    }
}
