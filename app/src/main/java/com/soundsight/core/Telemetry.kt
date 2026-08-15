package com.soundsight.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil

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

    private val _failuresByCause = MutableStateFlow<Map<String, Int>>(emptyMap())
    val failuresByCause: StateFlow<Map<String, Int>> = _failuresByCause.asStateFlow()

    private val recentLatencies = mutableListOf<Long>()

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

    fun recordDrop() {
        _droppedClips.value++
    }
}
