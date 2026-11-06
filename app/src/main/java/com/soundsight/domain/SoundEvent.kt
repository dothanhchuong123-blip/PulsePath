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

enum class EventSource {
    LOCAL,
    CLOUD,
    DEMO,

    /**
     * The phone's own accelerometer and gyroscope, not a microphone: a fall detected by
     * [com.soundsight.audio.motion.FallDetector]. Only COLLISION_IMPACT is ever produced this
     * way, and the presentation labels it as a fall rather than a collision heard nearby.
     */
    MOTION
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
    val isDemo: Boolean = false,
    val source: EventSource = if (isDemo) EventSource.DEMO else EventSource.CLOUD,
    /**
     * Capture instant in the elapsed-realtime (monotonic) domain - the same domain as
     * SensorEvent.timestamp / 1_000_000. The ONLY field motion corroboration may be judged on:
     * a wall-clock step between capture and classification must not create or destroy apparent
     * co-occurrence. Null for manually constructed events (demo, test, file replay), which by
     * contract can never be motion-corroborated. Never mix or subtract this with [capturedAtMs].
     */
    val capturedMonotonicMs: Long? = null,
    /**
     * Measured motion context at the capture instant (GNSS speed with accuracy and fix age, plus
     * the IMU moving vote), in "Always on" location mode; null otherwise - including when a fix
     * was absent, because a missing measurement is recorded as missing, never as zero. See
     * [com.soundsight.core.SpeedSnapshot].
     */
    val speedSnapshot: com.soundsight.core.SpeedSnapshot? = null
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

        fun fromLocal(
            soundId: SoundId,
            score: Float,
            capturedAtMs: Long,
            resolvedAtMs: Long,
            repeatCount: Int = 1,
            capturedMonotonicMs: Long? = null,
            speedSnapshot: com.soundsight.core.SpeedSnapshot? = null
        ): SoundEvent {
            val tier = canonicalTier(soundId)
            return SoundEvent(
                soundId = soundId,
                tier = tier,
                confidence = score.toDouble(),
                captionVi = AlertPresentation.getLabelVi(soundId),
                captionEn = AlertPresentation.getLabelEn(soundId),
                capturedAtMs = capturedAtMs,
                resolvedAtMs = resolvedAtMs,
                repeatCount = repeatCount,
                isDemo = false,
                source = EventSource.LOCAL,
                capturedMonotonicMs = capturedMonotonicMs,
                speedSnapshot = speedSnapshot
            )
        }

        /**
         * Confidence carried by a motion-detected fall. Not a probability - the fall rule fires
         * or it does not, and no mounted-phone measurement of its hit rate exists yet (W-L9) -
         * but the policy's CRITICAL floor is a confidence comparison, and a fall the rule
         * reports is meant to be treated as CRITICAL, so it clears that floor with margin.
         */
        const val FALL_RULE_CONFIDENCE = 0.90

        /**
         * A fall detected from motion alone. Same soundId and tier as an audio collision so the
         * takeover, the siren, history and the crash-response hand-off are the paths the rider
         * already has; [EventSource.MOTION] and the fall captions are what make it read as what
         * it is. [capturedAtMs] is the wall-clock instant of the impact, [capturedMonotonicMs]
         * the same instant in the sensor domain.
         */
        fun fromFall(
            capturedAtMs: Long,
            resolvedAtMs: Long,
            capturedMonotonicMs: Long,
            speedSnapshot: com.soundsight.core.SpeedSnapshot? = null
        ): SoundEvent = SoundEvent(
            soundId = SoundId.COLLISION_IMPACT,
            tier = Tier.CRITICAL,
            confidence = FALL_RULE_CONFIDENCE,
            captionVi = AlertPresentation.FALL_CAPTION_VI,
            captionEn = AlertPresentation.FALL_CAPTION_EN,
            capturedAtMs = capturedAtMs,
            resolvedAtMs = resolvedAtMs,
            repeatCount = 1,
            isDemo = false,
            source = EventSource.MOTION,
            capturedMonotonicMs = capturedMonotonicMs,
            speedSnapshot = speedSnapshot
        )

        fun fromClassification(
            result: ClassificationResult,
            capturedAtMs: Long,
            resolvedAtMs: Long,
            repeatCount: Int = 1,
            isDemo: Boolean = false,
            capturedMonotonicMs: Long? = null,
            speedSnapshot: com.soundsight.core.SpeedSnapshot? = null
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
                isDemo = isDemo,
                source = if (isDemo) EventSource.DEMO else EventSource.CLOUD,
                capturedMonotonicMs = if (isDemo) null else capturedMonotonicMs,
                speedSnapshot = if (isDemo) null else speedSnapshot
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
            isDemo: Boolean = false,
            source: EventSource = if (isDemo) EventSource.DEMO else EventSource.CLOUD
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
                isDemo = isDemo,
                source = source
            )
        }
    }
}
