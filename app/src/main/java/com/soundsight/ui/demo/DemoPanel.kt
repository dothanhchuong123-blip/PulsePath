package com.soundsight.ui.demo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.audio.ListenerService
import com.soundsight.core.AppLanguage
import com.soundsight.core.SoundSightStrings
import com.soundsight.core.Telemetry
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.SoundId
import com.soundsight.domain.Tier
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.WarningBg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DemoPanel(
    viewModel: HudViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hudState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVi = hudState.language == AppLanguage.VI

    // Local per-button cooldown states (disabled for 2200ms)
    val buttonInFlight = remember { mutableStateMapOf<SoundId, Boolean>() }

    // Telemetry state collected directly (out of HudState)
    val lastLatency by Telemetry.lastLatencyMs.collectAsState()
    val totalClassifications by Telemetry.totalClassifications.collectAsState()
    val droppedClips by Telemetry.droppedClips.collectAsState()
    val failureCount by Telemetry.failureCount.collectAsState()
    val quotaBlockedCount by Telemetry.quotaBlockedCount.collectAsState()
    val failuresByCause by Telemetry.failuresByCause.collectAsState()
    val spikesDetected by ListenerService.freezeEventCount.collectAsState()

    val p50 = remember(lastLatency, totalClassifications) { Telemetry.percentile(0.5) }
    val p90 = remember(lastLatency, totalClassifications) { Telemetry.percentile(0.9) }
    val latencyList = remember(lastLatency, totalClassifications) { Telemetry.latencyHistory() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.replayFromFile(context, it) }
    }

    Surface(
        color = Color(0xFF0F172A),
        contentColor = FgIdle,
        modifier = modifier
            .fillMaxSize()
            .testTag("demo_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Demo & Diagnostics",
                        style = AlertSub,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Test sound triggers, replay files, & view live telemetry",
                        style = HistoryRow,
                        color = Degraded,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("btn_close_demo_panel")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = FgIdle,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF334155))
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Section 1: Inject Demo Events (All 11 SoundId values)
                Text(
                    text = "INJECT SOUND EVENTS",
                    style = HistoryRow,
                    color = AccentLive,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SoundId.values().forEach { soundId ->
                        val label = if (isVi) AlertPresentation.getLabelVi(soundId) else AlertPresentation.getLabelEn(soundId)
                        val inFlight = buttonInFlight[soundId] == true
                        val tier = SoundEvent.canonicalTier(soundId)
                        val tierColor = when (tier) {
                            Tier.CRITICAL -> CriticalBg
                            Tier.WARNING -> WarningBg
                            Tier.SAFE -> Degraded
                        }

                        Button(
                            onClick = {
                                buttonInFlight[soundId] = true
                                viewModel.injectDemoEvent(soundId)
                                scope.launch {
                                    delay(2200L)
                                    buttonInFlight[soundId] = false
                                }
                            },
                            enabled = !inFlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_demo_sound_${soundId.name}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E293B),
                                contentColor = FgIdle,
                                disabledContainerColor = Color(0xFF1E293B).copy(alpha = 0.5f),
                                disabledContentColor = Degraded
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(tierColor, CircleShape)
                                    )
                                    Text(
                                        text = "$label (${soundId.name})",
                                        style = HistoryRow,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }

                                if (inFlight) {
                                    Text(
                                        text = "2.2s...",
                                        style = HistoryRow,
                                        color = AccentLive,
                                        fontSize = 11.sp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = AccentLive,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF334155))

                // Section 2: Replay From File
                Text(
                    text = "REPLAY AUDIO CLIP FROM FILE",
                    style = HistoryRow,
                    color = AccentLive,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch("audio/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_demo_replay_file"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLive,
                                contentColor = Color(0xFF0F172A)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Select Audio File to Replay",
                                    style = IdleStatus,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Note: Selecting a file sends a real network classification request and consumes daily Gemini quota.",
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF334155))

                // Section 3: Telemetry Readout
                Text(
                    text = "LIVE TELEMETRY READOUT",
                    style = HistoryRow,
                    color = AccentLive,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TelemetryRow("Last Latency", if (lastLatency > 0) "${lastLatency}ms" else "None")
                        TelemetryRow("p50 Latency", if (p50 > 0) "${p50}ms" else "N/A")
                        TelemetryRow("p90 Latency", if (p90 > 0) "${p90}ms" else "N/A")
                        TelemetryRow("Total Classifications", "$totalClassifications")
                        TelemetryRow("Dropped Clips (In-flight)", "$droppedClips")
                        TelemetryRow("Quota Blocked Count", "$quotaBlockedCount")
                        TelemetryRow("Spikes Detected (Freeze)", "$spikesDetected")

                        if (failuresByCause.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Failures by Cause:",
                                style = HistoryRow,
                                color = CriticalBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            failuresByCause.forEach { (cause, count) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = cause,
                                        style = HistoryRow,
                                        color = Degraded,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "$count",
                                        style = HistoryRow,
                                        color = FgIdle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF334155))

                // Section 4: Latency Histogram
                Text(
                    text = "LATENCY HISTOGRAM (RECENT 100)",
                    style = HistoryRow,
                    color = AccentLive,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val buckets = remember(latencyList) {
                            val b0_2 = latencyList.count { it in 0L..2000L }
                            val b2_4 = latencyList.count { it in 2001L..4000L }
                            val b4_6 = latencyList.count { it in 4001L..6000L }
                            val b6_8 = latencyList.count { it in 6001L..8000L }
                            val b8_plus = latencyList.count { it > 8000L }
                            listOf(
                                "0 - 2s" to b0_2,
                                "2 - 4s" to b2_4,
                                "4 - 6s" to b4_6,
                                "6 - 8s" to b6_8,
                                "8s+" to b8_plus
                            )
                        }

                        val maxBucketCount = maxOf(1, buckets.maxOf { it.second })

                        buckets.forEach { (label, count) ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = label,
                                        style = HistoryRow,
                                        color = Degraded,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "$count",
                                        style = HistoryRow,
                                        color = FgIdle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0xFF0F172A))
                                ) {
                                    val fraction = count.toFloat() / maxBucketCount.toFloat()
                                    if (fraction > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(AccentLive)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = HistoryRow,
            color = Degraded,
            fontSize = 13.sp
        )
        Text(
            text = value,
            style = HistoryRow,
            color = FgIdle,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}
