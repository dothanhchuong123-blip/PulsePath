package com.soundsight.ui.hud

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.core.AppLanguage
import com.soundsight.core.SoundSightStrings
import com.soundsight.domain.AlertPresentation
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.Phase
import com.soundsight.ui.demo.DemoPanel
import com.soundsight.ui.history.HistorySheet
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HudScreen(
    viewModel: HudViewModel,
    language: AppLanguage,
    onSettings: () -> Unit,
    onStop: () -> Unit
) {
    val hudState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = SoundSightStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }
    var showDemoPanel by remember { mutableStateOf(false) }

    if (hudState.permissionRevoked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgIdle)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = strings.permissionRevokedTitle,
                    style = AlertSub,
                    color = CriticalBg,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = strings.permissionRevokedBody,
                    style = IdleStatus,
                    color = FgIdle,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .testTag("btn_open_app_settings"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentLive,
                        contentColor = Color(0xFF10131A)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = strings.onboardingOpenSettingsButton,
                        style = IdleStatus,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        return
    }

    val (chipLabel, chipColor) = when {
        hudState.micUnavailable -> strings.micInUseChip to Degraded
        hudState.setupErrorSuspended -> strings.setupErrorChip to Degraded
        hudState.dailyQuotaExhausted -> strings.dailyQuotaChip to Degraded
        hudState.rateLimited -> strings.rateLimitedChip to Degraded
        hudState.consecutiveFailures >= 6 -> strings.buzzOnlyChip to Degraded
        !hudState.isOnline -> strings.hudOfflineChip to Degraded
        else -> strings.hudListeningChip to AccentLive
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgIdle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top row: wordmark on the left, status chip and settings button on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.appName,
                    style = AlertSub,
                    color = FgIdle,
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    val job = scope.launch {
                                        delay(2000)
                                        showDemoPanel = true
                                    }
                                    tryAwaitRelease()
                                    job.cancel()
                                }
                            )
                        }
                        .testTag("hud_wordmark")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .background(
                                color = chipColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("hud_status_chip")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = chipColor,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = chipLabel,
                            style = HistoryRow,
                            color = chipColor,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("hud_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = FgIdle,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Centre: Status text and MicLevelMeter (~1/3 screen height)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = strings.statusListening,
                    style = IdleStatus,
                    color = FgIdle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("hud_listening_status_text")
                )

                Spacer(modifier = Modifier.height(28.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MicLevelMeter(
                        level = viewModel.micLevel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Bottom: Two large touch targets (min 72dp tall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .testTag("hud_stop_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CriticalBg,
                        contentColor = CriticalFg
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = strings.hudStopButton,
                        style = IdleStatus,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { showHistory = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .testTag("hud_history_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = FgIdle
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "${strings.hudHistoryButton} (${hudState.history.size})",
                        style = IdleStatus,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // PRE_ALERT overlay: 12%-alpha white inner border ~24dp thick with 200-300ms fade in/out
        AnimatedVisibility(
            visible = hudState.phase == Phase.PRE_ALERT,
            enter = fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = fadeOut(animationSpec = tween(durationMillis = 250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 24.dp,
                        color = Color.White.copy(alpha = 0.12f)
                    )
                    .testTag("hud_pre_alert_overlay")
            )
        }

        // ALERT overlay: full-screen AlertTakeover on top of everything
        if (hudState.phase == Phase.ALERT && hudState.current != null) {
            val event = hudState.current!!
            AlertTakeover(
                event = event,
                presentation = AlertPresentation.forEvent(event),
                language = language,
                strings = strings,
                onDismiss = { viewModel.dismissAlert() }
            )
        }

        // History modal bottom sheet
        if (showHistory) {
            HistorySheet(
                history = hudState.history,
                language = language,
                onDismiss = { showHistory = false }
            )
        }

        // DemoPanel full-screen overlay on top of everything
        if (showDemoPanel) {
            DemoPanel(
                viewModel = viewModel,
                onClose = { showDemoPanel = false }
            )
        }
    }
}
