package com.soundsight.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CrisisAlert
import androidx.compose.material.icons.rounded.MedicalServices
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundsight.audio.ListenerService
import com.soundsight.ui.FormScreenScaffold
import com.soundsight.audio.motion.MotionSensor
import com.soundsight.core.AlertConfig
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.settings.illustration.CollisionIllustration
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.groupedShape
import kotlinx.coroutines.launch

/**
 * Everything about collision detection, on its own page.
 *
 * The emergency profile lives here rather than under Alerts because this is the only feature that
 * can open it, and the preview button is here for the same reason. With the switch off, both dim:
 * they stay visible so the page still explains what turning it on buys, but neither can be reached
 * while nothing would ever trigger them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollisionDetectionScreen(
    onNavigateToEmergency: () -> Unit,
    onPreviewCrashResponse: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = SoundSightStrings.current
    val hazard = LocalHazardColors.current

    var collisionOn by remember { mutableStateOf(AlertConfig.motionSensorsEnabled) }

    FormScreenScaffold(
        title = strings.collisionLabel,
        modifier = Modifier.testTag("collision_detection_screen"),
        onBackContent = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("collision_back")) {
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
                    testTag = "panel_collision_illustration",
                    // The aspect the default 260dp height was tuned against, at the 361dp-wide
                    // card a portrait phone gives it: wide enough that the 4:3 stage is scaled by
                    // width and trimmed by a sliver top and bottom, which is the crop this scene
                    // was framed for. Wider than this and the trim starts eating the bike.
                    aspect = 1.39f
                ) { illustrationModifier ->
                    CollisionIllustration(modifier = illustrationModifier)
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingSwitchRow(
                        title = strings.collisionLabel,
                        supporting = strings.collisionSub,
                        checked = collisionOn,
                        testTag = "switch_collision_detection",
                        shape = groupedShape(0, 2),
                        onCheckedChange = { checked ->
                            collisionOn = checked
                            AlertConfig.motionSensorsEnabled = checked
                            scope.launch {
                                OnboardingStore.setMotionSensorsEnabled(context, checked)
                            }
                            if (checked) {
                                if (ListenerService.isRunning.value) MotionSensor.start(context)
                            } else {
                                MotionSensor.stop()
                            }
                        }
                    )

                    SettingNavRow(
                        title = strings.emergencyTitle,
                        supporting = strings.emergencySub,
                        icon = Icons.Rounded.MedicalServices,
                        iconTint = hazard.Critical,
                        enabled = collisionOn,
                        onClick = onNavigateToEmergency,
                        testTag = "card_emergency_profile",
                        shape = groupedShape(1, 2)
                    )
                }
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (collisionOn) 1f else 0.38f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.crashPreviewLabel,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.crashPreviewSub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            // The crash-response screen is drawn on top of the HUD, so the caller
                            // returns there before showing it - otherwise it would open behind
                            // this page where nobody would see it.
                            onClick = onPreviewCrashResponse,
                            enabled = collisionOn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_preview_crash_response")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CrisisAlert,
                                contentDescription = null,
                                tint = hazard.Critical,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = strings.crashPreviewButton,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
