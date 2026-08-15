package com.soundsight.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsight.BuildConfig
import com.soundsight.audio.ListenerService
import com.soundsight.audio.Sensitivity
import com.soundsight.core.AppLanguage
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertLabel
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    viewModel: HudViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = SoundSightStrings.current
    var currentSensitivity by remember { mutableStateOf(ListenerService.sensitivity) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgIdle)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .verticalScroll(scrollState)
            .testTag("settings_screen")
    ) {
        // Top bar with back arrow and screen title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = FgIdle
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = strings.settingsTitle,
                style = AlertSub,
                color = FgIdle,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("settings_title")
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 1. Language Section
        Text(
            text = strings.langLabel,
            style = IdleStatus,
            color = FgIdle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("settings_lang_label")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip(
                selected = language == AppLanguage.VI,
                onClick = {
                    if (language != AppLanguage.VI) {
                        scope.launch {
                            OnboardingStore.setLanguage(context, AppLanguage.VI)
                        }
                        onLanguageChange(AppLanguage.VI)
                    }
                },
                label = {
                    Text(
                        text = "Tiếng Việt",
                        fontWeight = if (language == AppLanguage.VI) FontWeight.Bold else FontWeight.Normal
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("chip_lang_vi"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentLive.copy(alpha = 0.2f),
                    selectedLabelColor = AccentLive,
                    containerColor = Color(0xFF1E293B),
                    labelColor = Degraded
                )
            )

            FilterChip(
                selected = language == AppLanguage.EN,
                onClick = {
                    if (language != AppLanguage.EN) {
                        scope.launch {
                            OnboardingStore.setLanguage(context, AppLanguage.EN)
                        }
                        onLanguageChange(AppLanguage.EN)
                    }
                },
                label = {
                    Text(
                        text = "English",
                        fontWeight = if (language == AppLanguage.EN) FontWeight.Bold else FontWeight.Normal
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("chip_lang_en"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentLive.copy(alpha = 0.2f),
                    selectedLabelColor = AccentLive,
                    containerColor = Color(0xFF1E293B),
                    labelColor = Degraded
                )
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        HorizontalDivider(color = Color(0xFF334155))
        Spacer(modifier = Modifier.height(28.dp))

        // 2. Sensitivity Section
        Text(
            text = strings.sensitivityLabel,
            style = IdleStatus,
            color = FgIdle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("settings_sensitivity_label")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Sensitivity.entries.forEach { sensitivity ->
                val label = when (sensitivity) {
                    Sensitivity.LOW -> strings.sensitivityLow
                    Sensitivity.MEDIUM -> strings.sensitivityMedium
                    Sensitivity.HIGH -> strings.sensitivityHigh
                }
                val isSelected = currentSensitivity == sensitivity

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        currentSensitivity = sensitivity
                        ListenerService.sensitivity = sensitivity
                        scope.launch {
                            OnboardingStore.setSensitivity(context, sensitivity)
                        }
                    },
                    label = {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("chip_sensitivity_${sensitivity.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentLive.copy(alpha = 0.2f),
                        selectedLabelColor = AccentLive,
                        containerColor = Color(0xFF1E293B),
                        labelColor = Degraded
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        HorizontalDivider(color = Color(0xFF334155))
        Spacer(modifier = Modifier.height(28.dp))

        // 3. Test Alert Section
        Button(
            onClick = { viewModel.fireTestAlert() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("btn_test_alert"),
            colors = ButtonDefaults.buttonColors(
                containerColor = CriticalBg,
                contentColor = CriticalFg
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = strings.testAlertLabel,
                style = HistoryRow,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = strings.testAlertSub,
            style = HistoryRow,
            color = Degraded,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .testTag("settings_test_alert_sub")
        )

        Spacer(modifier = Modifier.height(28.dp))
        HorizontalDivider(color = Color(0xFF334155))
        Spacer(modifier = Modifier.height(28.dp))

        // 4. About Section
        Text(
            text = strings.aboutTitle,
            style = IdleStatus,
            color = FgIdle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("settings_about_label")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${strings.versionLabel} ${BuildConfig.VERSION_NAME}",
            style = HistoryRow,
            color = Degraded,
            modifier = Modifier.testTag("settings_version_text")
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = strings.aboutLatencyNote,
            style = HistoryRow,
            color = FgIdle,
            lineHeight = 20.sp,
            modifier = Modifier.testTag("settings_latency_note")
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = strings.onboardingCard3Body,
            style = HistoryRow,
            color = Degraded,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.testTag("settings_disclaimer_text")
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}
