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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    UNPROCESSED(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    )
}

data class AudioClip(
    val wav: ByteArray,
    val capturedAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioClip
        if (!wav.contentEquals(other.wav)) return false
        if (capturedAtMs != other.capturedAtMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = wav.contentHashCode()
        result = 31 * result + capturedAtMs.hashCode()
        return result
    }
}

class ListenerService : Service() {

    companion object {
        private const val TAG = "ListenerService"
        const val ACTION_START = "com.soundsight.action.START_LISTENER"
        const val ACTION_STOP = "com.soundsight.action.STOP_LISTENER"
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
        private const val COOLDOWN_MS = 4000L

        private val _preAlerts = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val preAlerts: SharedFlow<Unit> = _preAlerts.asSharedFlow()

        private val _clips = MutableSharedFlow<AudioClip>(extraBufferCapacity = 32)
        val clips: SharedFlow<AudioClip> = _clips.asSharedFlow()

        private val _lastCapturedClip = MutableStateFlow<AudioClip?>(null)
        val lastCapturedClip: StateFlow<AudioClip?> = _lastCapturedClip.asStateFlow()

        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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

        var sensitivity: Sensitivity = Sensitivity.MEDIUM

        // Debug-only statically settable configurations
        var micSource: MicSource = MicSource.MIC
        var enableNoiseSuppressor: Boolean = false
        var enableAutomaticGainControl: Boolean = false

        fun start(context: Context) {
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
    private val preRollBuffer = ByteArrayRingBuffer(PRE_ROLL_BYTES)
    private val activeCaptures = CopyOnWriteArrayList<ActiveCapture>()
    private var lastSpikeTimeMs: Long = 0L
    private var lastLevelEmitMs: Long = 0L

    private class ActiveCapture(
        val preRoll: ByteArray,
        val neededBytes: Int,
        val capturedAtMs: Long,
        val postRollBuffer: ByteArrayOutputStream = ByteArrayOutputStream(neededBytes)
    )

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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_RECORDING -> {
                if (_isRunning.value) {
                    stopAudioCapture()
                    startAudioCapture()
                }
                return START_NOT_STICKY
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
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

    private fun startInForeground() {
        val notification = buildForegroundNotification()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        _isRunning.value = true
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, ListenerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Đang lắng nghe / Listening")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
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
    }

    private fun startAudioCapture() {
        if (recordingJob?.isActive == true) return

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            val bufferSize = maxOf(minBufSize, BYTES_PER_CHUNK * 4)

            try {
                if (ActivityCompat.checkSelfPermission(
                        this@ListenerService,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    stopSelf()
                    return@launch
                }

                val currentSource = micSource
                Log.d(TAG, "Initializing AudioRecord with Source: $currentSource (AudioSource id: ${currentSource.audioSource})")

                val record = AudioRecord(
                    currentSource.audioSource,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize with $currentSource")
                    record.release()
                    stopSelf()
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

                Log.d(TAG, "AudioRecord started. MicSource: $currentSource, NS: $nsActive (requested: $enableNoiseSuppressor), AGC: $agcActive (requested: $enableAutomaticGainControl)")

                audioRecord = record
                record.startRecording()

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
                        processAudioChunk(chunkBytes, shortBuffer)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in AudioRecord: ${e.message}")
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Exception in AudioRecord: ${e.message}")
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

    private fun processAudioChunk(chunk: ByteArray, shortBuffer: ShortArray) {
        // 1. Convert bytes to 16-bit PCM shorts for RMS
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

        var sumSquares = 0.0
        for (sample in shortBuffer) {
            val s = sample.toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / shortBuffer.size)

        // 2. Submit to NoiseFloor
        noiseFloor.submit(rms)

        val currentFloor = noiseFloor.floor()
        val currentThreshold = currentFloor * sensitivity.multiplier

        // 3. Update level StateFlow (dBFS scale: -65 dB..-5 dB mapped linearly to 0f..1f, throttled to 100ms)
        val now = System.currentTimeMillis()
        if (now - lastLevelEmitMs >= 100L) {
            lastLevelEmitMs = now
            val dbfs = 20.0 * log10(max(rms, 1.0) / 32768.0)
            val normalized = ((dbfs - (-65.0)) / ((-5.0) - (-65.0))).toFloat().coerceIn(0f, 1f)
            _level.value = normalized
            _noiseFloorValue.value = currentFloor
            _thresholdValue.value = currentThreshold
            _timeSinceLastSpikeMs.value = if (lastSpikeTimeMs == 0L) -1L else (now - lastSpikeTimeMs)
        }

        // 4. Update ring buffer (pre-roll)
        preRollBuffer.write(chunk)

        // 5. Append chunk to any active post-roll captures
        for (capture in activeCaptures) {
            capture.postRollBuffer.write(chunk)
            if (capture.postRollBuffer.size() >= capture.neededBytes) {
                activeCaptures.remove(capture)
                finalizeClip(capture)
            }
        }

        // Check if freeze can be released
        if (activeCaptures.isEmpty() && (now - lastSpikeTimeMs) > COOLDOWN_MS) {
            unfreezeIfFrozen()
        }

        // 6. Spike detection (with 4000ms cooldown)
        if (now - lastSpikeTimeMs >= COOLDOWN_MS) {
            val isSpike = noiseFloor.isSpike(rms, sensitivity)
            if (isSpike) {
                lastSpikeTimeMs = now
                // Freeze noise floor during event
                _freezeEventCount.value++
                noiseFloor.setFrozen(true)

                // (a) IMMEDIATELY emit Tier 1 pre-alert
                _preAlerts.tryEmit(Unit)

                // (b) Snapshot pre-roll and start non-blocking post-roll capture
                val preRollSnapshot = preRollBuffer.snapshot()
                val capture = ActiveCapture(
                    preRoll = preRollSnapshot,
                    neededBytes = POST_ROLL_BYTES,
                    capturedAtMs = now
                )
                activeCaptures.add(capture)
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

            val clip = AudioClip(wav = wavBytes, capturedAtMs = capture.capturedAtMs)
            _lastCapturedClip.value = clip
            _clips.emit(clip)

            val now = System.currentTimeMillis()
            if (activeCaptures.isEmpty() && (now - lastSpikeTimeMs) > COOLDOWN_MS) {
                unfreezeIfFrozen()
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        _level.value = 0f
        activeCaptures.clear()
        unfreezeIfFrozen()

        stopAudioCapture()
        releaseWakeLock()

        serviceScope.cancel()

        super.onDestroy()
    }
}
