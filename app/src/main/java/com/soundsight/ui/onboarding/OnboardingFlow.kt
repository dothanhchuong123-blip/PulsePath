package com.soundsight.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.soundsight.audio.ListenerService
import com.soundsight.core.AppLanguage
import com.soundsight.core.EnStrings
import com.soundsight.core.LocalStrings
import com.soundsight.core.OnboardingStore
import com.soundsight.core.ViStrings
import com.soundsight.ui.hud.MicLevelMeter
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertLabel
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import kotlinx.coroutines.launch

enum class OnboardingStep {
    LANGUAGE, PRIMER, RELIABILITY, MOUNT_CHECK
}

@Composable
fun OnboardingFlow(
    onComplete: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf(OnboardingStep.LANGUAGE) }
    var currentLanguage by remember { mutableStateOf(AppLanguage.VI) }

    val strings = when (currentLanguage) {
        AppLanguage.VI -> ViStrings
        AppLanguage.EN -> EnStrings
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgIdle)
        ) {
            when (step) {
                OnboardingStep.LANGUAGE -> {
                    LanguageStep(
                        onSelectLanguage = { selectedLang ->
                            currentLanguage = selectedLang
                            coroutineScope.launch {
                                OnboardingStore.setLanguage(context, selectedLang)
                            }
                            step = OnboardingStep.PRIMER
                        }
                    )
                }

                OnboardingStep.PRIMER -> {
                    PrimerStep(
                        onPermissionsGranted = {
                            step = OnboardingStep.RELIABILITY
                        }
                    )
                }

                OnboardingStep.RELIABILITY -> {
                    ReliabilityStep(
                        onNext = {
                            step = OnboardingStep.MOUNT_CHECK
                        }
                    )
                }

                OnboardingStep.MOUNT_CHECK -> {
                    MountCheckStep(
                        onFinished = {
                            coroutineScope.launch {
                                OnboardingStore.setOnboarded(context, true)
                                onComplete(currentLanguage)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageStep(
    onSelectLanguage: (AppLanguage) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SoundSight",
            style = AlertLabel,
            color = FgIdle,
            modifier = Modifier.testTag("onboarding_title")
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = { onSelectLanguage(AppLanguage.VI) },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .testTag("btn_lang_vi"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E293B),
                contentColor = FgIdle
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "Tiếng Việt",
                style = AlertSub,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { onSelectLanguage(AppLanguage.EN) },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .testTag("btn_lang_en"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E293B),
                contentColor = FgIdle
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "English",
                style = AlertSub,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PrimerStep(
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalStrings.current

    var hasRequestedPermissions by remember { mutableStateOf(false) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }

    fun checkMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasRequestedPermissions = true
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: checkMicrophonePermission()
        if (audioGranted) {
            onPermissionsGranted()
        } else {
            val activity = context as? Activity
            val showRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
            if (!showRationale) {
                isPermanentlyDenied = true
            }
        }
    }

    if (isPermanentlyDenied) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.onboardingPermDeniedTitle,
                style = AlertSub,
                color = CriticalBg,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = strings.onboardingPermDeniedBody,
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
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = strings.onboardingPrimerTitle,
                    style = AlertSub,
                    color = FgIdle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Card 1: Why the mic
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = strings.onboardingCard1Title,
                            style = IdleStatus,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.onboardingCard1Body,
                            style = HistoryRow,
                            color = FgIdle,
                            lineHeight = 22.sp
                        )
                    }
                }

                // Card 2: What leaves the phone
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = strings.onboardingCard2Title,
                            style = IdleStatus,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.onboardingCard2Body,
                            style = HistoryRow,
                            color = FgIdle,
                            lineHeight = 22.sp
                        )
                    }
                }

                // Card 3: What this cannot do
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = strings.onboardingCard3Title,
                            style = IdleStatus,
                            color = CriticalBg,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.onboardingCard3Body,
                            style = HistoryRow,
                            color = FgIdle,
                            lineHeight = 22.sp
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (checkMicrophonePermission()) {
                        onPermissionsGranted()
                    } else {
                        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        launcher.launch(permissions.toTypedArray())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("btn_grant_permissions"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentLive,
                    contentColor = Color(0xFF10131A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = strings.onboardingPermGrantButton,
                    style = IdleStatus,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ReliabilityStep(
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.onboardingReliabilityTitle,
                style = AlertSub,
                color = FgIdle,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = strings.onboardingReliabilityBody,
                style = IdleStatus,
                color = FgIdle.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {}
                    onNext()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("btn_battery_optimization"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentLive,
                    contentColor = Color(0xFF10131A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = strings.onboardingBatteryButton,
                    style = IdleStatus,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onNext,
                modifier = Modifier.testTag("btn_skip_reliability")
            ) {
                Text(
                    text = strings.onboardingSkipButton,
                    style = IdleStatus,
                    color = FgIdle.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MountCheckStep(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalStrings.current

    var hasPreAlertFired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ListenerService.start(context)
    }

    LaunchedEffect(Unit) {
        ListenerService.preAlerts.collect {
            hasPreAlertFired = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = strings.onboardingMountInstruction,
                style = AlertSub,
                color = FgIdle,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = strings.onboardingClapInstruction,
                style = IdleStatus,
                color = FgIdle.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                MicLevelMeter(
                    level = ListenerService.level,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedVisibility(
                visible = hasPreAlertFired,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(
                            color = AccentLive.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("badge_mic_ok")
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(AccentLive, shape = CircleShape)
                    )
                    Text(
                        text = strings.onboardingMicOk,
                        style = IdleStatus,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = hasPreAlertFired,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .testTag("btn_start_after_onboarding"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentLive,
                    contentColor = Color(0xFF10131A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = strings.onboardingStartButton,
                    style = AlertSub,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
