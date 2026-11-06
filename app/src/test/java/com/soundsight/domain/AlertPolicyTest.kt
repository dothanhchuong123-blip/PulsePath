package com.soundsight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private val policy = AlertPolicy(collisionDetectionEnabled = { true })

    @Test
    fun testStaleDrop() {
        val nowMs = 100_000L
        val incoming = SoundEvent(
            soundId = SoundId.MOTORCYCLE_HORN,
            tier = Tier.WARNING,
            confidence = 0.90,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn",
            // Derived from STALE_MS rather than hard-coded: the previous literal 7000ms
            // silently encoded the old 6500ms budget, which was below the pipeline's own
            // latency, so this test passed while real alerts were being discarded.
            capturedAtMs = nowMs - (AlertPolicy.STALE_MS + 500L),
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
            capturedAtMs = 11500L,
            resolvedAtMs = 12000L,
            repeatCount = 1
        )

        val nowMs = 12000L
        // nowMs (12000) - current.capturedAtMs (0) = 12000ms > DEDUP_WINDOW_MS (6000ms)
        // and nowMs (12000) - incoming.capturedAtMs (11500) = 500ms <= ACTIONABLE_MS (2500ms)
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
                AlertPolicy.Decision.DiscardCollisionOff -> {}
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

    @Test
    fun testMidConfidenceCriticalDemotedToWarning() {
        val nowMs = 100_000L
        // 0.70 clears MIN_CONFIDENCE but misses MIN_CRITICAL_CONFIDENCE (0.80): the rider
        // still gets the alert, just without the full-screen CRITICAL takeover.
        val midConfCritical = SoundEvent(
            soundId = SoundId.EMERGENCY_SIREN,
            tier = Tier.CRITICAL,
            confidence = 0.70,
            captionVi = "Còi hú",
            captionEn = "Siren",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(incoming = midConfCritical, current = null, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.WARNING, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testCriticalAtExactFloorStaysCritical() {
        val nowMs = 100_000L
        val atFloor = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = AlertPolicy.MIN_CRITICAL_CONFIDENCE,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(incoming = atFloor, current = null, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.CRITICAL, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testVeryLowConfidenceCriticalDemotedOnlyOneTier() {
        val nowMs = 100_000L
        // Demotion is single-step: even a barely-confident CRITICAL lands on WARNING and is
        // shown, rather than cascading down to SAFE and being logged silently.
        val veryLowCritical = SoundEvent(
            soundId = SoundId.COLLISION_IMPACT,
            tier = Tier.CRITICAL,
            confidence = 0.10,
            captionVi = "Va chạm",
            captionEn = "Collision",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(incoming = veryLowCritical, current = null, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.WARNING, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testRealisticPipelineLatencyIsNotDiscarded() {
        val nowMs = 100_000L
        // A real truck air horn measured on-device: ~2.2s clip plus ~5.6s Live round trip, so
        // the event is ~7.8s old by the time it is decided. Under the old 6500ms budget this
        // was silently discarded and the rider saw nothing at all. Under STALE_MS=12000L it is
        // preserved, but because 7.8s > ACTIONABLE_MS (2500ms) for a cloud non-collision event,
        // it resolves to LogOnly rather than seizing screen ownership with Takeover.
        val realWorldAge = 7_800L
        val airHorn = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = nowMs - realWorldAge,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(incoming = airHorn, current = null, nowMs = nowMs)
        assertTrue("a horn at p90 pipeline latency is recorded in LogOnly, not discarded", decision is AlertPolicy.Decision.LogOnly)
        assertEquals(Tier.CRITICAL, (decision as AlertPolicy.Decision.LogOnly).event.tier)
    }

    @Test
    fun testPathologicallyOldEventStillDiscarded() {
        val nowMs = 100_000L
        // Well past any normal pipeline latency - e.g. a reconnect path surfacing an old clip.
        // The guard must still fire here, otherwise it protects nothing.
        val ancient = SoundEvent(
            soundId = SoundId.EMERGENCY_SIREN,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "Còi hú",
            captionEn = "Siren",
            capturedAtMs = nowMs - (AlertPolicy.STALE_MS + 1_000L),
            resolvedAtMs = nowMs
        )

        assertEquals(AlertPolicy.Decision.Discard, policy.decide(incoming = ancient, current = null, nowMs = nowMs))
    }

    @Test
    fun testStaleBudgetExceedsMeasuredPipelineLatency() {
        // Guards the calibration itself: the budget has to sit above the pipeline's own p90
        // total age (~8.4s measured), or the guard discards good alerts instead of stale ones.
        assertTrue(
            "STALE_MS=${AlertPolicy.STALE_MS} is below measured p90 pipeline age",
            AlertPolicy.STALE_MS > 8_400L
        )
    }

    @Test
    fun testMidConfidenceCollisionWithMotionCorroborationStaysCritical() {
        val nowMs = 100_000L
        val collision = SoundEvent(
            soundId = SoundId.COLLISION_IMPACT,
            tier = Tier.CRITICAL,
            confidence = 0.65, // below MIN_CRITICAL_CONFIDENCE on its own
            captionVi = "Va chạm",
            captionEn = "Collision",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(
            incoming = collision,
            current = null,
            nowMs = nowMs,
            motionCorroborated = true
        )
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.CRITICAL, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testMidConfidenceCollisionWithoutMotionCorroborationStillDemoted() {
        val nowMs = 100_000L
        val collision = SoundEvent(
            soundId = SoundId.COLLISION_IMPACT,
            tier = Tier.CRITICAL,
            confidence = 0.65,
            captionVi = "Va chạm",
            captionEn = "Collision",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        // motionCorroborated defaults to false - regression guard that existing call sites
        // (which don't pass it) keep today's behavior unchanged.
        val decision = policy.decide(incoming = collision, current = null, nowMs = nowMs)
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.WARNING, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testMotionCorroborationDoesNotExemptNonCollisionSounds() {
        val nowMs = 100_000L
        // A motion spike happening to coincide with, say, a mid-confidence truck horn is not
        // physical evidence the horn classification is correct - corroboration must be
        // soundId-specific, not a blanket override for any CRITICAL event.
        val horn = SoundEvent(
            soundId = SoundId.TRUCK_AIR_HORN,
            tier = Tier.CRITICAL,
            confidence = 0.65,
            captionVi = "CÒI HƠI XE TẢI",
            captionEn = "TRUCK AIR HORN",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(
            incoming = horn,
            current = null,
            nowMs = nowMs,
            motionCorroborated = true
        )
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.WARNING, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testMotionCorroborationDoesNotRescueVeryLowConfidenceCollision() {
        val nowMs = 100_000L
        // Corroboration exempts the CRITICAL demotion branch only; it must not also bypass the
        // separate very-low-confidence tier ladder if the design changes later. At present the
        // only demotion branch for CRITICAL is this one, so this asserts the branch condition
        // itself is confidence-based, not "corroboration always wins".
        val collision = SoundEvent(
            soundId = SoundId.COLLISION_IMPACT,
            tier = Tier.CRITICAL,
            confidence = 0.95, // already above the floor - corroboration should be a no-op here
            captionVi = "Va chạm",
            captionEn = "Collision",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policy.decide(
            incoming = collision,
            current = null,
            nowMs = nowMs,
            motionCorroborated = true
        )
        assertTrue(decision is AlertPolicy.Decision.Takeover)
        assertEquals(Tier.CRITICAL, (decision as AlertPolicy.Decision.Takeover).event.tier)
    }

    @Test
    fun testCollisionSuppressedWhenDetectionOff() {
        val policyWithCollisionOff = AlertPolicy(collisionDetectionEnabled = { false })
        val nowMs = 100_000L
        val collision = SoundEvent(
            soundId = SoundId.COLLISION_IMPACT,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "Va chạm",
            captionEn = "Collision",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policyWithCollisionOff.decide(
            incoming = collision,
            current = null,
            nowMs = nowMs,
            motionCorroborated = true
        )
        assertEquals(AlertPolicy.Decision.DiscardCollisionOff, decision)
    }

    @Test
    fun testNonCollisionNotSuppressedWhenDetectionOff() {
        val policyWithCollisionOff = AlertPolicy(collisionDetectionEnabled = { false })
        val nowMs = 100_000L
        val siren = SoundEvent(
            soundId = SoundId.EMERGENCY_SIREN,
            tier = Tier.CRITICAL,
            confidence = 0.95,
            captionVi = "Còi cứu thương",
            captionEn = "Emergency siren",
            capturedAtMs = nowMs - 1000L,
            resolvedAtMs = nowMs
        )

        val decision = policyWithCollisionOff.decide(
            incoming = siren,
            current = null,
            nowMs = nowMs
        )
        assertTrue(decision is AlertPolicy.Decision.Takeover)
    }
}
