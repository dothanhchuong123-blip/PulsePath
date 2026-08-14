package com.soundsight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private val policy = AlertPolicy()

    @Test
    fun testStaleDrop() {
        val nowMs = 100_000L
        val incoming = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.90,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            capturedAtMs = nowMs - 7000L, // 7000ms > STALE_MS (6500ms)
            resolvedAtMs = nowMs - 5000L
        )

        val decision = policy.decide(incoming = incoming, current = null, nowMs = nowMs)
        assertEquals(AlertPolicy.Decision.Discard, decision)
    }

    @Test
    fun testLowConfidenceDemotion() {
        val nowMs = 100_000L
        // Low confidence CRITICAL (< 0.55) demoted to WARNING
        val lowConfCritical = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 0.45,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision1 = policy.decide(incoming = lowConfCritical, current = null, nowMs = nowMs)
        assertTrue(decision1 is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.WARNING, (decision1 as AlertPolicy.Decision.Takeover).event.tier)

        // Low confidence WARNING (< 0.55) demoted to SAFE -> LogOnly
        val lowConfWarning = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.40,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision2 = policy.decide(incoming = lowConfWarning, current = null, nowMs = nowMs)
        assertTrue(decision2 is AlertPolicy.Decision.LogOnly)
        assertEquals(Tier.SAFE, (decision2 as AlertPolicy.Decision.LogOnly).event.tier)
    }

    @Test
    fun testDedupMerge() {
        val nowMs = 100_000L
        val current = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.85,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            capturedAtMs = nowMs - 3000L,
            resolvedAtMs = nowMs - 2000L,
            repeatCount = 1
        )

        val incoming = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.88,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            capturedAtMs = nowMs - 500L,
            resolvedAtMs = nowMs,
            repeatCount = 1
        )

        val decision = policy.decide(incoming = incoming, current = current, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.MergeWithCurrent)
        val mergedEvent = (decision as AlertPolicy.Decision.MergeWithCurrent).event
        assertEquals(SoundId.MOTORCYCLE_HORN, mergedEvent.soundId)
        assertEquals(2, mergedEvent.repeatCount)
    }

    @Test
    fun testCriticalOverWarning() {
        val nowMs = 100_000L
        val currentShowing = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.90,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            capturedAtMs = nowMs - 2000L,
            resolvedAtMs = nowMs - 1000L
        )

        val incomingCritical = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = nowMs - 500L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(incoming = incomingCritical, current = currentShowing, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        val takeoverEvent = (decision as AlertPolicy.Decision.Takeover).event
        assertEquals(SoundId.TRUCK_AIR_HORN, takeoverEvent.soundId)
        assertEquals(Tier.CRITICAL, takeoverEvent.tier)
    }

    @Test
    fun testWarningBlockedByCritical() {
        val nowMs = 100_000L
        val currentCritical = SoundEvent(
            soundId = SoundId.EMERGENCY_SIREN,
            tier = Tier.CRITICAL,
            confidence = 0.92,
            captionVi = "XE ƯU TIÊN",
            captionEn = "EMERGENCY VEHICLE",
            capturedAtMs = nowMs - 2000L,
            resolvedAtMs = nowMs - 1000L
        )

        val incomingWarning = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.85,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            capturedAtMs = nowMs - 500L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(incoming = incomingWarning, current = currentCritical, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.LogOnly)
        val logEvent = (decision as AlertPolicy.Decision.LogOnly).event
        assertEquals(SoundId.MOTORCYCLE_HORN, logEvent.soundId)
        assertEquals(Tier.WARNING, logEvent.tier)
    }

    @Test
    fun testSecondTruckAirHornAfterAlertHoldExpiredTakesOver() {
        val current = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = 0L,
            resolvedAtMs = 6000L,
            repeatCount = 1
        )

        val incoming = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = 6000L,
            resolvedAtMs = 12000L,
            repeatCount = 1
        )

        val nowMs = 12000L
        // nowMs (12000) - current.capturedAtMs (0) = 12000ms > DEDUP_WINDOW_MS (6000ms)
        val decision = policy.decide(incoming = incoming, current = current, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        val takeoverEvent = (decision as AlertPolicy.Decision.Takeover).event
        assertEquals(SoundId.TRUCK_AIR_HORN, takeoverEvent.soundId)
        assertEquals(1, takeoverEvent.repeatCount)
    }

    @Test
    fun testBurstMotorcycleHornEvery4000msFor80sProducesTakeoverAfterFirst() {
        var current: SoundEvent? = null
        var takeoverCount = 0
        val stepMs = 4000L
        val totalDurationMs = 80_000L

        var timeMs = 0L
        while (timeMs <= totalDurationMs) {
            val incoming = SoundEvent(
                soundId = SoundId.MOTORCYCLE_HORN,
                tier = Tier.WARNING,
                confidence = 0.90,
                captionVi = "Còi xe máy",
                captionEn = "Motorbike horn",
                capturedAtMs = timeMs,
                resolvedAtMs = timeMs + 500L,
                repeatCount = 1
            )

            val decision = policy.decide(incoming = incoming, current = current, nowMs = timeMs + 500L)
            when (decision) {
                is AlertPolicy.Decision.Takeover -> {
                    takeoverCount++
                    current = decision.event
                }
                is AlertPolicy.Decision.MergeWithCurrent -> {
                    current = decision.event
                }
                is AlertPolicy.Decision.LogOnly -> {
                    // Not taking over or merging
                }
                AlertPolicy.Decision.Discard -> {}
            }

            timeMs += stepMs
        }

        // Must have at least one Takeover after the first initial takeover
        assertTrue("Expected multiple takeovers across 80s burst, got $takeoverCount", takeoverCount > 1)
    }

    @Test
    fun testUnparseableTierStringUsesCanonicalTier() {
        val event = SoundEvent.fromStrings(
            soundIdStr = "TRUCK_AIR_HORN",
            tierStr = "Critical!!",
            confidence = 0.95,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = 1000L,
            resolvedAtMs = 1500L
        )

        assertEquals(Tier.CRITICAL, event.tier)

        val decision = policy.decide(incoming = event, current = null, nowMs = 1500L)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.CRITICAL, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testTwoDifferentUnparseableSoundIdsNotMerged() {
        val current = SoundEvent.fromStrings(
            soundIdStr = "UNKNOWN_SOUND_ABC",
            tierStr = "WARNING",
            confidence = 0.90,
            captionVi = "Âm thanh lạ",
            captionEn = "Strange sound",
            capturedAtMs = 1000L,
            resolvedAtMs = 1500L
        )

        val incoming = SoundEvent.fromStrings(
            soundIdStr = "ANOTHER_UNKNOWN_SOUND_XYZ",
            tierStr = "WARNING",
            confidence = 0.90,
            captionVi = "Âm thanh lạ 2",
            captionEn = "Strange sound 2",
            capturedAtMs = 2000L,
            resolvedAtMs = 2500L
        )

        assertEquals(SoundId.UNKNOWN, current.soundId)
        assertEquals(SoundId.UNKNOWN, incoming.soundId)

        // UNKNOWN soundIds must not be merged
        val decision = policy.decide(incoming = incoming, current = current, nowMs = 2500L)
        // UNKNOWN with canonical tier is SAFE -> LogOnly, but definitely not MergeWithCurrent
        assertTrue(decision !is AlertPolicy.Decision.MergeWithCurrent)
    }
}
