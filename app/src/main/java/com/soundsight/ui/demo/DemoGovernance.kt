package com.soundsight.ui.demo

import com.soundsight.audio.MicSource
import com.soundsight.audio.Sensitivity

/**
 * Pure functions and models governing access to the Demo & Diagnostics panel, sensitivity
 * gating, and restoring debug audio statics to safe operational defaults on session exit.
 *
 * Extracted so governance contracts (disclaimer enforcement per session, Max sensitivity gating,
 * and statics restoration) are unit-testable without Android or Compose dependencies.
 */
enum class DemoDisclaimerAction {
    /** First entry in a session: disclaimer modal must be displayed and acknowledged. */
    SHOW_DISCLAIMER,

    /** Re-entry in the same session: disclaimer already acknowledged, proceed directly. */
    PROCEED_DIRECTLY
}

/**
 * Decides whether entering demo/diagnostic mode requires displaying the safety disclaimer.
 */
fun decideDemoDisclaimerAction(acknowledgedInSession: Boolean): DemoDisclaimerAction =
    if (acknowledgedInSession) DemoDisclaimerAction.PROCEED_DIRECTLY else DemoDisclaimerAction.SHOW_DISCLAIMER

fun shouldShowDemoDisclaimer(acknowledgedInSession: Boolean): Boolean =
    decideDemoDisclaimerAction(acknowledgedInSession) == DemoDisclaimerAction.SHOW_DISCLAIMER

/**
 * Result of resolving sensitivity against the Demo & Diagnostics unlock gate.
 *
 * @param sensitivity The active or clamped sensitivity stop.
 * @param demoFastAlert Whether demo-fast alert dispatch is active (true only for MAX).
 * @param wasClamped True if the stored sensitivity was MAX but was clamped to HIGH because
 *                   the demo panel has not yet been visited.
 */
data class GatedSensitivityResolution(
    val sensitivity: Sensitivity,
    val demoFastAlert: Boolean,
    val wasClamped: Boolean
)

/**
 * Decides whether the Max sensitivity stop is unlocked.
 *
 * Max is gated behind opening Demo & Diagnostics because it activates [com.soundsight.core.AlertConfig.demoFastAlert],
 * modifying the alert path itself.
 */
fun isMaxSensitivityUnlocked(demoPanelVisited: Boolean): Boolean = demoPanelVisited

/**
 * Resolves the effective sensitivity against the demo-panel unlock gate.
 *
 * On load, if the stored sensitivity is MAX while the gate is locked, it clamps to HIGH and
 * clears demoFastAlert.
 */
fun resolveGatedSensitivity(
    stored: Sensitivity,
    demoPanelVisited: Boolean
): GatedSensitivityResolution {
    return if (stored == Sensitivity.MAX && !demoPanelVisited) {
        GatedSensitivityResolution(
            sensitivity = Sensitivity.HIGH,
            demoFastAlert = false,
            wasClamped = true
        )
    } else {
        GatedSensitivityResolution(
            sensitivity = stored,
            demoFastAlert = (stored == Sensitivity.MAX),
            wasClamped = false
        )
    }
}

/**
 * Snapshot of mutable debug-only audio statics on [com.soundsight.audio.ListenerService].
 */
data class DebugAudioStatics(
    val micSource: MicSource = MicSource.MIC,
    val enableNoiseSuppressor: Boolean = false,
    val enableAutomaticGainControl: Boolean = false
) {
    companion object {
        val DEFAULT = DebugAudioStatics(
            micSource = MicSource.MIC,
            enableNoiseSuppressor = false,
            enableAutomaticGainControl = false
        )
    }
}

/**
 * Result of planning an audio statics reset.
 */
data class AudioStaticsResetPlan(
    val needsRestart: Boolean,
    val targetState: DebugAudioStatics
)

/**
 * Evaluates whether current debug statics differ from safe defaults and determines if audio
 * recording must be restarted to restore operational defaults.
 */
fun planAudioStaticsReset(
    current: DebugAudioStatics,
    default: DebugAudioStatics = DebugAudioStatics.DEFAULT
): AudioStaticsResetPlan {
    val changed = current.micSource != default.micSource ||
        current.enableNoiseSuppressor != default.enableNoiseSuppressor ||
        current.enableAutomaticGainControl != default.enableAutomaticGainControl
    return AudioStaticsResetPlan(
        needsRestart = changed,
        targetState = default
    )
}

/**
 * Safe default for dynamic wallpaper color (false = brand blue scheme).
 */
const val DEFAULT_DYNAMIC_COLOR: Boolean = false

/**
 * Result of planning a dynamic color reset on demo session exit.
 */
data class DynamicColorResetPlan(
    val needsReset: Boolean,
    val targetState: Boolean = DEFAULT_DYNAMIC_COLOR
)

/**
 * Evaluates whether dynamic wallpaper color is altered from the safe brand default (false)
 * and plans restoration to safe default on demo session exit.
 */
fun planDynamicColorReset(
    currentDynamicColor: Boolean = true,
    default: Boolean = DEFAULT_DYNAMIC_COLOR
): DynamicColorResetPlan {
    return DynamicColorResetPlan(
        needsReset = (currentDynamicColor != default),
        targetState = default
    )
}
