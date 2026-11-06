package com.soundsight.ui.hud

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.soundsight.BuildConfig
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsight.core.AlertConfig
import com.soundsight.core.ClockStyle
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.Side
import com.soundsight.ui.theme.LocalClockTypography
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The HUD's visual body, with no ViewModel, no service and no navigation dependency.
 *
 * Split out from [HudScreen] so the screen can be rendered in a Robolectric/Roborazzi screenshot
 * test: HudViewModel's constructor starts the local classifier and the stereo analyser, which
 * cannot run in a JVM test, so the only way to get a real picture of this layout is to make the
 * layout independent of it. Everything here is a plain parameter.
 *
 * Layout adapts to aspect ratio rather than to a portrait/landscape flag. The bar-mounted phone is
 * used **landscape** - direction estimation is suppressed entirely in portrait - so the short-and-
 * wide case is the primary one, not an afterthought.
 *
 * Motion uses the glance-optimized M3-taxonomy springs declared in [Motion] rather than hard-coded
 * tweens, so an animation interrupted mid-flight carries its velocity into the new target instead
 * of restarting.
 *
 * Overlays (pre-alert border, takeover, history sheet, demo panel) deliberately stay in
 * [HudScreen] - they are driven by phase transitions rather than by steady state.
 */
@Composable
fun HudContent(
    clockText: String,
    statusText: String,
    chipLabel: String,
    chipColor: Color,
    isListening: Boolean,
    isRunning: Boolean,
    historyCount: Int,
    micLevel: StateFlow<Float>,
    alertIcon: ImageVector?,
    alertColor: Color,
    waveStyle: WaveStyle,
    direction: DirectionEstimate = DirectionEstimate.UNKNOWN,
    /**
     * Whether the cloud (Live) session is currently re-establishing itself.
     *
     * A resolved boolean rather than the raw `LiveState`, and already latched by the caller, for
     * two reasons: this stays a pure function of its parameters so the screenshot harness can
     * render it, and the same flag has to drive [chipLabel] and [chipColor], which the caller owns.
     */
    reconnecting: Boolean = false,
    /**
     * Whether the session is *still* connecting, as opposed to [reconnecting], which stays true for
     * a beat after it lands. The gap between the two is the window in which the ring drives to 100%
     * and is seen closed - see [rememberReconnectTrace].
     */
    reconnectActive: Boolean = false,
    /** Typography preset for the clock, cycled by a tap. See [com.soundsight.ui.theme.ClockTypography]. */
    clockStyle: ClockStyle = ClockStyle.STANDARD,
    /**
     * Live GNSS speed samples, or null when the rider's LocationMode is not "Always on". The
     * readout renders only when this is non-null AND the service is running; the value itself is
     * read in the draw phase - see [SpeedReadout].
     */
    speedSampleFlow: StateFlow<com.soundsight.core.SpeedSample?>? = null,
    /** Localized readout copy: the unit and the two no-reading states. */
    speedUnit: String = "km/h",
    speedNoFixLabel: String = "Waiting for GPS",
    speedHeldLabel: String = "signal lost",
    /**
     * Whether the speed number is drawn at all - a declutter preference, NOT the LocationMode
     * switch. False removes the readout from the tree entirely (not alpha-0): SpeedProvider keeps
     * streaming and every capture/collision path keeps recording speed; only the on-screen
     * number disappears. See [com.soundsight.core.OnboardingStore.speedReadoutVisibleFlow].
     */
    speedReadoutVisible: Boolean = true,
    /**
     * The 12-hour clock's meridiem mark ("AM"/"PM"/"SA"/"CH"), or null when the rider's clock is
     * 24-hour. The mark arrives SEPARATE from [clockText] on purpose: folding it into the clock
     * string would corrupt both the stacking-width measurement and [stackedClockParts]'s
     * colon-split. Its absence (null) is what turns the feature off - there is no second boolean.
     * It renders after and outside every width decision below, exactly the relationship
     * [SpeedReadout] has to the rest of the HUD: present, but never part of anyone else's layout
     * math. Computed from the hour value by the caller, never from a formatted date string - all
     * UI copy flows through the app's own VI/EN [com.soundsight.core.Strings].
     */
    amPmLabel: String? = null,
    onSettings: () -> Unit,
    onToggleRunning: () -> Unit,
    onHistory: () -> Unit,
    onWordmarkLongPress: () -> Unit,
    onClockTap: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val hazard = com.soundsight.ui.theme.LocalHazardColors.current
    val scope = rememberCoroutineScope()
    val alerting = alertIcon != null

    // Round 4, Step 1b: the control group must centre on the HOUR LINE, not on the clock's full
    // rendered height. Round 3 centred the header row against the clock as a whole; when the clock
    // stacked (long pill, portrait) that put the pill/history/settings row visibly below the top
    // of the hour digits - the "fix it to equal high as hour" report. The fix measures the hour
    // line once per (clockText, clockStyle) with the same textMeasurer pattern SqueezableClock
    // uses for its AM/PM offset math, then pads the control group down by half the difference
    // between that line's height and the group's own natural height (the 44dp icon buttons - the
    // pill is shorter). The header row itself becomes Top-aligned so both children start at the
    // same Y regardless of stacking; this padding is what does the centring.
    val density = LocalDensity.current
    val headerClockTypography = LocalClockTypography.current
    val headerTextMeasurer = rememberTextMeasurer()
    val hourLineHeightDp = remember(clockText, clockStyle, headerClockTypography, density) {
        val hourPart = stackedClockParts(clockText).first
        val style = headerClockTypography.forStyle(clockStyle)
        with(density) {
            headerTextMeasurer.measure(
                text = AnnotatedString(hourPart),
                style = style,
                maxLines = 1,
                softWrap = false
            ).size.height.toDp()
        }
    }

    // An alert washes the whole screen, not just the glyph. A rider is not looking at the phone
    // when the alert fires - they are looking at the road, and the phone is in peripheral vision.
    // A colour change across the entire surface is detectable there; a 72dp icon is not.
    // The wash is a tint of the theme background rather than a flat hazard fill, so the layout
    // stays readable and the full-bleed CRITICAL takeover is still a clear escalation above it.
    val washColor by animateColorAsState(
        targetValue = if (alerting) {
            lerpColor(colors.background, alertColor, 0.26f)
        } else {
            colors.background
        },
        animationSpec = Motion.defaultEffects(),
        label = "alert_wash"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (alerting) 1f else 0f,
        animationSpec = Motion.defaultEffects(),
        label = "alert_glow"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(washColor)
            .drawBehind {
                if (glowAlpha > 0f) {
                    // Radial bloom centred on the wave, so the colour is strongest where the
                    // information is and falls off towards the edges.
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                alertColor.copy(alpha = 0.30f * glowAlpha),
                                alertColor.copy(alpha = 0f)
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension * 0.85f
                        )
                    )
                }
            }
            .systemBarsPadding()
            .padding(
                start = HudEdgePadding,
                end = HudEdgePadding,
                top = HudTopPadding,
                bottom = HudBottomPadding
            )
    ) {
        val isCompactHeight = maxHeight < 520.dp
        // Sized off available *height*, never width: scaling off width is what made the wave fill
        // the screen when the phone was rotated into its primary landscape orientation.
        //
        // The landscape fraction is the larger of the two, which looks backwards until you see the
        // alerting state. The halo's radius comes from the *short* side of the wave box, so on a
        // short-and-wide screen a modest fraction produced a small ring marooned in a great deal
        // of empty width - the open waveform looked fine and the alert looked broken. Landscape is
        // also where the spare vertical room actually is, since the layout is only three stacked
        // pieces regardless of orientation.
        val waveHeight = (maxHeight * if (isCompactHeight) 0.50f else 0.26f).coerceIn(84.dp, 240.dp)
        val waveIcon = (waveHeight * 0.42f).coerceIn(36.dp, 76.dp)
        val showSpeedReadout =
            speedSampleFlow != null && isRunning && !alerting && speedReadoutVisible

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // Round 4, Step 1b: Top-aligned, not centred. Round 3's CentreVertically solved
                // the top-pinning problem (a pinned group bunching against only the first line of
                // a stacked clock) but introduced a new one: centring against the clock's FULL
                // rendered height put the control group visibly below the hour digits whenever
                // the clock stacked - the "fix it to equal high as hour" report. The alignment
                // now pins both children to the same top Y and [hourLineHeightDp]'s padding on
                // the control group below does the actual centring, against the HOUR LINE only,
                // in both the single-line and stacked cases.
                verticalAlignment = Alignment.Top
            ) {
                // Yield order (reversed in round 2, 2026-08-24, at the user's request - they used
                // both behaviours and prefer the original): the status pill measures first and
                // takes its natural one-line width; the clock is weighted and therefore measured
                // last, receiving whatever remains. A long pill ("Đang kết nối lại",
                // "Reconnecting") leaves the clock too little room for one line, and the clock
                // stacks minutes under hours - the outcome the original app produced with a
                // language-keyed flag, now reproduced from measured geometry alone (see
                // [shouldStackClock]). [SqueezableClock] still guards genuinely tiny windows with
                // a final scale fallback.
                //
                // Round 3, 2026-08-25 - the round-2 comment below was wrong about the trailing
                // edge. `weight(1f, fill = false)` let the clock report only its natural width,
                // so the pill/history/settings group packed hard against the digits and the right
                // side of the header sat empty - the "listening bar, history and settings too
                // close to the clock" report. Portrait now uses `weight(1f)` (fill = true): the
                // clock's SLOT fills its whole share, its content stays pinned to the leading
                // edge, and the control group lands flush against the true right edge with a real
                // gap to the clock. The stacking decision is untouched - [BoxWithConstraints]
                // reported the share as `constraints.maxWidth` under `fill = false` too, so
                // `availablePx`, the ratio and every measured-geometry test are unchanged.
                //
                // Round 3 gave landscape the opposite treatment on purpose - clock and controls
                // both content-sized, clustered together at the start edge - because the weight
                // existed only to absorb pill squeeze, which landscape never needs. Round 5
                // (second pass) reverses that: explicitly asked to put the group on the OPPOSITE
                // side from the clock, not next to it, so landscape now takes the same
                // `weight(1f)` treatment as portrait unconditionally. This is purely a docking
                // change - it does not reintroduce squeeze risk. `shouldStackClock`'s ratio only
                // gets LARGER as a result (availablePx is now the full row width minus the control
                // group instead of the clock's own natural width), and landscape's width margin
                // was already generous enough that "landscape never stacks because it simply has
                // the width" ([shouldStackClock]) holds even more comfortably now.
                //
                // Layout decisions in this row are geometry-only. No code here reads the chip's
                // *wording*: localized copy changes, and a layout keyed to specific Vietnamese or
                // English strings silently stops working the day the copy is rephrased. The clock
                // stacks from measured width alone; the pill renders its one natural line.
                //
                // The wordmark used to live here. A rider glancing down already knows which app is
                // running; what they actually want from a bar-mounted phone is the time, which
                // otherwise costs them a trip to the lock screen. The long-press that opens the
                // Demo panel moved onto the clock unchanged, and a quick tap now cycles typography
                // presets - both gestures live in the one detectTapGestures below, never split
                // across two pointerInput blocks, so they cannot race each other.
                SqueezableClock(
                    clockText = clockText,
                    clockStyle = clockStyle,
                    color = colors.onBackground,
                    amPmLabel = amPmLabel,
                    modifier = Modifier.weight(1f)
                        .pointerInput(Unit) {
                            // 2000ms long-press opens the Demo & Diagnostics panel in DEBUG builds only.
                            // An adb soak harness drives this exact element; preserve the duration
                            // and testTag (Hard Constraint 5 & Step 7d / H6). In release builds, this
                            // shortcut is inactive; entry is via Settings -> About (7 taps) -> Developer & Diagnostics.
                            detectTapGestures(
                                onTap = { onClockTap() },
                                onPress = {
                                    if (BuildConfig.DEBUG) {
                                        val job = scope.launch {
                                            delay(2000)
                                            onWordmarkLongPress()
                                        }
                                        tryAwaitRelease()
                                        job.cancel()
                                    }
                                }
                            )
                        }
                        .testTag("hud_wordmark")
                )

                // Round 4, Step 1a & Round 5, Step 1: explicit minimum gap between the clock's rendered
                // digits and the control group in both portrait and landscape.
                //
                // In portrait, the weight(1f) guarantees the group's POSITION (flush right) but not a
                // floor on the gap: a short pill + full-width control group could collapse the clock's
                // slot to almost exactly its natural text width, leaving ~21px on a 1080px screen - the
                // "still stick to the clock" report. As a real Row child this Spacer is subtracted from
                // the clock's weighted slot before SqueezableClock ever sees it, so shouldStackClock's
                // availablePx reflects it. 12dp is pinned here: the ClockStackThreshold remeasurement
                // showed 20dp already pushes the non-crowding "Đang nghe" chip's clock slot to 296px
                // against a 309px natural width (ratio 0.96) - enough to wrongly stack a label that
                // must stay on one line (pinned by ClockLayoutDecisionTest). Portrait cannot take the
                // wider landscape gap below without re-tripping that same regression.
                //
                // In landscape, SqueezableClock is weighted too (see the docking comment above), but
                // its available width is the full row minus the control group rather than the row
                // minus the pill/history/settings AND a squeezed clock slot, so this Spacer's width
                // still has no stacking side effect to protect against - "Landscape never stacks
                // because it simply has the width" (ClockStackThreshold doc) holds regardless. That
                // headroom is why landscape can and must use a wider value than portrait:
                // [drawReconnectTrace] in ReconnectTrace.kt
                // draws the status chip's dashed ring 6.6dp OUTSIDE the chip's own layout bounds
                // (RingOffset + WaveAmplitude + half of TraceStroke - see that file's comment, the same
                // 6.6dp that is why the chip-to-history gap below is 10dp and not 6dp). The first Round
                // 5 Step 1 fix closed the STRUCTURAL zero-gap bug (this Spacer was missing entirely in
                // landscape) by reusing 12dp, but that only bought ~5.4dp of true visible clearance from
                // the clock to the ring's outer edge while reconnecting - real, but thin enough on a
                // physical screen to still read as "touching" at a glance, which is exactly the state
                // ("Đang kết nối lại") both the original report and this regression were seen in. 18dp
                // keeps the same ring-bleed math the history gap already uses (18 - 6.6 = 11.4dp
                // clearance, comfortably more than that gap's own 3.4dp).
                Spacer(modifier = Modifier.width(if (isCompactHeight) 18.dp else 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        // Round 4, Step 1b: centre on the hour line, not on the clock's full
                        // height. Half the difference between the measured hour line and the
                        // group's natural height (the 44dp icon buttons; the pill is shorter)
                        // pushes the group down from the shared Top alignment so its middle sits
                        // level with the hour digits whether or not the clock stacked a second
                        // line under them. In landscape the clock never stacks and the one-line
                        // height matches the group, so this coerces to 0.dp - a no-op there.
                        .padding(top = ((hourLineHeightDp - 44.dp) / 2).coerceAtLeast(0.dp)),
                    // Unweighted on purpose: measured before the weighted clock, so the pill
                    // takes its natural one-line width and the clock absorbs the squeeze.
                    // (Portrait only; in landscape the clock is unweighted too - see above.)
                    // 10dp rather than 6: the status chip draws its reconnect trace outside its
                    // own bounds, and at 6dp the wave's outward crest reached the history button.
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatusChip(
                        label = chipLabel,
                        color = chipColor,
                        pulsing = isListening && isRunning,
                        reconnecting = reconnecting,
                        reconnectActive = reconnectActive
                    )

                    FilledTonalIconButton(
                        onClick = onHistory,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("hud_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = "History ($historyCount)",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("hud_settings_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = colors.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (chipColor == hazard.Critical) {
                    Surface(
                        color = hazard.Critical.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, hazard.Critical),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                            .testTag("hud_not_protecting_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = hazard.Critical,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = chipLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = hazard.Critical,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Switching wave style animates rather than swapping: the outgoing style scales
                // out and fades while the incoming one grows in, so the Settings control feels
                // like it is reshaping one object rather than replacing a picture.
                // The arrow sits on the side it is reporting, flanking the wave, rather than
                // in a fixed slot with an L/R letter in it. The rider does not have to decode
                // anything: the mark is simply over there, on the side the sound was.
                // A Box, not a Row: the indicators overlay the ends of the wave instead of
                // taking layout width from it. If they took width the wave would jump sideways
                // the moment an arrow appeared - and it appears exactly when the rider is least
                // able to tolerate the display rearranging itself.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(waveHeight),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = waveStyle,
                        // The two styles are two states of one object, so the outgoing style
                        // shrinks away rather than sliding off: nothing enters or leaves the
                        // screen, the mark just reshapes in place. Scale only, no fade-through
                        // - a crossfade here made the swap look like a page change.
                        transitionSpec = {
                            (
                                scaleIn(
                                    animationSpec = Motion.defaultSpatial(),
                                    initialScale = 0.90f
                                ) + fadeIn(animationSpec = Motion.fastEffects())
                                ) togetherWith (
                                scaleOut(
                                    animationSpec = Motion.fastSpatial(),
                                    targetScale = 0.94f
                                ) + fadeOut(animationSpec = Motion.fastEffects())
                                ) using SizeTransform(clip = false)
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "wave_style_switch"
                    ) { style ->
                        PulseWave(
                            level = micLevel,
                            active = isListening && isRunning,
                            accent = chipColor,
                            alertIcon = alertIcon,
                            alertColor = alertColor,
                            style = style,
                            iconSize = waveIcon,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    val flankTint = if (alerting) colors.onBackground else chipColor
                    val flankIcon = (waveHeight * 0.30f).coerceIn(28.dp, 46.dp)

                    // The chevrons are placed against the halo, not against the screen. Pinned to
                    // the outer edges they ended up half a screen away from the ring in landscape
                    // - reading as two unrelated marks rather than as an arrow pointing at the
                    // hazard. This inner box is sized from the halo's own radius so the gap stays
                    // constant, and it collapses to the full width on a screen too narrow for it.
                    Box(
                        modifier = Modifier
                            .widthIn(max = waveHeight * 1.7f)
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) {
                        DirectionIndicator(
                            direction = direction,
                            slot = Side.LEFT,
                            tint = flankTint,
                            iconSize = flankIcon,
                            showLabel = AlertConfig.directionLabelEnabled,
                            style = AlertConfig.arrowStyle,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                        DirectionIndicator(
                            direction = direction,
                            slot = Side.RIGHT,
                            tint = flankTint,
                            iconSize = flankIcon,
                            showLabel = AlertConfig.directionLabelEnabled,
                            style = AlertConfig.arrowStyle,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (isCompactHeight) 16.dp else 28.dp))

                // Status reads as a caption under the wave, not as a headline competing with it.
                // The wave is what tells the rider what is happening; this only names it.
                // Slides up as it changes rather than crossfading. Three separate crossfades
                // firing at once - chip, wave and this - read as the whole screen flickering;
                // giving each element its own kind of motion is what keeps them legible as
                // separate pieces of information.
                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = {
                        (
                            slideInVertically(animationSpec = Motion.defaultSpatial()) { it / 3 } +
                                fadeIn(animationSpec = Motion.defaultEffects())
                            ) togetherWith (
                            slideOutVertically(animationSpec = Motion.fastSpatial()) { -it / 3 } +
                                fadeOut(animationSpec = Motion.fastEffects())
                            )
                    },
                    label = "status_text"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (alerting) colors.onBackground else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("hud_listening_status_text")
                    )
                }
            }
        }

        StopControl(
            isRunning = isRunning,
            onClick = onToggleRunning,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        // Speed readout: bottom-left, the mirror of the Stop control, and deliberately NOT in
        // the header row or flow - that row's width budget is what the stacked-clock decision
        // spends, and a speedometer in it would crowd the top. Chrome, not a hazard signal:
        // theme colours only, and it stands down entirely during an alert so it cannot compete
        // with one.
        if (showSpeedReadout) {
            SpeedReadout(
                speedSampleFlow = requireNotNull(speedSampleFlow),
                unit = speedUnit,
                noFixLabel = speedNoFixLabel,
                heldLabel = speedHeldLabel,
                color = colors.onBackground,
                dimColor = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    }
}

/**
 * Content padding inside the safe area, applied on top of [systemBarsPadding].
 *
 * Asymmetric top-to-bottom, and that asymmetry is the point rather than an oversight. The two
 * edges are not carrying the same thing:
 *
 * - **Top** holds the clock, whose display face already ships a large amount of internal leading
 *   above the digits (a 44sp face on a 50sp line box). Stacking a symmetric 12dp on top of that
 *   leading read as the clock hanging well below the top of the screen with nothing in the gap;
 *   the padding here is trimmed close to nothing precisely because the typography is already
 *   supplying the optical gap.
 * - **Bottom** holds the Stop control and the speed readout, which are hit targets and glance
 *   targets with no internal leading of their own to borrow from. In landscape - the bar mount's
 *   primary orientation - the gesture navigation bar sits on a *side* edge, so the bottom inset is
 *   ~0 and these two controls sat flush against the physical edge of the panel. This is the
 *   padding that lifts them off it.
 *
 * Symmetric values here would have to satisfy both cases at once and would be wrong for one of
 * them; these are separated so each edge can be tuned against what actually sits on it.
 */
private val HudEdgePadding = 20.dp
private val HudTopPadding = 2.dp
private val HudBottomPadding = 24.dp

/**
 * Never below ~0.68x: a clock scaled past that stops being a glance-read and starts being a
 * squint, which defeats the point of it being the largest thing on screen after the wave. This is
 * the absolute minimum *scale* for whichever composition renders (single line or stacked); it is
 * no longer the stacking trigger - see [ClockStackThreshold].
 */
private const val ClockSqueezeFloor = 0.68f

/**
 * The clock stacks as soon as one line at its natural size would not fit the width it is given -
 * ratio < 1.0 - rather than shrinking first.
 *
 * Round 2, 2026-08-24: raised from 0.68 after reversing the header yield order (the pill now
 * measures first; see the HudContent header comment). Measured on the target device profile
 * (1080px-wide portrait, Robolectric NATIVE font metrics): with a long pill
 * ("Đang kết nối lại" / "Reconnecting" / "Đang lắng nghe") the clock receives ~269-278px against
 * a 309px natural width (ratio 0.87-0.90) - under 1.0, so it stacks, which is exactly what the
 * original language-keyed behaviour produced on these strings. With "Listening" or an idle chip
 * the ratio is >= 2.07, so it stays on one line. The old 0.68 clipping floor sat below all of
 * those ratios and could never stack them.
 *
 * Round 4, 2026-08-25 re-measured after Step 1a inserted the portrait minimum-gap Spacer between
 * the clock and the control group: the Spacer is a real Row child, so every slot above shrinks by
 * its 12dp (33px at the profile's 2.75 density). Long-pill slots are now ~236-245px (ratio
 * ~0.76-0.79) - still stacking; non-crowding labels sit at >= 318px (ratio >= 1.03) - still one
 * line. The threshold itself is unchanged; only the measured geometry around it moved.
 */
private const val ClockStackThreshold = 1.0f

/** Test-visible measured inputs to [shouldStackClock], populated by the real Compose layout. */
internal val ClockAvailableWidthKey = SemanticsPropertyKey<Int>("ClockAvailableWidthPx")
internal val ClockNaturalWidthKey = SemanticsPropertyKey<Int>("ClockNaturalWidthPx")
private var SemanticsPropertyReceiver.clockAvailableWidthPx by ClockAvailableWidthKey
private var SemanticsPropertyReceiver.clockNaturalWidthPx by ClockNaturalWidthKey

private data class ClockRenderState(val style: ClockStyle, val twoLine: Boolean)

/**
 * Whether the clock must stack, from measured pixels alone.
 *
 * True when rendering the clock on one line at its natural size would not fit the width it is
 * given (ratio below [ClockStackThreshold]) - stacking beats shrinking for a glance-read element.
 * There is no language, no label text and no orientation in this decision: localized copy changes,
 * and a layout keyed to specific Vietnamese/English strings would silently stop working the day
 * the wording is rephrased (and false-match unrelated copy in a third language). Geometry does
 * not. Landscape never stacks because it simply has the width.
 */
internal fun shouldStackClock(availableWidthPx: Int, naturalWidthPx: Int): Boolean {
    if (availableWidthPx <= 0 || naturalWidthPx <= 0) return false
    return availableWidthPx.toFloat() / naturalWidthPx < ClockStackThreshold
}

/**
 * Splits clock text for the stacked composition: hours above, minutes below. Round 2
 * (2026-08-24) restored the original look by keeping the colon on the minute line - "20" over
 * ":58", not "20" over "58". Text without a colon is returned whole on both lines (the caller
 * only stacks when a colon-shaped clock asked to).
 */
internal fun stackedClockParts(clockText: String): Pair<String, String> {
    val hour = clockText.substringBefore(":")
    val after = clockText.substringAfterLast(":")
    return if (after == clockText) {
        clockText to clockText
    } else {
        hour to ":$after"
    }
}

/**
 * The HUD clock, in whichever [ClockStyle] preset is active, drawn at its natural one-line size
 * when it fits and stacked minutes-under-hours when it does not; if even two lines do not fit, it
 * scales down to [ClockSqueezeFloor] rather than clipping.
 *
 * Round 2 reversed the header yield order: [HudContent] measures this glance-critical element
 * LAST - it is weighted, so the status pill takes its natural one-line width first and this clock
 * receives what remains. A long pill leaves it too little room for one line and it stacks, which
 * is the original behaviour the user asked back. Both directions animate on
 * [Motion.defaultSpatial], never a snap. Round 3: the weighting (and with it, last-in-row
 * measurement) is portrait-only now - in landscape the clock is unweighted and content-sized,
 * clustered at the header's start edge, where squeeze absorption has nothing to do.
 *
 * The stacking trigger is [shouldStackClock]: measured available width vs measured text width,
 * nothing else. The stacked composition keeps its colon ("20" above ":58") - restored in round 2,
 * 2026-08-24: the user used both looks and wants the colon back as part of the original stacked
 * clock's appearance.
 *
 * The layout width [BoxWithConstraints] reports upward is *not* affected by the graphicsLayer scale
 * (that is a paint-time transform only) - it is already clamped to the incoming constraint because
 * [Text] with `softWrap = false` always reports its measured width as `min(natural width,
 * constraints.maxWidth)`. That is what keeps this composable from ever pushing the row wider than
 * it was given, even before the scale-down is applied.
 */
@Composable
private fun SqueezableClock(
    clockText: String,
    clockStyle: ClockStyle,
    color: Color,
    modifier: Modifier = Modifier,
    amPmLabel: String? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The clock presets are one extended role, read through the theme rather than the file-level
    // helper - same resolution, but overridable in a preview/test harness via CompositionLocal.
    val clockTypography = LocalClockTypography.current
    val styleOf: (ClockStyle) -> TextStyle = clockTypography::forStyle

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopStart) {
        val availablePx = constraints.maxWidth
        val style = styleOf(clockStyle)
        val naturalWidthPx = remember(clockText, style, availablePx) {
            textMeasurer.measure(
                text = AnnotatedString(clockText),
                style = style,
                maxLines = 1,
                softWrap = false
            ).size.width
        }
        val singleLineRatio = if (naturalWidthPx <= 0 || availablePx <= 0) {
            1f
        } else {
            availablePx.toFloat() / naturalWidthPx
        }
        val twoLine = shouldStackClock(availablePx, naturalWidthPx)

        // When stacked in two lines, hours and minutes are stacked vertically to save width.
        // Decision (2026-08-24, round 2): the stacked clock KEEPS its colon - "20" above ":58".
        // The user used both looks and prefers the original, colon included. The leading colon
        // stays attached to the minutes; a clock text without a colon renders unchanged.
        val (hourPart, minutePart) = remember(clockText) { stackedClockParts(clockText) }
        val twoLineWidestPx = remember(hourPart, minutePart, style, amPmLabel, color, density) {
            val amPmExtraPx = if (amPmLabel != null) {
                with(density) { 4.dp.roundToPx() } +
                    textMeasurer.measure(
                        AnnotatedString(amPmLabel),
                        style = clockAmPmStyle(style, color),
                        maxLines = 1,
                        softWrap = false
                    ).size.width
            } else 0
            val minuteLinePx = textMeasurer.measure(
                AnnotatedString(minutePart),
                style = style,
                maxLines = 1,
                softWrap = false
            ).size.width + amPmExtraPx
            maxOf(
                textMeasurer.measure(AnnotatedString(hourPart), style = style, maxLines = 1, softWrap = false).size.width,
                minuteLinePx
            )
        }
        val twoLineRatio = if (twoLineWidestPx <= 0 || availablePx <= 0) {
            1f
        } else {
            availablePx.toFloat() / twoLineWidestPx
        }

        val targetScale = when {
            twoLine -> twoLineRatio.coerceIn(ClockSqueezeFloor, 1f)
            naturalWidthPx <= 0 || naturalWidthPx <= availablePx -> 1f
            else -> singleLineRatio.coerceIn(ClockSqueezeFloor, 1f)
        }
        val scale by animateFloatAsState(
            targetValue = targetScale,
            animationSpec = Motion.defaultSpatial(),
            label = "clock_squeeze"
        )
        // Scale, no crossfade - the same treatment the wave style switch right below this uses for
        // its own preset swap, so both read as one object reshaping rather than a picture changing.
        // The SizeTransform is what makes the one-line-to-two-line height change (and back) an
        // animation instead of a jump, matching the status chip's own break-and-expand.
        AnimatedContent(
            targetState = ClockRenderState(clockStyle, twoLine),
            transitionSpec = {
                (
                    scaleIn(animationSpec = Motion.defaultSpatial(), initialScale = 0.90f) +
                        fadeIn(animationSpec = Motion.fastEffects())
                    ) togetherWith (
                    scaleOut(animationSpec = Motion.fastSpatial(), targetScale = 0.94f) +
                        fadeOut(animationSpec = Motion.fastEffects())
                    ) using SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> Motion.defaultSpatial() })
            },
            modifier = Modifier
                // The regression test reads the exact geometry seen by this real composition and
                // feeds it back through [shouldStackClock]. These custom semantics are not spoken.
                .semantics {
                    clockAvailableWidthPx = availablePx
                    clockNaturalWidthPx = naturalWidthPx
                }
                .testTag("hud_clock_layout")
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // Anchored to the leading edge so the clock shrinks towards where it starts,
                    // not towards its own centre - starting position stays put as it squeezes.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .wrapContentSize(align = Alignment.TopStart, unbounded = true),
            label = "clock_style_switch"
        ) { state ->
            val lineStyle = styleOf(state.style)
            if (state.twoLine) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy((-4).dp)
                ) {
                    Text(
                        text = hourPart,
                        style = lineStyle,
                        color = color,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (amPmLabel == null) {
                        Text(
                            text = minutePart,
                            style = lineStyle,
                            color = color,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        // Stacked 12-hour: the mark trails the minutes on the same baseline.
                        // While the stack/squeeze trigger decision (shouldStackClock) is made from
                        // the clock digits alone, [twoLineWidestPx] above accounts for this mark and
                        // its 4dp spacer so the rendered minute line scales to fit within the slot.
                        Row {
                            Text(
                                text = minutePart,
                                style = lineStyle,
                                color = color,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.alignByBaseline()
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = amPmLabel,
                                style = clockAmPmStyle(lineStyle, color),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .alignByBaseline()
                                    .testTag("hud_clock_am_pm")
                            )
                        }
                    }
                }
            } else {
                if (amPmLabel == null) {
                    Text(
                        text = clockText,
                        style = lineStyle,
                        color = color,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    // Single-line 12-hour: the mark hangs under the clock, its left edge at the
                    // measured hour-part width - anchored to the hour digits' geometry, never
                    // centred under the whole string. Measured here with the same textMeasurer
                    // call pattern as [twoLineWidestPx]; like the stacked branch, none of this
                    // feeds back into [shouldStackClock] or the squeeze scale.
                    Box {
                        Text(
                            text = clockText,
                            style = lineStyle,
                            color = color,
                            maxLines = 1,
                            softWrap = false
                        )
                        val hourMeasure = remember(hourPart, lineStyle) {
                            textMeasurer.measure(
                                text = AnnotatedString(hourPart),
                                style = lineStyle,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        val lineMeasure = remember(clockText, lineStyle) {
                            textMeasurer.measure(
                                text = AnnotatedString(clockText),
                                style = lineStyle,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = amPmLabel,
                            style = clockAmPmStyle(lineStyle, color),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .absoluteOffset {
                                    IntOffset(hourMeasure.size.width, lineMeasure.size.height)
                                }
                                .testTag("hud_clock_am_pm")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Status pill.
 *
 * The dot's size is tied to whether the app is genuinely listening, which gives the state a second,
 * non-textual channel: a rider who cannot read the label at a glance can still see whether the
 * indicator is at full size or shrunken.
 *
 * A reconnect takes over the whole chip for as long as it lasts: the label says so, the colour
 * goes dim, and a progress ring fills around the outside of the pill. All three end together - the
 * ring closes into a complete circuit, and only then does the chip come back to full colour with
 * its listening label. See [drawReconnectTrace].
 */
@Composable
private fun StatusChip(
    label: String,
    color: Color,
    pulsing: Boolean,
    reconnecting: Boolean = false,
    reconnectActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Fast, not slow: this is a 9dp dot. On a mark this small a low-stiffness spring does not
    // read as weight, it reads as the indicator being slow to tell the truth.
    val dotScale by animateFloatAsState(
        targetValue = if (pulsing) 1f else 0.7f,
        animationSpec = Motion.fastSpatial(),
        label = "chip_dot"
    )

    val trace = rememberReconnectTrace(visible = reconnecting, active = reconnectActive)

    // The chip goes dim for the reconnect and bright again when it lands, so the colour change is
    // the completion signal. Animated rather than switched: a chip that snaps between two colours
    // reads as two different chips, and the transition itself is the thing being reported.
    val chipColor by animateColorAsState(
        targetValue = color,
        animationSpec = Motion.defaultEffects(),
        label = "chip_color"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            // First in the chain, so the trace is drawn against the pill's full outline rather
            // than the padded interior. Both animated values are read inside the draw lambda, so
            // a running trace invalidates the draw phase only and never recomposes the chip.
            .drawWithContent {
                drawContent()
                drawReconnectTrace(
                    progress = trace.progress.value,
                    phase = trace.phase.value,
                    // The chip's own colour, so the trace reads as the pill doing something
                    // rather than as a second, unrelated indicator parked on top of it.
                    color = chipColor,
                    alpha = trace.alpha.value
                )
            }
            .background(color = chipColor.copy(alpha = 0.16f), shape = PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("hud_status_chip")
    ) {
        Box(
            modifier = Modifier
                .size((9f * dotScale).dp)
                .background(color = chipColor, shape = CircleShape)
        )
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(animationSpec = Motion.defaultEffects()) togetherWith
                    fadeOut(animationSpec = Motion.fastEffects()))
                    // SizeTransform keeps a label change an animation rather than a jump. The
                    // pill renders its one natural line in every state (round 2, 2026-08-24):
                    // it is measured before the clock, and a long label squeezes the clock into
                    // stacking instead of wrapping here.
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> Motion.defaultSpatial() }))
            },
            label = "chip_label"
        ) { text ->
            Text(
                text = text,
                modifier = Modifier.testTag("hud_chip_label"),
                style = MaterialTheme.typography.labelLarge,
                color = chipColor,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Stop / start control.
 *
 * A squircle rather than a circle, and larger than a standard small FAB: it is the only control a
 * rider presses on this screen while wearing gloves, and a rounded square gives noticeably more
 * thumb area than a circle of the same visual weight. The corner radius animates between the two
 * states, so stopping and starting read as one object changing rather than two different buttons.
 */
@Composable
private fun StopControl(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hazard = LocalHazardColors.current
    val colors = MaterialTheme.colorScheme

    // Running is the tighter, more "button-like" corner; stopped relaxes towards the circle. The
    // shape is the state - the corner radius animates in place rather than swapping a shape token,
    // so stopping and starting read as one object deforming.
    val corner by animateDpAsState(
        targetValue = if (isRunning) 22.dp else 34.dp,
        animationSpec = Motion.dpSpatial(),
        label = "stop_corner"
    )
    val container by animateColorAsState(
        targetValue = if (isRunning) hazard.CriticalSoft else colors.primaryContainer,
        animationSpec = Motion.defaultEffects(),
        label = "stop_container"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = if (isRunning) hazard.OnCriticalSoft else colors.onPrimaryContainer,
        modifier = modifier
            .size(68.dp)
            .testTag("hud_stop_button")
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = isRunning,
                // Crisp and immediate. This is the one control on the screen with a direct
                // consequence, so its feedback should land the instant the finger lifts rather
                // than easing in like a decorative transition.
                transitionSpec = {
                    (
                        scaleIn(animationSpec = Motion.fastSpatial(), initialScale = 0.6f) +
                            fadeIn(animationSpec = Motion.fastEffects())
                        ) togetherWith (
                        scaleOut(animationSpec = Motion.fastSpatial(), targetScale = 0.6f) +
                            fadeOut(animationSpec = Motion.fastEffects())
                        )
                },
                label = "stop_icon"
            ) { running ->
                Icon(
                    imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = if (running) "Stop monitoring" else "Start monitoring",
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

/**
 * The bar-glance location-state and speed readout, bottom-left of the main screen (the mirror of
 * StopControl). It is one visual unit: LIVE/HELD/NO_FIX and the numeric speed are drawn together.
 *
 * The VALUE IS READ IN THE DRAW PHASE: [speedSampleFlow].value is read inside [drawBehind], never
 * collected into composition. This is the same discipline round 1 applied to `micLevel` in
 * PulseWave - a 1 Hz value is gentler than the audio level, but collecting it as state would
 * recompose the whole HUD once a second for a corner readout. The tick below exists only to
 * invalidate the draw at 4 Hz while the readout is visible, so a new sample, a LIVE→HELD ageing
 * step and the HELD expiry all appear without a single recomposition.
 *
 * States, all honest: LIVE shows the Doppler speed at full colour; HELD shows the last value dim,
 * with the held label - visibly stale, never presented as current; NO_FIX shows the no-fix label,
 * never a frozen value and never 0. Chrome, not a hazard signal: [color]/[dimColor] come from the
 * theme, never HazardColors.
 */
@Composable
private fun SpeedReadout(
    speedSampleFlow: StateFlow<com.soundsight.core.SpeedSample?>,
    unit: String,
    noFixLabel: String,
    heldLabel: String,
    color: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    val boostLevel = com.soundsight.ui.theme.LocalContrastBoostLevel.current
    // Font weight has no in-between, so it still switches once, at the midpoint of the ramp.
    val isBoosted = com.soundsight.ui.theme.LocalContrastBoosted.current
    val textMeasurer = rememberTextMeasurer()
    val tick: MutableIntState = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick.intValue++
        }
    }
    Box(
        modifier = modifier
            .then(
                if (boostLevel > 0f) {
                    // Width ramps from nothing so the readout does not suddenly acquire a box;
                    // the padding is applied whole, because reflowing text mid-fade is worse
                    // than reserving the space for the whole ramp.
                    Modifier.border(
                        width = com.soundsight.ui.theme.ContrastBoost
                            .borderWidth(boostLevel, 0.dp, 3.dp),
                        color = color,
                        shape = RoundedCornerShape(16.dp)
                    ).padding(horizontal = 8.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            )
            .drawBehind {
                tick.intValue // draw-phase invalidation driver; the value read is below
                val reading = com.soundsight.core.SpeedResolver.resolve(
                    speedSampleFlow.value,
                    com.soundsight.audio.motion.MotionSensor.nowMonotonicMs()
                )
                // Round 3 ports the one thing the losing round-2 copy did better: a bold
                // monospace numeral that reads like a purpose-built speedometer. Type styling
                // ONLY - the value is still read here in the draw phase (see the KDoc above);
                // collecting it into composition would reintroduce exactly the regression the
                // other copy shipped. LIVE/HELD/NO_FIX honesty and the 250ms tick are untouched.
                val numberStyle = TextStyle(
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = if (isBoosted) FontWeight.Black else FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-0.5).sp
                )
                val subStyle = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = if (isBoosted) FontWeight.Bold else FontWeight.W500
                )
                when {
                    reading.kmh == null -> {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = noFixLabel,
                            style = subStyle.copy(color = dimColor),
                            topLeft = Offset(0f, size.height / 2f)
                        )
                    }
                    else -> {
                        val valueColor = if (reading.state == com.soundsight.core.SpeedDisplayState.LIVE) {
                            color
                        } else {
                            dimColor
                        }
                        drawText(
                            textMeasurer = textMeasurer,
                            text = if (reading.kmh >= 99.95f) {
                                "${reading.kmh.toInt()}"
                            } else {
                                String.format(java.util.Locale.US, "%.1f", reading.kmh)
                            },
                            style = numberStyle.copy(color = valueColor),
                            topLeft = Offset(0f, 0f)
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = unit,
                            style = subStyle.copy(color = dimColor),
                            topLeft = Offset(0f, 48.sp.toPx())
                        )
                        if (reading.state == com.soundsight.core.SpeedDisplayState.HELD) {
                            drawText(
                                textMeasurer = textMeasurer,
                                text = heldLabel,
                                style = subStyle.copy(color = dimColor),
                                topLeft = Offset(0f, 66.sp.toPx())
                            )
                        }
                    }
                }
            }
            // Fixed footprint: wide enough for three digits at 44sp, tall enough for the value,
            // the unit and the held label. It reserves its corner; nothing reflows around it.
            .size(132.dp, 96.dp)
            .testTag("hud_speed_readout")
    )
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t
    )

/**
 * The 12-hour clock's AM/PM mark: small and faded from the start, because it is chrome confirming
 * a reading, not information competing with the clock itself. Reuses the size relationship
 * [SpeedReadout]'s unit label has to its number (12sp/16sp, W500 - the same numbers as that
 * composable's sub style) rather than inventing new ones, and fades through the clock's own
 * colour rather than introducing a second tone.
 */
private fun clockAmPmStyle(lineStyle: TextStyle, clockColor: Color): TextStyle = lineStyle.copy(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.W500,
    color = clockColor.copy(alpha = 0.55f)
)
