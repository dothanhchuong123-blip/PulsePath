package com.soundsight.core

object AlertConfig {
    var t2Enabled: Boolean = false // default OFF — free-tier quota (20/day)
                                   // cannot sustain always-on cloud classify.
                                   // A future ViewModel must not call
                                   // SoundClassifier at all when this is false;
                                   // Tier-1 pre-alert still fires regardless.
    var t2TakesOverScreen: Boolean = false
                                   // When a Takeover decision is used, whether it
                                   // shows a full-screen takeover (true) or is
                                   // presented like LogOnly — history + haptic
                                   // only, no phase change (false). Measured real
                                   // Gemini latency across two models (gemini-3.6-flash,
                                   // gemini-3.5-flash-lite) has landed at 5000-8700ms
                                   // end to end, above the 5300ms budget the 6500ms
                                   // stale-drop threshold allows for after the mandatory
                                   // 1200ms post-roll. A screen takeover built on that
                                   // latency would show a stale hazard after the rider
                                   // has already passed it. Kept mutable so it can be
                                   // flipped later if a faster path is found.
}

