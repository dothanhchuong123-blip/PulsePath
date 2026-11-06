package com.soundsight.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.soundsight.R
import com.soundsight.audio.dsp.BandpassIir
import com.soundsight.audio.dsp.Decimator3x
import com.soundsight.audio.liveness.LivenessLeaseManager
import com.soundsight.audio.liveness.LivenessState
import com.soundsight.audio.local.EarlyClassificationWindow
import com.soundsight.audio.local.LocalClassifier
import com.soundsight.core.AlertConfig
import com.soundsight.ui.demo.DebugAudioStatics
import com.soundsight.ui.demo.planAudioStaticsReset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

enum class MicSource(val audioSource: Int) {
    MIC(MediaRecorder.AudioSource.MIC),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
    UNPROCESSED(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    )
}

enum class CaptureMode {
    STEREO_48K_UNPROCESSED,
    STEREO_48K_PROCESSED,
    MONO_16K_FALLBACK
}

/**
 * A stereo capture plus the alignment metadata the analyser needs to trust it.
 *
 * [preRollFrameCount] is how many frames were actually snapshotted BEFORE post-roll began - not
 * assumed to be a full second. [triggerFrameIndex] points at the first frame considered at/after
 * the triggering onset: for spike captures that is the start of the already-appended 50 ms
 * trigger chunk; for forced captures it is the end of the snapshot (the moment of forcing). Both
 * values are required, never defaulted, because an analyser that guesses alignment analyses
 * post-event audio as if it were the event.
 */
data class StereoClip(
    val left: ShortArray,
    val right: ShortArray,
    val sampleRate: Int,
    val capturedAtMs: Long,
    val preRollFrameCount: Int,
    val triggerFrameIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StereoClip
        if (!left.contentEquals(other.left)) return false
        if (!right.contentEquals(other.right)) return false
        if (sampleRate != other.sampleRate) return false
        if (capturedAtMs != other.capturedAtMs) return false
        if (preRollFrameCount != other.preRollFrameCount) return false
        if (triggerFrameIndex != other.triggerFrameIndex) return false
        return true
    }

    override fun hashCode(): Int {
        var result = left.contentHashCode()
        result = 31 * result + right.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + capturedAtMs.hashCode()
        result = 31 * result + preRollFrameCount
        result = 31 * result + triggerFrameIndex
        return result
    }
}

/**
 * One captured clip plus the two timestamps it needs to carry.
 *
 * [capturedAtMs] is wall clock - used for staleness, history ordering and UI display.
 * [capturedMonotonicMs] is elapsed-realtime-since-boot in milliseconds - the SAME domain as
 * SensorEvent.timestamp / 1_000_000 (see MotionSensor). Only the monotonic field may be used for
 * audio/motion co-occurrence: a wall-clock step between a recorded motion spike and a
 * classification would otherwise fabricate or destroy apparent correlation. The two fields must
 * never be subtracted or mixed; they answer different questions.
 */
data class AudioClip(
    val wav: ByteArray,
    val capturedAtMs: Long,
    val capturedMonotonicMs: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioClip
        if (!wav.contentEquals(other.wav)) return false
        if (capturedAtMs != other.capturedAtMs) return false
        if (capturedMonotonicMs != other.capturedMonotonicMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = wav.contentHashCode()
        result = 31 * result + capturedAtMs.hashCode()
        result = 31 * result + (capturedMonotonicMs?.hashCode() ?: 0)
        return result
    }
}

/**
 * One early pre-roll snapshot emitted ~500 ms after spike onset for fast on-device YAMNet classification.
 *
 * [pcm] contains mono 16 kHz 16-bit PCM samples centered on the spike onset (`[onset - 500ms, onset + 500ms]`).
 * [capturedAtMs] is stamped with the spike wall-clock time (matching [StereoClip.capturedAtMs] for direction correlation).
 * [capturedMonotonicMs] is stamped with the monotonic spike instant for motion corroboration.
 */
data class EarlyAudioSnapshot(
    val pcm: ShortArray,
    val capturedAtMs: Long,
    val capturedMonotonicMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EarlyAudioSnapshot
        if (!pcm.contentEquals(other.pcm)) return false
        if (capturedAtMs != other.capturedAtMs) return false
        if (capturedMonotonicMs != other.capturedMonotonicMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + capturedAtMs.hashCode()
        result = 31 * result + capturedMonotonicMs.hashCode()
        return result
    }
}

/**
 * The foreground-service type mask for a listening session, from the rider's [LocationMode] and
 * the actual ACCESS_FINE_LOCATION grant state at start time.
 *
 * Microphone alone in OFF and COLLISION_ONLY - the collision-mode fresh fix is a one-shot
 * requestSingleUpdate from the crash screen, not the foreground service streaming, so it never
 * justifies the location type. The location type is added only for ALWAYS *and* only when the
 * permission is already held, because on Android 14+ startForeground throws if the location
 * type is claimed without the grant in hand. Below Q there are no FGS types at all: 0.
 */
internal fun foregroundServiceTypes(
    locationMode: com.soundsight.core.LocationMode,
    fineLocationGranted: Boolean,
    apiLevel: Int
): Int {
    if (apiLevel < Build.VERSION_CODES.Q) return 0
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    if (locationMode == com.soundsight.core.LocationMode.ALWAYS && fineLocationGranted) {
        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
    return types
}

/**
 * Pure decision function for ignoring duplicate start commands in [ListenerService].
 *
 * A duplicate [ListenerService.ACTION_START] (or redelivery with a null action) while already
 * running is ignored to prevent spinning up duplicate recording loops or Live sessions.
 * [ListenerService.ACTION_STOP] and [ListenerService.ACTION_RESTART_RECORDING] are never ignored
 * regardless of running state.
 */
internal fun shouldIgnoreDuplicateStart(action: String?, isRunning: Boolean): Boolean {
    if (action == ListenerService.ACTION_STOP ||
        action == ListenerService.ACTION_RESTART_RECORDING ||
        action == ListenerService.ACTION_PAUSE ||
        action == ListenerService.ACTION_RESUME
    ) {
        return false
    }
    return isRunning
}

class ListenerService : Service() {

    companion object {
        private const val TAG = "ListenerService"
        const val ACTION_START = "com.soundsight.action.START_LISTENER"
        const val ACTION_STOP = "com.soundsight.action.STOP_LISTENER"
        const val ACTION_PAUSE = "com.soundsight.action.PAUSE_LISTENER"
        const val ACTION_RESUME = "com.soundsight.action.RESUME_LISTENER"
        const val ACTION_RESTART_RECORDING = "com.soundsight.action.RESTART_RECORDING"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "soundsight_listener_channel"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MS = 50
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_MS / 1000 // 800
        private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2 // 1600 bytes
        private const val PRE_ROLL_BYTES = SAMPLE_RATE * 2 * 1 // 1.0s = 32000 bytes
        private const val POST_ROLL_BYTES = (SAMPLE_RATE * 2 * 1.2).toInt() // 1.2s = 38400 bytes
        /**
         * Minimum gap between two spike captures.
         *
         * Public because a screen that asks the rider to MAKE a sound has to know when the
         * detector is deaf. The direction calibration screen waits out this window between its
         * two steps: without it, the second shout lands inside the cooldown, produces no capture
         * at all, and the rider is told "did not hear sound" for a sound the app simply refused
         * to listen for. See DirectionCalibrationScreen.
         */
        const val SPIKE_COOLDOWN_MS = 4000L

        private const val COOLDOWN_MS = SPIKE_COOLDOWN_MS

        // Stereo 48kHz parameters
        private const val STEREO_SAMPLE_RATE = 48000
        private const val STEREO_PRE_ROLL_SHORTS = STEREO_SAMPLE_RATE * 2 * 1 // 1.0s interleaved stereo = 96000 shorts
        private const val STEREO_POST_ROLL_SHORTS = (STEREO_SAMPLE_RATE * 2 * 1.2).toInt() // 1.2s interleaved stereo = 115200 shorts
        // In 50ms at 48kHz stereo: 2400 frames = 4800 shorts
        private const val STEREO_SHORTS_PER_50MS = STEREO_SAMPLE_RATE * CHUNK_MS / 1000 * 2 // 4800 shorts
        private const val STEREO_BYTES_PER_50MS = STEREO_SHORTS_PER_50MS * 2 // 9600 bytes
        // Frames (not shorts) in one 50 ms trigger chunk at 48 kHz - the alignment unit between
        // a spike capture's pre-roll snapshot and its trigger onset.
        private const val STEREO_TRIGGER_CHUNK_FRAMES = STEREO_SAMPLE_RATE * CHUNK_MS / 1000 // 2400

        /** Monotonic ms since boot: the domain SensorEvent.timestamp uses (nanos / 1e6). */
        fun monotonicNowMs(): Long = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L

        private val _captureMode = MutableStateFlow(CaptureMode.MONO_16K_FALLBACK)
        val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

        /**
         * Tier-1 spike notifications, carrying the spike's wall-clock time.
         *
         * The payload is the same `capturedAtMs` stamped on the [AudioClip] and [StereoClip]
         * produced by this spike, so a consumer that reacts to the bare spike (the demo-mode
         * instant takeover) can still be matched against the stereo analyser's verdict for the
         * same capture by [com.soundsight.domain.DirectionCorrelator]. It used to be `Unit`, and
         * the demo card therefore had to invent its own timestamp - which by construction matched
         * no capture, so the direction arrow could never appear on it.
         */
        private val _preAlerts = MutableSharedFlow<Long>(extraBufferCapacity = 64)
        val preAlerts: SharedFlow<Long> = _preAlerts.asSharedFlow()

        private val _clips = MutableSharedFlow<AudioClip>(extraBufferCapacity = 32)
        val clips: SharedFlow<AudioClip> = _clips.asSharedFlow()

        private val _stereoClips = MutableSharedFlow<StereoClip>(extraBufferCapacity = 32)
        val stereoClips: SharedFlow<StereoClip> = _stereoClips.asSharedFlow()

        private val _earlySnapshots = MutableSharedFlow<EarlyAudioSnapshot>(extraBufferCapacity = 16)
        val earlySnapshots: SharedFlow<EarlyAudioSnapshot> = _earlySnapshots.asSharedFlow()

        private val _lastCapturedClip = MutableStateFlow<AudioClip?>(null)
        val lastCapturedClip: StateFlow<AudioClip?> = _lastCapturedClip.asStateFlow()

        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        /**
         * Tracks whether the HUD screen is currently visible to the rider.
         * The background siren alert MUST NOT fire while this is true.
         */
        val isHudVisible = MutableStateFlow(false)

        private val _noiseFloorValue = MutableStateFlow(1200.0)
        val noiseFloorValue: StateFlow<Double> = _noiseFloorValue.asStateFlow()

        private val _thresholdValue = MutableStateFlow(2400.0)
        val thresholdValue: StateFlow<Double> = _thresholdValue.asStateFlow()

        private val _freezeEventCount = MutableStateFlow(0)
        val freezeEventCount: StateFlow<Int> = _freezeEventCount.asStateFlow()

        private val _unfreezeEventCount = MutableStateFlow(0)
        val unfreezeEventCount: StateFlow<Int> = _unfreezeEventCount.asStateFlow()

        private val _timeSinceLastSpikeMs = MutableStateFlow(-1L)
        val timeSinceLastSpikeMs: StateFlow<Long> = _timeSinceLastSpikeMs.asStateFlow()

        private val _micUnavailable = MutableStateFlow(false)
        val micUnavailable: StateFlow<Boolean> = _micUnavailable.asStateFlow()

        private val _permissionRevoked = MutableStateFlow(false)
        val permissionRevoked: StateFlow<Boolean> = _permissionRevoked.asStateFlow()

        /**
         * Detector stop. Setting it also republishes [com.soundsight.core.AlertConfig.demoFastAlert],
         * because [Sensitivity.MAX] is not merely "a bit more sensitive" - it is the demo/experience
         * stop, and everything downstream that has to behave differently in a demo (the instant
         * spike takeover, the relaxed classification floors) reads that one flag rather than
         * reaching back into :audio for the enum. Done in the setter, not at the call sites, so
         * the four places that assign sensitivity cannot drift apart from each other.
         */
        var sensitivity: Sensitivity = Sensitivity.MEDIUM
            set(value) {
                field = value
                AlertConfig.demoFastAlert = (value == Sensitivity.MAX)
            }

        // Debug-only statically settable configurations
        var micSource: MicSource = MicSource.MIC
        var enableNoiseSuppressor: Boolean = false
        var enableAutomaticGainControl: Boolean = false

        /**
         * Restores debug audio statics (micSource, NS, AGC) to their default safe operational
         * values. If any were altered from default and [context] is provided while running,
         * restarts audio recording to ensure clean detector state (Step 7b / H6).
         *
         * @return true if statics changed and a restart was required, false otherwise.
         */
        fun resetDebugAudioStatics(context: Context? = null): Boolean {
            val current = DebugAudioStatics(micSource, enableNoiseSuppressor, enableAutomaticGainControl)
            val plan = planAudioStaticsReset(current)
            micSource = plan.targetState.micSource
            enableNoiseSuppressor = plan.targetState.enableNoiseSuppressor
            enableAutomaticGainControl = plan.targetState.enableAutomaticGainControl
            if (plan.needsRestart && context != null && isRunning.value) {
                restartRecording(context)
            }
            return plan.needsRestart
        }

        private var currentInstance: ListenerService? = null

        fun getRecentAudioMono16k(): ShortArray? {
            val instance = currentInstance ?: return null
            val snapshotBytes = instance.preRollBuffer.snapshot()
            if (snapshotBytes.isEmpty()) return null
            val shortCount = snapshotBytes.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(snapshotBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }

        /**
         * Manually triggers a capture identical in shape to a real spike-detected one (same
         * pre/post-roll windows, same WAV encoding, same clips/stereoClips flow), but without
         * touching spike cooldown, noise-floor freezing, or the Tier-1 pre-alert. For demo/testing
         * only, so a real sound doesn't have to be loud enough to clear the spike threshold.
         */
        fun forceCapture() {
            currentInstance?.forceCaptureNow()
        }

        fun extractMono16kFromWav(wavBytes: ByteArray): ShortArray {
            val pcmOffset = if (wavBytes.size >= 44 &&
                wavBytes[0] == 'R'.code.toByte() &&
                wavBytes[1] == 'I'.code.toByte() &&
                wavBytes[2] == 'F'.code.toByte() &&
                wavBytes[3] == 'F'.code.toByte()
            ) 44 else 0
            val pcmLength = (wavBytes.size - pcmOffset)
            val shortCount = pcmLength / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(wavBytes, pcmOffset, pcmLength).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }

        fun start(context: Context) {
            _permissionRevoked.value = false
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun restartRecording(context: Context) {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_RESTART_RECORDING
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld: Boolean = false

    private val noiseFloor = NoiseFloor(windowSeconds = 15.0)
    private val bandpassIir = BandpassIir(SAMPLES_PER_CHUNK)
    private val bandpassShortBuffer = ShortArray(SAMPLES_PER_CHUNK)
    private val preRollBuffer = ByteArrayRingBuffer(PRE_ROLL_BYTES)
    private val stereoPreRollBuffer = ShortArrayRingBuffer(STEREO_PRE_ROLL_SHORTS)
    private val activeCaptures = CopyOnWriteArrayList<ActiveCapture>()
    private var lastSpikeTimeMs: Long = 0L
    private var lastLevelEmitMs: Long = 0L

    private class ActiveCapture(
        val preRoll: ByteArray,
        val neededBytes: Int,
        val capturedAtMs: Long,
        val capturedAtMonotonicMs: Long,
        val stereoPreRoll: ShortArray? = null,
        val stereoNeededShorts: Int = 0,
        // Stereo alignment metadata carried through to StereoClip. Spike captures snapshot the
        // ring buffer AFTER the trigger chunk has been appended, so the chunk sits at the end of
        // the pre-roll and the trigger index is (preRollFrames - 2_400). Forced captures carry
        // an explicit index at the END of their snapshot - they are not spike-aligned, and must
        // not pretend to be.
        val stereoPreRollFrameCount: Int = 0,
        val stereoTriggerFrameIndex: Int = 0,
        val postRollBuffer: ByteArrayOutputStream = ByteArrayOutputStream(neededBytes),
        val stereoPostRollBuffer: ShortArrayList = ShortArrayList(stereoNeededShorts)
    )

    private class ShortArrayList(initialCapacity: Int) {
        private var array = ShortArray(initialCapacity)
        private var size = 0

        @Synchronized
        fun addAll(elements: ShortArray, offset: Int = 0, length: Int = elements.size) {
            val minCap = size + length
            if (minCap > array.size) {
                var newCap = array.size * 2
                if (newCap < minCap) newCap = minCap
                val newArr = ShortArray(newCap)
                System.arraycopy(array, 0, newArr, 0, size)
                array = newArr
            }
            System.arraycopy(elements, offset, array, size, length)
            size += length
        }

        @Synchronized
        fun size(): Int = size

        @Synchronized
        fun toShortArray(): ShortArray {
            val result = ShortArray(size)
            System.arraycopy(array, 0, result, 0, size)
            return result
        }
    }

    private class ShortArrayRingBuffer(val capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var writePos = 0
        private var count = 0

        @Synchronized
        fun write(data: ShortArray, offset: Int = 0, length: Int = data.size) {
            for (i in offset until (offset + length)) {
                buffer[writePos] = data[i]
                writePos = (writePos + 1) % capacity
                if (count < capacity) count++
            }
        }

        @Synchronized
        fun snapshot(): ShortArray {
            val result = ShortArray(count)
            if (count < capacity) {
                System.arraycopy(buffer, 0, result, 0, count)
            } else {
                val tail = capacity - writePos
                System.arraycopy(buffer, writePos, result, 0, tail)
                if (writePos > 0) {
                    System.arraycopy(buffer, 0, result, tail, writePos)
                }
            }
            return result
        }
    }

    private class ByteArrayRingBuffer(val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var writePos = 0
        private var count = 0

        @Synchronized
        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            for (i in offset until (offset + length)) {
                buffer[writePos] = data[i]
                writePos = (writePos + 1) % capacity
                if (count < capacity) count++
            }
        }

        @Synchronized
        fun snapshot(): ByteArray {
            val result = ByteArray(count)
            if (count < capacity) {
                System.arraycopy(buffer, 0, result, 0, count)
            } else {
                val tail = capacity - writePos
                System.arraycopy(buffer, writePos, result, 0, tail)
                if (writePos > 0) {
                    System.arraycopy(buffer, 0, result, tail, writePos)
                }
            }
            return result
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                _isRunning.value = false
                _isPaused.value = false
                LivenessLeaseManager.onProtectionStopped(this, "user_stop")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (_isRunning.value && !_isPaused.value) {
                    _isPaused.value = true
                    stopAudioCapture()
                    releaseWakeLock()
                    LivenessLeaseManager.onProtectionStopped(this, "user_pause")
                    updateNotification()
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (_isRunning.value && _isPaused.value) {
                    _isPaused.value = false
                    acquireWakeLock()
                    startAudioCapture()
                    updateNotification()
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART_RECORDING -> {
                if (_isRunning.value && !_isPaused.value) {
                    stopAudioCapture()
                    startAudioCapture()
                }
                return START_NOT_STICKY
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _permissionRevoked.value = true
            LivenessLeaseManager.onProtectionStopped(this, "mic_permission_revoked")
            stopSelf()
            return START_NOT_STICKY
        }

        // If service is already running and paused, a start command unpauses it
        if (_isRunning.value && _isPaused.value) {
            _isPaused.value = false
            acquireWakeLock()
            startAudioCapture()
            updateNotification()
            return START_NOT_STICKY
        }

        // Idempotent: a duplicate ACTION_START (or a plain re-delivery with a null action)
        // while already running must not spin up a second recording loop or Live session.
        if (shouldIgnoreDuplicateStart(intent?.action, _isRunning.value)) {
            return START_NOT_STICKY
        }

        // A refused foreground-service start is a stop, not a crash - see startInForeground.
        if (!startInForeground()) {
            return START_NOT_STICKY
        }
        _isPaused.value = false
        acquireWakeLock()
        startAudioCapture()

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (!isWakeLockHeld) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SoundSight::Listener"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                isWakeLockHeld = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        if (isWakeLockHeld) {
            try {
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing wake lock: ${e.message}")
            } finally {
                wakeLock = null
                isWakeLockHeld = false
            }
        }
    }

    /**
     * Promotes the service to the foreground. Returns false when the framework refused the
     * promotion, in which case nothing else should be started.
     */
    private fun startInForeground(): Boolean {
        val notification = buildForegroundNotification()
        // The FGS type is chosen at start time from the rider's LocationMode and the actual
        // grant state - never hard-coded. Android 14+ throws from startForeground if the
        // location type is claimed without ACCESS_FINE_LOCATION already held, which is why the
        // grant is an input here and why OFF / COLLISION_ONLY always start as microphone alone.
        val locationMode = runBlocking {
            try {
                withTimeout(2_000) {
                    com.soundsight.core.OnboardingStore.locationModeFlow(applicationContext).first()
                }
            } catch (_: Exception) {
                com.soundsight.core.LocationMode.OFF
            }
        }
        val fineGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val serviceType = foregroundServiceTypes(
            locationMode = locationMode,
            fineLocationGranted = fineGranted,
            apiLevel = Build.VERSION.SDK_INT
        )
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (e: Exception) {
            // Android 12+ refuses a foreground-service start issued from the background, and 14+
            // additionally refuses a microphone-typed one unless the app is in a state where the
            // while-in-use RECORD_AUDIO grant is live. The framework signals both by throwing out
            // of onStartCommand, and an exception escaping onStartCommand kills the whole process:
            // the rider then returns to a dead task painting nothing but its window background.
            // Monitoring that could not start must present as a paused HUD, never as a crash - the
            // rider can press Start again, and that press comes from the foreground.
            Log.e(TAG, "startForeground refused (${e.javaClass.simpleName}): ${e.message}")
            _isRunning.value = false
            LivenessLeaseManager.onProtectionStopped(this, "start_foreground_refused")
            stopSelf()
            return false
        }
        // Only after the promotion succeeded: location work started for a service that then failed
        // to reach the foreground would be left running with nothing to stop it.
        if (locationMode == com.soundsight.core.LocationMode.ALWAYS && fineGranted) {
            com.soundsight.core.SpeedProvider.start(applicationContext)
        }
        if (locationMode == com.soundsight.core.LocationMode.COLLISION_ONLY && fineGranted) {
            com.soundsight.core.PeriodicLocationRefresher.start(applicationContext, serviceScope)
        }
        _isRunning.value = true
        if (com.soundsight.core.AlertConfig.t2Enabled) {
            com.soundsight.data.live.LiveSession.start(applicationContext)
        }
        startHeartbeatProducer()
        observeLivenessChanges()
        return true
    }

    private var heartbeatJob: Job? = null
    private var livenessObserverJob: Job? = null
    private var lastCaptureChunkMs: Long = 0L

    private fun startHeartbeatProducer() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive && _isRunning.value && !_isPaused.value) {
                delay(1000L)
                if (!_isRunning.value || _isPaused.value) break
                val now = System.currentTimeMillis()
                val captureFresh = (now - lastCaptureChunkMs) < 2000L
                val captureActive = captureFresh && !_micUnavailable.value && !_permissionRevoked.value
                val inferenceVerified = LocalClassifier.isOperational(applicationContext)

                if (captureActive && inferenceVerified) {
                    LivenessLeaseManager.renewLease(
                        context = applicationContext,
                        captureTimestampMs = lastCaptureChunkMs,
                        inferenceVerified = true
                    )
                } else {
                    Log.w(TAG, "Heartbeat check failed: captureActive=$captureActive, inferenceVerified=$inferenceVerified")
                    if (_permissionRevoked.value || _micUnavailable.value) {
                        LivenessLeaseManager.onProtectionStopped(
                            applicationContext,
                            if (_permissionRevoked.value) "mic_permission_revoked" else "mic_unavailable"
                        )
                    }
                }
            }
        }
    }

    private fun observeLivenessChanges() {
        livenessObserverJob?.cancel()
        livenessObserverJob = serviceScope.launch {
            LivenessLeaseManager.snapshot.collect {
                if (_isRunning.value) {
                    updateNotification()
                }
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val tapIntent = Intent(this, com.soundsight.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ListenerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPausedNow = _isPaused.value
        val pauseResumeIntent = Intent(this, ListenerService::class.java).apply {
            action = if (isPausedNow) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val liveness = LivenessLeaseManager.snapshot.value
        val contentText = if (isPausedNow) {
            "Tạm dừng / Paused"
        } else {
            liveness.notificationText
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_pulsepath)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isPausedNow) {
            builder.addAction(
                R.drawable.ic_notification_play,
                "Resume",
                pauseResumePendingIntent
            )
        } else {
            builder.addAction(
                R.drawable.ic_notification_pause,
                "Pause",
                pauseResumePendingIntent
            )
        }

        builder.addAction(
            R.drawable.ic_notification_stop,
            "Stop",
            stopPendingIntent
        )

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun createNotificationChannel() {
        LivenessLeaseManager.ensureWarningNotificationChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SoundSight Audio Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground microphone monitoring service for traffic sounds"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopAudioCapture() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        recordingJob?.cancel()
        recordingJob = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            automaticGainControl?.release()
        } catch (_: Exception) {}
        automaticGainControl = null

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        audioRecord = null
        bandpassIir.reset()
    }

    private fun startAudioCapture() {
        if (recordingJob?.isActive == true) return
        bandpassIir.reset()

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            try {
                if (ActivityCompat.checkSelfPermission(
                        this@ListenerService,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    _permissionRevoked.value = true
                    LivenessLeaseManager.onProtectionStopped(this@ListenerService, "mic_permission_revoked")
                    stopSelf()
                    return@launch
                }

                data class RecordCandidate(
                    val source: Int,
                    val sampleRate: Int,
                    val channelConfig: Int,
                    val mode: CaptureMode,
                    val name: String
                )

                val ladder = listOf(
                    RecordCandidate(
                        source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC,
                        sampleRate = 48000,
                        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                        mode = CaptureMode.STEREO_48K_UNPROCESSED,
                        name = "UNPROCESSED 48kHz Stereo"
                    ),
                    RecordCandidate(
                        source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate = 48000,
                        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                        mode = CaptureMode.STEREO_48K_PROCESSED,
                        name = "VOICE_RECOGNITION 48kHz Stereo"
                    ),
                    RecordCandidate(
                        source = MediaRecorder.AudioSource.MIC,
                        sampleRate = 48000,
                        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                        mode = CaptureMode.STEREO_48K_PROCESSED,
                        name = "MIC 48kHz Stereo"
                    ),
                    RecordCandidate(
                        source = MediaRecorder.AudioSource.MIC,
                        sampleRate = 16000,
                        channelConfig = AudioFormat.CHANNEL_IN_MONO,
                        mode = CaptureMode.MONO_16K_FALLBACK,
                        name = "MIC 16kHz Mono Fallback"
                    )
                )

                var record: AudioRecord? = null
                var resolvedCandidate: RecordCandidate? = null

                while (isActive) {
                    for (candidate in ladder) {
                        val minBufSize = AudioRecord.getMinBufferSize(
                            candidate.sampleRate,
                            candidate.channelConfig,
                            AUDIO_FORMAT
                        )
                        if (minBufSize <= 0) continue

                        val bufferSize = if (candidate.mode == CaptureMode.MONO_16K_FALLBACK) {
                            maxOf(minBufSize, BYTES_PER_CHUNK * 4)
                        } else {
                            maxOf(minBufSize, STEREO_BYTES_PER_50MS * 4)
                        }

                        Log.d(TAG, "Attempting ladder candidate: ${candidate.name}")
                        val testRecord = AudioRecord(
                            candidate.source,
                            candidate.sampleRate,
                            candidate.channelConfig,
                            AUDIO_FORMAT,
                            bufferSize
                        )

                        if (testRecord.state == AudioRecord.STATE_INITIALIZED) {
                            record = testRecord
                            resolvedCandidate = candidate
                            _captureMode.value = candidate.mode
                            _micUnavailable.value = false
                            Log.d(TAG, "Successfully initialized AudioRecord with candidate: ${candidate.name} (mode=${candidate.mode})")
                            break
                        } else {
                            testRecord.release()
                        }
                    }

                    if (record != null) {
                        break
                    } else {
                        Log.e(TAG, "All ladder candidates failed to initialize. Retrying in 5000ms...")
                        _micUnavailable.value = true
                        delay(5000)
                    }
                }

                if (record == null || resolvedCandidate == null || !isActive) {
                    return@launch
                }

                val audioSessionId = record.audioSessionId

                var nsActive = false
                if (enableNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(audioSessionId).apply {
                            enabled = true
                        }
                        nsActive = noiseSuppressor?.enabled == true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initializing NoiseSuppressor: ${e.message}")
                    }
                }

                var agcActive = false
                if (enableAutomaticGainControl && AutomaticGainControl.isAvailable()) {
                    try {
                        automaticGainControl = AutomaticGainControl.create(audioSessionId).apply {
                            enabled = true
                        }
                        agcActive = automaticGainControl?.enabled == true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initializing AutomaticGainControl: ${e.message}")
                    }
                }

                Log.d(TAG, "AudioRecord started. Mode: ${resolvedCandidate.mode}, Source: ${resolvedCandidate.name}, NS: $nsActive, AGC: $agcActive")

                audioRecord = record
                record.startRecording()

                if (resolvedCandidate.mode == CaptureMode.MONO_16K_FALLBACK) {
                    // EXACT byte-for-byte mono 16kHz loop
                    val chunkBytes = ByteArray(BYTES_PER_CHUNK)
                    val shortBuffer = ShortArray(SAMPLES_PER_CHUNK)

                    while (isActive && _isRunning.value) {
                        var bytesRead = 0
                        while (bytesRead < BYTES_PER_CHUNK && isActive) {
                            val read = record.read(chunkBytes, bytesRead, BYTES_PER_CHUNK - bytesRead)
                            if (read <= 0) break
                            bytesRead += read
                        }

                        if (bytesRead == BYTES_PER_CHUNK) {
                            processAudioChunk(chunkBytes, shortBuffer, rawStereoShorts = null)
                        }
                    }
                } else {
                    // 48 kHz stereo capture loop
                    // 50ms at 48kHz stereo = 2400 frames = 4800 shorts = 9600 bytes
                    val stereoShortBuffer = ShortArray(STEREO_SHORTS_PER_50MS)
                    val mono16kShortBuffer = ShortArray(SAMPLES_PER_CHUNK)
                    val mono16kByteBuffer = ByteArray(BYTES_PER_CHUNK)

                    // Downmixed 48 kHz mono, low-pass filtered before 3:1 decimation so that
                    // content above the 8 kHz output Nyquist cannot alias back into the band.
                    val mono48kFrames = STEREO_SHORTS_PER_50MS / 2 // 2400 frames per 50 ms
                    val mono48kBuffer = ShortArray(mono48kFrames)
                    val decimator = com.soundsight.audio.dsp.Decimator3x(mono48kFrames)
                    decimator.reset()

                    // 100ms stereo DSP buffer (2 x 50ms chunks = 9600 shorts)
                    var stereo100msPending = ShortArray(STEREO_SHORTS_PER_50MS * 2)
                    var stereo100msOffset = 0

                    while (isActive && _isRunning.value) {
                        var shortsRead = 0
                        while (shortsRead < STEREO_SHORTS_PER_50MS && isActive) {
                            val read = record.read(stereoShortBuffer, shortsRead, STEREO_SHORTS_PER_50MS - shortsRead)
                            if (read <= 0) break
                            shortsRead += read
                        }

                        if (shortsRead == STEREO_SHORTS_PER_50MS) {
                            // Downmix stereo -> mono at 48 kHz, then anti-alias filter and
                            // decimate 3:1 to 16 kHz. The old code averaged 3 samples and kept
                            // every third, which barely attenuated 8-16 kHz content and let it
                            // fold back into the audible band as phantom tones.
                            // 2400 stereo frames -> 2400 mono 48k samples -> 800 mono 16k samples
                            for (f in 0 until mono48kFrames) {
                                val i = f * 2
                                mono48kBuffer[f] =
                                    ((stereoShortBuffer[i].toInt() + stereoShortBuffer[i + 1].toInt()) / 2).toShort()
                            }
                            decimator.process(mono48kBuffer, mono48kFrames, mono16kShortBuffer)

                            // Convert mono16k shorts to little-endian bytes for processAudioChunk
                            ByteBuffer.wrap(mono16kByteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mono16kShortBuffer)

                            // Emit 100ms stereo frames ONLY while activeCaptures is not empty
                            if (activeCaptures.isNotEmpty()) {
                                System.arraycopy(stereoShortBuffer, 0, stereo100msPending, stereo100msOffset, STEREO_SHORTS_PER_50MS)
                                stereo100msOffset += STEREO_SHORTS_PER_50MS
                                if (stereo100msOffset == stereo100msPending.size) {
                                    // 100ms ready (9600 shorts)
                                    // Reset offset
                                    stereo100msOffset = 0
                                }
                            } else {
                                stereo100msOffset = 0
                            }

                            processAudioChunk(mono16kByteBuffer, mono16kShortBuffer, rawStereoShorts = stereoShortBuffer)
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in AudioRecord: ${e.message}")
                _permissionRevoked.value = true
                LivenessLeaseManager.onProtectionStopped(this@ListenerService, "mic_permission_revoked")
                stopSelf()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception in AudioRecord: ${e.message}")
                LivenessLeaseManager.onProtectionStopped(this@ListenerService, "audiorecord_exception")
                stopSelf()
            }
        }
    }

    private fun unfreezeIfFrozen() {
        if (noiseFloor.isFrozenNow) {
            noiseFloor.setFrozen(false)
            _unfreezeEventCount.value++
        }
    }

    private fun forceCaptureNow() {
        val now = System.currentTimeMillis()
        val isStereo = _captureMode.value != CaptureMode.MONO_16K_FALLBACK
        val preRollSnapshot = preRollBuffer.snapshot()
        val stereoSnapshot = if (isStereo) stereoPreRollBuffer.snapshot() else null
        // Forced capture: the trigger is "now", i.e. the END of the pre-roll snapshot. The
        // explicit index keeps this honest - the analyser will see a trigger at the snapshot
        // boundary, never a fabricated spike-aligned chunk that was not recorded.
        val stereoPreRollFrames = (stereoSnapshot?.size ?: 0) / 2
        val capture = ActiveCapture(
            preRoll = preRollSnapshot,
            neededBytes = POST_ROLL_BYTES,
            capturedAtMs = now,
            capturedAtMonotonicMs = monotonicNowMs(),
            stereoPreRoll = stereoSnapshot,
            stereoNeededShorts = if (isStereo) STEREO_POST_ROLL_SHORTS else 0,
            stereoPreRollFrameCount = stereoPreRollFrames,
            stereoTriggerFrameIndex = stereoPreRollFrames
        )
        activeCaptures.add(capture)
    }

    private fun processAudioChunk(chunk: ByteArray, shortBuffer: ShortArray, rawStereoShorts: ShortArray? = null) {
        lastCaptureChunkMs = System.currentTimeMillis()
        // 1. Convert bytes to 16-bit PCM shorts for RMS (if not already matching)
        if (rawStereoShorts == null) {
            ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
        }

        // Broadband RMS for HUD level meter
        // NOTE: We keep _level broadband. The HUD wave is a mic level meter, not a detector;
        // making it band-limited would make the wave stop responding to the rider's own voice
        // and read as broken. This split is deliberate.
        var sumSquaresBroadband = 0.0
        for (sample in shortBuffer) {
            val s = sample.toDouble()
            sumSquaresBroadband += s * s
        }
        val broadbandRms = sqrt(sumSquaresBroadband / shortBuffer.size)

        // Band-limited RMS for spike detection and noise floor tracking (300–2500 Hz).
        // Wind (<200 Hz) and engine firing-order energy (20–120 Hz) are filtered out so that
        // detector sensitivity does not collapse while riding.
        bandpassIir.process(shortBuffer, shortBuffer.size, bandpassShortBuffer)

        var sumSquaresBand = 0.0
        for (sample in bandpassShortBuffer) {
            val s = sample.toDouble()
            sumSquaresBand += s * s
        }
        val inBandRms = sqrt(sumSquaresBand / bandpassShortBuffer.size)

        // 2. Submit band-limited RMS to NoiseFloor
        noiseFloor.submit(inBandRms)

        val currentFloor = noiseFloor.floor()
        val currentThreshold = noiseFloor.threshold(sensitivity)

        // 3. Update level StateFlow (dBFS scale: -65 dB..-5 dB mapped linearly to 0f..1f, throttled to 100ms)
        val now = System.currentTimeMillis()
        if (now - lastLevelEmitMs >= 100L) {
            lastLevelEmitMs = now
            val dbfs = 20.0 * log10(max(broadbandRms, 1.0) / 32768.0)
            val normalized = ((dbfs - (-65.0)) / ((-5.0) - (-65.0))).toFloat().coerceIn(0f, 1f)
            _level.value = normalized
            _noiseFloorValue.value = currentFloor
            _thresholdValue.value = currentThreshold
            _timeSinceLastSpikeMs.value = if (lastSpikeTimeMs == 0L) -1L else (now - lastSpikeTimeMs)
        }

        // 4. Update ring buffers (pre-roll)
        preRollBuffer.write(chunk)
        if (rawStereoShorts != null) {
            stereoPreRollBuffer.write(rawStereoShorts)
        }

        // 5. Append chunk to any active post-roll captures
        for (capture in activeCaptures) {
            capture.postRollBuffer.write(chunk)
            if (rawStereoShorts != null) {
                capture.stereoPostRollBuffer.addAll(rawStereoShorts)
            }
            if (capture.postRollBuffer.size() >= capture.neededBytes) {
                activeCaptures.remove(capture)
                finalizeClip(capture)
            }
        }

        // Check if freeze can be released
        if (activeCaptures.isEmpty() && (now - lastSpikeTimeMs) > COOLDOWN_MS) {
            unfreezeIfFrozen()
        }

        // 6. Spike detection on band-limited RMS (with 4000ms cooldown)
        if (now - lastSpikeTimeMs >= COOLDOWN_MS) {
            val isSpike = noiseFloor.isSpike(inBandRms, sensitivity)
            if (isSpike) {
                val spikeNowMs = now
                val spikeMonotonicMs = monotonicNowMs()
                lastSpikeTimeMs = spikeNowMs
                // Freeze noise floor during event
                _freezeEventCount.value++
                noiseFloor.setFrozen(true)

                // (a) IMMEDIATELY emit Tier 1 pre-alert
                _preAlerts.tryEmit(spikeNowMs)

                // (b) Snapshot pre-roll and start non-blocking post-roll capture.
                // The snapshot happens AFTER the trigger chunk was appended to the ring buffer
                // in step 4, so the 50 ms trigger chunk is the tail of the snapshot and the
                // trigger onset is (preRollFrames - 2_400). That index - not a hardcoded frame
                // 48_000 - is what the analyser must align to.
                val preRollSnapshot = preRollBuffer.snapshot()
                val stereoSnapshot = if (rawStereoShorts != null) stereoPreRollBuffer.snapshot() else null
                val stereoPreRollFrames = (stereoSnapshot?.size ?: 0) / 2
                val capture = ActiveCapture(
                    preRoll = preRollSnapshot,
                    neededBytes = POST_ROLL_BYTES,
                    capturedAtMs = spikeNowMs,
                    capturedAtMonotonicMs = spikeMonotonicMs,
                    stereoPreRoll = stereoSnapshot,
                    stereoNeededShorts = if (rawStereoShorts != null) STEREO_POST_ROLL_SHORTS else 0,
                    stereoPreRollFrameCount = stereoPreRollFrames,
                    stereoTriggerFrameIndex =
                        if (stereoSnapshot != null) {
                            (stereoPreRollFrames - STEREO_TRIGGER_CHUNK_FRAMES).coerceAtLeast(0)
                        } else {
                            0
                        }
                )
                activeCaptures.add(capture)

                // (c) Schedule early local classification snapshot at trigger + 500 ms
                scheduleEarlySnapshot(spikeNowMs, spikeMonotonicMs)
            }
        }
    }

    private fun scheduleEarlySnapshot(spikeAtMs: Long, spikeMonotonicMs: Long) {
        serviceScope.launch(Dispatchers.Default) {
            delay(EarlyClassificationWindow.SNAPSHOT_DELAY_MS)
            if (!_isRunning.value) return@launch
            val snapshotBytes = preRollBuffer.snapshot()
            val safeWindow = EarlyClassificationWindow.resolve(
                snapshotByteCount = snapshotBytes.size,
                delayMs = EarlyClassificationWindow.SNAPSHOT_DELAY_MS
            )
            if (safeWindow != null && snapshotBytes.isNotEmpty()) {
                val shortCount = snapshotBytes.size / 2
                val shorts = ShortArray(shortCount)
                ByteBuffer.wrap(snapshotBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                _earlySnapshots.emit(
                    EarlyAudioSnapshot(
                        pcm = shorts,
                        capturedAtMs = spikeAtMs,
                        capturedMonotonicMs = spikeMonotonicMs
                    )
                )
            }
        }
    }

    private fun finalizeClip(capture: ActiveCapture) {
        serviceScope.launch(Dispatchers.Default) {
            val postRollBytes = capture.postRollBuffer.toByteArray()
            val totalPcm = ByteArray(capture.preRoll.size + postRollBytes.size)
            System.arraycopy(capture.preRoll, 0, totalPcm, 0, capture.preRoll.size)
            System.arraycopy(postRollBytes, 0, totalPcm, capture.preRoll.size, postRollBytes.size)

            val wavBytes = WavEncoder.encode(
                pcm16 = totalPcm,
                sampleRate = SAMPLE_RATE,
                channels = 1
            )

            val clip = AudioClip(
                wav = wavBytes,
                capturedAtMs = capture.capturedAtMs,
                capturedMonotonicMs = capture.capturedAtMonotonicMs
            )
            _lastCapturedClip.value = clip
            _clips.emit(clip)

            // Emit StereoClip if stereo data was captured
            if (capture.stereoPreRoll != null) {
                val postRollShorts = capture.stereoPostRollBuffer.toShortArray()
                val totalStereoShorts = ShortArray(capture.stereoPreRoll.size + postRollShorts.size)
                System.arraycopy(capture.stereoPreRoll, 0, totalStereoShorts, 0, capture.stereoPreRoll.size)
                System.arraycopy(postRollShorts, 0, totalStereoShorts, capture.stereoPreRoll.size, postRollShorts.size)

                val frameCount = totalStereoShorts.size / 2
                val left = ShortArray(frameCount)
                val right = ShortArray(frameCount)
                for (i in 0 until frameCount) {
                    left[i] = totalStereoShorts[i * 2]
                    right[i] = totalStereoShorts[i * 2 + 1]
                }

                val stereoClip = StereoClip(
                    left = left,
                    right = right,
                    sampleRate = STEREO_SAMPLE_RATE,
                    capturedAtMs = capture.capturedAtMs,
                    preRollFrameCount = capture.stereoPreRollFrameCount,
                    triggerFrameIndex = capture.stereoTriggerFrameIndex
                )
                _stereoClips.emit(stereoClip)
            }

            val now = System.currentTimeMillis()
            if (activeCaptures.isEmpty() && (now - lastSpikeTimeMs) > COOLDOWN_MS) {
                unfreezeIfFrozen()
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        _isPaused.value = false
        _level.value = 0f
        activeCaptures.clear()
        unfreezeIfFrozen()
        com.soundsight.core.SpeedProvider.stop()
        com.soundsight.core.PeriodicLocationRefresher.stop()

        com.soundsight.data.live.LiveSession.stop()
        BackgroundSirenPlayer.stop(this)

        heartbeatJob?.cancel()
        heartbeatJob = null
        livenessObserverJob?.cancel()
        livenessObserverJob = null
        LivenessLeaseManager.onProtectionStopped(this, "service_destroy")

        stopAudioCapture()
        releaseWakeLock()

        serviceScope.cancel()

        if (currentInstance === this) {
            currentInstance = null
        }

        super.onDestroy()
    }
}
