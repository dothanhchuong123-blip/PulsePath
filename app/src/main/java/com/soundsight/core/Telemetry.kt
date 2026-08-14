package com.soundsight.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Telemetry {
    private val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    private val _totalClassifications = MutableStateFlow(0)
    val totalClassifications: StateFlow<Int> = _totalClassifications.asStateFlow()

    private val _droppedClips = MutableStateFlow(0)
    val droppedClips: StateFlow<Int> = _droppedClips.asStateFlow()

    private val _failureCount = MutableStateFlow(0)
    val failureCount: StateFlow<Int> = _failureCount.asStateFlow()

    private val _quotaBlockedCount = MutableStateFlow(0)
    val quotaBlockedCount: StateFlow<Int> = _quotaBlockedCount.asStateFlow()

    fun recordLatency(elapsedMs: Long) {
        _lastLatencyMs.value = elapsedMs
        _totalClassifications.value++
    }

    fun recordFailure() {
        _failureCount.value++
    }

    fun recordQuotaBlocked() {
        _quotaBlockedCount.value++
    }

    fun recordDrop() {
        _droppedClips.value++
    }
}
