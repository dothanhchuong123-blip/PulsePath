package com.soundsight.core

object AlertConfig {
    var t2Enabled: Boolean = false // default OFF — free-tier quota (20/day)
                                   // cannot sustain always-on cloud classify.
                                   // A future ViewModel must not call
                                   // SoundClassifier at all when this is false;
                                   // Tier-1 pre-alert still fires regardless.
    var t2TakesOverScreen: Boolean = true
                                   // When a Takeover decision is used, whether it
                                   // shows a full-screen takeover (true) or is
                                   // presented like LogOnly — history + haptic
                                   // only, no phase change (false). Flip based on
                                   // measured cloud latency once available.
}
