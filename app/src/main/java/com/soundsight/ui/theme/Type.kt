package com.soundsight.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AlertLabel = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W800,
    fontSize = 44.sp,
    lineHeight = 48.sp,
    letterSpacing = 0.sp
)

val AlertSub = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W500,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp
)

val IdleStatus = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W500,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp
)

val HistoryRow = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W400,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = AlertLabel,
    headlineMedium = AlertSub,
    titleMedium = IdleStatus,
    bodyMedium = HistoryRow
)
