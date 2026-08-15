package com.soundsight.ui.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.WarningBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class SourceProbeResult(
    val name: String,
    val sourceId: Int,
    val constructed: Boolean,
    val actualSource: Int = -1,
    val actualChannelCount: Int = -1,
    val actualSampleRate: Int = -1,
    val error: String? = null
)

data class RecordingProbeResult(
    val selectedSource: String,
    val sampleRate: Int,
    val totalSamplesPerChannel: Int,
    val byteIdentical: Boolean,
    val normCorrLag0: Double,
    val bestBroadbandLag: Int,
    val maxBroadbandCorr: Double,
    val rmsLdBFS: Double,
    val rmsRdBFS: Double,
    val rmsLLowDb: Double,
    val rmsRLowDb: Double,
    val ildLowDb: Double,
    val rmsLHighDb: Double,
    val rmsRHighDb: Double,
    val ildHighDb: Double,
    val bestLowBandLag: Int,
    val maxLowBandCorr: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StereoProbeScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var unprocessedProp by remember { mutableStateOf("Checking...") }
    var sourceProbes by remember { mutableStateOf<List<SourceProbeResult>>(emptyList()) }
    var recordingResult by remember { mutableStateOf<RecordingProbeResult?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }

    // Run capability probes on screen open
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val propVal = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            unprocessedProp = propVal ?: "null (unsupported or undefined)"

            val sources = mutableListOf(
                "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
                "MIC" to MediaRecorder.AudioSource.MIC,
                "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION
            )

            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val results = sources.map { (name, sourceId) ->
                if (!hasPermission) {
                    SourceProbeResult(
                        name = name,
                        sourceId = sourceId,
                        constructed = false,
                        error = "RECORD_AUDIO permission not granted"
                    )
                } else {
                    probeAudioSource(sourceId, name)
                }
            }
            sourceProbes = results
        }
    }

    // Determine overall PASS / FAIL
    val passStatus = remember(sourceProbes, recordingResult) {
        val grantedStereo = sourceProbes.any { it.constructed && it.actualChannelCount == 2 }
        if (recordingResult != null) {
            val rec = recordingResult!!
            if (rec.byteIdentical) {
                false to "FAIL: Left and Right channels are byte-identical (Mono cloned)"
            } else if (rec.normCorrLag0 >= 0.98) {
                false to "FAIL: Lag-0 correlation is >= 0.98 (${String.format(Locale.US, "%.4f", rec.normCorrLag0)})"
            } else if (!grantedStereo) {
                false to "FAIL: No AudioRecord granted with channelCount == 2"
            } else {
                true to "PASS: True stereo input verified (distinct channels, lag-0 corr < 0.98)"
            }
        } else if (sourceProbes.isNotEmpty()) {
            if (grantedStereo) {
                null to "READY TO TEST: Stereo source available, tap 'Record 3 seconds'"
            } else {
                false to "FAIL: None of the tested sources granted 2 channels at 48kHz"
            }
        } else {
            null to "PROBING HARDWARE CAPABILITIES..."
        }
    }

    fun buildClipboardReport(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val timeStr = sdf.format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== SOUNDSIGHT STEREO CAPABILITY PROBE ===")
        sb.appendLine("Timestamp: $timeStr")
        sb.appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
        sb.appendLine("Summary: ${passStatus.second}")
        sb.appendLine()
        sb.appendLine("--- PART 1: HARDWARE CAPABILITY ---")
        sb.appendLine("PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED: $unprocessedProp")
        sourceProbes.forEach { p ->
            sb.appendLine("Source ${p.name} (id=${p.sourceId}):")
            if (p.constructed) {
                sb.appendLine("  Constructed: SUCCESS")
                sb.appendLine("  Granted Source: ${p.actualSource}")
                sb.appendLine("  Granted Channel Count: ${p.actualChannelCount}")
                sb.appendLine("  Granted Sample Rate: ${p.actualSampleRate} Hz")
            } else {
                sb.appendLine("  Constructed: FAILED (${p.error})")
            }
        }
        sb.appendLine()
        sb.appendLine("--- PART 2: 3-SECOND STEREO CAPTURE DSP ANALYSIS ---")
        if (recordingResult != null) {
            val r = recordingResult!!
            sb.appendLine("Selected Source: ${r.selectedSource}")
            sb.appendLine("Sample Rate: ${r.sampleRate} Hz")
            sb.appendLine("Samples per Channel: ${r.totalSamplesPerChannel}")
            sb.appendLine("Byte-Identical Channels: ${if (r.byteIdentical) "YES (FATAL MONO CLONE)" else "NO (Distinct Channels)"}")
            sb.appendLine("Normalised Cross-Correlation at Lag 0: ${String.format(Locale.US, "%.4f", r.normCorrLag0)}")
            sb.appendLine("Broadband Search [-64..+64]:")
            sb.appendLine("  Best Broadband Lag: ${r.bestBroadbandLag} samples (${String.format(Locale.US, "%.2f", r.bestBroadbandLag * 1000.0 / r.sampleRate)} ms)")
            sb.appendLine("  Max Broadband Correlation: ${String.format(Locale.US, "%.4f", r.maxBroadbandCorr)}")
            sb.appendLine("Broadband RMS:")
            sb.appendLine("  Left:  ${String.format(Locale.US, "%.2f", r.rmsLdBFS)} dBFS")
            sb.appendLine("  Right: ${String.format(Locale.US, "%.2f", r.rmsRdBFS)} dBFS")
            sb.appendLine("Band-limited Analysis:")
            sb.appendLine("  Low Band (150 - 1000 Hz):")
            sb.appendLine("    RMS Left:  ${String.format(Locale.US, "%.2f", r.rmsLLowDb)} dBFS")
            sb.appendLine("    RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRLowDb)} dBFS")
            sb.appendLine("    Low-Band ILD: ${String.format(Locale.US, "%.2f", r.ildLowDb)} dB")
            sb.appendLine("    Best Low-Band Lag: ${r.bestLowBandLag} samples (${String.format(Locale.US, "%.2f", r.bestLowBandLag * 1000.0 / r.sampleRate)} ms)")
            sb.appendLine("    Max Low-Band Correlation: ${String.format(Locale.US, "%.4f", r.maxLowBandCorr)}")
            sb.appendLine("  High Band (2000 - 6000 Hz):")
            sb.appendLine("    RMS Left:  ${String.format(Locale.US, "%.2f", r.rmsLHighDb)} dBFS")
            sb.appendLine("    RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRHighDb)} dBFS")
            sb.appendLine("    High-Band ILD: ${String.format(Locale.US, "%.2f", r.ildHighDb)} dB")
        } else {
            sb.appendLine("No 3-second recording analysis run yet.")
        }
        sb.appendLine("==========================================")
        return sb.toString()
    }

    Scaffold(
        containerColor = BgIdle,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Stereo Audio Capability Probe",
                        style = AlertSub,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_back_stereo_probe")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FgIdle
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // PART 4: PASS / FAIL SUMMARY BANNER
            val (isPass, statusMsg) = passStatus
            val bannerColor = when (isPass) {
                true -> Color(0xFF065F46) // Emerald green
                false -> CriticalBg
                null -> Color(0xFF1E293B) // Neutral slate
            }
            val bannerTextColor = when (isPass) {
                true -> AccentLive
                false -> CriticalFg
                null -> FgIdle
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = bannerColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stereo_probe_summary_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "HARDWARE VERIFICATION SUMMARY",
                        style = HistoryRow,
                        fontWeight = FontWeight.Bold,
                        color = bannerTextColor,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusMsg,
                        style = IdleStatus,
                        fontWeight = FontWeight.Bold,
                        color = bannerTextColor,
                        fontSize = 14.sp
                    )
                }
            }

            // ACTION BUTTONS: Record 3s & Copy Results
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val firstSource = sourceProbes.firstOrNull { it.constructed }
                        if (firstSource == null) {
                            recordingError = "No valid AudioRecord source available to capture stereo."
                            return@Button
                        }
                        isRecording = true
                        recordingError = null
                        scope.launch {
                            try {
                                val res = withContext(Dispatchers.IO) {
                                    captureAndAnalyzeStereo(firstSource.sourceId, firstSource.name)
                                }
                                recordingResult = res
                            } catch (e: Exception) {
                                recordingError = "Recording failed: ${e.message}"
                            } finally {
                                isRecording = false
                            }
                        }
                    },
                    enabled = !isRecording && sourceProbes.any { it.constructed },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentLive,
                        contentColor = BgIdle
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_record_stereo_probe")
                ) {
                    if (isRecording) {
                        CircularProgressIndicator(
                            color = BgIdle,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Recording 3s...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Record 3s", fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val report = buildClipboardReport()
                        clipboard.setPrimaryClip(ClipData.newPlainText("StereoProbe", report))
                        Toast.makeText(context, "Full report copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF334155),
                        contentColor = FgIdle
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_copy_stereo_probe")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Results", fontWeight = FontWeight.Bold)
                }
            }

            recordingError?.let { err ->
                Text(
                    text = err,
                    color = CriticalBg,
                    style = HistoryRow,
                    fontSize = 12.sp
                )
            }

            // PART 1: CAPABILITY REPORT
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "PART 1: AUDIO SOURCE CONSTRUCT PROBES",
                        style = HistoryRow,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )

                    Text(
                        text = "AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED:\n$unprocessedProp",
                        style = HistoryRow,
                        color = FgIdle,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )

                    HorizontalDivider(color = Color(0xFF2E3D4F))

                    sourceProbes.forEach { p ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Source: ${p.name} (AudioSource.${p.name})",
                                style = HistoryRow,
                                fontWeight = FontWeight.Bold,
                                color = if (p.constructed) AccentLive else CriticalBg,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (p.constructed) {
                                Text("• Construction: SUCCEEDED", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Actual Source Granted: ${p.actualSource}", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Actual Channels: ${p.actualChannelCount} ${if (p.actualChannelCount == 2) "(STEREO)" else "(MONO)"}", color = if (p.actualChannelCount == 2) AccentLive else WarningBg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Actual Sample Rate: ${p.actualSampleRate} Hz", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            } else {
                                Text("• Construction: FAILED", color = CriticalBg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Error: ${p.error}", color = Degraded, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // PART 2: DSP ANALYSIS REPORT
            if (recordingResult != null) {
                val r = recordingResult!!
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "PART 2: 3-SECOND STEREO DSP ANALYSIS",
                            style = HistoryRow,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Text(
                            text = "Captured via: ${r.selectedSource} @ ${r.sampleRate}Hz (${r.totalSamplesPerChannel} samples/channel)",
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp
                        )

                        HorizontalDivider(color = Color(0xFF2E3D4F))

                        // Byte identical check
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("a) Byte-identical channels:", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = if (r.byteIdentical) "TRUE (FATAL MONO)" else "FALSE (Pass)",
                                color = if (r.byteIdentical) CriticalBg else AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Lag 0 correlation
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("b) Norm Corr @ Lag 0:", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = String.format(Locale.US, "%.4f", r.normCorrLag0),
                                color = if (r.normCorrLag0 < 0.98) AccentLive else WarningBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Broadband best lag
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("c) Broadband Lag [-64..+64]:", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = "${r.bestBroadbandLag} smp (r=${String.format(Locale.US, "%.4f", r.maxBroadbandCorr)})",
                                color = FgIdle,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Broadband RMS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("d) Broadband RMS (L / R):", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = "${String.format(Locale.US, "%.1f", r.rmsLdBFS)} / ${String.format(Locale.US, "%.1f", r.rmsRdBFS)} dBFS",
                                color = FgIdle,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        HorizontalDivider(color = Color(0xFF2E3D4F))

                        Text(
                            text = "e) Band-limited RMS & ILD Analysis:",
                            style = HistoryRow,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "LOW BAND (150 - 1000 Hz):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text("• RMS Left: ${String.format(Locale.US, "%.2f", r.rmsLLowDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRLowDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• Low-band ILD (20*log10(L/R)): ${String.format(Locale.US, "%.2f", r.ildLowDb)} dB", color = AccentLive, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• Low-band Lag (f): ${r.bestLowBandLag} smp (r=${String.format(Locale.US, "%.4f", r.maxLowBandCorr)})", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "HIGH BAND (2000 - 6000 Hz):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text("• RMS Left: ${String.format(Locale.US, "%.2f", r.rmsLHighDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRHighDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• High-band ILD (20*log10(L/R)): ${String.format(Locale.US, "%.2f", r.ildHighDb)} dB", color = AccentLive, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---- Probe & DSP Implementation ----

private fun probeAudioSource(sourceId: Int, name: String): SourceProbeResult {
    var record: AudioRecord? = null
    try {
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBufferSize, sampleRate * 2 * 2)

        record = AudioRecord(
            sourceId,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (record.state == AudioRecord.STATE_INITIALIZED) {
            val actualSource = record.audioSource
            val actualChannels = record.channelCount
            val actualRate = record.sampleRate
            return SourceProbeResult(
                name = name,
                sourceId = sourceId,
                constructed = true,
                actualSource = actualSource,
                actualChannelCount = actualChannels,
                actualSampleRate = actualRate
            )
        } else {
            return SourceProbeResult(
                name = name,
                sourceId = sourceId,
                constructed = false,
                error = "AudioRecord STATE_UNINITIALIZED"
            )
        }
    } catch (e: Exception) {
        return SourceProbeResult(
            name = name,
            sourceId = sourceId,
            constructed = false,
            error = e.message ?: e.toString()
        )
    } finally {
        try {
            record?.release()
        } catch (_: Exception) {}
    }
}

private fun captureAndAnalyzeStereo(sourceId: Int, sourceName: String): RecordingProbeResult {
    val sampleRate = 48000
    val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    val bufferSize = max(minBufferSize, sampleRate * 2 * 2)

    val record = AudioRecord(
        sourceId,
        sampleRate,
        channelConfig,
        audioFormat,
        bufferSize
    )

    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IllegalStateException("Failed to initialize AudioRecord for $sourceName")
    }

    val totalSamplesNeeded = sampleRate * 3 // 3 seconds per channel
    val interleavedShortsNeeded = totalSamplesNeeded * 2 // 2 channels
    val interleavedBuffer = ShortArray(interleavedShortsNeeded)
    var totalRead = 0

    try {
        record.startRecording()
        val tempBuffer = ShortArray(4096)
        while (totalRead < interleavedShortsNeeded) {
            val toRead = minOf(tempBuffer.size, interleavedShortsNeeded - totalRead)
            val read = record.read(tempBuffer, 0, toRead)
            if (read < 0) {
                throw IllegalStateException("AudioRecord.read returned error code $read")
            }
            if (read > 0) {
                System.arraycopy(tempBuffer, 0, interleavedBuffer, totalRead, read)
                totalRead += read
            }
        }
    } finally {
        try {
            record.stop()
            record.release()
        } catch (_: Exception) {}
    }

    // De-interleave into left and right float arrays (normalized -1.0..1.0)
    val left = FloatArray(totalSamplesNeeded)
    val right = FloatArray(totalSamplesNeeded)
    var byteIdentical = true

    for (i in 0 until totalSamplesNeeded) {
        val sL = interleavedBuffer[i * 2]
        val sR = interleavedBuffer[i * 2 + 1]
        if (sL != sR) {
            byteIdentical = false
        }
        left[i] = sL / 32768.0f
        right[i] = sR / 32768.0f
    }

    // b) Normalized cross-correlation at lag 0
    val normCorrLag0 = computeNormalizedCorrelation(left, right, lag = 0)

    // c) Integer lag search over -64..+64 for broadband
    val (bestBroadbandLag, maxBroadbandCorr) = findBestLag(left, right, maxLag = 64)

    // d) RMS of each channel separately in dBFS
    val rmsL = computeRms(left)
    val rmsR = computeRms(right)
    val rmsLdBFS = toDbFs(rmsL)
    val rmsRdBFS = toDbFs(rmsR)

    // e) Bandpass filter: Low (150-1000 Hz) and High (2000-6000 Hz)
    val biquadLow = BiquadBandpass(fLow = 150.0, fHigh = 1000.0, sampleRate = sampleRate.toDouble())
    val biquadHigh = BiquadBandpass(fLow = 2000.0, fHigh = 6000.0, sampleRate = sampleRate.toDouble())

    val leftLow = biquadLow.process(left)
    val rightLow = biquadLow.process(right)

    val leftHigh = biquadHigh.process(left)
    val rightHigh = biquadHigh.process(right)

    val rmsLLow = computeRms(leftLow)
    val rmsRLow = computeRms(rightLow)
    val rmsLLowDb = toDbFs(rmsLLow)
    val rmsRLowDb = toDbFs(rmsRLow)
    val ildLowDb = if (rmsRLow > 1e-9) 20.0 * log10(rmsLLow / rmsRLow) else 0.0

    val rmsLHigh = computeRms(leftHigh)
    val rmsRHigh = computeRms(rightHigh)
    val rmsLHighDb = toDbFs(rmsLHigh)
    val rmsRHighDb = toDbFs(rmsRHigh)
    val ildHighDb = if (rmsRHigh > 1e-9) 20.0 * log10(rmsLHigh / rmsRHigh) else 0.0

    // f) Low-band lag search over -64..+64
    val (bestLowBandLag, maxLowBandCorr) = findBestLag(leftLow, rightLow, maxLag = 64)

    return RecordingProbeResult(
        selectedSource = sourceName,
        sampleRate = sampleRate,
        totalSamplesPerChannel = totalSamplesNeeded,
        byteIdentical = byteIdentical,
        normCorrLag0 = normCorrLag0,
        bestBroadbandLag = bestBroadbandLag,
        maxBroadbandCorr = maxBroadbandCorr,
        rmsLdBFS = rmsLdBFS,
        rmsRdBFS = rmsRdBFS,
        rmsLLowDb = rmsLLowDb,
        rmsRLowDb = rmsRLowDb,
        ildLowDb = ildLowDb,
        rmsLHighDb = rmsLHighDb,
        rmsRHighDb = rmsRHighDb,
        ildHighDb = ildHighDb,
        bestLowBandLag = bestLowBandLag,
        maxLowBandCorr = maxLowBandCorr
    )
}

// ---- Naive DSP Helpers ----

private fun computeRms(samples: FloatArray): Double {
    var sumSq = 0.0
    for (s in samples) {
        sumSq += s * s
    }
    return sqrt(sumSq / samples.size)
}

private fun toDbFs(rms: Double): Double {
    if (rms <= 1e-9) return -120.0
    return 20.0 * log10(rms)
}

private fun computeNormalizedCorrelation(left: FloatArray, right: FloatArray, lag: Int): Double {
    val n = left.size
    val startL = if (lag >= 0) lag else 0
    val startR = if (lag >= 0) 0 else -lag
    val length = n - kotlin.math.abs(lag)
    if (length <= 0) return 0.0

    var sumLR = 0.0
    var sumL2 = 0.0
    var sumR2 = 0.0

    for (i in 0 until length) {
        val l = left[startL + i].toDouble()
        val r = right[startR + i].toDouble()
        sumLR += l * r
        sumL2 += l * l
        sumR2 += r * r
    }

    val denom = sqrt(sumL2 * sumR2)
    return if (denom > 1e-12) sumLR / denom else 0.0
}

private fun findBestLag(left: FloatArray, right: FloatArray, maxLag: Int): Pair<Int, Double> {
    var bestLag = 0
    var maxCorr = -2.0

    for (lag in -maxLag..maxLag) {
        val corr = computeNormalizedCorrelation(left, right, lag)
        if (corr > maxCorr) {
            maxCorr = corr
            bestLag = lag
        }
    }
    return bestLag to maxCorr
}

/**
 * Standard second-order IIR Biquad Band-pass Filter (constant 0 dB peak gain).
 */
private class BiquadBandpass(fLow: Double, fHigh: Double, sampleRate: Double) {
    private val f0 = sqrt(fLow * fHigh)
    private val bw = (fHigh - fLow) / f0
    private val w0 = 2.0 * PI * f0 / sampleRate
    private val alpha = sin(w0) * Math.sinh(Math.log(2.0) / 2.0 * bw * w0 / sin(w0))

    // BPF with constant 0 dB peak gain:
    // b0 = alpha, b1 = 0, b2 = -alpha
    // a0 = 1 + alpha, a1 = -2*cos(w0), a2 = 1 - alpha
    private val a0 = 1.0 + alpha
    private val b0 = alpha / a0
    private val b1 = 0.0
    private val b2 = -alpha / a0
    private val a1 = (-2.0 * cos(w0)) / a0
    private val a2 = (1.0 - alpha) / a0

    fun process(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[i] = y0.toFloat()

            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return output
    }
}
