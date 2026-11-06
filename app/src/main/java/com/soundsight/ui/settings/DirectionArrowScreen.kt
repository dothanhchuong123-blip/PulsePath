package com.soundsight.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.core.AlertConfig
import com.soundsight.core.ArrowStyle
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.domain.DirectionConfidence
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.Side
import com.soundsight.ui.FormScreenScaffold
import com.soundsight.ui.hud.DirectionIndicator
import com.soundsight.ui.settings.illustration.DirectionArrowIllustration
import com.soundsight.ui.theme.groupedShape
import kotlinx.coroutines.launch

/**
 * Everything about the direction arrow, on its own page.
 *
 * The illustration leads because the arrow is the one alert element a rider has no prior model
 * for. "Show whether a sound came from your left or right" is accurate and tells you nothing about
 * what will actually appear on the screen at 40km/h; thirty seconds of watching it happen does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectionArrowScreen(
    onNavigateToCalibration: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = SoundSightStrings.current

    var arrowOn by remember { mutableStateOf(AlertConfig.directionArrowEnabled) }
    var arrowStyle by remember { mutableStateOf(AlertConfig.arrowStyle) }
    var labelOn by remember { mutableStateOf(AlertConfig.directionLabelEnabled) }

    // A6 migration: an install that calibrated before polarity was bound to a rotation has
    // channelsSwapped but no rotation key. The live analyser fail-closes such installs to
    // UNKNOWN, so this notice explains WHY the arrow stopped instead of leaving it silently
    // dead - and it disappears the moment recalibration writes the rotation. It is one stable,
    // persistent prompt for exactly the legacy state, never a repeating dialog.
    val calibrationState by OnboardingStore.directionCalibrationStateFlow(context)
        .collectAsStateWithLifecycle(initialValue = null)
    val needsRecalibration =
        calibrationState?.let { it.channelsSwappedPresent && it.calibrationRotation == null } == true

    FormScreenScaffold(
        title = strings.arrowLabel,
        modifier = Modifier.testTag("direction_arrow_screen"),
        onBackContent = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("direction_back")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                IllustrationPanel(
                    shape = MaterialTheme.shapes.large,
                    testTag = "panel_direction_illustration",
                    // 200dp is not a taste call: at a 361dp-wide card it makes the panel exactly
                    // the aspect of this composition's framed region, so the drawing fills the
                    // card edge to edge with nothing cropped off either side. Any taller and the
                    // sound arcs - which enter from the outermost 130 units of the frame - start
                    // getting trimmed at their peak.
                    height = 200.dp,
                    // 361:200, the same ratio 200dp was derived from - and exactly this
                    // composition's framed region, 1200x665. Capping the card here is what keeps
                    // the arcs intact when the card is free to grow wider than the phone.
                    aspect = 1.805f
                ) { illustrationModifier ->
                    DirectionArrowIllustration(modifier = illustrationModifier)
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingSwitchRow(
                        title = strings.arrowLabel,
                        supporting = strings.arrowSub,
                        checked = arrowOn,
                        testTag = "switch_direction_arrow",
                        shape = groupedShape(0, 2),
                        onCheckedChange = { checked ->
                            arrowOn = checked
                            AlertConfig.directionArrowEnabled = checked
                            scope.launch {
                                OnboardingStore.setDirectionArrowEnabled(context, checked)
                            }
                        }
                    )

                    // Dimmed rather than hidden while the arrow is off: a control that disappears
                    // takes its own explanation with it.
                    SettingSwitchRow(
                        title = strings.directionLabelLabel,
                        supporting = strings.directionLabelSub,
                        checked = labelOn,
                        enabled = arrowOn,
                        testTag = "switch_direction_label",
                        shape = groupedShape(1, 2),
                        onCheckedChange = { checked ->
                            labelOn = checked
                            AlertConfig.directionLabelEnabled = checked
                            scope.launch {
                                OnboardingStore.setDirectionLabelEnabled(context, checked)
                            }
                        }
                    )
                }
            }

            item {
                Box(modifier = Modifier.alpha(if (arrowOn) 1f else 0.38f)) {
                    ArrowStyleCard(
                        label = strings.arrowStyleLabel,
                        chevronLabel = strings.arrowStyleChevron,
                        arrowLabel = strings.arrowStyleArrow,
                        style = arrowStyle,
                        enabled = arrowOn,
                        onSelect = { style ->
                            arrowStyle = style
                            AlertConfig.arrowStyle = style
                            scope.launch { OnboardingStore.setArrowStyle(context, style) }
                        }
                    )
                }
            }

            item {
                if (needsRecalibration) {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("card_direction_recalibration_notice")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = strings.directionRecalibrationNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(
                                onClick = onNavigateToCalibration,
                                enabled = arrowOn,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("btn_recalibrate_now")
                            ) {
                                Text(text = strings.directionCalibrationTitle)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Box(modifier = Modifier.alpha(if (arrowOn) 1f else 0.38f)) {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = strings.directionCalibrationStep1,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(
                                onClick = onNavigateToCalibration,
                                enabled = arrowOn,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("btn_settings_calibration")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CenterFocusStrong,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = strings.directionCalibrationTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * Arrow style, previewed with the real indicator rather than described.
 *
 * Same reasoning as the wave-style card in [SettingsScreen]: "Chevron" and "Arrow" are two words
 * for a shape, and a rider comparing them wants to see the shape. Both previews are shown at once -
 * LEFT at high confidence and RIGHT at medium - because the style choice also has to survive that
 * split, and seeing them side by side is the only way to check that it does.
 */
@Composable
private fun ArrowStyleCard(
    label: String,
    chevronLabel: String,
    arrowLabel: String,
    style: ArrowStyle,
    enabled: Boolean,
    onSelect: (ArrowStyle) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_arrow_style")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .testTag("arrow_style_preview"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DirectionIndicator(
                    direction = DirectionEstimate(Side.LEFT, DirectionConfidence.HIGH),
                    slot = Side.LEFT,
                    tint = accent,
                    iconSize = 36.dp,
                    showLabel = false,
                    style = style
                )
                DirectionIndicator(
                    direction = DirectionEstimate(Side.RIGHT, DirectionConfidence.MEDIUM),
                    slot = Side.RIGHT,
                    tint = accent,
                    iconSize = 36.dp,
                    showLabel = false,
                    style = style
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            EmphasisSelector(
                options = listOf(
                    ArrowStyle.CHEVRON to chevronLabel,
                    ArrowStyle.ARROW to arrowLabel
                ),
                selected = style,
                onSelect = { if (enabled) onSelect(it) },
                testTagFor = { "chip_arrow_${it.name.lowercase()}" }
            )
        }
    }
}
