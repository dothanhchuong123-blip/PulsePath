package com.soundsight.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsight.core.AppLanguage
import com.soundsight.core.SoundSightStrings
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.GlyphId
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.Tier
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.WarningBg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<SoundEvent>,
    language: AppLanguage,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val strings = SoundSightStrings.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E293B),
        contentColor = FgIdle,
        modifier = Modifier.testTag("history_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = strings.historyTitle,
                style = AlertSub,
                color = FgIdle,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("history_sheet_title")
            )

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = strings.historyEmpty,
                        style = HistoryRow,
                        color = Degraded,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("history_empty_text")
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_list"),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = history,
                        key = { "${it.soundId}_${it.capturedAtMs}" }
                    ) { event ->
                        HistoryRowItem(
                            event = event,
                            language = language,
                            timeFormatted = timeFormat.format(Date(event.capturedAtMs))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRowItem(
    event: SoundEvent,
    language: AppLanguage,
    timeFormatted: String
) {
    val strings = SoundSightStrings.current
    val label = if (language == AppLanguage.VI) {
        AlertPresentation.getLabelVi(event.soundId)
    } else {
        AlertPresentation.getLabelEn(event.soundId)
    }

    val caption = if (language == AppLanguage.VI) {
        event.captionVi.ifBlank { event.captionEn }
    } else {
        event.captionEn.ifBlank { event.captionVi }
    }

    val iconVector: ImageVector = when (AlertPresentation.getGlyph(event.soundId)) {
        GlyphId.SHIELD -> Icons.Filled.Shield
        GlyphId.MEGAPHONE -> Icons.Filled.Campaign
        GlyphId.BELL -> Icons.Filled.Notifications
        GlyphId.GAUGE -> Icons.Filled.Speed
        GlyphId.ARROW_DOWN -> Icons.Filled.ArrowDownward
        GlyphId.TRUCK -> Icons.Filled.LocalShipping
        GlyphId.SIREN -> Icons.Filled.Emergency
        GlyphId.TRAIN -> Icons.Filled.Train
        GlyphId.BURST -> Icons.Filled.Warning
        GlyphId.QUESTION -> Icons.Filled.QuestionMark
    }

    val tierDotColor = when (event.tier) {
        Tier.CRITICAL -> CriticalBg
        Tier.WARNING -> WarningBg
        Tier.SAFE -> Degraded
    }

    Surface(
        color = BgIdle,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_${event.soundId}_${event.capturedAtMs}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Tier indicator dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = tierDotColor, shape = CircleShape)
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Icon
                Icon(
                    imageVector = iconVector,
                    contentDescription = label,
                    tint = if (event.tier == Tier.CRITICAL) CriticalBg else if (event.tier == Tier.WARNING) WarningBg else FgIdle,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Label and caption
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = HistoryRow,
                            fontWeight = FontWeight.SemiBold,
                            color = FgIdle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (event.repeatCount > 1) {
                            Text(
                                text = " ×${event.repeatCount}",
                                style = HistoryRow,
                                fontWeight = FontWeight.Bold,
                                color = if (event.tier == Tier.CRITICAL) CriticalBg else WarningBg,
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Time formatted HH:mm and demo badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (event.isDemo) {
                    Text(
                        text = strings.demoTagLabel,
                        style = HistoryRow,
                        color = Degraded,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = timeFormatted,
                    style = HistoryRow,
                    color = Degraded,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
