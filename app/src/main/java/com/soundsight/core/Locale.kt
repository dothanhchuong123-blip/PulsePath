package com.soundsight.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage {
    VI, EN
}

interface Strings {
    val appName: String
    val tagline: String
    val btnStart: String
    val btnStop: String
    val statusListening: String
    val statusChecking: String
    val statusOffline: String
    val permTitle: String
    val permBody: String
    val permAllow: String
    val historyTitle: String
    val historyEmpty: String
    val settingsTitle: String
    val langLabel: String
    val sensitivityLabel: String
    val testAlertLabel: String
    val aboutDisclaimer: String
}

object ViStrings : Strings {
    override val appName = "SoundSight"
    override val tagline = "Nhìn thấy âm thanh bạn không nghe được."
    override val btnStart = "Bắt đầu giám sát"
    override val btnStop = "Dừng giám sát"
    override val statusListening = "Đang lắng nghe giao thông"
    override val statusChecking = "Đang nhận diện âm thanh..."
    override val statusOffline = "Đang tạm dừng"
    override val permTitle = "Yêu cầu quyền truy cập Micro"
    override val permBody = "SoundSight cần quyền Micro để nhận biết tiếng còi, còi ưu tiên và các âm thanh nguy hiểm khi lái xe."
    override val permAllow = "Cấp quyền"
    override val historyTitle = "Lịch sử âm thanh gần đây"
    override val historyEmpty = "Chưa phát hiện âm thanh cảnh báo nào."
    override val settingsTitle = "Cài đặt"
    override val langLabel = "Ngôn ngữ"
    override val sensitivityLabel = "Độ nhạy mic"
    override val testAlertLabel = "Thử cảnh báo"
    override val aboutDisclaimer = "SoundSight là công cụ hỗ trợ nhận biết trực quan và rung, không thay thế cho sự quan sát trực tiếp khi tham gia giao thông."
}

object EnStrings : Strings {
    override val appName = "SoundSight"
    override val tagline = "Sees the sounds you can't hear."
    override val btnStart = "Start Monitoring"
    override val btnStop = "Stop Monitoring"
    override val statusListening = "Listening to traffic"
    override val statusChecking = "Analyzing sound..."
    override val statusOffline = "Paused"
    override val permTitle = "Microphone Permission Required"
    override val permBody = "SoundSight needs microphone access to detect sirens, horns, and traffic sounds while riding."
    override val permAllow = "Grant Permission"
    override val historyTitle = "Recent Sound History"
    override val historyEmpty = "No alert sounds detected yet."
    override val settingsTitle = "Settings"
    override val langLabel = "Language"
    override val sensitivityLabel = "Mic Sensitivity"
    override val testAlertLabel = "Test Alert"
    override val aboutDisclaimer = "SoundSight is a visual and haptic awareness aid. It does not replace visual road awareness."
}

val LocalStrings = staticCompositionLocalOf<Strings> { ViStrings }

object SoundSightStrings {
    val current: Strings
        @Composable
        @ReadOnlyComposable
        get() = LocalStrings.current
}
