package com.soundsight.audio.liveness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * OS-owned BroadcastReceiver triggered by AlarmManager upon liveness lease expiry.
 *
 * This receiver executes independently of the ListenerService process lifecycle.
 * If the process dies or halts heartbeats, the OS AlarmManager delivers an alarm intent here,
 * which verifies whether the lease has expired without renewal and posts the high-importance
 * warning notification to notify the rider that protection has stopped.
 */
class LivenessWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.w(TAG, "Watchdog alarm received: evaluating liveness lease expiry")
        LivenessLeaseManager.onWatchdogAlarmFired(context, intent)
    }

    companion object {
        private const val TAG = "LivenessWatchdog"
        const val ACTION_WATCHDOG_CHECK = "com.soundsight.action.LIVENESS_WATCHDOG_CHECK"
    }
}
