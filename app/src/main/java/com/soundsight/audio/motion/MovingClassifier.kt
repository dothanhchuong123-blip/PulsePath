package com.soundsight.audio.motion

/**
 * What the IMU can and cannot contribute to speed, stated plainly because the temptation to
 * overstate it is real:
 *
 *  - It CANNOT produce absolute speed. Integrating accelerometer output drifts without bound
 *    within seconds; no dead reckoning is implemented anywhere in this app, and no IMU-derived
 *    number may ever be presented as a speed.
 *  - It CAN separate stopped from moving, cheaply and reliably: a parked phone shows a steady
 *    ~9.81 m/s² magnitude and a still gyro, while engine idle, road vibration and the rider's
 *    own movements raise both. GNSS speed is noisiest exactly at low speed, so this distinction
 *    is worth having there.
 *  - It CAN flag a lean or a hard bump in the same window as a capture (see
 *    [MotionSensor.hasRecentSpike]) - context recorded on a B1 row, because a capture taken
 *    mid-corner is not the same measurement as one taken upright.
 *  - Bridging short GNSS gaps (tunnel, underpass) is [com.soundsight.core.SpeedResolver]'s
 *    HELD state: the last GNSS value, visibly stale, never an IMU-derived substitute.
 *
 * Everything here is fusion for labelling and display confidence. None of it is a new detector.
 */
object MovingClassifier {
    /** Deviation from gravity above which the phone is being shaken, not merely parked. */
    const val ACCEL_DEVIATION_MSS = 0.45f

    /** Gyro magnitude (rad/s) above which the phone is rotating, not merely resting. */
    const val GYRO_RAD_S = 0.06f

    /** Standard gravity, for the deviation test. */
    const val GRAVITY_MSS = 9.81f

    /**
     * One moving vote from the two magnitudes [MotionSensor] already tracks. OR, not AND: a
     * phone flat on a idling fuel tank can stay gyro-quiet while vibration moves the accelerometer,
     * and a smooth push start can move the gyro before vibration builds.
     */
    fun isMovingVote(accelMagnitude: Float, gyroMagnitude: Float): Boolean {
        val accelDeviation = kotlin.math.abs(accelMagnitude - GRAVITY_MSS)
        return accelDeviation > ACCEL_DEVIATION_MSS || gyroMagnitude > GYRO_RAD_S
    }
}
