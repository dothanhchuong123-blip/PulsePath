package com.soundsight.domain

import com.soundsight.core.AlertConfig

/**
 * @param collisionDetectionEnabled Whether the rider has collision detection switched on. Read
 * through a lambda rather than a plain `Boolean` because the switch can be flipped while a policy
 * instance is alive, and read *here* rather than at each call site on purpose: [decide] has six
 * call sites across the local and cloud paths, and the last rule that lived at the call sites
 * (the CRITICAL confidence floor) drifted apart between two of them. A rule that must hold
 * everywhere belongs in the one place everything goes through.
 *
 * The default reads [AlertConfig] so production wiring is `AlertPolicy()` and nothing has to
 * remember; tests pass an explicit lambda so they state which rider they are describing instead
 * of inheriting a global default.
 */
class AlertPolicy(
    private val collisionDetectionEnabled: () -> Boolean = { AlertConfig.motionSensorsEnabled }
) {
    companion object {
        // Age budget for an alert, measured from capturedAtMs (the START of the clip), so the
        // clip's own ~2.2s duration is already spent before classification even begins.
        // Measured on-device 2026-08-19 and re-benchmarked with confirmed Arm 2 (slice 10240)
        // over 100 turns on 2026-09-04: Live T2 latency p50 5131ms / p95 6446ms. capturedAtMs
        // already includes the 1.0s pre-roll, so the age at decision is T2 plus the 1.2s
        // post-roll: roughly 6.3s (p50) to 7.6s (p95) - NOT 7.3/8.6s, which double-counted the
        // pre-roll (D5 rank 7). D7 (2026-09-14, 394 completed turns on 40 real clips, fresh
        // socket per turn) puts T2 at p50 5.4s / p95 6.8s, min 4.1s, the same band. The
        // previous 6500ms budget was therefore below the pipeline's own normal latency and
        // silently discarded good alerts - a real truck air horn was captured, classified, and
        // then dropped with no alert and no history entry. This value must stay comfortably
        // above p99 total age so the guard only catches genuinely pathological delays (e.g. a
        // reconnect path surfacing a 20s-old clip), never the normal path.
        const val STALE_MS = 12000L

        /**
         * **PROVISIONAL, NOT MEASURED.** Maximum event age from [SoundEvent.capturedAtMs] within
         * which a non-collision cloud classification is permitted to seize the screen with a
         * full-bleed [Decision.Takeover].
         *
         * At 40 km/h (≈ 11.1 m/s), an alert arriving at 2500 ms represents a hazard ~28 m behind
         * the rider. At the measured cloud age at decision of 6.3 s p50 (~70 m behind) or 7.6 s
         * p95 (~84 m behind) - see [STALE_MS]; D7 measured 0 of 393 cloud answers inside this
         * budget - a full-screen takeover is a startling event with no actionable response
         * for a hazard already passed. Events arriving between [ACTIONABLE_MS] and [STALE_MS]
         * therefore resolve to [Decision.LogOnly] rather than [Decision.Discard] — preserved in
         * history, telemetry, and haptics, but without seizing screen ownership.
         *
         * A field campaign (B1-style) would settle this by measuring the age past which a rider
         * reports the alert as confusing rather than useful.
         */
        const val ACTIONABLE_MS = 2500L

        const val DEDUP_WINDOW_MS = 6000L
        const val MIN_CONFIDENCE = 0.55
        // CRITICAL clears a higher bar than the generic floor: it is the only tier that
        // takes over the screen with the strongest haptic, so a confidently-wrong CRITICAL
        // costs the rider far more than a demoted one. Below this the event drops to
        // WARNING, never to silence, so a real hazard reported with mediocre confidence is
        // still surfaced.
        const val MIN_CRITICAL_CONFIDENCE = 0.80

        // Bounded motion-rescue floor for COLLISION_IMPACT, split by source ON PURPOSE.
        //
        // A combined accel+gyro spike may exempt a collision from the CRITICAL demotion above,
        // but only down to its source's floor - never unboundedly. The two scores are NOT the
        // same quantity: LOCAL preserves a YAMNet score from the on-device model, CLOUD
        // preserves whatever confidence the remote model reported, and they arise differently,
        // so they get separately named constants even though both start at the same value.
        // Neither value is calibrated yet; both were chosen as today's existing numbers so this
        // change bounds the rescue without re-tuning anything.
        const val MIN_LOCAL_COLLISION_RESCUE_CONFIDENCE = 0.60
        const val MIN_CLOUD_COLLISION_RESCUE_CONFIDENCE = 0.60

        // Local (on-device YAMNet) admission floors. These are a different quantity from the
        // cloud confidence floors above: a YAMNet score is a sigmoid per label, and real-world
        // hazards sit lower on it than the cloud model's self-reported confidence.
        //
        // MEASURED 2026-09-14 on the pp_device emulator against the 40 D4 stimulus clips
        // (measurements/local-classifier/2026-09-14-emulator/): real recordings of sirens and
        // horns score 0.59-0.74 on their hazard label at least as often as they clear 0.80 -
        // an approaching ESC-50 siren peaked at Siren=0.67, a fire engine at 0.67-0.74, a
        // scooter horn at 0.59-0.67. With the previous rule (CRITICAL labels dropped outright
        // below 0.80, WARNING below 0.70) a local candidate reached policy on 5 of the 33
        // hazard clips; with one admission floor at 0.60 one reaches policy on 12 (10 with the
        // right sound, 11 with the right tier), and on none of the 7 non-hazard clips at either
        // setting. The five synthetic canonical clips are unrecognisable to
        // YAMNet at any floor (it hears "Theremin", "Telephone", "Music"), so they are not what
        // these numbers describe.
        //
        // ONE floor for every hazard label, CRITICAL included, because the CRITICAL/WARNING
        // split is decide()'s job: a CRITICAL label under MIN_CRITICAL_CONFIDENCE is demoted to
        // WARNING there - surfaced, not seized - which is what the policy's own "never to
        // silence" contract always said and what the pre-filter contradicted by dropping it.
        const val LOCAL_WARNING_MIN_SCORE = 0.60f

        // ---- Demo/experience mode (AlertConfig.demoFastAlert, i.e. Sensitivity.MAX) ----
        //
        // The Max stop exists so a hazard alert can be SEEN on demand, indoors, from a laptop
        // speaker. The reason that did not work was never the spike gate - a loud speaker clears
        // the 1.33x threshold easily, which is why the pre-alert buzz and border always fired.
        // It was these floors: YAMNet's top score for a horn played through a speaker sits around
        // 0.30-0.60, under LOCAL_WARNING_MIN_SCORE,
        // so the candidate was thrown away before policy ever saw it.
        //
        // In demo mode the floors drop to the classifier's own reporting threshold (YAMNet is
        // configured with setScoreThreshold(0.3f), so 0.30 is the lowest score that can ever
        // arrive - anything lower is not "a weak label", it is no label at all). The result is
        // "show me the alert screen, even if the label is wrong", which is what a demo needs and
        // what a rider must never get: these are deliberately NOT the shipping numbers.
        const val DEMO_MIN_CONFIDENCE = 0.30
        const val DEMO_MIN_CRITICAL_CONFIDENCE = 0.30
        const val DEMO_LOCAL_WARNING_MIN_SCORE = 0.30f

        /** Generic (WARNING) confidence floor for the mode we are in. */
        fun minConfidence(): Double =
            if (AlertConfig.demoFastAlert) DEMO_MIN_CONFIDENCE else MIN_CONFIDENCE

        /** CRITICAL confidence floor for the mode we are in. */
        fun minCriticalConfidence(): Double =
            if (AlertConfig.demoFastAlert) DEMO_MIN_CRITICAL_CONFIDENCE else MIN_CRITICAL_CONFIDENCE

        /**
         * The local (on-device) candidate pre-filter: which classification results are worth
         * building a [SoundEvent] and consulting policy for.
         *
         * Canonical COLLISION_IMPACT is special-cased down to [MIN_LOCAL_COLLISION_RESCUE_CONFIDENCE]:
         * the previous blanket 0.80 bar threw away every local collision in the 0.60-0.79 band
         * before policy could evaluate it - while the cloud path let an arbitrarily low
         * collision through to that same policy - so the two paths violated the bounded rescue
         * contract in opposite directions. A non-corroborated local collision in that band now
         * reaches policy and becomes WARNING rather than disappearing. Other CRITICAL sound IDs
         * are admitted from [LOCAL_WARNING_MIN_SCORE] like any hazard; between it and
         * MIN_CRITICAL_CONFIDENCE they too reach policy and become WARNING.
         */
        fun admitsLocalCandidate(soundId: SoundId, score: Float): Boolean {
            val demo = AlertConfig.demoFastAlert
            return when (SoundEvent.canonicalTier(soundId)) {
                Tier.CRITICAL ->
                    if (soundId == SoundId.COLLISION_IMPACT) {
                        score >= MIN_LOCAL_COLLISION_RESCUE_CONFIDENCE.toFloat()
                    } else {
                        score >= if (demo) DEMO_LOCAL_WARNING_MIN_SCORE else LOCAL_WARNING_MIN_SCORE
                    }
                Tier.WARNING ->
                    score >= if (demo) DEMO_LOCAL_WARNING_MIN_SCORE else LOCAL_WARNING_MIN_SCORE
                Tier.SAFE -> false
            }
        }

        /**
         * Motion-rescue floor for one event's [EventSource]. DEMO events are constructed by hand
         * and must never gain motion rescue, hence the unreachable floor rather than a default.
         * A MOTION event *is* the motion evidence - there is no audio score for a spike to
         * rescue - so it gets no floor below the ordinary CRITICAL one: it clears
         * [MIN_CRITICAL_CONFIDENCE] on its own ([SoundEvent.FALL_RULE_CONFIDENCE]) or it is
         * demoted like anything else.
         */
        fun collisionRescueFloor(source: EventSource): Double = when (source) {
            EventSource.LOCAL -> MIN_LOCAL_COLLISION_RESCUE_CONFIDENCE
            EventSource.CLOUD -> MIN_CLOUD_COLLISION_RESCUE_CONFIDENCE
            EventSource.DEMO -> Double.POSITIVE_INFINITY
            EventSource.MOTION -> MIN_CRITICAL_CONFIDENCE
        }
    }

    sealed interface Decision {
        data class Takeover(val event: SoundEvent) : Decision
        data class MergeWithCurrent(val event: SoundEvent) : Decision
        data class LogOnly(val event: SoundEvent) : Decision

        /** Thrown away for arriving too late to act on - see [STALE_MS]. */
        data object Discard : Decision

        /**
         * Thrown away because the rider has collision detection switched off.
         *
         * A separate variant rather than a flag on [Discard] so that every exhaustive `when` over
         * [Decision] fails to compile until it has been told what to do with it. The two are
         * counted separately in [com.soundsight.core.Telemetry]: a suppressed collision is a
         * setting working as asked, a stale discard is the pipeline being too slow, and reporting
         * them as one number would hide both.
         */
        data object DiscardCollisionOff : Decision
    }

    /**
     * @param motionCorroborated True if the phone's accelerometer+gyroscope recorded a combined
     * motion spike (see MotionSpikeDetector) close in time to [incoming]. Only meaningful for
     * COLLISION_IMPACT: a physical impact/fall is independent evidence the audio classification
     * is real, so it exempts a collision from the CRITICAL demotion below - but only down to
     * the event's source-specific rescue floor ([collisionRescueFloor]); it is bounded, never a
     * blanket override. It does not affect any other soundId - there is no physical reason a
     * motion spike should corroborate a siren or a horn.
     */
    fun decide(
        incoming: SoundEvent,
        current: SoundEvent?,
        nowMs: Long,
        motionCorroborated: Boolean = false
    ): Decision {
        // 0. COLLISION DETECTION OFF: suppress the whole soundId, not just the sensor input.
        //
        // motionSensorsEnabled used to gate only the sensor subscription and the confidence lift
        // below, which left the audio path completely untouched: with the switch off, a loud
        // enough crunch still raised a collision alert, because LocalClassifier still maps YAMNet
        // "Shatter" to COLLISION_IMPACT and the cloud prompt still lists it as a returnable label.
        // The switch reads as a master toggle for the feature and now behaves as one.
        //
        // First, ahead of staleness and confidence, because those are questions about how much to
        // trust an alert and this one is about whether the rider asked for it at all.
        if (incoming.soundId == SoundId.COLLISION_IMPACT && !collisionDetectionEnabled()) {
            return Decision.DiscardCollisionOff
        }

        // 1. STALE: nowMs - incoming.capturedAtMs > STALE_MS -> Discard.
        if (nowMs - incoming.capturedAtMs > STALE_MS) {
            return Decision.Discard
        }

        // 2. ACTIONABILITY: late cloud events (arriving past ACTIONABLE_MS) resolve to LogOnly.
        // They still enter history, telemetry and the haptic path, but no longer seize the screen.
        // Exemptions:
        // 1. COLLISION_IMPACT is exempt: the post-collision screen is still useful at t+8s, and
        //    crashCandidate() in HudViewModel only fires off a CRITICAL collision takeover.
        // 2. EventSource.LOCAL and EventSource.DEMO are unaffected: local events arrive at ~1.3s
        //    (well inside budget) and demo events are hand-constructed to demonstrate UI alerts.
        // 3. Exempt in demo mode (AlertConfig.demoFastAlert): the whole point of the Max stop is
        //    that the alert screen appears, and measured cloud latency (p50 ~7.0s, p90 ~8.4s) is
        //    always past ACTIONABLE_MS, so with this rule live EVERY non-collision cloud event
        //    resolves to LogOnly - which is silent (see HudViewModel.handleDecision: LogOnly
        //    writes history and nothing else, not even a haptic). That is the correct road
        //    behaviour and exactly the wrong demo behaviour.
        if (!AlertConfig.demoFastAlert &&
            incoming.source == EventSource.CLOUD &&
            incoming.soundId != SoundId.COLLISION_IMPACT &&
            (nowMs - incoming.capturedAtMs > ACTIONABLE_MS)
        ) {
            val loggedEvent = when {
                incoming.tier == Tier.CRITICAL && incoming.confidence < MIN_CRITICAL_CONFIDENCE ->
                    incoming.copy(tier = Tier.WARNING)
                incoming.tier == Tier.WARNING && incoming.confidence < MIN_CONFIDENCE ->
                    incoming.copy(tier = Tier.SAFE)
                else -> incoming
            }
            return Decision.LogOnly(loggedEvent)
        }

        // 3. CONFIDENCE: demote one tier when the event misses its tier's floor
        // (CRITICAL below MIN_CRITICAL_CONFIDENCE -> WARNING, WARNING below MIN_CONFIDENCE
        // -> LogOnly-as-SAFE). Demotion is single-step: a CRITICAL never falls past WARNING
        // in one decision, however low its confidence. A motion-corroborated COLLISION_IMPACT
        // is exempted from the CRITICAL demotion ONLY down to its source-specific rescue floor
        // - see collisionRescueFloor. Before the floor existed, a cloud collision at ANY
        // confidence (0.10 included) plus any spike within the lookup window stayed CRITICAL,
        // took over the screen and entered the crash path; that unbounded rescue was the live
        // defect. DEMO events get an unreachable floor, so hand-built events cannot be
        // corroborated into CRITICAL.
        // Apply before all tier comparisons below.
        val criticalFloor = minCriticalConfidence()
        val warningFloor = minConfidence()
        val effectiveEvent = when {
            incoming.tier == Tier.CRITICAL && incoming.confidence < criticalFloor ->
                if (
                    incoming.soundId == SoundId.COLLISION_IMPACT &&
                    motionCorroborated &&
                    incoming.confidence >= collisionRescueFloor(incoming.source)
                ) {
                    incoming
                } else {
                    incoming.copy(tier = Tier.WARNING)
                }
            incoming.tier == Tier.WARNING && incoming.confidence < warningFloor ->
                incoming.copy(tier = Tier.SAFE)
            else -> incoming
        }

        // 4. SAFE tier -> LogOnly (never takes over the screen).
        if (effectiveEvent.tier == Tier.SAFE) {
            return Decision.LogOnly(effectiveEvent)
        }

        // 5. DEDUP: same soundId as `current` and within DEDUP_WINDOW_MS -> MergeWithCurrent
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

        // 6. ESCALATE: CRITICAL always Takeover, even over a showing CRITICAL of a different soundId.
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

        // 7. Otherwise Takeover.
        return Decision.Takeover(effectiveEvent)
    }
}
