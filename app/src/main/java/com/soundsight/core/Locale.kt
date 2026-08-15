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
    val hudListeningChip: String
    val hudOfflineChip: String
    val hudStopButton: String
    val hudHistoryButton: String
    val dismissLabel: String
    val onboardingPrimerTitle: String
    val onboardingCard1Title: String
    val onboardingCard1Body: String
    val onboardingCard2Title: String
    val onboardingCard2Body: String
    val onboardingCard3Title: String
    val onboardingCard3Body: String
    val onboardingPermGrantButton: String
    val onboardingPermDeniedTitle: String
    val onboardingPermDeniedBody: String
    val onboardingOpenSettingsButton: String
    val onboardingReliabilityTitle: String
    val onboardingReliabilityBody: String
    val onboardingBatteryButton: String
    val onboardingSkipButton: String
    val onboardingMountInstruction: String
    val onboardingClapInstruction: String
    val onboardingMicOk: String
    val onboardingStartButton: String
    val aboutLatencyNote: String
    val testAlertSub: String
    val sensitivityLow: String
    val sensitivityMedium: String
    val sensitivityHigh: String
    val aboutTitle: String
    val versionLabel: String
    val permissionRevokedTitle: String
    val permissionRevokedBody: String
    val micInUseChip: String
    val setupErrorChip: String
    val dailyQuotaChip: String
    val rateLimitedChip: String
    val buzzOnlyChip: String
    val demoTagLabel: String
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
    override val hudListeningChip = "Đang lắng nghe"
    override val hudOfflineChip = "Mất mạng - chỉ báo rung"
    override val hudStopButton = "Dừng"
    override val hudHistoryButton = "Lịch sử"
    override val dismissLabel = "Đã hiểu"
    override val onboardingPrimerTitle = "Cách SoundSight hoạt động"
    override val onboardingCard1Title = "Lắng nghe xung quanh"
    override val onboardingCard1Body = "Ứng dụng dùng micro để nhận biết tiếng còi xe, còi ưu tiên và các âm thanh giao thông bạn khó nghe thấy khi đội mũ bảo hiểm."
    override val onboardingCard2Title = "Dữ liệu âm thanh"
    override val onboardingCard2Body = "Khi phát hiện âm thanh lớn bất thường, đoạn ghi âm ngắn ~2 giây được gửi tới Google Gemini API để phân loại. Ứng dụng không lưu trữ bất kỳ bản ghi âm nào trên thiết bị hay máy chủ."
    override val onboardingCard3Title = "Giới hạn & Lưu ý an toàn"
    override val onboardingCard3Body = "SoundSight là công cụ hỗ trợ nhận biết, không thay thế cho việc quan sát trực tiếp và không phải hệ thống tránh va chạm. Cảnh báo có độ trễ vài giây và hoạt động tốt nhất với còi tầm xa hoặc còi xe tải. Hãy luôn lái xe cẩn thận như khi không có ứng dụng."
    override val onboardingPermGrantButton = "Cấp quyền truy cập"
    override val onboardingPermDeniedTitle = "Cần quyền Micro để hoạt động"
    override val onboardingPermDeniedBody = "Quyền Micro đã bị từ chối. Vui lòng mở Cài đặt ứng dụng và cấp quyền Micro để SoundSight có thể lắng nghe âm thanh giao thông."
    override val onboardingOpenSettingsButton = "Mở Cài đặt"
    override val onboardingReliabilityTitle = "Duy trì hoạt động nền"
    override val onboardingReliabilityBody = "Một số hãng điện thoại (Samsung, Xiaomi, Oppo, Vivo...) có thể tự động đóng ứng dụng chạy ngầm, làm gián đoạn cảnh báo âm thanh khi đang lái xe."
    override val onboardingBatteryButton = "Tắt tối ưu hóa pin"
    override val onboardingSkipButton = "Bỏ qua"
    override val onboardingMountInstruction = "Kẹp điện thoại vào giá đỡ, hướng về phía bạn."
    override val onboardingClapInstruction = "Hãy vỗ tay một lần để kiểm tra micro."
    override val onboardingMicOk = "Mic OK"
    override val onboardingStartButton = "Bắt đầu"
    override val aboutLatencyNote = "Hệ thống 2 tầng: rung cảnh báo tức thì trên thiết bị khi phát hiện âm thanh lớn, sau đó AI phân loại và ghi vào lịch sử trong vài giây."
    override val testAlertSub = "Kích hoạt mô phỏng cảnh báo toàn màn hình và rung."
    override val sensitivityLow = "Thấp"
    override val sensitivityMedium = "Vừa"
    override val sensitivityHigh = "Cao"
    override val aboutTitle = "Giới thiệu"
    override val versionLabel = "Phiên bản"
    override val permissionRevokedTitle = "Quyền Micro đã bị thu hồi"
    override val permissionRevokedBody = "SoundSight không thể tiếp tục lắng nghe âm thanh nếu không có quyền Micro. Vui lòng cấp lại quyền trong Cài đặt ứng dụng."
    override val micInUseChip = "Micro bị chiếm"
    override val setupErrorChip = "Lỗi cấu hình"
    override val dailyQuotaChip = "Hết lượt AI hôm nay"
    override val rateLimitedChip = "Tạm quá tải"
    override val buzzOnlyChip = "Chỉ báo rung"
    override val demoTagLabel = "demo"
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
    override val hudListeningChip = "Listening"
    override val hudOfflineChip = "Offline - buzz only"
    override val hudStopButton = "Stop"
    override val hudHistoryButton = "History"
    override val dismissLabel = "Got it"
    override val onboardingPrimerTitle = "How SoundSight Works"
    override val onboardingCard1Title = "Listening to Traffic"
    override val onboardingCard1Body = "SoundSight uses the microphone to listen for sirens, horns, and traffic sounds you might miss while riding."
    override val onboardingCard2Title = "Audio Data & Privacy"
    override val onboardingCard2Body = "When a sound spike is detected, a ~2-second clip is sent to Google's Gemini API for classification. No audio recordings are stored on your device or any server."
    override val onboardingCard3Title = "Limitations & Safety"
    override val onboardingCard3Body = "SoundSight is an awareness aid, not collision avoidance. Alerts arrive a few seconds after a sound. It works best for sirens and truck air horns audible from far away. It will not warn about a horn right behind you in time to react. Always ride as if the app is not there."
    override val onboardingPermGrantButton = "Grant Permissions"
    override val onboardingPermDeniedTitle = "Microphone Permission Required"
    override val onboardingPermDeniedBody = "Microphone access was denied. Please open App Settings and grant microphone permission so SoundSight can monitor traffic sounds."
    override val onboardingOpenSettingsButton = "Open Settings"
    override val onboardingReliabilityTitle = "Background Reliability"
    override val onboardingReliabilityBody = "Some manufacturers (Samsung, Xiaomi, Oppo, Vivo) aggressively kill background apps, which would silence critical alerts while riding."
    override val onboardingBatteryButton = "Disable Battery Optimization"
    override val onboardingSkipButton = "Skip"
    override val onboardingMountInstruction = "Clip your phone to the mount, facing you."
    override val onboardingClapInstruction = "Clap your hands once to test the microphone."
    override val onboardingMicOk = "Mic OK"
    override val onboardingStartButton = "Start"
    override val aboutLatencyNote = "Two-tier system: an instant on-device vibration alert when a loud sound is detected, followed by AI classification recorded to history within a few seconds."
    override val testAlertSub = "Triggers a full-screen alert takeover and haptic pattern."
    override val sensitivityLow = "Low"
    override val sensitivityMedium = "Medium"
    override val sensitivityHigh = "High"
    override val aboutTitle = "About"
    override val versionLabel = "Version"
    override val permissionRevokedTitle = "Microphone Permission Revoked"
    override val permissionRevokedBody = "SoundSight cannot listen for traffic sounds without microphone access. Please re-enable the permission in App Settings."
    override val micInUseChip = "Mic in use"
    override val setupErrorChip = "Setup error"
    override val dailyQuotaChip = "Daily AI limit reached"
    override val rateLimitedChip = "Rate limited"
    override val buzzOnlyChip = "Buzz only"
    override val demoTagLabel = "demo"
}

val LocalStrings = staticCompositionLocalOf<Strings> { ViStrings }

object SoundSightStrings {
    val current: Strings
        @Composable
        @ReadOnlyComposable
        get() = LocalStrings.current
}
