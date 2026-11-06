package com.soundsight.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.soundsight.core.AlertConfig
import com.soundsight.domain.Tier
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure decision policy for background siren playback.
 *
 * Rules:
 *  - Gated by [sirenEnabled] (rider's opt-in toggle in Settings).
 *  - NEVER fires while [isHudVisible] is true: the on-screen alert takeover already covers that case.
 *  - Only fires for [Tier.CRITICAL] events: vibration alone handles lower tiers while backgrounded.
 *  - Respects DND Total Silence ([NotificationManager.INTERRUPTION_FILTER_NONE]): no sound may escape total silence.
 *  - Respects Silent mode ([AudioManager.RINGER_MODE_SILENT]): rider explicitly muted the phone.
 *  - Requires non-zero alarm volume: a muted alarm stream cannot play.
 */
fun canPlayBackgroundSiren(
    sirenEnabled: Boolean,
    isHudVisible: Boolean,
    tier: Tier,
    interruptionFilter: Int,
    ringerMode: Int,
    alarmVolume: Int
): Boolean {
    if (!sirenEnabled) return false
    if (isHudVisible) return false
    if (tier != Tier.CRITICAL) return false
    if (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) return false
    if (ringerMode == AudioManager.RINGER_MODE_SILENT) return false
    if (alarmVolume <= 0) return false
    return true
}

/**
 * Manages playback of the audible siren alert on [AudioAttributes.USAGE_ALARM] / [AudioManager.STREAM_ALARM].
 *
 * Requests transient audio focus before playback and releases it when finished or stopped.
 *
 * The siren is synthesised, not shipped as an asset. The `res/raw/siren_alert.wav` it replaces
 * was itself a generated tone (a 3 s, 600-1400 Hz wail - byte-identical to the test-sound
 * `emergency_siren.wav`), so nothing audible changes; what changes is that the app no longer
 * carries a 264 KB binary that cannot travel through a text-only sync of the source, and the
 * playback path no longer depends on MediaPlayer decoding a file.
 */
object BackgroundSirenPlayer {
    private const val TAG = "BackgroundSirenPlayer"

    /** Output rate of the synthesised siren. 44.1 kHz is supported on every Android device. */
    const val SIREN_SAMPLE_RATE = 44_100

    /** Length of one siren burst. */
    const val SIREN_DURATION_MS = 3_000

    /** Centre of the wail and its deviation: the tone sweeps 600 Hz .. 1400 Hz. */
    const val SIREN_CENTRE_HZ = 1_000.0
    const val SIREN_DEVIATION_HZ = 400.0

    /** One up-and-down sweep takes this long. */
    const val SIREN_SWEEP_PERIOD_MS = 1_700

    /** Peak amplitude as a fraction of full scale, matching the asset it replaces. */
    const val SIREN_PEAK = 0.9

    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    /**
     * Renders the siren as 16-bit mono PCM: a sine carrier whose frequency itself follows a
     * sine between [SIREN_CENTRE_HZ] - [SIREN_DEVIATION_HZ] and [SIREN_CENTRE_HZ] +
     * [SIREN_DEVIATION_HZ] once every [SIREN_SWEEP_PERIOD_MS]. The phase is integrated sample
     * by sample so the sweep is click-free. Pure so it can be tested without a device.
     */
    fun synthesizeSirenPcm(
        sampleRate: Int = SIREN_SAMPLE_RATE,
        durationMs: Int = SIREN_DURATION_MS
    ): ShortArray {
        val n = (sampleRate.toLong() * durationMs / 1000L).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        val sweepOmega = 2.0 * PI / (SIREN_SWEEP_PERIOD_MS / 1000.0)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val freq = SIREN_CENTRE_HZ + SIREN_DEVIATION_HZ * sin(sweepOmega * t)
            // Sample first, then advance: the burst starts at exactly zero, so there is no
            // step at the first sample for the speaker to click on.
            out[i] = (sin(phase) * SIREN_PEAK * Short.MAX_VALUE).toInt().toShort()
            phase += 2.0 * PI * freq / sampleRate
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
        return out
    }

    /**
     * Checks eligibility and plays the siren alert if all conditions are met.
     *
     * @return true if playback started, false otherwise.
     */
    fun playIfEligible(
        context: Context,
        tier: Tier,
        isHudVisible: Boolean,
        sirenEnabled: Boolean = AlertConfig.sirenAlertInBackground
    ): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        val interruptionFilter = notificationManager?.currentInterruptionFilter
            ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val alarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0

        val eligible = canPlayBackgroundSiren(
            sirenEnabled = sirenEnabled,
            isHudVisible = isHudVisible,
            tier = tier,
            interruptionFilter = interruptionFilter,
            ringerMode = ringerMode,
            alarmVolume = alarmVolume
        )

        if (!eligible) {
            Log.d(
                TAG,
                "Siren skipped: enabled=$sirenEnabled, hudVisible=$isHudVisible, tier=$tier, " +
                    "filter=$interruptionFilter, ringerMode=$ringerMode, volume=$alarmVolume"
            )
            return false
        }

        return play(context)
    }

    /**
     * Requests audio focus on the alarm stream and starts playback of the synthesised siren.
     */
    fun play(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        synchronized(lock) {
            stop()

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            stop()
                        }
                    }
                    .build()
                activeFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }

            if (!focusGranted) {
                Log.w(TAG, "Audio focus request refused for alarm stream")
                activeFocusRequest = null
                return false
            }

            try {
                val pcm = synthesizeSirenPcm()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SIREN_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                // Static mode: the whole burst is written once and played from memory, so
                // there is no streaming thread to starve while the service is under load.
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                if (track.state != AudioTrack.STATE_NO_STATIC_DATA && track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack failed to initialise (state=${track.state})")
                    track.release()
                    abandonFocus(audioManager)
                    return false
                }
                val written = track.write(pcm, 0, pcm.size)
                if (written < pcm.size) {
                    Log.e(TAG, "AudioTrack accepted $written of ${pcm.size} samples")
                    track.release()
                    abandonFocus(audioManager)
                    return false
                }
                // Release focus and the track when the burst ends, the same way the old
                // MediaPlayer completion listener did.
                track.setNotificationMarkerPosition(pcm.size)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        synchronized(lock) {
                            if (audioTrack === t) stop(context)
                        }
                    }

                    override fun onPeriodicNotification(t: AudioTrack) = Unit
                })

                audioTrack = track
                track.play()
                Log.i(TAG, "Background siren alert playing on STREAM_ALARM")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting siren playback: ${e.message}", e)
                abandonFocus(audioManager)
                return false
            }
        }
    }

    /**
     * Immediately stops playback, releases the AudioTrack, and abandons audio focus.
     */
    fun stop(context: Context? = null) {
        synchronized(lock) {
            try {
                audioTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }

            context?.let { ctx ->
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.let { abandonFocus(it) }
            }
        }
    }

    private fun abandonFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        activeFocusRequest = null
    }
}
