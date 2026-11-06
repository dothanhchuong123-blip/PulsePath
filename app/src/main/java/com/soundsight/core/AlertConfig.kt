package com.soundsight.core

/**
 * Which glyph family the direction indicator draws.
 *
 * Purely presentational - both styles carry the same two pieces of information (which side, and
 * whether the estimate was high or medium confidence) and both are non-mirroring, because the
 * arrow reports which side of the *motorbike* a sound came from and that does not flip in an RTL
 * locale. See [com.soundsight.ui.hud.DirectionIndicator].
 */
enum class ArrowStyle { CHEVRON, ARROW }

/**
 * Typography preset for the HUD clock, cycled by tapping it. Purely presentational - all four
 * still render the same clock content with tabular figures: "HH:mm" by default, or the rider's
 * opt-in 12-hour "h:mm" with a separate small AM/PM mark (round 3, persisted through
 * OnboardingStore.use24HourClockFlow) - see [com.soundsight.ui.hud] and [com.soundsight.ui.theme].
 */
enum class ClockStyle { STANDARD, THIN, MONO, BOXED }

/**
 * Rider override mode for high-contrast boost in harsh light:
 *  - [AUTO]: Ambient light sensor drives boost on/off with hysteresis (default);
 *  - [ALWAYS_ON]: High-contrast boost is permanently forced on;
 *  - [OFF]: High-contrast boost is disabled.
 */
enum class ContrastBoostMode { AUTO, ALWAYS_ON, OFF }

object AlertConfig {
    var contrastBoostMode: ContrastBoostMode = ContrastBoostMode.AUTO
    var t2Enabled: Boolean = true // default ON
                                   // cannot sustain always-on cloud classify.
                                   // A future ViewModel must not call
                                   // SoundClassifier at all when this is false;
                                   // Tier-1 pre-alert still fires regardless.
    var motionSensorsEnabled: Boolean = false // default OFF
                                   // Accelerometer+gyroscope need no runtime permission, but they
                                   // are a continuous SENSOR_DELAY_GAME subscription for a feature
                                   // most riders will never trigger, so this is opt-in rather than
                                   // opt-out. User-toggleable in settings. Only ever used to
                                   // corroborate a COLLISION_IMPACT classification - see
                                   // AlertPolicy.decide's motionCorroborated param.
    var t2TakesOverScreen: Boolean = true
                                   // When a Takeover decision is used, whether it
                                   // shows a full-screen takeover (true) or is
                                   // presented like LogOnly — history + haptic
                                   // only, no phase change (false). Was false:
                                   // measured real Gemini latency (5000-8700ms end
                                   // to end) exceeded the 5300ms budget the old
                                   // 6500ms AlertPolicy.STALE_MS threshold allowed
                                   // for after the mandatory 1200ms post-roll, so a
                                   // full-screen takeover could show a hazard the
                                   // rider had already passed. Flipped to true
                                   // 2026-08-19 once STALE_MS was raised to 12000ms
                                   // (measured p90 total pipeline age ~8.4s, see
                                   // AlertPolicy.STALE_MS), which leaves ~10.8s of
                                   // budget after the post-roll — comfortably above
                                   // every latency this was measured against.
    var vibrationEnabled: Boolean = true // default ON
                                   // Master haptic switch, checked as the first statement of
                                   // Haptics.play so no pattern - not even CRITICAL - can vibrate
                                   // when the rider has turned haptics off. The visual alert, the
                                   // history entry and the telemetry counters are unaffected:
                                   // turning off vibration must never turn off the warning itself.
                                   // Exists because the phone is often bar-mounted rather than
                                   // pocketed, where a 400ms CRITICAL buzz rattles the mount and is
                                   // felt by nobody.
    var directionArrowEnabled: Boolean = false // default OFF
                                   // Whether a resolved left/right side estimate is drawn on the
                                   // HUD and the takeover. Purely presentational: StereoAnalyzer
                                   // and DirectionGate keep running and keep logging telemetry
                                   // either way. Separate from OnboardingStore's directionDisabled
                                   // flag, which is set during calibration when the hardware cannot
                                   // do direction at all and which suppresses the DSP itself.
                                   // Off by default because a trustworthy side estimate needs a
                                   // landscape mount and a calibration pass; shown before either
                                   // has happened it is a confident-looking arrow that is right
                                   // about half the time, which is worse than no arrow at all.
    var arrowStyle: ArrowStyle = ArrowStyle.CHEVRON
                                   // Which glyph family the direction indicator draws. Look only -
                                   // see the ArrowStyle doc.
    var clockStyle: ClockStyle = ClockStyle.STANDARD // default: today's look
                                   // Which typography preset the HUD clock renders in, cycled by a
                                   // quick tap on the clock itself. Persisted through
                                   // OnboardingStore.clockStyleFlow and read into this field in
                                   // MainActivity.onCreate, the same way t2Enabled is.
    var directionLabelEnabled: Boolean = false // default OFF
                                   // Whether the indicator spells out "From your left"/"From your
                                   // right" under the glyph. Off by default because the HUD shows
                                   // both slots side by side, where the position already says which
                                   // side it is and the words are two more things to read at 40km/h.
                                   // Worth turning on for a rider who finds the glyph alone
                                   // ambiguous, which is exactly the kind of thing that varies per
                                   // person and so belongs in settings rather than in a guess.
    var themeMode: String = "SYSTEM" // SYSTEM | LIGHT | DARK
                                   // Stored as a String rather than the ThemeMode enum because
                                   // this object lives in :core and the enum lives in :ui.theme;
                                   // parsed at the single point of use in MainActivity.
    var waveStyleDots: Boolean = false // default OFF = the continuous line style
                                   // Which of the two HUD wave treatments to draw. Purely a look
                                   // preference: both styles read the same level source, use the
                                   // same hazard colours and perform the same alert morph, so this
                                   // cannot change what the rider is told - only how it looks.
    var keepMonitoringInBackground: Boolean = false // default OFF
                                   // Whether ListenerService continues monitoring behind its
                                   // foreground notification when the app leaves the screen.
                                   // Persisted in OnboardingStore.
    var sirenAlertInBackground: Boolean = false // default OFF
                                   // Whether an audible siren alert plays on the alarm stream for
                                   // CRITICAL events when the app is running in the background and
                                   // not visible. Nested under keepMonitoringInBackground.
    var demoFastAlert: Boolean = false
                                   // DERIVED, NEVER SET BY HAND: mirrors
                                   // ListenerService.sensitivity == Sensitivity.MAX, kept in sync
                                   // by that property's setter so there is exactly one place a
                                   // rider can turn this on and exactly one place it can drift.
                                   //
                                   // What it does: turns the Max stop from "a slightly lower spike
                                   // threshold" into an explicit demo/experience mode - the whole
                                   // point of Max, which was never the 1.33 multiplier. In this
                                   // mode a bare acoustic spike immediately seizes the screen with
                                   // a provisional UNKNOWN/WARNING takeover (HudViewModel's
                                   // pre-alert collector), classification floors drop to
                                   // DEMO_MIN_* and the ACTIONABLE_MS cloud demotion is skipped,
                                   // so a real label upgrades that provisional card in place
                                   // instead of arriving after the screen is already gone.
                                   //
                                   // This deliberately trades precision for latency and MUST NOT
                                   // be the shipping default: at Max the app will show a
                                   // full-screen hazard alert for a door slam, a shout, or a
                                   // laptop speaker. That is the requested demo behaviour, not a
                                   // safety claim. See AlertPolicy.decide and NoiseFloor.MAX.
    var useFirebaseEphemeralAuth: Boolean = false
                                   // Default OFF: keeps today's working BuildConfigCredentialProvider
                                   // path (real GEMINI_API_KEY embedded in the APK - a known,
                                   // documented risk, see GeminiCredentialProvider) active until the
                                   // Firebase backend in /functions is actually deployed. Flip to true
                                   // only after: (1) a real Firebase project exists and
                                   // google-services.json is in app/, (2) the mintLiveToken function
                                   // is deployed, (3) GEMINI_API_KEY is set as a Functions secret (NOT
                                   // also left in .env once this is on). Flipping this on without a
                                   // deployed backend does not crash - LiveSession falls through to
                                   // LiveSessionCoordinator's existing reconnect/backoff, which just
                                   // keeps retrying a connection that keeps failing auth - it simply
                                   // never connects, so verify by checking Live Total Events actually
                                   // increments after flipping, not just the absence of a crash.
}
