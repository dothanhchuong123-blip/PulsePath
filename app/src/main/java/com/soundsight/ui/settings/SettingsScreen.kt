package com.soundsight.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CrisisAlert
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soundsight.BuildConfig
import com.soundsight.audio.ListenerService
import androidx.core.content.ContextCompat
import com.soundsight.audio.Sensitivity
import com.soundsight.audio.motion.MotionSensor
import com.soundsight.core.AlertConfig
import com.soundsight.core.AppLanguage
import com.soundsight.core.ArrowStyle
import com.soundsight.core.ContrastBoostMode
import com.soundsight.core.LocationBatteryCost
import com.soundsight.core.LocationMode
import com.soundsight.core.OnboardingStore
import com.soundsight.light.LightSensor
import com.soundsight.core.SoundSightStrings
import com.soundsight.core.developerCountdownText
import com.soundsight.core.PeriodicLocationRefresher
import com.soundsight.core.SpeedProvider
import com.soundsight.data.live.LiveSession
import com.soundsight.domain.HapticPattern
import com.soundsight.ui.Haptics
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.demo.DemoPanel
import com.soundsight.ui.demo.planDynamicColorReset
import com.soundsight.ui.FormScreenScaffold
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape
import com.soundsight.ui.theme.groupedShape
import kotlinx.coroutines.launch

/**
 * Section header. Uses the primary colour rather than a divider to separate groups: dividers add a
 * horizontal line per group, which on a settings list this long reads as clutter.
 */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * One toggle row.
 *
 * The whole row is one `toggleable` node with [Role.Switch], not a Card-plus-Switch pair that
 * each handle clicks: two independently-clickable controls performing one action is exactly what
 * assistive technologies should not have to narrate twice. The nested Switch is display-only
 * (`onCheckedChange = null`) - a visual reflection of the row's state. The 72dp minimum keeps the
 * whole-row target gloves can actually hit; the corner radius shapes recognition, it does not
 * enlarge the target.
 */
@Composable
internal fun SettingSwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    shape: Shape? = null,
    enabled: Boolean = true
) {
    Card(
        shape = shape ?: MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .testTag(testTag)
    ) {
        ListItem(
            headlineContent = {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}


/**
 * Segmented selector where the selected option is physically larger than the others.
 *
 * Material 3's selection guidance leans on size and shape, not only fill, to mark the active
 * choice - and for this app that is the difference between a rider seeing which sensitivity is
 * active at a glance and having to compare two similar tonal fills in sunlight. The selected chip
 * takes more width and a taller height, and both animate on the app's spatial spring so switching
 * reads as the selection physically growing into place.
 *
 * Size and fill are reinforcement, never the sole cue: each option is a real `selectable` node
 * with [Role.RadioButton] inside a [selectableGroup], so the selection contract is announced -
 * "selected"/"not selected", one-of-many - rather than implied by pixels alone.
 */
@Composable
internal fun <T> EmphasisSelector(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    testTagFor: (T) -> String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val weight by animateFloatAsState(
                targetValue = if (isSelected) 1.7f else 1f,
                animationSpec = Motion.defaultSpatial(),
                label = "selector_weight"
            )
            val height by animateDpAsState(
                targetValue = if (isSelected) 60.dp else 48.dp,
                animationSpec = Motion.dpSpatial(),
                label = "selector_height"
            )
            val container by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                animationSpec = Motion.defaultEffects(),
                label = "selector_container"
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = Motion.defaultEffects(),
                label = "selector_content"
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = container,
                contentColor = content,
                modifier = Modifier
                    .weight(weight)
                    .height(height)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) }
                    )
                    .testTag(testTagFor(value))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = if (isSelected) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/** Mic sensitivity as an immediate, four-stop Material 3 slider. */
@Composable
private fun SensitivitySlider(
    label: String,
    optionLabels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    allowMaxStop: Boolean = true
) {
    val strings = SoundSightStrings.current

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier
                                .size(26.dp)
                                .testTag("sensitivity_icon")
                        )
                    }
                }
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_sensitivity_label")
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = PillShape
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(
                            text = optionLabels.getOrElse(selectedIndex) { "" },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("sensitivity_value_label")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            MaterialSensitivitySlider(
                label = label,
                optionLabels = optionLabels,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                increaseActionLabel = strings.a11yIncrease,
                decreaseActionLabel = strings.a11yDecrease,
                revealLastStopAfterIndex = Sensitivity.HIGH.ordinal,
                allowMaxStop = allowMaxStop
            )

            // Hard-constraint 8: the Max stop's multiplier is derived (taper continuation), not
            // measured, so the UI says so in plain words exactly while Max is the live setting.
            if (allowMaxStop && selectedIndex == Sensitivity.MAX.ordinal) {
                Text(
                    text = strings.sensitivityMaxSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("sensitivity_max_note")
                )
            }
        }
    }
}

/**
 * The looping illustration that heads a feature section.
 *
 * Direction arrow and collision detection are the two settings on this screen whose *name* does
 * not tell a rider what turning them on will look like - one draws a mark they have never seen,
 * the other opens a screen they hopefully never will. A sentence of supporting text was carrying
 * that load and doing it badly. Showing the feature happening, on a phone, on a bike, answers it
 * before the switch is touched.
 *
 * It runs continuously rather than on tap: this sits at the top of its group where it is the first
 * thing scrolled to, and a still frame of a motion design reads as a broken image.
 *
 * ## Why the card has a maximum width
 *
 * `IllustrationSupport.stage()` is a **cover** fit - `k = max(width / srcW, height / srcH)` - so
 * the card's aspect ratio, not its size, is what decides which slice of the drawing survives. At a
 * fixed height, a wider card means a larger `k`, and past the authored aspect the scale is driven
 * entirely by width: rotate the phone and a 361dp card becomes an ~800dp one at the same height,
 * the drawing scales up about 2x, and everything outside the centre band is cropped away.
 *
 * An earlier attempt fixed this from the other end, by widening the virtual viewport - which
 * un-cropped the sides by pulling the lens back and shrinking the subject, exactly the trade
 * [com.soundsight.ui.settings.illustration] rejected when it was authored. So the composition and
 * `stage()` are untouched and the *card* is capped instead: at most [aspect] times its height, and
 * centred in whatever width is left over. In portrait the cap never binds; in landscape it is the
 * entire fix, and the lens stays as framed in both.
 */
@Composable
internal fun IllustrationPanel(
    shape: Shape,
    testTag: String,
    // Each illustration sets its own height, because each is framed differently: the card's aspect
    // ratio is what decides how much of the drawing survives the crop, so it is part of the
    // framing decision rather than a layout constant they can share.
    height: Dp = 260.dp,
    // The width:height ratio this illustration was framed against, passed explicitly by each call
    // site for the same reason the height is: it is a framing decision, not a layout constant.
    aspect: Float = 1.39f,
    illustration: @Composable (Modifier) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .height(height)
                .widthIn(max = height * aspect)
                .fillMaxWidth()
                .testTag(testTag)
        ) {
            illustration(Modifier.fillMaxSize())
        }
    }
}

/**
 * A row that navigates somewhere else, which can be dimmed out when its owning feature is off.
 *
 * Dimmed rather than hidden. The emergency profile only does anything once collision detection can
 * fire, but removing it would leave a rider who filled it in wondering where their data went - and
 * would hide the one control that explains *why* the feature above it is worth turning on.
 */
@Composable
internal fun SettingNavRow(
    title: String,
    supporting: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    shape: Shape? = null
) {
    Card(
        shape = shape ?: MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        ListItem(
            headlineContent = {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint)
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

/**
 * High-contrast boost card in Appearance settings.
 *
 * When an ambient light sensor is present, displays the 3-way [EmphasisSelector] for
 * AUTO, ALWAYS_ON, and OFF.
 * When no ambient light sensor is found, gracefully degrades to a manual toggle
 * explaining that the device lacks the required hardware.
 */
@Composable
internal fun ContrastBoostSettingCard(
    hasLightSensor: Boolean,
    contrastBoostMode: ContrastBoostMode,
    onModeChange: (ContrastBoostMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = SoundSightStrings.current
    if (hasLightSensor) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = modifier.fillMaxWidth().testTag("card_contrast_boost")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = strings.contrastBoostLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.contrastBoostSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_contrast_boost_sub")
                )
                Spacer(modifier = Modifier.height(12.dp))
                EmphasisSelector(
                    options = listOf(
                        ContrastBoostMode.AUTO to strings.contrastBoostAuto,
                        ContrastBoostMode.ALWAYS_ON to strings.contrastBoostAlwaysOn,
                        ContrastBoostMode.OFF to strings.contrastBoostOff
                    ),
                    selected = contrastBoostMode,
                    onSelect = onModeChange,
                    testTagFor = { "chip_contrast_${it.name.lowercase()}" }
                )
            }
        }
    } else {
        SettingSwitchRow(
            title = strings.contrastBoostLabel,
            supporting = strings.contrastBoostNoSensorSub,
            checked = contrastBoostMode == ContrastBoostMode.ALWAYS_ON,
            testTag = "switch_contrast_boost_manual",
            onCheckedChange = { checked ->
                val mode = if (checked) ContrastBoostMode.ALWAYS_ON else ContrastBoostMode.OFF
                onModeChange(mode)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    viewModel: HudViewModel,
    onNavigateToDirectionArrow: () -> Unit = {},
    onNavigateToCollision: () -> Unit = {},
    onThemeModeChange: (String) -> Unit = {},
    onTestAlert: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = SoundSightStrings.current
    val hazard = LocalHazardColors.current
    val haptics = remember(context) { Haptics(context) }

    var currentSensitivity by remember { mutableStateOf(ListenerService.sensitivity) }
    var vibrationOn by remember { mutableStateOf(AlertConfig.vibrationEnabled) }
    var takeoverOn by remember { mutableStateOf(AlertConfig.t2TakesOverScreen) }
    var arrowOn by remember { mutableStateOf(AlertConfig.directionArrowEnabled) }
    var arrowStyle by remember { mutableStateOf(AlertConfig.arrowStyle) }
    var directionLabelOn by remember { mutableStateOf(AlertConfig.directionLabelEnabled) }
    var collisionOn by remember { mutableStateOf(AlertConfig.motionSensorsEnabled) }
    var cloudOn by remember { mutableStateOf(AlertConfig.t2Enabled) }
    var themeMode by remember { mutableStateOf(AlertConfig.themeMode) }
    val hasLightSensor = remember { LightSensor.hasSensor(context) }
    var contrastBoostMode by remember { mutableStateOf(AlertConfig.contrastBoostMode) }
    var showResetDialog by remember { mutableStateOf(false) }
    var speedReadoutOn by remember { mutableStateOf(true) }
    var locationMode by remember { mutableStateOf(LocationMode.OFF) }
    var locationPermissionDenied by remember { mutableStateOf(false) }
    var pendingLocationMode by remember { mutableStateOf<LocationMode?>(null) }
    var keepMonitoringInBackgroundOn by remember { mutableStateOf(AlertConfig.keepMonitoringInBackground) }
    var sirenAlertInBackgroundOn by remember { mutableStateOf(AlertConfig.sirenAlertInBackground) }
    var showBatteryOptDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        OnboardingStore.contrastBoostModeFlow(context).collect {
            contrastBoostMode = it
            AlertConfig.contrastBoostMode = it
        }
    }
    LaunchedEffect(Unit) {
        OnboardingStore.locationModeFlow(context).collect { locationMode = it }
    }
    LaunchedEffect(Unit) {
        OnboardingStore.speedReadoutVisibleFlow(context).collect { speedReadoutOn = it }
    }
    LaunchedEffect(Unit) {
        OnboardingStore.keepMonitoringInBackgroundFlow(context).collect {
            keepMonitoringInBackgroundOn = it
            AlertConfig.keepMonitoringInBackground = it
        }
    }
    LaunchedEffect(Unit) {
        OnboardingStore.sirenAlertInBackgroundFlow(context).collect {
            sirenAlertInBackgroundOn = it
            AlertConfig.sirenAlertInBackground = it
        }
    }
    var demoPanelVisited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        OnboardingStore.demoPanelVisitedFlow(context).collect { visited ->
            demoPanelVisited = visited
            if (!visited && currentSensitivity == Sensitivity.MAX) {
                currentSensitivity = Sensitivity.HIGH
                ListenerService.sensitivity = Sensitivity.HIGH
                AlertConfig.demoFastAlert = false
                scope.launch { OnboardingStore.setSensitivity(context, Sensitivity.HIGH) }
            }
        }
    }
    var developerModeUnlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        OnboardingStore.developerModeUnlockedFlow(context).collect { unlocked ->
            developerModeUnlocked = unlocked
        }
    }
    val tapCounter = remember { DeveloperTapCounter() }
    var activeToast by remember { mutableStateOf<Toast?>(null) }

    var showDemoPanel by remember { mutableStateOf(false) }
    var showDemoDisclaimerDialog by remember { mutableStateOf(false) }

    fun requestOpenDemo() {
        if (viewModel.hasAcknowledgedDemoDisclaimer()) {
            viewModel.markDemoPanelVisited()
            scope.launch { OnboardingStore.setDemoPanelVisited(context) }
            showDemoPanel = true
        } else {
            showDemoDisclaimerDialog = true
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "Failed to launch battery optimization request: ${e.message}")
                }
            }
        }
    }

    fun restartListenerServiceForNewType() {
        // Android 14+ fixes the FGS type when startForeground runs; a service that began as
        // microphone-only cannot grow a location type mid-flight, so a mode change while
        // listening restarts the service exactly once so it re-picks its type at start.
        if (ListenerService.isRunning.value) {
            ListenerService.stop(context)
            ListenerService.start(context)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val requested = pendingLocationMode
        pendingLocationMode = null
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine && requested != null) {
            locationPermissionDenied = false
            locationMode = requested
            scope.launch { OnboardingStore.setLocationMode(context, requested) }
            restartListenerServiceForNewType()
        } else {
            // Declined: fall back to Off, say so in the row, leave everything else working -
            // the standing contract that the app is fully functional with location denied.
            locationPermissionDenied = true
            locationMode = LocationMode.OFF
            SpeedProvider.stop()
            PeriodicLocationRefresher.stop()
            scope.launch { OnboardingStore.setLocationMode(context, LocationMode.OFF) }
        }
    }

    fun applyLocationMode(mode: LocationMode) {
        locationMode = mode
        if (mode == LocationMode.OFF) {
            SpeedProvider.stop()
            PeriodicLocationRefresher.stop()
        }
        scope.launch { OnboardingStore.setLocationMode(context, mode) }
        restartListenerServiceForNewType()
    }

    fun selectLocationMode(mode: LocationMode) {
        if (mode == LocationMode.OFF) {
            locationPermissionDenied = false
            applyLocationMode(mode)
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            locationPermissionDenied = false
            applyLocationMode(mode)
        } else {
            pendingLocationMode = mode
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * Puts the in-memory config, the persisted store and this screen's own state back to their
     * defaults in one pass, and starts or stops the two services whose lifecycle follows a
     * setting. Written once here rather than spread across the switches because a reset that
     * missed a service would leave the sensors running for a feature the UI now says is off.
     */
    fun applyDefaults() {
        currentSensitivity = Sensitivity.MEDIUM
        vibrationOn = true
        takeoverOn = true
        arrowOn = false
        arrowStyle = ArrowStyle.CHEVRON
        directionLabelOn = false
        collisionOn = false
        cloudOn = true
        themeMode = "SYSTEM"
        speedReadoutOn = true
        locationMode = LocationMode.OFF
        locationPermissionDenied = false

        ListenerService.sensitivity = Sensitivity.MEDIUM
        AlertConfig.vibrationEnabled = true
        AlertConfig.t2TakesOverScreen = true
        AlertConfig.directionArrowEnabled = false
        AlertConfig.arrowStyle = ArrowStyle.CHEVRON
        AlertConfig.directionLabelEnabled = false
        AlertConfig.motionSensorsEnabled = false
        AlertConfig.t2Enabled = true
        AlertConfig.waveStyleDots = false
        AlertConfig.themeMode = "SYSTEM"
        AlertConfig.keepMonitoringInBackground = false
        AlertConfig.sirenAlertInBackground = false

        keepMonitoringInBackgroundOn = false
        sirenAlertInBackgroundOn = false
        developerModeUnlocked = false

        MotionSensor.stop()
        SpeedProvider.stop()
        PeriodicLocationRefresher.stop()
        if (ListenerService.isRunning.value) LiveSession.start(context)

        onThemeModeChange("SYSTEM")
        scope.launch { OnboardingStore.resetToDefaults(context) }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(imageVector = Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text(text = strings.resetDefaultsLabel) },
            text = { Text(text = strings.resetDefaultsDialogBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        applyDefaults()
                        showResetDialog = false
                    },
                    modifier = Modifier.testTag("btn_reset_defaults_confirm")
                ) {
                    Text(text = strings.resetDefaultsButton, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(text = strings.resetDefaultsCancel)
                }
            },
            modifier = Modifier.testTag("dialog_reset_defaults")
        )
    }

    if (showBatteryOptDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptDialog = false },
            title = { Text(text = strings.batteryOptDialogTitle) },
            text = { Text(text = strings.batteryOptDialogBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryOptDialog = false
                        requestIgnoreBatteryOptimizations()
                    },
                    modifier = Modifier.testTag("btn_battery_opt_confirm")
                ) {
                    Text(text = strings.batteryOptDialogConfirm, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBatteryOptDialog = false },
                    modifier = Modifier.testTag("btn_battery_opt_dismiss")
                ) {
                    Text(text = strings.batteryOptDialogDismiss)
                }
            },
            modifier = Modifier.testTag("dialog_battery_optimization")
        )
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    if (backDispatcher != null && showDemoPanel) {
        BackHandler {
            ListenerService.resetDebugAudioStatics(context)
            scope.launch {
                OnboardingStore.setDynamicColorEnabled(context, planDynamicColorReset().targetState)
            }
            showDemoPanel = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FormScreenScaffold(
            title = strings.settingsTitle,
        modifier = Modifier.testTag("settings_screen"),
        onBackContent = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            // Language lives in the corner of the bar rather than in the list below,
            // where it used to be a full card. See GlobeLanguageToggle for why: the one
            // control a rider needs after picking a language they cannot read is the one
            // control that must not require reading the list to find.
            GlobeLanguageToggle(
                language = language,
                contentDescription = if (language == AppLanguage.EN) {
                    strings.langSwitchToVietnamese
                } else {
                    strings.langSwitchToEnglish
                },
                onToggle = { lang ->
                    if (language != lang) {
                        scope.launch { OnboardingStore.setLanguage(context, lang) }
                        onLanguageChange(lang)
                    }
                },
                modifier = Modifier.padding(end = 4.dp)
            )
        },
        titleTag = "settings_title"
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionHeader(strings.settingsSectionStartSafely) }

            item {
                SensitivitySlider(
                    label = strings.sensitivityLabel,
                    optionLabels = Sensitivity.entries.map { sensitivity ->
                        when (sensitivity) {
                            Sensitivity.LOW -> strings.sensitivityLow
                            Sensitivity.MEDIUM -> strings.sensitivityMedium
                            Sensitivity.HIGH -> strings.sensitivityHigh
                            Sensitivity.MAX -> strings.sensitivityMax
                        }
                    },
                    selectedIndex = Sensitivity.entries.indexOf(currentSensitivity),
                    onSelect = { index ->
                        val sensitivity = Sensitivity.entries[index]
                        currentSensitivity = sensitivity
                        ListenerService.sensitivity = sensitivity
                        scope.launch {
                            OnboardingStore.setSensitivity(context, sensitivity)
                        }
                    },
                    allowMaxStop = demoPanelVisited
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingSwitchRow(
                        title = strings.takeoverLabel,
                        supporting = strings.takeoverSub,
                        checked = takeoverOn,
                        testTag = "switch_takeover",
                        shape = groupedShape(0, 2),
                        onCheckedChange = { checked ->
                            takeoverOn = checked
                            AlertConfig.t2TakesOverScreen = checked
                            scope.launch { OnboardingStore.setTakeoverEnabled(context, checked) }
                        }
                    )

                    SettingSwitchRow(
                        title = strings.vibrationLabel,
                        supporting = strings.vibrationSub,
                        checked = vibrationOn,
                        testTag = "switch_vibration",
                        shape = groupedShape(1, 2),
                        onCheckedChange = { checked ->
                            vibrationOn = checked
                            AlertConfig.vibrationEnabled = checked
                            scope.launch { OnboardingStore.setVibrationEnabled(context, checked) }
                            // Confirmation buzz on enable: a vibration setting the rider cannot
                            // feel is a vibration setting they will not trust. Fired after the
                            // flag is set because Haptics.play returns early when it is false.
                            if (checked) haptics.play(HapticPattern.WARNING)
                        }
                    )
                }
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.locationModeLabel,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                locationPermissionDenied -> strings.locationPermissionDeniedSub
                                locationMode == LocationMode.OFF -> strings.locationModeOffSub
                                locationMode == LocationMode.COLLISION_ONLY -> strings.locationModeCollisionSub
                                else -> LocationBatteryCost.measuredPctPer30Min?.let {
                                    String.format(strings.locationModeAlwaysSub, "$it%%")
                                } ?: strings.locationModeAlwaysSubUnmeasured
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        EmphasisSelector(
                            options = listOf(
                                LocationMode.OFF to strings.locationModeOff,
                                LocationMode.COLLISION_ONLY to strings.locationModeCollisionOnly,
                                LocationMode.ALWAYS to strings.locationModeAlways
                            ),
                            selected = locationMode,
                            onSelect = { selectLocationMode(it) },
                            testTagFor = { "chip_location_${it.name.lowercase()}" }
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingSwitchRow(
                        title = strings.keepMonitoringInBackgroundLabel,
                        supporting = strings.keepMonitoringInBackgroundSub,
                        checked = keepMonitoringInBackgroundOn,
                        testTag = "switch_background_monitoring",
                        shape = groupedShape(0, 2),
                        onCheckedChange = { checked ->
                            keepMonitoringInBackgroundOn = checked
                            AlertConfig.keepMonitoringInBackground = checked
                            scope.launch {
                                OnboardingStore.setKeepMonitoringInBackground(context, checked)
                            }
                            if (checked) {
                                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                                } else {
                                    true
                                }
                                if (!isIgnoring) {
                                    showBatteryOptDialog = true
                                }
                            }
                        }
                    )

                    SettingSwitchRow(
                        title = strings.sirenAlertInBackgroundLabel,
                        supporting = strings.sirenAlertInBackgroundSub,
                        checked = keepMonitoringInBackgroundOn && sirenAlertInBackgroundOn,
                        enabled = keepMonitoringInBackgroundOn,
                        testTag = "switch_background_siren",
                        shape = groupedShape(1, 2),
                        onCheckedChange = { checked ->
                            sirenAlertInBackgroundOn = checked
                            AlertConfig.sirenAlertInBackground = checked
                            scope.launch {
                                OnboardingStore.setSirenAlertInBackground(context, checked)
                            }
                        }
                    )
                }
            }

            item { SectionHeader(strings.settingsSectionDetection) }

            // Each opens its own page rather than expanding here. Both carry a looping
            // illustration plus two or three sub-options, and inlining that put ~700dp of feature
            // explanation in the middle of a list whose other rows are one line each - the two
            // features that need the most explaining were burying everything else. The row states
            // what it is set to; the page explains what it does.
            //
            // They are two separate cards, not one grouped block: grouping is this screen's way of
            // saying "these belong together as one setting" (as vibration and full-screen alerts
            // do), and these two are unrelated features that happen to be adjacent.
            item {
                SettingNavRow(
                    title = strings.collisionLabel,
                    supporting = if (collisionOn) strings.settingsStateOn else strings.settingsStateOff,
                    icon = Icons.Rounded.CrisisAlert,
                    iconTint = hazard.Critical,
                    enabled = true,
                    onClick = onNavigateToCollision,
                    testTag = "row_collision_detection"
                )
            }

            item {
                SettingNavRow(
                    title = strings.arrowLabel,
                    supporting = if (arrowOn) strings.settingsStateOn else strings.settingsStateOff,
                    icon = Icons.Rounded.Explore,
                    iconTint = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = onNavigateToDirectionArrow,
                    testTag = "row_direction_arrow"
                )
            }

            item { SectionHeader(strings.settingsSectionAppearance) }

            item {
                ContrastBoostSettingCard(
                    hasLightSensor = hasLightSensor,
                    contrastBoostMode = contrastBoostMode,
                    onModeChange = { mode ->
                        contrastBoostMode = mode
                        AlertConfig.contrastBoostMode = mode
                        scope.launch { OnboardingStore.setContrastBoostMode(context, mode) }
                    }
                )
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.themeModeLabel,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        EmphasisSelector(
                            options = listOf(
                                "SYSTEM" to strings.themeModeSystem,
                                "LIGHT" to strings.themeModeLight,
                                "DARK" to strings.themeModeDark
                            ),
                            selected = themeMode,
                            onSelect = { mode ->
                                themeMode = mode
                                AlertConfig.themeMode = mode
                                scope.launch { OnboardingStore.setThemeMode(context, mode) }
                                onThemeModeChange(mode)
                            },
                            testTagFor = { "chip_theme_${it.lowercase()}" }
                        )
                    }
                }
            }

            item {
                // Round 3: the speed NUMBER's own switch - deliberately NOT the mode selector
                // above. A rider may want Always-on GNSS accuracy (collision messages, capture
                // speed columns) without a number competing on the HUD; hiding it never stops
                // the provider. Only meaningful in ALWAYS, so it dims through the exact
                // alpha+enabled treatment the calibration card uses when the arrow feature is
                // off (established convention, not a second dimming style).
                SettingSwitchRow(
                    title = strings.speedReadoutLabel,
                    supporting = strings.speedReadoutSub,
                    checked = speedReadoutOn,
                    enabled = locationMode == LocationMode.ALWAYS,
                    testTag = "switch_speed_readout",
                    onCheckedChange = { checked ->
                        speedReadoutOn = checked
                        scope.launch { OnboardingStore.setSpeedReadoutVisible(context, checked) }
                    }
                )
            }

            item { SectionHeader(strings.settingsSectionAboutMaintenance) }

            item {
                SettingSwitchRow(
                    title = strings.cloudAiLabel,
                    supporting = strings.cloudAiSub,
                    checked = cloudOn,
                    testTag = "switch_cloud_ai",
                    onCheckedChange = { checked ->
                        cloudOn = checked
                        AlertConfig.t2Enabled = checked
                        scope.launch { OnboardingStore.setT2Enabled(context, checked) }
                        if (checked) {
                            if (ListenerService.isRunning.value) LiveSession.start(context)
                        } else {
                            LiveSession.stop()
                        }
                    }
                )
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.testAlertLabel,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.testAlertSub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings_test_alert_sub")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = onTestAlert,
                            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                // The one control on this screen that should look like the thing it
                                // previews. Hazard colours, never the dynamic scheme.
                                containerColor = hazard.Critical,
                                contentColor = hazard.OnCritical
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_test_alert")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = strings.testAlertLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.resetDefaultsLabel,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.resetDefaultsSub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            // Behind a confirmation because it is a single tap that silently
                            // undoes every deliberate choice on this screen, including turning
                            // collision detection back off.
                            onClick = { showResetDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_reset_defaults")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = strings.resetDefaultsButton,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.aboutTitle,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("settings_about_label")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${strings.versionLabel} ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .testTag("settings_version_text")
                                .clickable {
                                    when (val result = tapCounter.onVersionTap(developerModeUnlocked)) {
                                        is DeveloperTapCounter.TapResult.Countdown -> {
                                            activeToast?.cancel()
                                            activeToast = Toast.makeText(
                                                context,
                                                strings.developerCountdownText(result.stepsRemaining),
                                                Toast.LENGTH_SHORT
                                            ).also { it.show() }
                                        }
                                        DeveloperTapCounter.TapResult.Unlocked -> {
                                            activeToast?.cancel()
                                            activeToast = Toast.makeText(
                                                context,
                                                strings.developerModeEnabled,
                                                Toast.LENGTH_SHORT
                                            ).also { it.show() }
                                            developerModeUnlocked = true
                                            scope.launch {
                                                OnboardingStore.setDeveloperModeUnlocked(context, true)
                                            }
                                        }
                                        DeveloperTapCounter.TapResult.AlreadyUnlocked -> {
                                            activeToast?.cancel()
                                            activeToast = Toast.makeText(
                                                context,
                                                strings.developerModeAlreadyEnabled,
                                                Toast.LENGTH_SHORT
                                            ).also { it.show() }
                                        }
                                        DeveloperTapCounter.TapResult.Ignored -> {}
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.aboutLatencyNote,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("settings_latency_note")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.onboardingCard3Body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings_disclaimer_text")
                        )
                    }
                }
            }

            if (developerModeUnlocked) {
                item {
                    SettingNavRow(
                        title = strings.developerRowTitle,
                        supporting = strings.developerRowSubtitle,
                        icon = Icons.Rounded.BugReport,
                        iconTint = MaterialTheme.colorScheme.primary,
                        enabled = true,
                        onClick = { requestOpenDemo() },
                        testTag = "row_developer_diagnostics"
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showDemoPanel) {
        DemoPanel(
            viewModel = viewModel,
            onClose = {
                ListenerService.resetDebugAudioStatics(context)
                scope.launch {
                    OnboardingStore.setDynamicColorEnabled(context, planDynamicColorReset().targetState)
                }
                showDemoPanel = false
            }
        )
    }

    if (showDemoDisclaimerDialog) {
        AlertDialog(
            onDismissRequest = { showDemoDisclaimerDialog = false },
            title = {
                Text(
                    text = strings.demoDisclaimerTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = strings.demoDisclaimerBody,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.acknowledgeDemoDisclaimer()
                        viewModel.markDemoPanelVisited()
                        scope.launch { OnboardingStore.setDemoPanelVisited(context) }
                        showDemoDisclaimerDialog = false
                        showDemoPanel = true
                    },
                    modifier = Modifier.testTag("btn_confirm_demo_disclaimer")
                ) {
                    Text(strings.demoDisclaimerConfirm)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDemoDisclaimerDialog = false },
                    modifier = Modifier.testTag("btn_dismiss_demo_disclaimer")
                ) {
                    Text(strings.demoDisclaimerDismiss)
                }
            },
            modifier = Modifier.testTag("dialog_demo_disclaimer")
        )
    }
}
}
