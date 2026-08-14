package com.soundsight.domain

class AlertPolicy {
    companion object {
        const val STALE_MS = 6500L
        const val DEDUP_WINDOW_MS = 6000L
        const val MIN_CONFIDENCE = 0.55
    }

    sealed interface Decision {
        data class Takeover(val event: SoundEvent) : Decision
        data class MergeWithCurrent(val event: SoundEvent) : Decision
        data class LogOnly(val event: SoundEvent) : Decision
        data object Discard : Decision
    }

    fun decide(incoming: SoundEvent, current: SoundEvent?, nowMs: Long): Decision {
        // 1. STALE: nowMs - incoming.capturedAtMs > STALE_MS -> Discard.
        if (nowMs - incoming.capturedAtMs > STALE_MS) {
            return Decision.Discard
        }

        // 2. CONFIDENCE: confidence < MIN_CONFIDENCE -> demote one tier (CRITICAL->WARNING, WARNING->LogOnly-as-SAFE).
        // Apply before all tier comparisons below.
        val effectiveEvent = if (incoming.confidence < MIN_CONFIDENCE) {
            when (incoming.tier) {
                Tier.CRITICAL -> incoming.copy(tier = Tier.WARNING)
                Tier.WARNING -> incoming.copy(tier = Tier.SAFE)
                Tier.SAFE -> incoming
            }
        } else {
            incoming
        }

        // 3. SAFE tier -> LogOnly (never takes over the screen).
        if (effectiveEvent.tier == Tier.SAFE) {
            return Decision.LogOnly(effectiveEvent)
        }

        // 4. DEDUP: same soundId as `current` and within DEDUP_WINDOW_MS -> MergeWithCurrent
        // - Window measured from capturedAtMs only (drop resolvedAtMs).
        // - Never dedup when soundId == SoundId.UNKNOWN.
        // - The merged event returned by MergeWithCurrent must KEEP current.capturedAtMs (first occurrence).
        //   Everything else (tier, confidence, captions, resolvedAtMs) comes from incoming event; repeatCount = current.repeatCount + 1.
        if (current != null &&
            effectiveEvent.soundId != SoundId.UNKNOWN &&
            current.soundId == effectiveEvent.soundId &&
            (nowMs - current.capturedAtMs <= DEDUP_WINDOW_MS)
        ) {
            val merged = effectiveEvent.copy(
                capturedAtMs = current.capturedAtMs,
                repeatCount = current.repeatCount + 1
            )
            return Decision.MergeWithCurrent(merged)
        }

        // 5. ESCALATE: CRITICAL always Takeover, even over a showing CRITICAL of a different soundId.
        // WARNING -> Takeover only if `current` is null or SAFE; if a CRITICAL is showing, WARNING becomes LogOnly.
        if (effectiveEvent.tier == Tier.CRITICAL) {
            return Decision.Takeover(effectiveEvent)
        }

        if (effectiveEvent.tier == Tier.WARNING) {
            if (current != null && current.tier == Tier.CRITICAL) {
                return Decision.LogOnly(effectiveEvent)
            }
            return Decision.Takeover(effectiveEvent)
        }

        // 6. Otherwise Takeover.
        return Decision.Takeover(effectiveEvent)
    }
}
