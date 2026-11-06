package com.soundsight.ui.emergency

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soundsight.core.EmergencyProfile
import com.soundsight.core.LocationHelper
import com.soundsight.core.LocationMode
import com.soundsight.core.OnboardingStore
import com.soundsight.core.Strings
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape

/**
 * What the phone shows after a detected collision.
 *
 * ## This screen never calls or texts anyone by itself
 *
 * Both actions open the phone's own dialer or messaging app with the number and body pre-filled,
 * and a human presses send. Three separate reasons, all of which would have to be wrong before
 * auto-sending became correct:
 *
 * 1. **The trigger is not validated against real crash data.** `MotionSpikeDetector`'s thresholds
 *    came from a handful of measurements by one person on one phone, the most severe of which was
 *    a drop onto something soft. A system that auto-dials on that evidence will eventually
 *    auto-dial because somebody dropped their phone.
 * 2. **`SEND_SMS` and `CALL_PHONE` are restricted permissions.** `ACTION_DIAL` and `ACTION_SENDTO`
 *    need no permission at all and are the documented way to do this.
 * 3. **A false auto-call has a real cost** - a family member's phone ringing to tell them their
 *    Deaf relative has crashed, when they have not.
 *
 * ## It has two audiences
 *
 * The rider, who taps a button; and a passer-by, for whom **the screen itself is the message**.
 * That second audience is the entire reason this screen does not auto-dismiss and cannot be
 * closed by tapping the background - unlike [com.soundsight.ui.hud.AlertTakeover], which is
 * deliberately tap-anywhere-dismissable. An unconscious rider's phone has to keep showing this.
 * Only the explicit "I'm OK" button closes it, and it sits below the call actions so it cannot be
 * reached first by someone scrolling - or, in landscape, by someone glancing.
 *
 * ## Both pane arrangements, and why landscape is not a nicety
 *
 * A phone thrown from a bike lands in whatever orientation it lands in, and the stranger who picks
 * it up gets whatever is on the screen. In a single scrolling column the call buttons fall below
 * the fold in landscape, so a passer-by would have to *discover that the screen scrolls* before
 * finding the one control this screen exists to offer. So the layout branches:
 *
 * - **Stacked** - one column: identity above, actions below, the whole thing scrollable because a
 *   long medical note legitimately runs past the bottom.
 * - **Split** - two panes. Identity and medical detail on the left, every action button on the
 *   right, all of them on screen at once with no scrolling needed to reach them. Only the left
 *   pane scrolls, and only if the medical notes are long enough to need it; the buttons never move.
 *
 * The branch is decided by [crashResponseUsesSplitLayout]: both panes must be *usably large*,
 * not merely wider-than-tall. The raw `maxWidth > maxHeight` comparison this replaces also held
 * for square-ish windows (a fold half-open) and compact-height wide windows, where a "landscape"
 * split would cram one of the two panes below any usable size - a wrong guess to make on the one
 * screen that must remain readable by whoever finds the phone.
 *
 * The ordering rule survives the branch unchanged: "I'm OK" stays last and stays low-emphasis in
 * both layouts.
 *
 * @param isPreview True when [profile] is [EmergencyProfile.demoPlaceholder] rather than the
 * rider's own saved data - it adds a chip saying so. Without it the preview shows a plausible name
 * and blood type in the exact styling of the real thing, and someone could reasonably walk away
 * believing they had already filled the profile in.
 */
/**
 * Minimum pane size for the two-pane split, as compatibility starting points preserved from the
 * previously verified shapes: 640×360 (short landscape) and 873×393 both still split; a square-ish
 * window or a compact-height wide one now stacks instead of guessing. Boundary tests decide whether
 * these thresholds move - they are not design truth in themselves.
 */
private val CrashSplitMinWidth = 600.dp
private val CrashSplitMinHeight = 360.dp

/**
 * The split-vs-stacked decision: both dimensions must clear their minimum usable pane size.
 *
 * Replaces `maxWidth > maxHeight`, which a square-ish or fold-posture window satisfied while
 * leaving a pane too small for the identity/actions composition. On a safety screen that is the
 * wrong place to gamble on aspect ratio.
 */
internal fun crashResponseUsesSplitLayout(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth >= CrashSplitMinWidth && maxHeight >= CrashSplitMinHeight

@Composable
fun CrashResponseScreen(
    profile: EmergencyProfile,
    strings: Strings,
    onDismiss: () -> Unit,
    isPreview: Boolean = false
) {
    val hazard = LocalHazardColors.current
    val context = LocalContext.current
    val bg = hazard.Critical
    val fg = hazard.OnCritical

    // Keep the screen awake for as long as this is showing. A phone that locks itself thirty
    // seconds after a crash takes the medical information away from whoever picked it up.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = Motion.DurationAlertIn,
                easing = Motion.EmphasizedDecelerate
            )
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .testTag("crash_response_screen")
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Measured after the system bars are taken out, so a landscape phone with a wide
                // cutout still resolves the way it looks rather than the way the display is spec'd.
                val useSplitLayout = crashResponseUsesSplitLayout(maxWidth, maxHeight)

                if (useSplitLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                // The left pane is the only thing that can scroll, and only a long
                                // medical note makes it need to. The buttons opposite it are
                                // unaffected either way, which is the whole point of the split.
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CrashIdentity(
                                profile = profile,
                                strings = strings,
                                fg = fg,
                                compact = true,
                                isPreview = isPreview
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CrashActions(
                                profile = profile,
                                strings = strings,
                                context = context,
                                bg = bg,
                                fg = fg,
                                onDismiss = onDismiss,
                                buttonHeight = 56.dp,
                                gap = 10.dp,
                                okGap = 24.dp
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CrashIdentity(
                            profile = profile,
                            strings = strings,
                            fg = fg,
                            compact = false,
                            isPreview = isPreview
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        CrashActions(
                            profile = profile,
                            strings = strings,
                            context = context,
                            bg = bg,
                            fg = fg,
                            onDismiss = onDismiss,
                            buttonHeight = 72.dp,
                            gap = 12.dp,
                            okGap = 28.dp
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Who this is and what a paramedic needs to know, in one block.
 *
 * [compact] is the landscape cut: the same content at smaller type, because in landscape this
 * shares the screen with the action column instead of sitting above it.
 */
@Composable
private fun ColumnScope.CrashIdentity(
    profile: EmergencyProfile,
    strings: Strings,
    fg: Color,
    compact: Boolean,
    isPreview: Boolean
) {
    if (isPreview) {
        // Outlined rather than filled, and above everything else: it has to be the first thing
        // read, without competing with the blood-type pill that a bystander actually needs.
        Surface(
            color = Color.Transparent,
            contentColor = fg,
            shape = PillShape,
            border = BorderStroke(1.dp, fg.copy(alpha = 0.7f)),
            modifier = Modifier.testTag("crash_preview_chip")
        ) {
            Text(
                text = strings.crashPreviewChip,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
        Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
    }

    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = null,
        tint = fg,
        modifier = Modifier.size(if (compact) 44.dp else 72.dp)
    )

    Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))

    Text(
        text = strings.crashTitle,
        style = if (compact) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.displaySmall
        },
        color = fg,
        fontWeight = FontWeight.W800,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("crash_title")
    )

    if (profile.riderName.isNotBlank()) {
        Spacer(modifier = Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            text = profile.riderName,
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("crash_rider_name")
        )
    }

    if (profile.bloodType.isNotBlank()) {
        Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
        Surface(
            color = fg.copy(alpha = 0.18f),
            contentColor = fg,
            shape = PillShape,
            modifier = Modifier.testTag("crash_blood_type")
        ) {
            Text(
                text = "${strings.crashBloodLabel}: ${profile.bloodType}",
                style = if (compact) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.Bold,
                modifier = if (compact) {
                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                } else {
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                }
            )
        }
    }

    if (profile.medicalNotes.isNotBlank()) {
        Spacer(modifier = Modifier.height(if (compact) 10.dp else 14.dp))
        Text(
            text = profile.medicalNotes,
            style = if (compact) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("crash_medical_notes")
        )
    }

    Spacer(modifier = Modifier.height(if (compact) 10.dp else 16.dp))

    Text(
        text = strings.crashBystanderNote,
        style = MaterialTheme.typography.labelMedium,
        color = fg.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("crash_bystander_note")
    )
}

/**
 * The action buttons, in the one order they are allowed to appear in.
 *
 * Both layouts call this, so the ordering rule - call actions first, "I'm OK" last and visibly
 * quieter than the rest - is stated once and cannot drift between orientations. Only the
 * measurements differ: landscape gets shorter buttons and tighter gaps to fit the column.
 */
@Composable
private fun ColumnScope.CrashActions(
    profile: EmergencyProfile,
    strings: Strings,
    context: Context,
    bg: Color,
    fg: Color,
    onDismiss: () -> Unit,
    buttonHeight: Dp,
    gap: Dp,
    okGap: Dp
) {
    val scope = rememberCoroutineScope()
    CrashActionButton(
        label = callLabel(strings, profile.contact1Name, profile.contact1Phone),
        icon = Icons.Filled.Call,
        container = fg,
        content = bg,
        testTag = "btn_crash_call_1",
        height = buttonHeight,
        onClick = { dial(context, profile.contact1Phone, strings) }
    )

    if (profile.hasSecondContact) {
        Spacer(modifier = Modifier.height(gap))
        CrashActionButton(
            label = callLabel(strings, profile.contact2Name, profile.contact2Phone),
            icon = Icons.Filled.Call,
            container = fg,
            content = bg,
            testTag = "btn_crash_call_2",
            height = buttonHeight,
            onClick = { dial(context, profile.contact2Phone, strings) }
        )
    }

    Spacer(modifier = Modifier.height(gap))

    CrashActionButton(
        label = strings.crashSendLocation,
        icon = Icons.Filled.Sms,
        container = fg.copy(alpha = 0.24f),
        content = fg,
        testTag = "btn_crash_send_location",
        height = buttonHeight,
        onClick = { composeLocationSms(context, profile, strings, scope) }
    )

    Spacer(modifier = Modifier.height(okGap))

    // Least prominent of the three, and last. The rider who is fine can find it; it is
    // not something a disoriented person hits before the call button.
    CrashActionButton(
        label = strings.crashImOk,
        icon = null,
        container = fg.copy(alpha = 0.14f),
        content = fg,
        testTag = "btn_crash_im_ok",
        height = buttonHeight,
        onClick = onDismiss
    )
}

@Composable
private fun CrashActionButton(
    label: String,
    icon: ImageVector?,
    container: Color,
    content: Color,
    testTag: String,
    onClick: () -> Unit,
    height: Dp = 72.dp
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .testTag(testTag)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/** "Call Mai", or the bare number when no name was saved. */
private fun callLabel(strings: Strings, name: String, phone: String): String =
    if (name.isBlank()) phone else "${strings.crashCallButton} $name"

/**
 * Opens the dialer with the number filled in. Does **not** place the call.
 *
 * [Uri.fromParts] rather than `Uri.parse("tel:$phone")` because saved numbers routinely contain
 * spaces, dashes and parentheses; fromParts encodes the opaque part correctly, where string
 * concatenation produces a URI the dialer silently rejects.
 */
private fun dial(context: Context, phone: String, strings: Strings) {
    if (phone.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, strings.crashNoAppFound, Toast.LENGTH_LONG).show()
    }
}

/**
 * Opens the messaging app with a pre-written message. Does **not** send it.
 *
 * Location is read here, at the moment of the tap, and nowhere else - never in the background and
 * never on a schedule. When the rider's LocationMode allows it (collision-only / always-on), one
 * fresh fix is requested with a short timeout so the message carries a current position rather
 * than a possibly-stale cache; in OFF mode, and whenever the fresh fix does not arrive in time,
 * this is exactly today's cached-fix behaviour. If there is no permission or no fix at all, the
 * message still gets composed; it just says location is unavailable, which is more useful than no
 * message at all.
 */
private fun composeLocationSms(
    context: Context,
    profile: EmergencyProfile,
    strings: Strings,
    scope: CoroutineScope
) {
    scope.launch {
        val mode = try {
            OnboardingStore.locationModeFlow(context).first()
        } catch (_: Exception) {
            LocationMode.OFF
        }
        val location = if (mode == LocationMode.OFF) {
            LocationHelper.lastKnownLocation(context)
        } else {
            LocationHelper.freshFix(context)
        }
        sendCrashSms(context, profile, strings, location)
    }
}

private fun sendCrashSms(
    context: Context,
    profile: EmergencyProfile,
    strings: Strings,
    location: Location?
) {
    val name = profile.riderName.ifBlank { strings.appName }
    val body = if (location != null) {
        String.format(
            strings.crashSmsBody,
            name,
            LocationHelper.mapsLink(location),
            LocationHelper.ageMinutes(location)
        )
    } else {
        String.format(strings.crashSmsBodyNoLocation, name)
    }

    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", profile.contact1Phone, null))
        .apply { putExtra("sms_body", body) }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, strings.crashNoAppFound, Toast.LENGTH_LONG).show()
    }
}
