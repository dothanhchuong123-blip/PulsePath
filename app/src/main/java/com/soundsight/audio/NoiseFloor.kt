package com.soundsight.audio

enum class Sensitivity(val multiplier: Double) {
    LOW(2.6),
    MEDIUM(2.0),
    HIGH(1.6)
}

class NoiseFloor(
    private val windowSeconds: Double = 15.0,
    private val minFloor: Double = 1200.0
) {
    private val history = ArrayDeque<Pair<Long, Double>>()
    private var isFrozen: Boolean = false

    val isFrozenNow: Boolean
        get() = isFrozen

    @Synchronized
    fun setFrozen(frozen: Boolean) {
        this.isFrozen = frozen
    }

    @Synchronized
    fun submit(rms: Double) {
        if (isFrozen) return
        val now = System.currentTimeMillis()
        history.addLast(now to rms)
        cleanOldEntries(now)
    }

    @Synchronized
    fun floor(): Double {
        val now = System.currentTimeMillis()
        cleanOldEntries(now)
        if (history.isEmpty()) return minFloor

        val sorted = history.map { it.second }.sorted()
        val size = sorted.size
        // 20th percentile calculation
        val index = ((size - 1) * 0.20).toInt().coerceIn(0, size - 1)
        val p20 = sorted[index]

        return maxOf(p20, minFloor)
    }

    @Synchronized
    fun isSpike(rms: Double, sensitivity: Sensitivity): Boolean {
        val currentFloor = floor()
        val threshold = currentFloor * sensitivity.multiplier
        return rms >= threshold
    }

    private fun cleanOldEntries(now: Long) {
        val windowMs = (windowSeconds * 1000).toLong()
        while (history.isNotEmpty() && (now - history.first().first) > windowMs) {
            history.removeFirst()
        }
    }
}
