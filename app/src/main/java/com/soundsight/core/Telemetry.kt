package com.soundsight.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil

object Telemetry {
    private val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    private val _lastLiveLatencyMs = MutableStateFlow(0L)
    val lastLiveLatencyMs: StateFlow<Long> = _lastLiveLatencyMs.asStateFlow()

    private val _totalClassifications = MutableStateFlow(0)
    val totalClassifications: StateFlow<Int> = _totalClassifications.asStateFlow()

    private val _totalLiveClassifications = MutableStateFlow(0)
    val totalLiveClassifications: StateFlow<Int> = _totalLiveClassifications.asStateFlow()

    private val _droppedClips = MutableStateFlow(0)
    val droppedClips: StateFlow<Int> = _droppedClips.asStateFlow()

    private val _failureCount = MutableStateFlow(0)
    val failureCount: StateFlow<Int> = _failureCount.asStateFlow()

    private val _quotaBlockedCount = MutableStateFlow(0)
    val quotaBlockedCount: StateFlow<Int> = _quotaBlockedCount.asStateFlow()

    /**
     * Alerts thrown away by AlertPolicy for arriving too late. This was previously invisible:
     * a classified truck air horn could be dropped and leave no trace anywhere, so nobody
     * could tell the difference between "no sound happened" and "the warning was discarded".
     */
    private val _staleDiscardCount = MutableStateFlow(0)
    val staleDiscardCount: StateFlow<Int> = _staleDiscardCount.asStateFlow()

    /**
     * How many times a COLLISION_IMPACT classification co-occurred with a real accelerometer+
     * gyroscope motion spike close in time. Diagnostic only - useful during real-world
     * calibration to see how often corroboration is even available, separate from whether it
     * changed an AlertPolicy decision.
     */
    private val _motionCorroborationCount = MutableStateFlow(0)
    val motionCorroborationCount: StateFlow<Int> = _motionCorroborationCount.asStateFlow()

    /**
     * Collision alerts suppressed because the rider has collision detection switched off.
     *
     * Deliberately not folded into [staleDiscardCount]. That counter means "the pipeline was too
     * slow"; this one means "the setting did what it was asked". Summing them would make a working
     * toggle look like a performance problem, and would leave nothing at all to distinguish a
     * suppressed crash from a quiet street.
     */
    private val _collisionSuppressedCount = MutableStateFlow(0)
    val collisionSuppressedCount: StateFlow<Int> = _collisionSuppressedCount.asStateFlow()

    private val _fallDetectedCount = MutableStateFlow(0)
    val fallDetectedCount: StateFlow<Int> = _fallDetectedCount.asStateFlow()

    private val _failuresByCause = MutableStateFlow<Map<String, Int>>(emptyMap())
    val failuresByCause: StateFlow<Map<String, Int>> = _failuresByCause.asStateFlow()

    /** Detections by [com.soundsight.domain.SoundId] name, for the demo panel's statistics section. */
    private val _eventsBySoundId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val eventsBySoundId: StateFlow<Map<String, Int>> = _eventsBySoundId.asStateFlow()

    private val _strayAudioCount = MutableStateFlow(0)
    val strayAudioCount: StateFlow<Int> = _strayAudioCount.asStateFlow()

    private val _strayAudioBytesTotal = MutableStateFlow(0L)
    val strayAudioBytesTotal: StateFlow<Long> = _strayAudioBytesTotal.asStateFlow()

    private val _localInferenceCount = MutableStateFlow(0)
    val localInferenceCount: StateFlow<Int> = _localInferenceCount.asStateFlow()

    private val _localOfflineAlertsCount = MutableStateFlow(0)
    val localOfflineAlertsCount: StateFlow<Int> = _localOfflineAlertsCount.asStateFlow()

    private val _localMeanInferenceTimeMs = MutableStateFlow(0.0)
    val localMeanInferenceTimeMs: StateFlow<Double> = _localMeanInferenceTimeMs.asStateFlow()

    private var totalLocalInferenceTimeMs: Long = 0L

    private val recentLatencies = mutableListOf<Long>()
    private val recentLiveLatencies = mutableListOf<Long>()

    fun recordLocalInference(elapsedMs: Long) {
        _localInferenceCount.update { count ->
            val newCount = count + 1
            totalLocalInferenceTimeMs += elapsedMs
            _localMeanInferenceTimeMs.value = totalLocalInferenceTimeMs.toDouble() / newCount
            newCount
        }
    }

    fun recordLocalOfflineAlert() {
        _localOfflineAlertsCount.update { it + 1 }
    }

    fun meanLocalInferenceMs(): Double = _localMeanInferenceTimeMs.value

    fun recordLatency(elapsedMs: Long) {
        _lastLatencyMs.value = elapsedMs
        _totalClassifications.value++
        synchronized(recentLatencies) {
            recentLatencies.add(elapsedMs)
            if (recentLatencies.size > 100) {
                recentLatencies.removeAt(0)
            }
        }
    }

    fun recordLiveLatency(elapsedMs: Long) {
        _lastLiveLatencyMs.value = elapsedMs
        _totalLiveClassifications.value++
        synchronized(recentLiveLatencies) {
            recentLiveLatencies.add(elapsedMs)
            if (recentLiveLatencies.size > 100) {
                recentLiveLatencies.removeAt(0)
            }
        }
    }

    fun recordStrayAudioBytes(byteCount: Int) {
        _strayAudioCount.update { it + 1 }
        _strayAudioBytesTotal.update { it + byteCount }
    }

    fun livePercentile(p: Double): Long {
        val copy = synchronized(recentLiveLatencies) {
            recentLiveLatencies.toList()
        }
        if (copy.isEmpty()) return 0L
        val sorted = copy.sorted()
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    fun liveLatencyHistory(): List<Long> {
        return synchronized(recentLiveLatencies) {
            recentLiveLatencies.toList()
        }
    }

    fun percentile(p: Double): Long {
        val copy = synchronized(recentLatencies) {
            recentLatencies.toList()
        }
        if (copy.isEmpty()) return 0L
        val sorted = copy.sorted()
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    fun latencyHistory(): List<Long> {
        return synchronized(recentLatencies) {
            recentLatencies.toList()
        }
    }

    fun recordFailureByCause(cause: String) {
        _failuresByCause.update { current ->
            val count = current[cause] ?: 0
            current + (cause to (count + 1))
        }
    }

    fun recordFailure() {
        _failureCount.value++
    }

    fun recordQuotaBlocked() {
        _quotaBlockedCount.value++
    }

    /**
     * Counts Live socket closes by cause.
     *
     * [quotaBlockedCount] only ever had one caller - the REST path's HTTP 429 - so a Live session
     * closed for quota incremented nothing at all and left only a transient string behind. The
     * cause is kept as a String because the enum lives in :data.live and this object is in :core.
     */
    private val _liveCloseKindCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val liveCloseKindCounts: StateFlow<Map<String, Int>> = _liveCloseKindCounts.asStateFlow()

    fun recordLiveCloseKind(kind: String) {
        _liveCloseKindCounts.update { current -> current + (kind to ((current[kind] ?: 0) + 1)) }
    }

    fun recordDrop() {
        _droppedClips.value++
    }

    fun recordStaleDiscard() {
        _staleDiscardCount.value++
    }

    fun recordMotionCorroboration() {
        _motionCorroborationCount.value++
    }

    fun recordCollisionSuppressed() {
        _collisionSuppressedCount.value++
    }

    /** A fall the motion-only rule reported (before policy; a suppressed one still counts here). */
    fun recordFallDetected() {
        _fallDetectedCount.value++
    }

    fun recordClassifiedEvent(soundIdName: String) {
        _eventsBySoundId.update { current ->
            val count = current[soundIdName] ?: 0
            current + (soundIdName to (count + 1))
        }
    }

    fun resetForTesting() {
        _lastLatencyMs.value = 0L
        _lastLiveLatencyMs.value = 0L
        _totalClassifications.value = 0
        _totalLiveClassifications.value = 0
        synchronized(recentLatencies) { recentLatencies.clear() }
        synchronized(recentLiveLatencies) { recentLiveLatencies.clear() }
    }
}
