package com.soundsight.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundsight.audio.AudioClip
import com.soundsight.audio.ListenerService
import com.soundsight.audio.Sensitivity
import com.soundsight.core.AlertConfig
import com.soundsight.core.AppLanguage
import com.soundsight.core.OnboardingStore
import com.soundsight.core.QuotaExhaustedException
import com.soundsight.core.ContrastBoostMode
import com.soundsight.core.SpeedSnapshot
import com.soundsight.core.Telemetry
import com.soundsight.light.LightSensor
import com.soundsight.data.ClientErrorException
import com.soundsight.data.SoundClassifier
import com.soundsight.domain.AlertPolicy
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.DirectionCorrelator
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.EventSource
import com.soundsight.domain.HapticPattern
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.SoundId
import com.soundsight.domain.Tier
import com.soundsight.ui.demo.resolveGatedSensitivity
import com.soundsight.ui.hud.PreAlertVisuals
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.soundsight.audio.local.LocalClassifier
import com.soundsight.audio.motion.FallDetector
import com.soundsight.audio.motion.MotionSensor
import com.soundsight.audio.local.LocalResult
import com.soundsight.audio.dsp.StereoAnalyzer
import com.soundsight.data.live.LiveClassificationEvent
import com.soundsight.data.live.LiveSession
import com.soundsight.data.live.LiveState
import com.soundsight.audio.liveness.LivenessLeaseManager
import com.soundsight.audio.liveness.LivenessState

enum class Phase { IDLE, PRE_ALERT, ALERT, DEGRADED }

sealed interface Effect {
    data class Vibrate(val pattern: HapticPattern) : Effect
    data object PreAlertFlash : Effect
}

data class HudState(
    val phase: Phase,
    val current: SoundEvent?,
    val history: List<SoundEvent>,          // newest first, max 20
    val language: AppLanguage,
    val sensitivity: Sensitivity,
    val isOnline: Boolean,
    val lastErrorKey: String?,
    val micUnavailable: Boolean = false,
    val permissionRevoked: Boolean = false,
    val setupErrorSuspended: Boolean = false,
    val dailyQuotaExhausted: Boolean = false,
    val rateLimited: Boolean = false,
    val consecutiveFailures: Int = 0,
    val liveState: LiveState = LiveState.CONNECTING,
    val livenessState: LivenessState = LivenessState.NOT_PROTECTING,
    /** See [com.soundsight.audio.liveness.LivenessSnapshot.riderIdle]: NOT_PROTECTING by choice. */
    val livenessRiderIdle: Boolean = true,
    /**
     * Which side [current] came from, when the stereo analyser could tell.
     *
     * Defaults to [DirectionEstimate.UNKNOWN] and stays there most of the time - portrait
     * orientation, calibration disabled, or a correlation the gate would not stand behind all
     * resolve to UNKNOWN, and each of those is a normal outcome rather than a fault. The UI draws
     * nothing at all in that case; see DirectionIndicator for why a placeholder would be worse
     * than an absence.
     */
    val direction: DirectionEstimate = DirectionEstimate.UNKNOWN,
    /**
     * The collision whose takeover just ended, if it is one the crash-response screen should
     * follow. Null for everything else.
     *
     * A CRITICAL collision is the one alert with an "after". Every other alert is finished when
     * its takeover is; this one hands off. The UI still decides whether to show anything - it only
     * does if the rider actually saved an emergency profile - so setting this is not a promise
     * that a screen appears.
     */
    val crashResponseFor: SoundEvent? = null
)

class HudViewModel(application: Application) : AndroidViewModel(application) {

    private val alertPolicy = AlertPolicy()
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var holdTimerJob: Job? = null
    private var rateLimitJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _uiState.update {
                val next = it.copy(isOnline = true)
                if (it.phase == Phase.DEGRADED) next.copy(phase = Phase.IDLE) else next
            }
            // This callback used to only repaint the status chip. The Live session was left to
            // rediscover the network by waiting out its own backoff, which is why switching
            // between wi-fi and mobile data looked like it never reconnected.
            if (AlertConfig.t2Enabled && ListenerService.isRunning.value) {
                LiveSession.onNetworkAvailable()
            }
        }

        override fun onLost(network: Network) {
            // A network being lost is not the same thing as being offline. Switching from wi-fi to
            // mobile data delivers onAvailable(mobile) *before* onLost(wifi), so taking this
            // callback at face value left the chip reading "Offline" on a phone with a perfectly
            // good connection - and, once the reconnect indicator was added, hid it behind an
            // offline label for the entire window it exists to cover. Re-ask the system instead.
            _uiState.update { it.copy(isOnline = hasInternetConnectivity()) }
        }
    }

    private val _uiState = MutableStateFlow(
        HudState(
            phase = Phase.IDLE,
            current = null,
            history = emptyList(),
            language = AppLanguage.VI,
            sensitivity = ListenerService.sensitivity,
            isOnline = hasInternetConnectivity(),
            lastErrorKey = null,
            micUnavailable = ListenerService.micUnavailable.value,
            permissionRevoked = ListenerService.permissionRevoked.value,
            setupErrorSuspended = false,
            dailyQuotaExhausted = false,
            rateLimited = false,
            consecutiveFailures = 0,
            livenessState = LivenessLeaseManager.snapshot.value.state,
            livenessRiderIdle = LivenessLeaseManager.snapshot.value.riderIdle
        )
    )
    val uiState: StateFlow<HudState> = _uiState.asStateFlow()

    @OptIn(FlowPreview::class)
    val micLevel: StateFlow<Float> = ListenerService.level
        .sample(100L)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 64)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val localClassifierMutex = Mutex()
    @Volatile private var lastSpikeClassificationMs: Long = 0L

    /** See [DirectionCorrelator] for why matching needs a cache rather than a latest-wins rule. */
    private val directionCorrelator = DirectionCorrelator()

    private fun directionFor(event: SoundEvent?): DirectionEstimate =
        directionCorrelator.directionFor(event?.capturedAtMs)

    init {
        LocalClassifier.init(getApplication())
        StereoAnalyzer.start(getApplication(), viewModelScope)

        // The stereo analyser has been running since P18 with nothing attached to its output.
        // This is the collector that finally reads it.
        viewModelScope.launch {
            StereoAnalyzer.results.collect { result ->
                directionCorrelator.record(result.capturedAtMs, result.estimate)
                // Late arrival: the alert is already on screen, so the arrow appears a moment
                // after the glyph rather than with it. Deliberately not suppressed - a direction
                // that lands 200ms into a 2.5s alert is still most of the alert's lifetime spent
                // showing it, and the alternative is showing nothing at all.
                _uiState.update {
                    if (directionCorrelator.matches(
                            it.current?.capturedAtMs,
                            result.capturedAtMs
                        )
                    ) {
                        it.copy(direction = result.estimate)
                    } else {
                        it
                    }
                }
            }
        }

        // Load persisted sensitivity on startup
        viewModelScope.launch {
            try {
                val persistedSensitivity = OnboardingStore.sensitivityFlow(getApplication()).first()
                val demoPanelVisited = OnboardingStore.demoPanelVisitedFlow(getApplication()).first()
                val resolution = resolveGatedSensitivity(persistedSensitivity, demoPanelVisited)
                if (resolution.wasClamped) {
                    OnboardingStore.setSensitivity(getApplication(), resolution.sensitivity)
                }
                ListenerService.sensitivity = resolution.sensitivity
                AlertConfig.demoFastAlert = resolution.demoFastAlert
                _uiState.update { it.copy(sensitivity = resolution.sensitivity) }
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to restore sensitivity: ${e.message}")
            }
        }

        // Load persisted motion-sensors preference, then keep MotionSensor's registration in
        // lockstep with monitoring on/off. The settings toggle (MainActivity) also calls
        // MotionSensor.start/stop directly for immediate effect while already running - both
        // paths are idempotent, so there is no ordering hazard between them.
        viewModelScope.launch {
            try {
                AlertConfig.motionSensorsEnabled =
                    OnboardingStore.motionSensorsEnabledFlow(getApplication()).first()
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to restore motion sensors setting: ${e.message}")
            }
            ListenerService.isRunning.collect { running ->
                if (running && AlertConfig.motionSensorsEnabled) {
                    MotionSensor.start(getApplication())
                } else {
                    MotionSensor.stop()
                }
            }
        }

        // A fall the motion sensors detected. MotionSensor is registered only while listening
        // with collision detection on, so nothing arrives here otherwise; if one did, policy's
        // DiscardCollisionOff would still catch it. Goes through the same decide() as an audio
        // collision on purpose: staleness, dedup against a collision already showing, and the
        // CRITICAL takeover -> hold -> crash-response hand-off are all reused, not re-invented.
        viewModelScope.launch {
            MotionSensor.fallEvents.collect { fall -> onFallDetected(fall) }
        }

        // Keep LightSensor's registration in lockstep with monitoring on/off and contrastBoostMode.
        // Registered only while listening in AUTO mode, preserving battery and copying MotionSensor's lifecycle.
        viewModelScope.launch {
            try {
                AlertConfig.contrastBoostMode =
                    OnboardingStore.contrastBoostModeFlow(getApplication()).first()
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to restore contrast boost mode: ${e.message}")
            }
            combine(
                ListenerService.isRunning,
                OnboardingStore.contrastBoostModeFlow(getApplication())
            ) { running, mode ->
                AlertConfig.contrastBoostMode = mode
                running to mode
            }.collect { (running, mode) ->
                if (running && mode == ContrastBoostMode.AUTO) {
                    LightSensor.start(getApplication())
                } else {
                    LightSensor.stop()
                }
            }
        }

        // Restore the presentation-layer preferences. These are read by Haptics and by the
        // HUD/takeover, not by AlertPolicy, so a failure to restore them degrades the experience
        // but never the detection: every default is the safe direction (vibration on, takeover on,
        // arrow off until the rider has calibrated it).
        viewModelScope.launch {
            try {
                val app = getApplication<android.app.Application>()
                AlertConfig.vibrationEnabled = OnboardingStore.vibrationEnabledFlow(app).first()
                AlertConfig.directionArrowEnabled =
                    OnboardingStore.directionArrowEnabledFlow(app).first()
                AlertConfig.arrowStyle = OnboardingStore.arrowStyleFlow(app).first()
                AlertConfig.directionLabelEnabled =
                    OnboardingStore.directionLabelEnabledFlow(app).first()
                AlertConfig.t2TakesOverScreen = OnboardingStore.takeoverEnabledFlow(app).first()
                AlertConfig.themeMode = OnboardingStore.themeModeFlow(app).first()
                AlertConfig.waveStyleDots = OnboardingStore.waveStyleDotsFlow(app).first()
                AlertConfig.keepMonitoringInBackground =
                    OnboardingStore.keepMonitoringInBackgroundFlow(app).first()
                AlertConfig.sirenAlertInBackground =
                    OnboardingStore.sirenAlertInBackgroundFlow(app).first()
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to restore alert presentation settings: ${e.message}")
            }
        }

        // Collect micUnavailable and permissionRevoked
        viewModelScope.launch {
            ListenerService.micUnavailable.collect { unavailable ->
                _uiState.update { it.copy(micUnavailable = unavailable) }
            }
        }

        viewModelScope.launch {
            ListenerService.permissionRevoked.collect { revoked ->
                _uiState.update { it.copy(permissionRevoked = revoked) }
            }
        }

        // Register NetworkCallback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e("HudViewModel", "Failed to register network callback: ${e.message}")
        }

        // 1. Collect ListenerService.preAlerts -> immediately emit Effect.Vibrate(PRE_ALERT)
        // and Effect.PreAlertFlash, set phase = PRE_ALERT. No suspending network work on this path, ever.
        viewModelScope.launch {
            ListenerService.preAlerts.collect { spikeAtMs ->
                // Demo/experience mode (Sensitivity.MAX): the spike itself is the alert. Skip the
                // 400ms pre-alert wash entirely and seize the screen now with a provisional card,
                // rather than flashing a border and then almost always going quiet because no
                // classification cleared its floor. See fireInstantSpikeAlert.
                if (AlertConfig.demoFastAlert) {
                    fireInstantSpikeAlert(spikeAtMs)
                    return@collect
                }
                _effects.emit(Effect.Vibrate(HapticPattern.PRE_ALERT))
                _effects.emit(Effect.PreAlertFlash)
                _uiState.update { it.copy(phase = Phase.PRE_ALERT) }
                delay(PreAlertVisuals.DURATION_MS)
                _uiState.update { if (it.phase == Phase.PRE_ALERT) it.copy(phase = Phase.IDLE) else it }
            }
        }

        // Collect LiveSession.liveState
        viewModelScope.launch {
            LiveSession.liveState.collect { state ->
                _uiState.update { it.copy(liveState = state) }
                syncCloudStateToLiveness()
            }
        }

        // Collect LivenessLeaseManager.snapshot
        viewModelScope.launch {
            LivenessLeaseManager.snapshot.collect { liveness ->
                _uiState.update { it.copy(livenessState = liveness.state, livenessRiderIdle = liveness.riderIdle) }
            }
        }

        // Collect LiveSession.events (Primary Live path)
        viewModelScope.launch {
            LiveSession.events.collect { liveEvent ->
                _uiState.update { it.copy(consecutiveFailures = 0, dailyQuotaExhausted = false) }
                val event = SoundEvent.fromClassification(
                    result = liveEvent.result,
                    capturedAtMs = liveEvent.capturedAtMs,
                    resolvedAtMs = System.currentTimeMillis(),
                    capturedMonotonicMs = liveEvent.capturedMonotonicMs,
                    speedSnapshot = SpeedSnapshot.capture()
                )
                val motionCorroborated = motionCorroboratesCollision(event)
                val decision = alertPolicy.decide(
                    incoming = event,
                    current = _uiState.value.current,
                    nowMs = System.currentTimeMillis(),
                    motionCorroborated = motionCorroborated
                )
                handleDecision(decision)
            }
        }

        // 2a. Collect early spike-triggered audio snapshots for prompt on-device YAMNet classification
        viewModelScope.launch {
            ListenerService.earlySnapshots.collect { snapshot ->
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        localClassifierMutex.withLock {
                            lastSpikeClassificationMs = System.currentTimeMillis()
                            val startMs = System.currentTimeMillis()
                            val localResult = LocalClassifier.classify(getApplication(), snapshot.pcm)
                            val elapsedMs = System.currentTimeMillis() - startMs
                            Telemetry.recordLocalInference(elapsedMs)

                            if (localResult != null) {
                                val shouldFire =
                                    AlertPolicy.admitsLocalCandidate(localResult.soundId, localResult.score)
                                if (shouldFire) {
                                    val localEvent = SoundEvent.fromLocal(
                                        soundId = localResult.soundId,
                                        score = localResult.score,
                                        capturedAtMs = snapshot.capturedAtMs,
                                        resolvedAtMs = System.currentTimeMillis(),
                                        capturedMonotonicMs = snapshot.capturedMonotonicMs,
                                        speedSnapshot = SpeedSnapshot.capture()
                                    )
                                    if (!_uiState.value.isOnline) {
                                        Telemetry.recordLocalOfflineAlert()
                                    }
                                    val motionCorroborated = motionCorroboratesCollision(localEvent)
                                    val decision = alertPolicy.decide(
                                        incoming = localEvent,
                                        current = _uiState.value.current,
                                        nowMs = System.currentTimeMillis(),
                                        motionCorroborated = motionCorroborated
                                    )
                                    handleDecision(decision)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HudViewModel", "Error in early local classifier: ${e.message}")
                    }
                }
            }
        }

        // 2b. Collect ListenerService.clips.
        viewModelScope.launch {
            ListenerService.clips.collect { clip ->
                // On-device MediaPipe YAMNet classification (Tier 1 on-device without blocking network or pre-alert)
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        localClassifierMutex.withLock {
                            lastSpikeClassificationMs = System.currentTimeMillis()
                            val startMs = System.currentTimeMillis()
                            val mono16k = ListenerService.extractMono16kFromWav(clip.wav)
                            val localResult = LocalClassifier.classify(getApplication(), mono16k)
                            val elapsedMs = System.currentTimeMillis() - startMs
                            Telemetry.recordLocalInference(elapsedMs)

                            if (localResult != null) {
                                // Local candidate pre-filter, shared with the periodic path
                                // below via AlertPolicy.admitsLocalCandidate: canonical
                                // COLLISION_IMPACT is admitted down to
                                // MIN_LOCAL_COLLISION_RESCUE_CONFIDENCE so a 0.60-0.79 local
                                // collision reaches policy (becoming WARNING without motion,
                                // CRITICAL with it) instead of being silently discarded before
                                // policy can evaluate it - which was the opposite defect from
                                // the cloud path's unbounded rescue. Other CRITICAL sound IDs
                                // keep the MIN_CRITICAL_CONFIDENCE bar.
                                val shouldFire =
                                    AlertPolicy.admitsLocalCandidate(localResult.soundId, localResult.score)
                                if (shouldFire) {
                                    val localEvent = SoundEvent.fromLocal(
                                        soundId = localResult.soundId,
                                        score = localResult.score,
                                        capturedAtMs = clip.capturedAtMs,
                                        resolvedAtMs = System.currentTimeMillis(),
                                        capturedMonotonicMs = clip.capturedMonotonicMs,
                                        speedSnapshot = SpeedSnapshot.capture()
                                    )
                                    if (!_uiState.value.isOnline) {
                                        Telemetry.recordLocalOfflineAlert()
                                    }
                                    val motionCorroborated = motionCorroboratesCollision(localEvent)
                                    val decision = alertPolicy.decide(
                                        incoming = localEvent,
                                        current = _uiState.value.current,
                                        nowMs = System.currentTimeMillis(),
                                        motionCorroborated = motionCorroborated
                                    )
                                    handleDecision(decision)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HudViewModel", "Error in clip local classifier: ${e.message}")
                    }
                }

                if (!AlertConfig.t2Enabled || _uiState.value.setupErrorSuspended || _uiState.value.rateLimited) {
                    return@collect
                }

                // If LiveSession is ready or connecting/sending and not in FAILED state, try Live first
                val currentLiveState = LiveSession.liveState.value

                // OFFLINE is the coordinator's debounced verdict that there is no network at all.
                // Both cloud paths below are then guaranteed to fail: sendClip refuses because the
                // state is not READY, and the REST fallback becomes one doomed HTTP request per
                // clip - which wakes the radio, climbs consecutiveFailures until the chip degrades
                // to "buzz only", and tells the rider nothing the offline chip has not already
                // said. The on-device classifier above has already run on this clip and keeps
                // alerting throughout, which is the whole point of having it.
                if (currentLiveState == LiveState.OFFLINE) return@collect

                var sentViaLive = false
                if (currentLiveState != LiveState.FAILED) {
                    sentViaLive = LiveSession.sendClip(clip)
                }

                // If LiveSession did not accept the clip (connecting, reconnecting, busy sending, or failed), fallback to REST SoundClassifier
                if (!sentViaLive) {
                    val result = SoundClassifier.classify(getApplication(), clip)
                    result.fold(
                        onSuccess = { classificationResult ->
                            _uiState.update { it.copy(consecutiveFailures = 0, dailyQuotaExhausted = false) }
                            val event = SoundEvent.fromClassification(
                                result = classificationResult,
                                capturedAtMs = clip.capturedAtMs,
                                resolvedAtMs = System.currentTimeMillis(),
                                capturedMonotonicMs = clip.capturedMonotonicMs,
                                speedSnapshot = SpeedSnapshot.capture()
                            )
                            val motionCorroborated = motionCorroboratesCollision(event)
                            val decision = alertPolicy.decide(
                                incoming = event,
                                current = _uiState.value.current,
                                nowMs = System.currentTimeMillis(),
                                motionCorroborated = motionCorroborated
                            )
                            handleDecision(decision)
                        },
                        onFailure = { error ->
                            handleClassifyFailure(error)
                        }
                    )
                }
            }
        }

        // 3. Periodic on-device classification demoted to an idle sweep: skips when a spike
        // classification ran recently, cutting constant CPU load and avoiding unsynchronized windows.
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                if (!ListenerService.isRunning.value) continue
                if (localClassifierMutex.isLocked) continue
                val now = System.currentTimeMillis()
                if (now - lastSpikeClassificationMs < IDLE_SWEEP_SUPPRESSION_MS) continue

                if (localClassifierMutex.tryLock()) {
                    try {
                        val pcm = ListenerService.getRecentAudioMono16k()
                        if (pcm != null && pcm.size >= 8000) {
                            val startMs = System.currentTimeMillis()
                            val startMonotonicMs = MotionSensor.nowMonotonicMs()
                            val localResult = LocalClassifier.classify(getApplication(), pcm)
                            val elapsedMs = System.currentTimeMillis() - startMs
                            Telemetry.recordLocalInference(elapsedMs)

                            if (localResult != null) {
                                // Same shared pre-filter as the clip path above. This periodic
                                // path has no AudioClip, so its capture instant is approximated
                                // by classification start - the audio in the recent-buffer
                                // snapshot ends at about that moment. The same approximation
                                // already applied to capturedAtMs; capturedMonotonicMs mirrors
                                // it in the sensor timestamp domain so motion co-occurrence
                                // stays physically comparable.
                                val shouldFire =
                                    AlertPolicy.admitsLocalCandidate(localResult.soundId, localResult.score)
                                if (shouldFire) {
                                    val localEvent = SoundEvent.fromLocal(
                                        soundId = localResult.soundId,
                                        score = localResult.score,
                                        capturedAtMs = startMs,
                                        resolvedAtMs = System.currentTimeMillis(),
                                        capturedMonotonicMs = startMonotonicMs
                                    )
                                    if (!_uiState.value.isOnline) {
                                        Telemetry.recordLocalOfflineAlert()
                                    }
                                    val motionCorroborated = motionCorroboratesCollision(localEvent)
                                    val decision = alertPolicy.decide(
                                        incoming = localEvent,
                                        current = _uiState.value.current,
                                        nowMs = System.currentTimeMillis(),
                                        motionCorroborated = motionCorroborated
                                    )
                                    handleDecision(decision)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HudViewModel", "Error in periodic local classifier: ${e.message}")
                    } finally {
                        localClassifierMutex.unlock()
                    }
                }
            }
        }
    }

    private fun handleClassifyFailure(error: Throwable) {
        when {
            error is QuotaExhaustedException -> {
                Telemetry.recordFailureByCause("quota_exhausted")
                _uiState.update {
                    it.copy(
                        dailyQuotaExhausted = true,
                        lastErrorKey = "quota_exhausted"
                    )
                }
            }
            error is ClientErrorException && (error.code == 401 || error.code == 403) -> {
                Telemetry.recordFailureByCause("setup_error")
                _uiState.update {
                    it.copy(
                        setupErrorSuspended = true,
                        lastErrorKey = "setup_error"
                    )
                }
            }
            error is ClientErrorException && error.code == 429 -> {
                Telemetry.recordFailureByCause("rate_limited")
                rateLimitJob?.cancel()
                _uiState.update {
                    it.copy(
                        rateLimited = true,
                        lastErrorKey = "rate_limited"
                    )
                }
                rateLimitJob = viewModelScope.launch {
                    delay(30_000L)
                    _uiState.update { it.copy(rateLimited = false) }
                }
            }
            else -> {
                val key = when (error) {
                    is java.net.SocketTimeoutException -> "timeout"
                    is java.net.UnknownHostException -> "unreachable"
                    else -> "classify_failed"
                }
                Telemetry.recordFailureByCause(key)
                _uiState.update {
                    val nextFailures = it.consecutiveFailures + 1
                    val nextState = it.copy(
                        consecutiveFailures = nextFailures,
                        lastErrorKey = key
                    )
                    if (!it.isOnline) {
                        nextState.copy(phase = Phase.DEGRADED)
                    } else {
                        nextState
                    }
                }
            }
        }
    }

    /**
     * Turns a [FallDetector.FallEvent] into the collision event the rest of the app knows how to
     * show. The capture instant is the impact, not the moment the rule was satisfied - the rule
     * needs at least 1.5 s of stillness after the impact (up to 10 s for a bike that keeps
     * moving), so the event is already that old when it gets here, and STALE_MS (12 s) is
     * measured from capture. The wall-clock stamp is derived from
     * the monotonic one so the two describe the same instant.
     */
    private suspend fun onFallDetected(fall: FallDetector.FallEvent) {
        val nowWallMs = System.currentTimeMillis()
        val nowMonotonicMs = MotionSensor.nowMonotonicMs()
        val capturedAtMs = nowWallMs - (nowMonotonicMs - fall.impactAtMs)
        Log.i(
            "HudViewModel",
            "Fall detected: peak %.1f m/s², tilt %.0f°, still %d ms, impact %d ms ago".format(
                fall.peakAccelDeltaMs2, fall.tiltDegrees, fall.stillMs, nowMonotonicMs - fall.impactAtMs
            )
        )
        Telemetry.recordFallDetected()
        val event = SoundEvent.fromFall(
            capturedAtMs = capturedAtMs,
            resolvedAtMs = nowWallMs,
            capturedMonotonicMs = fall.impactAtMs,
            speedSnapshot = SpeedSnapshot.capture()
        )
        val decision = alertPolicy.decide(
            incoming = event,
            current = _uiState.value.current,
            nowMs = nowWallMs,
            // The event is the motion evidence; see AlertPolicy.collisionRescueFloor for MOTION.
            motionCorroborated = true
        )
        handleDecision(decision)
    }

    /**
     * Checks whether a real accelerometer+gyroscope motion spike (MotionSensor) occurred close
     * to [event]'s capture time, and records telemetry when it did. Only COLLISION_IMPACT can
     * be corroborated by motion - see AlertPolicy.decide's motionCorroborated doc for why.
     *
     * Judged ONLY in the monotonic domain, and only for events that actually carry a capture
     * instant in that domain: a null [SoundEvent.capturedMonotonicMs] means the event was
     * constructed by hand (demo, test alert, file replay), and hand-built events must never be
     * corroborated into escalation. The wall-clock capturedAtMs is never consulted here.
     */
    private fun motionCorroboratesCollision(event: SoundEvent): Boolean {
        if (event.soundId != SoundId.COLLISION_IMPACT) return false
        val aroundMonotonicMs = event.capturedMonotonicMs ?: return false
        val corroborated = MotionSensor.hasRecentSpike(aroundMonotonicMs)
        if (corroborated) {
            Telemetry.recordMotionCorroboration()
        }
        return corroborated
    }

    private suspend fun handleDecision(decision: AlertPolicy.Decision) {
        when (decision) {
            is AlertPolicy.Decision.Takeover -> {
                val takeoverEvent = decision.event
                Telemetry.recordClassifiedEvent(takeoverEvent.soundId.name)
                val presentation = AlertPresentation.forEvent(takeoverEvent)
                val newHistory = (listOf(takeoverEvent) + _uiState.value.history).take(20)
                _effects.emit(Effect.Vibrate(presentation.haptic))
                if (takeoverEvent.tier == Tier.CRITICAL) {
                    com.soundsight.audio.BackgroundSirenPlayer.playIfEligible(
                        context = getApplication(),
                        tier = takeoverEvent.tier,
                        isHudVisible = ListenerService.isHudVisible.value,
                        sirenEnabled = AlertConfig.sirenAlertInBackground
                    )
                }
                val direction = directionFor(takeoverEvent)
                if (AlertConfig.t2TakesOverScreen) {
                    _uiState.update {
                        it.copy(
                            phase = Phase.ALERT,
                            current = takeoverEvent,
                            history = newHistory,
                            direction = direction
                        )
                    }
                    startHoldTimer(presentation.holdMs)
                } else {
                    _uiState.update {
                        it.copy(
                            current = takeoverEvent,
                            history = newHistory,
                            direction = direction
                        )
                    }
                }
            }
            is AlertPolicy.Decision.MergeWithCurrent -> {
                Telemetry.recordClassifiedEvent(decision.event.soundId.name)
                if (decision.event.tier == Tier.CRITICAL) {
                    com.soundsight.audio.BackgroundSirenPlayer.playIfEligible(
                        context = getApplication(),
                        tier = decision.event.tier,
                        isHudVisible = ListenerService.isHudVisible.value,
                        sirenEnabled = AlertConfig.sirenAlertInBackground
                    )
                }
                // A merge re-reads the cache rather than keeping the old arrow: the merged event
                // carries the newer capture's timestamp, and the newer capture is the one the
                // rider is being warned about now.
                _uiState.update {
                    it.copy(
                        current = decision.event,
                        direction = directionFor(decision.event)
                    )
                }
            }
            is AlertPolicy.Decision.LogOnly -> {
                Telemetry.recordClassifiedEvent(decision.event.soundId.name)
                val newHistory = (listOf(decision.event) + _uiState.value.history).take(20)
                _uiState.update {
                    it.copy(history = newHistory)
                }
            }
            AlertPolicy.Decision.Discard -> {
                // No state change, but the discard itself must be recorded. The classification
                // telemetry inside SoundClassifier/ListenerService only says a clip was
                // classified; without this counter a dropped alert leaves no trace at all and
                // looks identical to silence.
                Telemetry.recordStaleDiscard()
            }
            AlertPolicy.Decision.DiscardCollisionOff -> {
                // Counted apart from the stale discard above. This one is the collision-detection
                // switch doing exactly what the rider asked, and rolling it into the staleness
                // counter would read in the Demo panel as a pipeline fault instead of a setting.
                Telemetry.recordCollisionSuppressed()
            }
        }
    }

    /**
     * Demo-mode fast path: put a full alert screen up on the bare acoustic spike, before any
     * classifier has run.
     *
     * Only reachable when [AlertConfig.demoFastAlert] is set, i.e. the rider has dragged the
     * sensitivity slider to its extended Max stop. It exists because of a mismatch that made Max
     * look broken: the spike gate fired reliably (that is the buzz and the border a demo already
     * produced), but the takeover needs a *classified* hazard, and on a laptop speaker YAMNet's
     * top score lands around 0.30-0.60 - under the 0.70/0.80 floors - while the cloud path's
     * answer arrives ~7s later, past ACTIONABLE_MS. So the screen almost never appeared.
     *
     * The card is deliberately provisional, not a fabricated hazard: UNKNOWN soundId, WARNING
     * tier, confidence 0.0, and a caption that says only what is actually known ("loud sound
     * detected"). Nothing here claims to have recognised anything. It is also NOT written to
     * history - a spike is not an event, and one history row per door slam would bury the real
     * ones. When a real classification lands inside the hold window it arrives through the normal
     * [handleDecision] Takeover path, which replaces `current` and restarts the hold, so the card
     * upgrades in place from "Unknown sound" to the actual label instead of the alert being over
     * before the label exists. Dedup cannot swallow that upgrade: AlertPolicy never dedups
     * against SoundId.UNKNOWN.
     *
     * Haptic is [HapticPattern.WARNING] (180-80-180), not PRE_ALERT: a single 60ms tick is not
     * something a rider feels through a bar mount and a glove, and this path is the one that is
     * meant to be felt.
     *
     * @param spikeAtMs the spike's wall-clock time, straight from [ListenerService.preAlerts].
     * This is the SAME key the stereo analyser stamps its verdict with, and stamping the
     * provisional card with it is what lets the direction arrow appear on it. Previously this
     * method called System.currentTimeMillis() itself; that value matched no capture, so
     * [DirectionCorrelator] returned UNKNOWN for the card and also refused every late estimate,
     * meaning the arrow could never be shown in demo mode - the one mode built for showing it.
     */
    private suspend fun fireInstantSpikeAlert(spikeAtMs: Long) {
        val now = System.currentTimeMillis()
        val provisional = SoundEvent(
            soundId = SoundId.UNKNOWN,
            tier = Tier.WARNING,
            confidence = 0.0,
            captionVi = "Phát hiện tiếng động lớn",
            captionEn = "Loud sound detected",
            capturedAtMs = spikeAtMs,
            resolvedAtMs = now,
            repeatCount = 1,
            isDemo = false,
            source = EventSource.LOCAL,
            // No monotonic stamp on purpose: this is not a captured clip, and a provisional card
            // must never be eligible for motion corroboration into a collision.
            capturedMonotonicMs = null
        )
        _effects.emit(Effect.Vibrate(HapticPattern.WARNING))
        _uiState.update {
            it.copy(
                phase = Phase.ALERT,
                current = provisional,
                direction = directionFor(provisional)
            )
        }
        startHoldTimer(AlertPresentation.getHoldMs(Tier.WARNING))
    }

    fun fireTestAlert() {
        val now = System.currentTimeMillis()
        val event = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 1.0,
            captionVi = "CÒI HƠI XE TẢI (thử)",
            captionEn = "TRUCK AIR HORN (test)",
            capturedAtMs = now,
            resolvedAtMs = now,
            repeatCount = 1
        )
        val decision = alertPolicy.decide(
            incoming = event,
            current = _uiState.value.current,
            nowMs = now
        )
        viewModelScope.launch {
            when (decision) {
                is AlertPolicy.Decision.Takeover -> {
                    val takeoverEvent = decision.event
                    val presentation = AlertPresentation.forEvent(takeoverEvent)
                    val newHistory = (listOf(takeoverEvent) + _uiState.value.history).take(20)
                    _effects.emit(Effect.Vibrate(presentation.haptic))
                    // Test alert bypasses AlertConfig.t2TakesOverScreen and always takes over
                    _uiState.update {
                        it.copy(
                            phase = Phase.ALERT,
                            current = takeoverEvent,
                            history = newHistory,
                            // A synthetic event has no capture behind it, so there is no real
                            // estimate to attach. Showing a fabricated arrow in the one place a
                            // rider goes to learn what an alert looks like would teach them to
                            // expect a direction that the live path often cannot provide.
                            direction = DirectionEstimate.UNKNOWN
                        )
                    }
                    startHoldTimer(presentation.holdMs)
                }
                is AlertPolicy.Decision.MergeWithCurrent -> {
                    handleDecision(decision)
                }
                is AlertPolicy.Decision.LogOnly -> {
                    handleDecision(decision)
                }
                AlertPolicy.Decision.Discard -> {
                    handleDecision(decision)
                }
                AlertPolicy.Decision.DiscardCollisionOff -> {
                    // Unreachable in practice - the test alert is a TRUCK_AIR_HORN, not a
                    // collision - but routed the same way so this stays correct if the test
                    // event's soundId is ever changed.
                    handleDecision(decision)
                }
            }
        }
    }

    fun injectDemoEvent(soundId: SoundId) {
        viewModelScope.launch {
            delay(2200L)
            val now = System.currentTimeMillis()
            val tier = SoundEvent.canonicalTier(soundId)
            val event = SoundEvent.fromStrings(
                soundIdStr = soundId.name,
                tierStr = tier.name,
                confidence = 0.9,
                captionVi = AlertPresentation.getLabelVi(soundId),
                captionEn = AlertPresentation.getLabelEn(soundId),
                capturedAtMs = now,
                resolvedAtMs = now,
                repeatCount = 1,
                isDemo = true
            )
            val decision = alertPolicy.decide(
                incoming = event,
                current = _uiState.value.current,
                nowMs = now
            )
            handleDecision(decision)
        }
    }

    fun replayFromFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to read audio file: ${e.message}")
                null
            }
            if (bytes == null || bytes.isEmpty()) {
                return@launch
            }

            val clip = AudioClip(wav = bytes, capturedAtMs = System.currentTimeMillis())
            val result = SoundClassifier.classify(context, clip)
            result.fold(
                onSuccess = { classificationResult ->
                    _uiState.update { it.copy(consecutiveFailures = 0, dailyQuotaExhausted = false) }
                    val now = System.currentTimeMillis()
                    val event = SoundEvent.fromClassification(
                        result = classificationResult,
                        capturedAtMs = clip.capturedAtMs,
                        resolvedAtMs = now,
                        isDemo = true
                    )
                    val decision = alertPolicy.decide(
                        incoming = event,
                        current = _uiState.value.current,
                        nowMs = now
                    )
                    handleDecision(decision)
                },
                onFailure = { error ->
                    handleClassifyFailure(error)
                }
            )
        }
    }

    fun dismissAlert() {
        holdTimerJob?.cancel()
        holdTimerJob = null
        _uiState.update {
            it.copy(
                phase = Phase.IDLE,
                current = null,
                direction = DirectionEstimate.UNKNOWN,
                crashResponseFor = crashCandidate(it.current)
            )
        }
    }

    private fun startHoldTimer(holdMs: Long) {
        holdTimerJob?.cancel()
        holdTimerJob = viewModelScope.launch {
            delay(holdMs)
            _uiState.update {
                it.copy(
                    phase = Phase.IDLE,
                    current = null,
                    direction = DirectionEstimate.UNKNOWN,
                    crashResponseFor = crashCandidate(it.current)
                )
            }
        }
    }

    private fun crashCandidate(event: SoundEvent?): SoundEvent? = event?.takeIf {
        it.soundId == SoundId.COLLISION_IMPACT && it.tier == Tier.CRITICAL && !it.isDemo
    }

    fun dismissCrashResponse() {
        _uiState.update { it.copy(crashResponseFor = null) }
    }

    fun triggerDemoCrashResponse() {
        val now = System.currentTimeMillis()
        val event = SoundEvent.fromStrings(
            soundIdStr = SoundId.COLLISION_IMPACT.name,
            tierStr = Tier.CRITICAL.name,
            confidence = 0.95,
            captionVi = AlertPresentation.getLabelVi(SoundId.COLLISION_IMPACT),
            captionEn = AlertPresentation.getLabelEn(SoundId.COLLISION_IMPACT),
            capturedAtMs = now,
            resolvedAtMs = now,
            repeatCount = 1,
            isDemo = true
        )
        _uiState.update { it.copy(crashResponseFor = event) }
    }

    fun previewCrashResponse() = triggerDemoCrashResponse()

    private var hasAcknowledgedDemoDisclaimer: Boolean = false

    fun hasAcknowledgedDemoDisclaimer(): Boolean = hasAcknowledgedDemoDisclaimer

    fun acknowledgeDemoDisclaimer() {
        hasAcknowledgedDemoDisclaimer = true
    }

    /**
     * Records that Demo & Diagnostics has been opened, unlocking [Sensitivity.MAX] persistently.
     */
    fun markDemoPanelVisited() {
        viewModelScope.launch {
            try {
                OnboardingStore.setDemoPanelVisited(getApplication(), true)
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to persist demoPanelVisited: ${e.message}")
            }
        }
    }

    /** Whether the device has a network with internet capability right now. */
    private fun hasInternetConnectivity(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Synchronizes current network and LiveSession status into the LivenessLeaseManager.
     */
    fun syncCloudStateToLiveness() {
        val s = _uiState.value
        val isCloudOnline = s.isOnline && s.liveState != LiveState.OFFLINE && !s.dailyQuotaExhausted && !s.setupErrorSuspended
        val isCloudConnecting = s.liveState == LiveState.CONNECTING || s.liveState == LiveState.RECONNECTING
        val isCloudDegraded = s.rateLimited || s.consecutiveFailures >= 6 || s.liveState == LiveState.FAILED
        LivenessLeaseManager.updateCloudState(
            context = getApplication(),
            cloudOnline = isCloudOnline,
            cloudConnecting = isCloudConnecting,
            cloudDegraded = isCloudDegraded
        )
    }

    override fun onCleared() {
        super.onCleared()
        try {
            LightSensor.stop()
        } catch (_: Exception) {}
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private companion object {
        const val IDLE_SWEEP_SUPPRESSION_MS = 4000L
    }
}
