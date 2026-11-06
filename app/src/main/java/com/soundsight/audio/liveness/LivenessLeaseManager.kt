package com.soundsight.audio.liveness

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soundsight.MainActivity
import com.soundsight.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * Manages the OS-owned expiring liveness lease according to W-H8-3 contract.
 *
 * Core properties:
 * - Lease duration defaults to 4000 ms; must be actively renewed by verified local capture/inference.
 * - Leases are armed via Android OS AlarmManager so that upon process death or failure to renew,
 *   the OS delivers an alarm that posts a high-importance warning notification.
 * - Records full provenance and transition traces for verification.
 */
object LivenessLeaseManager {
    private const val TAG = "LivenessLeaseManager"

    const val DEFAULT_LEASE_DURATION_MS = 4000L
    const val CHANNEL_LISTENER_ID = "soundsight_listener_channel"
    const val CHANNEL_WARNING_ID = "soundsight_protection_warning"
    const val WARNING_NOTIFICATION_ID = 1005
    const val ACTION_WATCHDOG_CHECK = "com.soundsight.action.LIVENESS_WATCHDOG_CHECK"
    const val PREFS_NAME = "soundsight_liveness_lease"
    const val KEY_LEASE_ARMED = "is_lease_armed"
    const val KEY_LEASE_EXPIRY_ELAPSED_MS = "lease_expiry_elapsed_ms"
    const val KEY_LEASE_EXPIRY_WALL_MS = "lease_expiry_wall_ms"
    const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    const val KEY_LAST_STATE = "last_state"
    const val WARNING_TIMEOUT_MS = 60_000L
    const val EXTRA_LEASE_EXPIRY_ELAPSED = "extra_lease_expiry_elapsed"
    const val EXTRA_LEASE_EXPIRY_WALL = "extra_lease_expiry_wall"
    const val EXTRA_LAST_HEARTBEAT = "extra_last_heartbeat"

    data class TransitionRecord(
        val timestampMs: Long,
        val event: String,
        val fromState: String,
        val toState: String,
        val heartbeatTimestampMs: Long,
        val leaseExpiryMs: Long,
        val notificationText: String,
        val notificationChannel: String,
        val reason: String,
        val validity: Boolean = true
    )

    private val transitions = Collections.synchronizedList(mutableListOf<TransitionRecord>())

    var leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS
    var heartbeatProducerKilled: Boolean = false

    private var lastCaptureTimestampMs: Long = 0L
    private var lastHeartbeatMs: Long = 0L
    private var leaseExpiryElapsedRealtimeMs: Long = 0L
    private var leaseExpiryWallClockMs: Long = 0L

    var isCloudOnline: Boolean = true
    var isCloudConnecting: Boolean = false
    var isCloudDegraded: Boolean = false

    private val initialSnapshot = LivenessSnapshot(
        state = LivenessState.NOT_PROTECTING,
        heartbeatTimestampMs = 0L,
        leaseExpiryMs = 0L,
        notificationText = "Protection stopped — hazards may be missed",
        notificationChannel = CHANNEL_WARNING_ID,
        reason = LivenessSnapshot.REASON_INITIAL,
        captureActive = false,
        inferenceVerified = false
    )

    private val _snapshot = MutableStateFlow(initialSnapshot)
    val snapshot: StateFlow<LivenessSnapshot> = _snapshot.asStateFlow()
    val state: StateFlow<LivenessState> get() = MutableStateFlow(_snapshot.value.state)

    fun ensureWarningNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_WARNING_ID,
                "PulsePath Protection Warning",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-importance warnings when audio protection terminates or degrades unexpectedly"
                enableVibration(true)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    fun postWarningNotification(context: Context, text: String) {
        try {
            ensureWarningNotificationChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_WARNING_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_pulsepath)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(false)
                .setAutoCancel(true)
                .setTimeoutAfter(WARNING_TIMEOUT_MS)
                .setContentIntent(pendingIntent)
                .build()
            manager?.notify(WARNING_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "postWarningNotification failed: ${e.message}")
        }
    }

    fun cancelWarningNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(WARNING_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun armWatchdogAlarm(context: Context, triggerAtElapsedRealtimeMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, LivenessWatchdogReceiver::class.java).apply {
            action = ACTION_WATCHDOG_CHECK
            putExtra(EXTRA_LEASE_EXPIRY_ELAPSED, triggerAtElapsedRealtimeMs)
            putExtra(EXTRA_LEASE_EXPIRY_WALL, leaseExpiryWallClockMs)
            putExtra(EXTRA_LAST_HEARTBEAT, lastCaptureTimestampMs)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedRealtimeMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedRealtimeMs,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedRealtimeMs,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedRealtimeMs,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog alarm: ${e.message}")
        }
    }

    @Synchronized
    fun renewLease(
        context: Context,
        captureTimestampMs: Long,
        inferenceVerified: Boolean,
        cloudOnline: Boolean = isCloudOnline,
        cloudConnecting: Boolean = isCloudConnecting,
        cloudDegraded: Boolean = isCloudDegraded,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (heartbeatProducerKilled) {
            Log.w(TAG, "renewLease refused: heartbeatProducerKilled == true (mutation active)")
            return false
        }
        if (!inferenceVerified) {
            Log.w(TAG, "renewLease refused: inferenceVerified == false")
            return false
        }
        if (captureTimestampMs <= lastCaptureTimestampMs && lastCaptureTimestampMs != 0L) {
            Log.w(TAG, "renewLease refused: capture timestamp $captureTimestampMs not advancing past $lastCaptureTimestampMs")
            return false
        }
        lastCaptureTimestampMs = captureTimestampMs
        lastHeartbeatMs = nowWallMs
        leaseExpiryElapsedRealtimeMs = nowElapsedMs + leaseDurationMs
        leaseExpiryWallClockMs = nowWallMs + leaseDurationMs

        isCloudOnline = cloudOnline
        isCloudConnecting = cloudConnecting
        isCloudDegraded = cloudDegraded

        val nextState = when {
            !cloudOnline -> LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL
            cloudConnecting || cloudDegraded -> LivenessState.DEGRADED_BUT_LOCAL
            else -> LivenessState.PROTECTED
        }

        val notifText = when (nextState) {
            LivenessState.PROTECTED -> "Protection active · local"
            LivenessState.DEGRADED_BUT_LOCAL,
            LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL -> "Local protection only"
            LivenessState.NOT_PROTECTING -> "Protection stopped — hazards may be missed"
        }

        val prev = _snapshot.value
        val next = LivenessSnapshot(
            state = nextState,
            heartbeatTimestampMs = captureTimestampMs,
            leaseExpiryMs = leaseExpiryWallClockMs,
            notificationText = notifText,
            notificationChannel = CHANNEL_LISTENER_ID,
            reason = "verified_heartbeat_renewal",
            captureActive = true,
            inferenceVerified = true,
            wallClockMs = nowWallMs
        )
        _snapshot.value = next
        recordTransition(prev, next, "heartbeat_renewed")

        // Recovery: dismiss any prior protection warning since active protection is confirmed
        cancelWarningNotification(context)

        // Persist lease metadata so OS-owned watchdog survives process death
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_LEASE_ARMED, true)
                .putLong(KEY_LEASE_EXPIRY_ELAPSED_MS, leaseExpiryElapsedRealtimeMs)
                .putLong(KEY_LEASE_EXPIRY_WALL_MS, leaseExpiryWallClockMs)
                .putLong(KEY_LAST_HEARTBEAT_MS, captureTimestampMs)
                .putString(KEY_LAST_STATE, nextState.wireName)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist lease state: ${e.message}")
        }

        armWatchdogAlarm(context, leaseExpiryElapsedRealtimeMs)
        return true
    }

    @Synchronized
    fun updateCloudState(
        context: Context,
        cloudOnline: Boolean,
        cloudConnecting: Boolean,
        cloudDegraded: Boolean,
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        isCloudOnline = cloudOnline
        isCloudConnecting = cloudConnecting
        isCloudDegraded = cloudDegraded

        val current = _snapshot.value
        if (current.state == LivenessState.NOT_PROTECTING) return

        val nextState = when {
            !cloudOnline -> LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL
            cloudConnecting || cloudDegraded -> LivenessState.DEGRADED_BUT_LOCAL
            else -> LivenessState.PROTECTED
        }

        if (nextState != current.state) {
            val notifText = when (nextState) {
                LivenessState.PROTECTED -> "Protection active · local"
                LivenessState.DEGRADED_BUT_LOCAL,
                LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL -> "Local protection only"
                LivenessState.NOT_PROTECTING -> "Protection stopped — hazards may be missed"
            }
            val next = current.copy(
                state = nextState,
                notificationText = notifText,
                wallClockMs = nowWallMs,
                reason = "cloud_state_changed"
            )
            _snapshot.value = next
            recordTransition(current, next, "cloud_state_changed")
        }
    }

    @Synchronized
    fun checkAndExpireIfOverdue(
        context: Context,
        reason: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = _snapshot.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasArmed = prefs.getBoolean(KEY_LEASE_ARMED, false)

        if (current.state == LivenessState.NOT_PROTECTING && !wasArmed) return false

        val effectiveExpiryElapsed = if (leaseExpiryElapsedRealtimeMs != 0L) {
            leaseExpiryElapsedRealtimeMs
        } else {
            prefs.getLong(KEY_LEASE_EXPIRY_ELAPSED_MS, 0L)
        }
        val effectiveExpiryWall = if (leaseExpiryWallClockMs != 0L) {
            leaseExpiryWallClockMs
        } else {
            prefs.getLong(KEY_LEASE_EXPIRY_WALL_MS, 0L)
        }

        if (nowElapsedMs >= effectiveExpiryElapsed || nowWallMs >= effectiveExpiryWall) {
            val next = LivenessSnapshot(
                state = LivenessState.NOT_PROTECTING,
                heartbeatTimestampMs = current.heartbeatTimestampMs.takeIf { it != 0L }
                    ?: prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L),
                leaseExpiryMs = effectiveExpiryWall,
                notificationText = "Protection stopped — hazards may be missed",
                notificationChannel = CHANNEL_WARNING_ID,
                reason = "lease_expired_$reason",
                captureActive = false,
                inferenceVerified = false,
                wallClockMs = nowWallMs
            )
            _snapshot.value = next
            prefs.edit().putBoolean(KEY_LEASE_ARMED, false).apply()
            recordTransition(current, next, "lease_expired")
            postWarningNotification(context, next.notificationText)
            return true
        }
        return false
    }

    @Synchronized
    fun onWatchdogAlarmFired(
        context: Context,
        intent: Intent?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasArmed = prefs.getBoolean(KEY_LEASE_ARMED, false)
        val savedExpiryElapsed = prefs.getLong(KEY_LEASE_EXPIRY_ELAPSED_MS, 0L)
        val savedExpiryWall = prefs.getLong(KEY_LEASE_EXPIRY_WALL_MS, 0L)
        val savedLastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)

        val current = _snapshot.value

        // If process was alive and lease was already extended beyond this alarm trigger time
        if (current.state != LivenessState.NOT_PROTECTING && nowElapsedMs < leaseExpiryElapsedRealtimeMs - 100L) {
            Log.i(TAG, "Watchdog alarm arrived but active lease is already extended to $leaseExpiryElapsedRealtimeMs")
            return
        }

        // Alarm represents lease expiry: either process died or local heartbeat ceased
        Log.w(TAG, "Watchdog alarm triggered: wasArmed=$wasArmed, currentState=${current.state.wireName}")
        val next = LivenessSnapshot(
            state = LivenessState.NOT_PROTECTING,
            heartbeatTimestampMs = if (current.heartbeatTimestampMs != 0L) current.heartbeatTimestampMs else savedLastHeartbeat,
            leaseExpiryMs = if (current.leaseExpiryMs != 0L) current.leaseExpiryMs else savedExpiryWall,
            notificationText = "Protection stopped — hazards may be missed",
            notificationChannel = CHANNEL_WARNING_ID,
            reason = if (current.state == LivenessState.NOT_PROTECTING && wasArmed) "os_alarm_watchdog_process_death" else "os_alarm_watchdog",
            captureActive = false,
            inferenceVerified = false,
            wallClockMs = nowWallMs
        )
        _snapshot.value = next
        prefs.edit().putBoolean(KEY_LEASE_ARMED, false).apply()
        recordTransition(current, next, next.reason)
        postWarningNotification(context, next.notificationText)
    }

    @Synchronized
    fun onProtectionStopped(
        context: Context,
        reason: String,
        nowWallMs: Long = System.currentTimeMillis()
    ) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val intent = Intent(context, LivenessWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager?.cancel(pendingIntent)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_LEASE_ARMED, false).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up alarm on protection stop: ${e.message}")
        }

        val current = _snapshot.value

        // The service winding itself down AFTER protection already stopped is not a second
        // event: onDestroy follows every user_stop, and letting it overwrite the reason would
        // turn a deliberate Stop into an alarm one callback later.
        if (current.state == LivenessState.NOT_PROTECTING && reason == "service_destroy") {
            recordTransition(current, current, "protection_stopped_${reason}_already_stopped")
            return
        }

        val riderInitiated = reason.startsWith(LivenessSnapshot.RIDER_REASON_PREFIX)
        val next = LivenessSnapshot(
            state = LivenessState.NOT_PROTECTING,
            heartbeatTimestampMs = current.heartbeatTimestampMs,
            leaseExpiryMs = nowWallMs,
            notificationText = if (riderInitiated) "Protection paused" else "Protection stopped — hazards may be missed",
            notificationChannel = CHANNEL_WARNING_ID,
            reason = reason,
            captureActive = false,
            inferenceVerified = false,
            wallClockMs = nowWallMs
        )
        _snapshot.value = next
        recordTransition(current, next, "protection_stopped_$reason")
        if (riderInitiated) {
            // The rider did this; there is nothing to warn about, and a stale warning from an
            // earlier involuntary stop must not outlive their decision to take over.
            cancelWarningNotification(context)
        } else {
            postWarningNotification(context, next.notificationText)
        }
    }

    fun isLeaseActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return _snapshot.value.state != LivenessState.NOT_PROTECTING && nowElapsedMs < leaseExpiryElapsedRealtimeMs
    }

    fun killHeartbeatProducer() {
        heartbeatProducerKilled = true
        Log.w(TAG, "heartbeatProducerKilled set to true (negative control mutation active)")
    }

    fun restoreHeartbeatProducer() {
        heartbeatProducerKilled = false
    }

    fun simulateProcessDeath() {
        heartbeatProducerKilled = false
        leaseDurationMs = DEFAULT_LEASE_DURATION_MS
        lastCaptureTimestampMs = 0L
        lastHeartbeatMs = 0L
        leaseExpiryElapsedRealtimeMs = 0L
        leaseExpiryWallClockMs = 0L
        isCloudOnline = true
        isCloudConnecting = false
        isCloudDegraded = false
        _snapshot.value = initialSnapshot
        // Intentionally keep SharedPreferences and registered alarms to simulate real OS process death
    }

    fun resetForTesting(context: Context? = null) {
        heartbeatProducerKilled = false
        leaseDurationMs = DEFAULT_LEASE_DURATION_MS
        lastCaptureTimestampMs = 0L
        lastHeartbeatMs = 0L
        leaseExpiryElapsedRealtimeMs = 0L
        leaseExpiryWallClockMs = 0L
        isCloudOnline = true
        isCloudConnecting = false
        isCloudDegraded = false
        _snapshot.value = initialSnapshot
        transitions.clear()
        context?.let {
            try {
                it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
                val manager = it.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(WARNING_NOTIFICATION_ID)
            } catch (_: Exception) {}
        }
    }

    private fun recordTransition(from: LivenessSnapshot, to: LivenessSnapshot, event: String) {
        val record = TransitionRecord(
            timestampMs = to.wallClockMs,
            event = event,
            fromState = from.state.wireName,
            toState = to.state.wireName,
            heartbeatTimestampMs = to.heartbeatTimestampMs,
            leaseExpiryMs = to.leaseExpiryMs,
            notificationText = to.notificationText,
            notificationChannel = to.notificationChannel,
            reason = to.reason,
            validity = true
        )
        transitions.add(record)
        Log.i(TAG, "TRANSITION: [${from.state.wireName}] -> [${to.state.wireName}] ($event)")
    }

    fun getTransitionTrace(): List<TransitionRecord> = transitions.toList()

    fun exportTransitionsCsv(): String {
        val sb = StringBuilder()
        sb.append("timestamp_ms,event,from_state,to_state,heartbeat_timestamp_ms,lease_expiry_ms,notification_text,notification_channel,reason,validity\n")
        synchronized(transitions) {
            for (t in transitions) {
                sb.append("${t.timestampMs},\"${t.event}\",${t.fromState},${t.toState},${t.heartbeatTimestampMs},${t.leaseExpiryMs},\"${t.notificationText}\",${t.notificationChannel},\"${t.reason}\",${t.validity}\n")
            }
        }
        return sb.toString()
    }

    fun exportTransitionsJson(): String {
        val list = getTransitionTrace()
        val items = list.joinToString(",\n") { t ->
            """  {
    "timestamp_ms": ${t.timestampMs},
    "event": "${t.event}",
    "from_state": "${t.fromState}",
    "to_state": "${t.toState}",
    "heartbeat_timestamp_ms": ${t.heartbeatTimestampMs},
    "lease_expiry_ms": ${t.leaseExpiryMs},
    "notification_text": "${t.notificationText}",
    "notification_channel": "${t.notificationChannel}",
    "reason": "${t.reason}",
    "validity": ${t.validity}
  }"""
        }
        return "[\n$items\n]"
    }
}
