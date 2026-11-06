package com.soundsight.core

import com.soundsight.audio.motion.MotionSensor
import com.soundsight.audio.motion.MovingClassifier

/**
 * The measured-motion context stamped onto a capture or a collision event, from the GNSS speed
 * stream ([SpeedProvider], "Always on" mode only) and the IMU.
 *
 * Every field is nullable because absence is information: null speed means no usable fix at that
 * instant - it never means zero, and it is never replaced by an assumed or IMU-derived number.
 * The IMU cannot produce speed (see [MovingClassifier]); it only contributes the moving vote.
 */
data class SpeedSnapshot(
    val kmh: Float?,
    val accuracyM: Float?,
    val fixAgeMs: Long?,
    val imuMovingVote: Boolean?
) {
    companion object {
        /** Snapshot at "now" from whatever the speed stream last delivered; null speed when no live fix. */
        fun capture(): SpeedSnapshot {
            val reading = SpeedResolver.resolve(SpeedProvider.sample.value, MotionSensor.nowMonotonicMs())
            val imuMoving = if (MotionSensor.session.isRegistered) {
                MovingClassifier.isMovingVote(
                    MotionSensor.lastAccelMagnitude,
                    MotionSensor.lastGyroMagnitude
                )
            } else {
                null
            }
            return SpeedSnapshot(
                kmh = reading.kmh,
                accuracyM = if (reading.kmh != null) reading.accuracyM else null,
                fixAgeMs = if (reading.kmh != null) reading.fixAgeMs else null,
                imuMovingVote = imuMoving
            )
        }
    }
}
