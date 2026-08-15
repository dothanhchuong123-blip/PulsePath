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
import com.soundsight.core.Telemetry
import com.soundsight.data.ClientErrorException
import com.soundsight.data.SoundClassifier
import com.soundsight.domain.AlertPolicy
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.HapticPattern
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.SoundId
import com.soundsight.domain.Tier
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val consecutiveFailures: Int = 0
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
        }

        override fun onLost(network: Network) {
            _uiState.update { it.copy(isOnline = false) }
        }
    }

    private val _uiState = MutableStateFlow(
        HudState(
            phase = Phase.IDLE,
            current = null,
            history = emptyList(),
            language = AppLanguage.VI,
            sensitivity = ListenerService.sensitivity,
            isOnline = checkInitialOnline(),
            lastErrorKey = null,
            micUnavailable = ListenerService.micUnavailable.value,
            permissionRevoked = ListenerService.permissionRevoked.value,
            setupErrorSuspended = false,
            dailyQuotaExhausted = false,
            rateLimited = false,
            consecutiveFailures = 0
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

    init {
        // Load persisted sensitivity on startup
        viewModelScope.launch {
            try {
                val persistedSensitivity = OnboardingStore.sensitivityFlow(getApplication()).first()
                ListenerService.sensitivity = persistedSensitivity
                _uiState.update { it.copy(sensitivity = persistedSensitivity) }
            } catch (e: Exception) {
                Log.e("HudViewModel", "Failed to restore sensitivity: ${e.message}")
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
            ListenerService.preAlerts.collect {
                _effects.emit(Effect.Vibrate(HapticPattern.PRE_ALERT))
                _effects.emit(Effect.PreAlertFlash)
                _uiState.update { it.copy(phase = Phase.PRE_ALERT) }
                delay(400)
                _uiState.update { if (it.phase == Phase.PRE_ALERT) it.copy(phase = Phase.IDLE) else it }
            }
        }

        // 2. Collect ListenerService.clips.
        viewModelScope.launch {
            ListenerService.clips.collect { clip ->
                if (!AlertConfig.t2Enabled || _uiState.value.setupErrorSuspended || _uiState.value.rateLimited) {
                    return@collect
                }

                val result = SoundClassifier.classify(getApplication(), clip)
                result.fold(
                    onSuccess = { classificationResult ->
                        _uiState.update { it.copy(consecutiveFailures = 0, dailyQuotaExhausted = false) }
                        val event = SoundEvent.fromClassification(
                            result = classificationResult,
                            capturedAtMs = clip.capturedAtMs,
                            resolvedAtMs = System.currentTimeMillis()
                        )
                        val decision = alertPolicy.decide(
                            incoming = event,
                            current = _uiState.value.current,
                            nowMs = System.currentTimeMillis()
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

    private suspend fun handleDecision(decision: AlertPolicy.Decision) {
        when (decision) {
            is AlertPolicy.Decision.Takeover -> {
                val takeoverEvent = decision.event
                val presentation = AlertPresentation.forEvent(takeoverEvent)
                val newHistory = (listOf(takeoverEvent) + _uiState.value.history).take(20)
                _effects.emit(Effect.Vibrate(presentation.haptic))
                if (AlertConfig.t2TakesOverScreen) {
                    _uiState.update {
                        it.copy(
                            phase = Phase.ALERT,
                            current = takeoverEvent,
                            history = newHistory
                        )
                    }
                    startHoldTimer(presentation.holdMs)
                } else {
                    _uiState.update {
                        it.copy(current = takeoverEvent, history = newHistory)
                    }
                }
            }
            is AlertPolicy.Decision.MergeWithCurrent -> {
                _uiState.update {
                    it.copy(current = decision.event)
                }
            }
            is AlertPolicy.Decision.LogOnly -> {
                val newHistory = (listOf(decision.event) + _uiState.value.history).take(20)
                _uiState.update {
                    it.copy(history = newHistory)
                }
            }
            AlertPolicy.Decision.Discard -> {
                // No state change (Telemetry already recorded inside SoundClassifier/ListenerService)
            }
        }
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
                            history = newHistory
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
                current = null
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
                    current = null
                )
            }
        }
    }

    private fun checkInitialOnline(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }
}
