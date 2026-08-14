package com.soundsight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.soundsight.audio.ListenerService
import com.soundsight.audio.MicSource
import com.soundsight.audio.Sensitivity
import com.soundsight.core.AlertConfig
import com.soundsight.core.AppLanguage
import com.soundsight.core.DailyQuotaGuard
import com.soundsight.core.EnStrings
import com.soundsight.core.LocalStrings
import com.soundsight.core.SoundSightStrings
import com.soundsight.core.Telemetry
import com.soundsight.core.ViStrings
import com.soundsight.data.ClassificationDebugResult
import com.soundsight.data.ClassificationResult
import com.soundsight.data.GeminiConfig
import com.soundsight.data.SoundClassifier
import com.soundsight.ui.theme.AccentLive
import kotlinx.coroutines.launch
import com.soundsight.ui.theme.AlertLabel
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.SoundSightTheme
import com.soundsight.ui.theme.WarningBg
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var currentLanguage by remember { mutableStateOf(AppLanguage.VI) }
            val strings = when (currentLanguage) {
                AppLanguage.VI -> ViStrings
                AppLanguage.EN -> EnStrings
            }

            CompositionLocalProvider(LocalStrings provides strings) {
                SoundSightTheme {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            PlaceholderScreen(
                                currentLanguage = currentLanguage,
                                onToggleLanguage = {
                                    currentLanguage = if (currentLanguage == AppLanguage.VI) {
                                        AppLanguage.EN
                                    } else {
                                        AppLanguage.VI
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaceholderScreen(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit
) {
    val context = LocalContext.current
    val strings = SoundSightStrings.current

    val isRunning by ListenerService.isRunning.collectAsStateWithLifecycle()
    val liveLevel by ListenerService.level.collectAsStateWithLifecycle()
    val noiseFloorVal by ListenerService.noiseFloorValue.collectAsStateWithLifecycle()
    val thresholdVal by ListenerService.thresholdValue.collectAsStateWithLifecycle()
    val freezeCount by ListenerService.freezeEventCount.collectAsStateWithLifecycle()
    val unfreezeCount by ListenerService.unfreezeEventCount.collectAsStateWithLifecycle()
    val timeSinceLastSpike by ListenerService.timeSinceLastSpikeMs.collectAsStateWithLifecycle()
    val lastClip by ListenerService.lastCapturedClip.collectAsStateWithLifecycle()

    var preAlertCount by remember { mutableIntStateOf(0) }
    var selectedMicSource by remember { mutableStateOf(ListenerService.micSource) }
    var selectedSensitivity by remember { mutableStateOf(ListenerService.sensitivity) }
    var nsEnabled by remember { mutableStateOf(ListenerService.enableNoiseSuppressor) }
    var agcEnabled by remember { mutableStateOf(ListenerService.enableAutomaticGainControl) }
    var disableThinkingState by remember { mutableStateOf(GeminiConfig.disableThinking) }
    var t2EnabledState by remember { mutableStateOf(AlertConfig.t2Enabled) }
    var callsLeftToday by remember { mutableIntStateOf(DailyQuotaGuard.remainingToday(context)) }

    val coroutineScope = rememberCoroutineScope()
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        ListenerService.preAlerts.collect {
            preAlertCount++
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            ListenerService.start(context)
        }
    }

    Scaffold(
        containerColor = BgIdle,
        contentColor = FgIdle
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.appName,
                    style = AlertLabel,
                    color = AccentLive,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("app_name_text")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = strings.tagline,
                    style = AlertSub,
                    color = FgIdle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("tagline_text")
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Status & Diagnostics Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF161E27)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isRunning) strings.statusListening else strings.statusOffline,
                                style = IdleStatus,
                                color = if (isRunning) AccentLive else Degraded,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = if (isRunning) "ACTIVE" else "IDLE",
                                style = HistoryRow,
                                color = if (isRunning) AccentLive else Degraded
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Mic Level (dBFS mapped):",
                                    style = HistoryRow,
                                    color = Degraded
                                )
                                Text(
                                    text = String.format(Locale.US, "%.3f", liveLevel),
                                    style = HistoryRow,
                                    color = FgIdle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("mic_level_text")
                                )
                            }

                            LinearProgressIndicator(
                                progress = { liveLevel },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = if (liveLevel > 0.6f) WarningBg else AccentLive,
                                trackColor = BgIdle,
                                strokeCap = StrokeCap.Round
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Pre-alerts (Tier 1 fired):",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Text(
                                text = "$preAlertCount",
                                style = IdleStatus,
                                color = if (preAlertCount > 0) WarningBg else FgIdle,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("pre_alert_count_text")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Floor / Threshold (RMS):",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Text(
                                text = "${noiseFloorVal.toInt()} / ${thresholdVal.toInt()}",
                                style = HistoryRow,
                                color = FgIdle,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("noise_floor_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Debug Row (Temporary)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF101720)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Debug: Audio Source & FX (A/B Test)",
                            style = HistoryRow,
                            color = WarningBg,
                            fontWeight = FontWeight.Bold
                        )

                        // MicSource selector chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MicSource.values().forEach { source ->
                                FilterChip(
                                    selected = (selectedMicSource == source),
                                    onClick = {
                                        selectedMicSource = source
                                        ListenerService.micSource = source
                                        ListenerService.restartRecording(context)
                                    },
                                    label = { Text(source.name, style = HistoryRow) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentLive,
                                        selectedLabelColor = BgIdle,
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = FgIdle
                                    )
                                )
                            }
                        }

                        // Sensitivity selector chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Sensitivity.values().forEach { sens ->
                                FilterChip(
                                    selected = (selectedSensitivity == sens),
                                    onClick = {
                                        selectedSensitivity = sens
                                        ListenerService.sensitivity = sens
                                    },
                                    label = { Text(sens.name, style = HistoryRow) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentLive,
                                        selectedLabelColor = BgIdle,
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = FgIdle
                                    )
                                )
                            }
                        }

                        // NS & AGC toggles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("NoiseSuppressor", style = HistoryRow, color = FgIdle)
                                Switch(
                                    checked = nsEnabled,
                                    onCheckedChange = {
                                        nsEnabled = it
                                        ListenerService.enableNoiseSuppressor = it
                                        ListenerService.restartRecording(context)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentLive,
                                        checkedTrackColor = AccentLive.copy(alpha = 0.3f)
                                    )
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("AGC", style = HistoryRow, color = FgIdle)
                                Switch(
                                    checked = agcEnabled,
                                    onCheckedChange = {
                                        agcEnabled = it
                                        ListenerService.enableAutomaticGainControl = it
                                        ListenerService.restartRecording(context)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentLive,
                                        checkedTrackColor = AccentLive.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }

                        // Event counters & Spike timer readouts
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Freeze / Unfreeze Events:",
                                    style = HistoryRow,
                                    color = Degraded
                                )
                                Text(
                                    text = "$freezeCount / $unfreezeCount",
                                    style = HistoryRow,
                                    color = FgIdle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("freeze_unfreeze_count_text")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Time since last spike:",
                                    style = HistoryRow,
                                    color = Degraded
                                )
                                Text(
                                    text = if (timeSinceLastSpike < 0) "N/A" else "$timeSinceLastSpike ms",
                                    style = HistoryRow,
                                    color = if (timeSinceLastSpike in 0..4000) WarningBg else FgIdle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("time_since_spike_text")
                                )
                            }
                        }

                        // Enable Tier-2 (Gemini) toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enable Tier-2 (Gemini)",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Switch(
                                checked = t2EnabledState,
                                onCheckedChange = { checked ->
                                    t2EnabledState = checked
                                    AlertConfig.t2Enabled = checked
                                },
                                modifier = Modifier.testTag("enable_t2_switch")
                            )
                        }

                        // Disable thinking toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Disable thinking",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Switch(
                                checked = disableThinkingState,
                                onCheckedChange = { checked ->
                                    disableThinkingState = checked
                                    GeminiConfig.disableThinking = checked
                                },
                                modifier = Modifier.testTag("disable_thinking_switch")
                            )
                        }

                        // Gemini Quota Remaining
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Gemini calls left today: $callsLeftToday/${DailyQuotaGuard.dailyLimit}",
                                style = HistoryRow,
                                color = Degraded,
                                modifier = Modifier.testTag("gemini_quota_remaining_text")
                            )
                        }

                        // Gemini Test Trigger
                        Button(
                            onClick = {
                                if (!AlertConfig.t2Enabled) {
                                    geminiTestResult = "Tier-2 disabled"
                                    return@Button
                                }
                                val clipToTest = lastClip
                                if (clipToTest == null) {
                                    geminiTestResult = "Error: No AudioClip captured yet. Start listener and trigger a spike first."
                                    return@Button
                                }
                                isTestingGemini = true
                                geminiTestResult = "Calling Gemini API..."
                                coroutineScope.launch {
                                    val start = System.currentTimeMillis()
                                    val result = SoundClassifier.classifyDebug(context, clipToTest)
                                    val latencyMs = System.currentTimeMillis() - start
                                    isTestingGemini = false
                                    callsLeftToday = DailyQuotaGuard.remainingToday(context)
                                    geminiTestResult = result.fold(
                                        onSuccess = { dbg ->
                                            val r = dbg.result
                                            val t = dbg.trace
                                            val u = dbg.usage
                                            val kb = String.format(Locale.US, "%.2f", t.bodyBytesSent / 1024.0)
                                            val attemptTimes = t.attemptMs.joinToString(", ") { "${it}ms" }
                                            "Sound: ${r.soundId}\n" +
                                            "Tier: ${r.tier}\n" +
                                            "Confidence: ${r.confidence}\n" +
                                            "Caption (VI): ${r.captionVi}\n" +
                                            "Caption (EN): ${r.captionEn}\n" +
                                            "Latency: ${latencyMs} ms\n" +
                                            "Attempts: ${t.attempts}\n" +
                                            "Per-attempt: $attemptTimes\n" +
                                            "Request body: $kb KB\n" +
                                            "Thoughts tokens: ${u.thoughtsTokenCount}\n" +
                                            "Total tokens: ${u.totalTokenCount}"
                                        },
                                        onFailure = { err ->
                                            "Classification Failed (${latencyMs} ms):\n${err.message ?: err.toString()}"
                                        }
                                    )
                                }
                            },
                            enabled = !isTestingGemini,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF334155),
                                contentColor = FgIdle
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("test_gemini_button")
                        ) {
                            Text(
                                text = if (isTestingGemini) "Testing Gemini..." else "Test Gemini call",
                                style = HistoryRow,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        geminiTestResult?.let { res ->
                            Text(
                                text = res,
                                style = HistoryRow,
                                color = if (res.startsWith("Sound:")) AccentLive else WarningBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("gemini_test_result_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Start / Stop Controls
                if (!isRunning) {
                    Button(
                        onClick = {
                            val audioGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            if (audioGranted) {
                                ListenerService.start(context)
                            } else {
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentLive,
                            contentColor = BgIdle
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .testTag("start_stop_button")
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = strings.btnStart,
                            style = IdleStatus,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            ListenerService.stop(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CriticalBg,
                            contentColor = CriticalFg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .testTag("start_stop_button")
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = strings.btnStop,
                            style = IdleStatus,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Language Toggle
                OutlinedButton(
                    onClick = onToggleLanguage,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .testTag("language_toggle_button")
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (currentLanguage == AppLanguage.VI) "Chuyển sang English (EN)" else "Switch to Tiếng Việt (VI)",
                        style = HistoryRow,
                        color = FgIdle
                    )
                }
            }
        }
    }
}
