package com.soundsight.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage {
    VI, EN
}

data class PrivacyPolicySection(
    val title: String,
    val body: String
)

data class PrivacyPolicyContent(
    val introduction: String,
    val sections: List<PrivacyPolicySection>
)

interface Strings {
    val appName: String
    val tagline: String
    val splashQuote: String
    val btnStart: String
    val btnStop: String
    val statusListening: String
    val statusPaused: String
    val hudPausedChip: String
    val statusChecking: String
    val statusOffline: String
    val permTitle: String
    val permBody: String
    val permAllow: String
    val historyTitle: String
    val historyEmpty: String
    val settingsTitle: String
    val langLabel: String
    /**
     * Spoken description of the globe in the Settings app bar. Both name the language a press
     * would switch *to*, because that is what the control does - the badge beside the globe is
     * what says which one is in force.
     */
    val langSwitchToEnglish: String
    val langSwitchToVietnamese: String
    val sensitivityLabel: String
    val testAlertLabel: String
    val aboutDisclaimer: String
    val hudListeningChip: String
    /**
     * Shown while the cloud session is re-establishing itself - after a network switch, or after a
     * dropped socket. Distinct from [hudOfflineChip], which means there is no connection at all:
     * this one is a state that resolves on its own, so it says what is happening rather than what
     * has been lost.
     */
    val hudReconnectingChip: String
    /**
     * Shown when there is no usable network.
     *
     * Says "local detection", not "buzz only": the on-device classifier keeps running and keeps
     * producing labelled alerts with no network at all, so the old wording reported a working
     * tier as a failure and undersold the one thing still protecting the rider.
     */
    val hudOfflineChip: String
    val hudStopButton: String
    val hudHistoryButton: String
    val dismissLabel: String
    val onboardingPrimerTitle: String
    val onboardingLanguageTitle: String
    val onboardingLanguageSubtitle: String
    val onboardingContinue: String
    val disclaimerTitle: String
    val disclaimerBullet1: String
    val disclaimerBullet2: String
    val disclaimerBullet3: String
    val disclaimerBullet3Prefix: String
    val disclaimerBullet3Link: String
    val disclaimerBullet3Suffix: String
    val disclaimerAcceptButton: String
    val onboardingNextButton: String
    val onboardingGetStartedButton: String
    val onboardingCard1Title: String
    val onboardingCard1Body: String
    val onboardingCard2Title: String
    val onboardingCard2Body: String
    val onboardingCard3Title: String
    val onboardingCard3Body: String
    /**
     * The one line of the whole onboarding a rider must carry onto the road. It is deliberately
     * NOT part of [onboardingCard3Body]: buried in a paragraph it reads as one more caveat, and
     * the carousel is skippable, so the sentence that decides whether the app is trusted too much
     * gets its own emphasised callout - see IntroCarouselScreen's PagerSlide.highlight.
     */
    val onboardingCard3Highlight: String
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
    /**
     * The gated fourth stop's name. Its multiplier is a taper continuation, NOT a measured
     * value - see [sensitivityMaxSub], which must say so wherever the stop is offered.
     */
    val sensitivityMax: String
    /** Provisional-flag copy shown while Max is the live sensitivity (hard constraint 8). */
    val sensitivityMaxSub: String
    val a11yIncrease: String
    val a11yDecrease: String
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
    val directionCalibrationTitle: String
    val directionCalibrationStatusUncalibrated: String
    val directionCalibrationStatusCalibrated: String
    val directionCalibrationStatusUnsupported: String
    val directionCalibrationNeedListener: String
    val directionCalibrationReturnHome: String
    val directionCalibrationStep1: String
    val directionCalibrationStep2: String
    val directionCalibrationListenBtn: String
    val directionCalibrationListening: String
    val directionCalibrationTimeout: String
    val directionCalibrationLowConfidence: String
    val directionCalibrationSuccess: String
    val directionCalibrationUnsupportedMsg: String
    val directionCalibrationDoneBtn: String
    val directionCalibrationSkipBtn: String
    val directionCalibrationStep1Label: String
    val directionCalibrationStep2Label: String
    val directionCalibrationRetryBtn: String
    val directionCalibrationRecalibrateBtn: String
    val directionCalibrationCooldown: String
    val directionCalibrationUnusableCapture: String
    val directionCalibrationLagImplausible: String
    val directionCalibrationLagAmbiguous: String
    val directionCalibrationSameSide: String
    val directionCalibrationNeedLandscape: String
    val directionCalibrationRotateTitle: String
    val directionCalibrationRotateBody: String
    val directionCalibrationRotatedAway: String
    val directionCalibrationStandLeft: String
    val directionCalibrationStandRight: String
    /**
     * One-time migration notice (A6): shown while an old calibration exists that predates
     * rotation binding. Direction stays UNKNOWN until the rider recalibrates, so this tells
     * them why instead of leaving a silently dead arrow.
     */
    val directionRecalibrationNotice: String
    val settingsSectionAlerts: String
    val settingsSectionStartSafely: String
    val settingsSectionDetection: String
    val settingsSectionGeneral: String
    val settingsSectionAppearance: String
    val settingsSectionAboutMaintenance: String
    val vibrationLabel: String
    val vibrationSub: String
    val takeoverLabel: String
    val takeoverSub: String
    val keepMonitoringInBackgroundLabel: String
    val keepMonitoringInBackgroundSub: String
    val sirenAlertInBackgroundLabel: String
    val sirenAlertInBackgroundSub: String
    val batteryOptDialogTitle: String
    val batteryOptDialogBody: String
    val batteryOptDialogConfirm: String
    val batteryOptDialogDismiss: String
    val notificationListening: String
    val notificationPaused: String
    val notificationProtectionActive: String
    val notificationLocalProtectionOnly: String
    val notificationNotProtecting: String
    val hudProtectionActiveChip: String
    val hudLocalProtectionOnlyChip: String
    val hudNotProtectingChip: String
    val statusProtectionActive: String
    val statusLocalProtectionOnly: String
    val statusNotProtecting: String
    val notificationActionPause: String
    val notificationActionResume: String
    val notificationActionStop: String
    val arrowLabel: String
    val arrowSub: String
    val collisionLabel: String
    val collisionSub: String
    val cloudAiLabel: String
    val cloudAiSub: String
    val voiceHailAction: String
    val themeModeLabel: String
    val themeModeSystem: String
    val themeModeLight: String
    val themeModeDark: String
    val contrastBoostLabel: String
    val contrastBoostSub: String
    val contrastBoostAuto: String
    val contrastBoostAlwaysOn: String
    val contrastBoostOff: String
    val contrastBoostNoSensorSub: String
    val wallpaperColorsLabel: String
    val wallpaperColorsSub: String
    val locationSectionLabel: String
    val locationModeLabel: String
    val locationModeOff: String
    val locationModeCollisionOnly: String
    val locationModeAlways: String
    val locationModeOffSub: String
    val locationModeCollisionSub: String
    /** The "Always on" supporting text; %s is the measured battery cost per 30 minutes. */
    val locationModeAlwaysSub: String
    val locationModeAlwaysSubUnmeasured: String
    val locationPermissionDeniedSub: String
    val speedUnitKmh: String
    val speedNoFix: String
    val speedHeld: String
    /** Settings row: whether the HUD draws the speed number. Independent of LocationMode. */
    val speedReadoutLabel: String
    val speedReadoutSub: String
    /** Settings row: keep the HUD clock on 24-hour time. Off renders 12-hour with [clockAmLabel]/[clockPmLabel]. */
    val clock24HourLabel: String
    val clock24HourSub: String
    /**
     * The 12-hour clock's meridiem marks, localized independently of the device locale - the
     * clock has always ignored device locale (hardcoded Locale.US), and these stay consistent
     * with that: "SA"/"CH" (sáng/chiều) for Vietnamese, "AM"/"PM" for English.
     */
    val clockAmLabel: String
    val clockPmLabel: String
    val waveStyleLabel: String
    val waveStyleLine: String
    val waveStyleDots: String
    /** Tap-hint under the wave-style preview, e.g. "Tap to switch". */
    val waveStyleTapHint: String
    val directionLeft: String
    val directionRight: String
    val arrowStyleLabel: String
    val arrowStyleChevron: String
    val arrowStyleArrow: String
    val directionLabelLabel: String
    val directionLabelSub: String
    val crashPreviewLabel: String
    val crashPreviewSub: String
    val crashPreviewButton: String
    val resetDefaultsLabel: String
    val resetDefaultsSub: String
    val resetDefaultsButton: String
    val resetDefaultsDialogBody: String
    val resetDefaultsCancel: String
    val resetDefaultsDone: String
    val micPermissionTitle: String
    val micPermissionBody: String
    val micPermissionLater: String
    val settingsStateOn: String
    val settingsStateOff: String

    // ── Emergency profile / post-collision (P29) ────────────────────────────
    val emergencyTitle: String
    val emergencySub: String
    val emergencyYourDetails: String
    val emergencyEnableLabel: String
    val emergencyEnableSub: String
    val emergencyRiderName: String
    val emergencyBloodType: String
    val emergencyMedicalNotes: String
    val emergencyMedicalNotesHint: String
    val emergencyContact1: String
    val emergencyContact2: String
    val emergencyContactName: String
    val emergencyContactPhone: String
    val emergencySave: String
    val emergencySaved: String
    val emergencyClear: String
    val emergencyClearConfirm: String
    val emergencyClearDialogTitle: String
    val emergencyClearDialogBody: String
    val emergencyClearDialogConfirm: String
    val emergencyCancel: String
    val emergencyPrivacyNote: String
    val emergencyNeedsContact: String
    val emergencyLocationNote: String
    val crashTitle: String
    val crashPreviewChip: String
    val crashSampleName: String
    val crashSampleContact: String
    val crashSampleNotes: String
    val crashBloodLabel: String
    val crashBystanderNote: String
    val crashCallButton: String
    val crashSendLocation: String
    val crashImOk: String
    /** Format args: 1=rider name, 2=maps link, 3=age in minutes. */
    val crashSmsBody: String
    /** Format arg: 1=rider name. */
    val crashSmsBodyNoLocation: String
    val crashNoAppFound: String

    // ── Demo & Diagnostics governance (Step 7 / H6) ──────────────────────────
    val demoDisclaimerTitle: String
    val demoDisclaimerBody: String
    val demoDisclaimerConfirm: String
    val demoDisclaimerDismiss: String
    val demoAlertBadge: String
    val developerRowTitle: String
    val developerRowSubtitle: String
    val developerModeCountdown: String
    val developerModeCountdownSingle: String
    val developerModeEnabled: String
    val developerModeAlreadyEnabled: String

    val privacyPolicyTitle: String
    val privacyPolicyContent: PrivacyPolicyContent
}

object ViStrings : Strings {
    override val appName = "PulsePath"
    override val tagline = "Nhìn thấy âm thanh bạn không nghe được."
    override val splashQuote = "Feel the pulse. Own the path"
    override val btnStart = "Bắt đầu giám sát"
    override val btnStop = "Dừng giám sát"
    override val statusListening = "Đang lắng nghe giao thông"
    override val statusPaused = "Đã tạm dừng"
    override val hudPausedChip = "Tạm dừng"
    override val statusChecking = "Đang nhận diện âm thanh..."
    override val statusOffline = "Đang tạm dừng"
    override val permTitle = "Yêu cầu quyền truy cập Micro"
    override val permBody = "PulsePath cần quyền Micro để nhận biết tiếng còi, còi ưu tiên và các âm thanh nguy hiểm khi lái xe."
    override val permAllow = "Cấp quyền"
    override val historyTitle = "Lịch sử âm thanh gần đây"
    override val historyEmpty = "Chưa phát hiện âm thanh cảnh báo nào."
    override val settingsTitle = "Cài đặt"
    override val langLabel = "Ngôn ngữ"
    override val langSwitchToEnglish = "Chuyển sang tiếng Anh"
    override val langSwitchToVietnamese = "Chuyển sang tiếng Việt"
    override val sensitivityLabel = "Độ nhạy mic"
    override val testAlertLabel = "Thử cảnh báo"
    override val aboutDisclaimer = "PulsePath là công cụ hỗ trợ nhận biết trực quan và rung, không thay thế cho sự quan sát trực tiếp khi tham gia giao thông."
    override val hudListeningChip = "Đang lắng nghe"
    override val hudReconnectingChip = "Đang kết nối lại"
    override val hudOfflineChip = "Mất mạng - nhận dạng tại chỗ"
    override val hudStopButton = "Dừng"
    override val hudHistoryButton = "Lịch sử"
    override val dismissLabel = "Đã hiểu"
    override val onboardingPrimerTitle = "Cách PulsePath hoạt động"
    override val onboardingLanguageTitle = "Chọn ngôn ngữ"
    override val onboardingLanguageSubtitle = "Bạn có thể đổi ngôn ngữ bất cứ lúc nào trong Cài đặt."
    override val onboardingContinue = "Tiếp tục"
    override val disclaimerTitle = "Lưu ý an toàn & Miễn trừ trách nhiệm"
    override val disclaimerBullet1 = "PulsePath chỉ hỗ trợ bạn nhận biết âm thanh; đây không phải hệ thống tự động tránh va chạm."
    override val disclaimerBullet2 = "Bạn luôn có trách nhiệm quan sát đường đi và xung quanh khi lái xe."
    override val disclaimerBullet3 = "Cảnh báo có thể đến chậm. Vui lòng xem Điều khoản sử dụng để biết giới hạn trách nhiệm của ứng dụng."
    override val disclaimerBullet3Prefix = "Cảnh báo có thể đến chậm. Vui lòng xem "
    override val disclaimerBullet3Link = "Điều khoản sử dụng"
    override val disclaimerBullet3Suffix = " để biết giới hạn trách nhiệm của ứng dụng."
    override val disclaimerAcceptButton = "Tôi đã hiểu & Đồng ý"
    override val onboardingNextButton = "Tiếp theo"
    override val onboardingGetStartedButton = "Bắt đầu ngay"
    override val onboardingCard1Title = "Lắng nghe xung quanh"
    override val onboardingCard1Body = "PulsePath dùng micro để nhận biết còi xe, còi ưu tiên và các âm thanh giao thông bạn có thể bỏ lỡ khi đang đi đường."
    override val onboardingCard2Title = "Dữ liệu âm thanh"
    override val onboardingCard2Body = "Khi phát hiện âm thanh lớn bất thường, PulsePath gửi một đoạn âm thanh khoảng 2 giây đến Google Gemini để phân loại. Ứng dụng không lưu bản ghi âm trên điện thoại hoặc máy chủ."
    override val onboardingCard3Title = "Giới hạn & Lưu ý an toàn"
    override val onboardingCard3Body = "PulsePath giúp bạn nhận biết âm thanh, nhưng không phòng tránh va chạm hoặc thay thế việc quan sát. Cảnh báo có thể chậm vài giây và hiệu quả nhất với còi ưu tiên, còi hơi xe tải từ xa. Còi ngay phía sau có thể đến quá muộn để phản ứng."
    override val onboardingCard3Highlight = "Luôn lái xe như thể không có ứng dụng."
    override val onboardingPermGrantButton = "Cấp quyền truy cập"
    override val onboardingPermDeniedTitle = "Cần quyền Micro để hoạt động"
    override val onboardingPermDeniedBody = "Quyền micro đang tắt. Mở Cài đặt ứng dụng và cho phép quyền này để PulsePath có thể nhận biết âm thanh giao thông."
    override val onboardingOpenSettingsButton = "Mở Cài đặt"
    override val onboardingReliabilityTitle = "Duy trì hoạt động nền"
    override val onboardingReliabilityBody = "Một số hãng điện thoại (Samsung, Xiaomi, Oppo, Vivo...) có thể tự động đóng ứng dụng chạy ngầm, làm gián đoạn cảnh báo âm thanh khi đang lái xe."
    override val onboardingBatteryButton = "Tắt tối ưu hóa pin"
    override val onboardingSkipButton = "Bỏ qua"
    override val onboardingMountInstruction = "Kẹp điện thoại vào giá đỡ, hướng về phía bạn."
    override val onboardingClapInstruction = "Hãy vỗ tay một lần để kiểm tra micro."
    override val onboardingMicOk = "Mic OK"
    override val onboardingStartButton = "Bắt đầu"
    override val aboutLatencyNote = "Cảnh báo có 2 bước: điện thoại rung ngay khi nghe âm thanh lớn; sau đó AI phân loại và lưu kết quả vào Lịch sử trong vài giây."
    override val testAlertSub = "Hiển thị cảnh báo thử toàn màn hình và rung để bạn kiểm tra."
    override val sensitivityLow = "Thấp"
    override val sensitivityMedium = "Vừa"
    override val sensitivityHigh = "Cao"
    override val sensitivityMax = "Tối đa"
    override val sensitivityMaxSub = "Chế độ trình diễn, không dùng khi lái xe. Mọi tiếng động lớn sẽ lập tức hiện màn hình cảnh báo, kể cả khi nhận dạng sai. Hãy chọn Cao hoặc thấp hơn khi ra đường."
    override val a11yIncrease = "Tăng"
    override val a11yDecrease = "Giảm"
    override val aboutTitle = "Giới thiệu"
    override val versionLabel = "Phiên bản"
    override val permissionRevokedTitle = "Quyền Micro đã bị thu hồi"
    override val permissionRevokedBody = "PulsePath không thể tiếp tục lắng nghe âm thanh nếu không có quyền Micro. Vui lòng cấp lại quyền trong Cài đặt ứng dụng."
    override val micInUseChip = "Micro bị chiếm"
    override val setupErrorChip = "Lỗi cấu hình"
    override val dailyQuotaChip = "Hết lượt AI hôm nay"
    override val rateLimitedChip = "Tạm quá tải"
    override val buzzOnlyChip = "Chỉ báo rung"
    override val demoTagLabel = "demo"
    override val directionCalibrationTitle = "Hiệu chuẩn hướng"
    override val directionCalibrationStatusUncalibrated = "Chưa hiệu chuẩn"
    override val directionCalibrationStatusCalibrated = "Đã hiệu chuẩn"
    override val directionCalibrationStatusUnsupported = "Không hỗ trợ"
    override val directionCalibrationNeedListener = "Hãy bật giám sát trước khi hiệu chuẩn."
    override val directionCalibrationReturnHome = "Về màn hình chính"
    override val directionCalibrationStep1 = "Đặt điện thoại nằm ngang trên giá đỡ. Đứng bên TRÁI màn hình, cách khoảng một sải tay, rồi hô “A” to trong khoảng 1 giây."
    override val directionCalibrationStep2 = "Giữ nguyên điện thoại. Chuyển sang bên PHẢI màn hình rồi hô “A” to trong khoảng 1 giây."
    override val directionCalibrationListenBtn = "Nghe"
    override val directionCalibrationListening = "Đang lắng nghe tiếng hét 'A'..."
    override val directionCalibrationTimeout = "Không nghe thấy âm thanh. Thử lại."
    override val directionCalibrationLowConfidence = "Chưa nghe rõ, thử lại và hét to hơn."
    override val directionCalibrationSuccess = "Đã hiệu chuẩn xong."
    override val directionCalibrationUnsupportedMsg = "Điện thoại này không xác định được hướng. Các tính năng khác vẫn hoạt động bình thường."
    override val directionCalibrationDoneBtn = "Hoàn tất"
    override val directionCalibrationSkipBtn = "Bỏ qua"
    override val directionCalibrationStep1Label = "BƯỚC 1 / 2"
    override val directionCalibrationStep2Label = "BƯỚC 2 / 2"
    override val directionCalibrationRetryBtn = "Thử lại"
    override val directionCalibrationRecalibrateBtn = "Hiệu chuẩn lại"
    override val directionCalibrationCooldown = "Chờ %1\$d giây rồi hãy hô"
    override val directionCalibrationUnusableCapture = "Bản ghi chưa dùng được. Hãy chờ vài giây rồi thử lại."
    override val directionCalibrationLagImplausible = "Kết quả đo vượt ngoài giới hạn. Hãy đứng ngang tầm điện thoại, cách khoảng một sải tay rồi thử lại."
    override val directionCalibrationLagAmbiguous = "Chưa phân biệt được trái/phải. Hãy đứng lệch hẳn sang một bên rồi thử lại."
    override val directionCalibrationSameSide = "Hai lần đo cho ra cùng một bên. Hãy hiệu chuẩn lại, chú ý đứng bên trái màn hình ở bước 1 và bên phải màn hình ở bước 2."
    override val directionCalibrationNeedLandscape = "Hãy đặt điện thoại nằm ngang như khi gắn trên xe rồi hiệu chuẩn lại."
    override val directionCalibrationRotateTitle = "Hãy xoay điện thoại nằm ngang"
    override val directionCalibrationRotateBody = "Chỉ hiệu chuẩn được khi điện thoại nằm ngang, đúng như lúc gắn trên xe. Xoay ngang rồi nút Nghe sẽ bật lên."
    override val directionCalibrationRotatedAway = "Điện thoại bị xoay ngược khi đang ghi nên bản ghi đó đã bị bỏ. Đã quay lại bước 1 - hãy đặt điện thoại đúng chiều như trên giá đỡ."
    override val directionCalibrationStandLeft = "Đứng bên trái màn hình"
    override val directionCalibrationStandRight = "Đứng bên phải màn hình"
    override val directionRecalibrationNotice = "Bản cập nhật này cần hiệu chuẩn lại hướng âm thanh để mũi tên hoạt động. Hãy đặt điện thoại ngang như khi gắn trên xe rồi hiệu chuẩn lại."
    override val settingsSectionAlerts = "CẢNH BÁO"
    override val settingsSectionStartSafely = "KHỞI ĐẦU AN TOÀN"
    override val settingsSectionDetection = "NHẬN DIỆN"
    override val settingsSectionGeneral = "CHUNG"
    override val settingsSectionAppearance = "GIAO DIỆN"
    override val settingsSectionAboutMaintenance = "THÔNG TIN & BẢO TRÌ"
    override val vibrationLabel = "Rung"
    override val vibrationSub = "Rung khi có cảnh báo. Nếu tắt, cảnh báo vẫn hiện trên màn hình."
    override val takeoverLabel = "Cảnh báo toàn màn hình"
    override val takeoverSub = "Cảnh báo nguy hiểm sẽ hiển thị toàn màn hình. Nếu tắt, ứng dụng chỉ rung và lưu vào Lịch sử."
    override val keepMonitoringInBackgroundLabel = "Tiếp tục lắng nghe khi chạy ngầm"
    override val keepMonitoringInBackgroundSub = "Duy trì nhận diện âm thanh khi tắt màn hình hoặc chuyển sang ứng dụng khác."
    override val sirenAlertInBackgroundLabel = "Còi báo động khi chạy ngầm"
    override val sirenAlertInBackgroundSub = "Phát âm thanh còi báo mức khẩn cấp qua kênh báo thức khi ứng dụng không hiển thị trên màn hình."
    override val batteryOptDialogTitle = "Bỏ qua tối ưu hóa pin"
    override val batteryOptDialogBody = "Để tiếp tục lắng nghe khi chạy ngầm mà không bị hệ thống tự động tắt, PulsePath cần được miễn trừ khỏi tính năng tối ưu hóa pin."
    override val batteryOptDialogConfirm = "Cấp quyền"
    override val batteryOptDialogDismiss = "Để sau"
    override val notificationListening = "Đang lắng nghe"
    override val notificationPaused = "Tạm dừng"
    override val notificationProtectionActive = "Bảo vệ đang hoạt động · trên máy"
    override val notificationLocalProtectionOnly = "Chỉ bảo vệ trên máy"
    override val notificationNotProtecting = "Đã dừng bảo vệ — có thể bỏ lỡ cảnh báo"
    override val hudProtectionActiveChip = "Bảo vệ đang hoạt động"
    override val hudLocalProtectionOnlyChip = "Chỉ bảo vệ trên máy"
    override val hudNotProtectingChip = "Dừng bảo vệ — có thể bỏ lỡ cảnh báo"
    override val statusProtectionActive = "Bảo vệ đang hoạt động · trên máy"
    override val statusLocalProtectionOnly = "Chỉ bảo vệ trên máy"
    override val statusNotProtecting = "Dừng bảo vệ — có thể bỏ lỡ cảnh báo"
    override val notificationActionPause = "Tạm dừng"
    override val notificationActionResume = "Tiếp tục"
    override val notificationActionStop = "Dừng"
    override val arrowLabel = "Mũi tên chỉ hướng"
    override val arrowSub = "Cho biết âm thanh đến từ bên trái hay bên phải. Chỉ dùng được khi điện thoại đặt ngang."
    override val collisionLabel = "Phát hiện va chạm"
    override val collisionSub = "Nhận biết ngã xe bằng cảm biến chuyển động của điện thoại: một cú va đập mạnh, rồi xe nằm nghiêng bất động. Tắt thì không có cảnh báo va chạm nào cả."
    override val cloudAiLabel = "Lịch sử & chú thích đám mây"
    override val cloudAiSub = "Thêm nhãn đám mây có độ trễ vào lịch sử. Nhận diện trên thiết bị mới là thứ cảnh báo bạn; tính năng này không làm cảnh báo nhanh hơn hay chính xác hơn."
    override val voiceHailAction = "Có người gọi bạn — nhường đường"
    override val themeModeLabel = "Giao diện"
    override val themeModeSystem = "Theo máy"
    override val themeModeLight = "Sáng"
    override val themeModeDark = "Tối"
    override val contrastBoostLabel = "Tương phản cao dưới nắng"
    override val contrastBoostSub = "Tự động tăng độ tương phản và độ sáng màn hình khi gặp ánh nắng gắt."
    override val contrastBoostAuto = "Tự động"
    override val contrastBoostAlwaysOn = "Luôn bật"
    override val contrastBoostOff = "Tắt"
    override val contrastBoostNoSensorSub = "Thiết bị không có cảm biến ánh sáng — chỉ hỗ trợ điều khiển thủ công."
    override val wallpaperColorsLabel = "Màu theo hình nền"
    override val wallpaperColorsSub = "Dùng màu lấy từ hình nền của bạn. Màu cảnh báo an toàn vẫn giữ nguyên."
    override val locationSectionLabel = "VỊ TRÍ"
    override val locationModeLabel = "Vị trí & tốc độ"
    override val locationModeOff = "Tắt"
    override val locationModeCollisionOnly = "Tự động"
    override val locationModeAlways = "Luôn bật"
    override val locationModeOffSub = "Không dùng vị trí khi di chuyển. Cảnh báo va chạm vẫn hoạt động; tin nhắn khẩn cấp chỉ có thể dùng vị trí đã lưu trước đó nếu bạn từng cấp quyền."
    override val locationModeCollisionSub = "Cập nhật vị trí vài phút một lần khi đang đi, và lấy lại vị trí khi phát hiện va chạm để gửi kèm tin nhắn khẩn cấp. Tiết kiệm pin hơn Luôn bật, nhưng không hiển thị tốc độ trực tiếp."
    override val locationModeAlwaysSub = "Hiển thị tốc độ trên màn hình chính và lưu tốc độ đo được vào dữ liệu nghiên cứu. Tốn thêm khoảng %s pin mỗi 30 phút lắng nghe."
    override val locationModeAlwaysSubUnmeasured = "Hiển thị tốc độ trên màn hình chính và lưu tốc độ đo được vào dữ liệu nghiên cứu. Mức tiêu thụ pin chưa được đo trên máy này."
    override val locationPermissionDeniedSub = "Bạn chưa cấp quyền vị trí nên chế độ này đang tắt. Các tính năng khác vẫn hoạt động; bạn có thể cấp quyền bất cứ lúc nào."
    override val speedUnitKmh = "km/h"
    override val speedNoFix = "Chờ GPS"
    override val speedHeld = "mất tín hiệu"
    override val speedReadoutLabel = "Số tốc độ trên màn hình"
    override val speedReadoutSub = "Ẩn số tốc độ trên màn hình chính. Ứng dụng vẫn đo tốc độ để hỗ trợ tin nhắn sau va chạm và dữ liệu nghiên cứu."
    override val clock24HourLabel = "Giờ 24 giờ"
    override val clock24HourSub = "Hiển thị giờ 24 giờ trên màn hình chính. Tắt để dùng giờ 12 giờ với ký hiệu SA/CH."
    override val clockAmLabel = "SA"
    override val clockPmLabel = "CH"
    override val waveStyleLabel = "Kiểu sóng âm"
    override val waveStyleLine = "Đường liền"
    override val waveStyleDots = "Khối hình"
    override val waveStyleTapHint = "Chạm để đổi kiểu"
    override val directionLeft = "Bên trái"
    override val directionRight = "Bên phải"
    override val arrowStyleLabel = "Kiểu mũi tên"
    override val arrowStyleChevron = "Mũi nhọn"
    override val arrowStyleArrow = "Mũi tên"
    override val directionLabelLabel = "Hiện chữ chỉ hướng"
    override val directionLabelSub = "Hiển thị “Bên trái” hoặc “Bên phải” dưới mũi tên."
    override val crashPreviewLabel = "Xem thử màn hình sau va chạm"
    override val crashPreviewSub = "Xem trước màn hình xuất hiện sau va chạm thật. Bản xem trước không lưu gì vào Lịch sử."
    override val crashPreviewButton = "Xem thử"
    override val resetDefaultsLabel = "Đặt lại mặc định"
    override val resetDefaultsSub = "Khôi phục các cài đặt về mặc định ban đầu. Ngôn ngữ và hồ sơ khẩn cấp vẫn được giữ nguyên."
    override val resetDefaultsButton = "Đặt lại"
    override val resetDefaultsDialogBody = "Khôi phục cài đặt mặc định? Mũi tên chỉ hướng và phát hiện va chạm sẽ tắt. Ngôn ngữ, hồ sơ khẩn cấp và kết quả hiệu chuẩn vẫn được giữ nguyên."
    override val resetDefaultsCancel = "Huỷ"
    override val resetDefaultsDone = "Đã đặt lại cài đặt"
    override val micPermissionTitle = "Cho phép PulsePath nghe"
    override val micPermissionBody = "Hãy cho phép dùng micro để PulsePath nhận biết còi xe, còi ưu tiên và các âm thanh giao thông khi bạn di chuyển."
    override val micPermissionLater = "Để sau"
    override val settingsStateOn = "Đang bật"
    override val settingsStateOff = "Đang tắt"

    override val emergencyTitle = "Hồ sơ khẩn cấp"
    override val emergencySub = "Hiển thị sau khi PulsePath phát hiện va chạm."
    override val emergencyYourDetails = "Thông tin của bạn"
    override val emergencyEnableLabel = "Bật hồ sơ khẩn cấp"
    override val emergencyEnableSub = "Sau va chạm, hiển thị thông tin này cùng nút gọi. Nếu tắt, hồ sơ này sẽ không xuất hiện."
    override val emergencyRiderName = "Tên của bạn"
    override val emergencyBloodType = "Nhóm máu"
    override val emergencyMedicalNotes = "Ghi chú y tế"
    override val emergencyMedicalNotesHint = "Dị ứng, thuốc đang dùng, bệnh nền…"
    override val emergencyContact1 = "Người liên hệ 1"
    override val emergencyContact2 = "Người liên hệ 2 (không bắt buộc)"
    override val emergencyContactName = "Tên"
    override val emergencyContactPhone = "Số điện thoại"
    override val emergencySave = "Lưu"
    override val emergencySaved = "Đã lưu"
    override val emergencyClear = "Xoá toàn bộ dữ liệu"
    override val emergencyClearConfirm = "Đã xoá toàn bộ hồ sơ khẩn cấp."
    override val emergencyClearDialogTitle = "Xoá hồ sơ khẩn cấp?"
    override val emergencyClearDialogBody = "Thao tác này xóa tên, nhóm máu, ghi chú y tế và số liên hệ khỏi điện thoại này. Không thể hoàn tác."
    override val emergencyClearDialogConfirm = "Xoá"
    override val emergencyCancel = "Huỷ"
    override val emergencyPrivacyNote = "Thông tin này chỉ lưu trên điện thoại của bạn. PulsePath không gửi đi và không ghi vào nhật ký."
    override val emergencyNeedsContact = "Cần số điện thoại của Người liên hệ 1 để màn hình hỗ trợ sau va chạm xuất hiện."
    override val emergencyLocationNote = "Chỉ dùng vị trí khi bạn chọn Gửi vị trí của tôi. Không cấp quyền, các phần khác vẫn dùng được."
    override val crashTitle = "PHÁT HIỆN VA CHẠM"
    override val crashPreviewChip = "XEM THỬ - DỮ LIỆU MẪU"
    override val crashSampleName = "Nguyễn Văn A (mẫu)"
    override val crashSampleContact = "Người liên hệ mẫu"
    override val crashSampleNotes = "Ghi chú y tế mẫu - hãy điền hồ sơ khẩn cấp của bạn."
    override val crashBloodLabel = "Nhóm máu"
    override val crashBystanderNote = "Nếu bạn đang giúp người này: họ bị Điếc hoặc khiếm thính. Hãy bấm nút gọi trên màn hình này."
    override val crashCallButton = "Gọi"
    override val crashSendLocation = "Gửi vị trí của tôi"
    override val crashImOk = "Tôi không sao"
    override val crashSmsBody = "%1\$s có thể vừa gặp tai nạn. Vị trí gần nhất: %2\$s (ghi nhận %3\$d phút trước). Tin này do PulsePath soạn sẵn."
    override val crashSmsBodyNoLocation = "%1\$s có thể vừa gặp tai nạn. Không lấy được vị trí. Tin này do PulsePath soạn sẵn."
    override val crashNoAppFound = "Không tìm thấy ứng dụng phù hợp trên máy."

    // ── Demo & Diagnostics governance (Step 7 / H6) ──────────────────────────
    override val demoDisclaimerTitle = "Chẩn đoán nội bộ & Hiệu chuẩn"
    override val demoDisclaimerBody =
        "Môi trường này chỉ dành riêng cho việc tinh chỉnh thuật toán, hiệu chuẩn và kiểm tra chẩn đoán. " +
            "Môi trường này tạo ra các cảnh báo mô phỏng và KHÔNG an toàn để sử dụng khi đang lái xe trên đường. " +
            "Bạn có chắc chắn muốn tiếp tục?"
    override val demoDisclaimerConfirm = "Tôi hiểu & Tiếp tục"
    override val demoDisclaimerDismiss = "Hủy"
    override val demoAlertBadge = "THỬ NGHIỆM"
    override val developerRowTitle = "Chẩn đoán & Nhà phát triển"
    override val developerRowSubtitle = "Chẩn đoán nội bộ, hạn mức & độ nhạy"
    override val developerModeCountdown = "Bạn còn %d bước nữa để bật chế độ nhà phát triển"
    override val developerModeCountdownSingle = "Bạn còn 1 bước nữa để bật chế độ nhà phát triển"
    override val developerModeEnabled = "Đã bật chế độ nhà phát triển"
    override val developerModeAlreadyEnabled = "Chế độ nhà phát triển đã được bật"

    override val privacyPolicyTitle = "Chính sách quyền riêng tư"
    override val privacyPolicyContent = PrivacyPolicyContent(
        introduction = "PulsePath là công cụ hỗ trợ nhận biết âm thanh giao thông bằng hình ảnh và rung. Chính sách này giải thích cách ứng dụng Android xử lý thông tin khi bạn dùng các tính năng của mình.",
        sections = listOf(
            PrivacyPolicySection(
                title = "Thông tin ứng dụng xử lý",
                body = "Khi bạn bắt đầu lắng nghe và cho phép quyền micro, PulsePath thu âm thanh xung quanh để nhận biết âm thanh giao thông. Phần lớn việc nhận diện diễn ra ngay trên thiết bị. Khi có phân loại đám mây, đoạn âm thanh ngắn có thể được gửi đến Google Gemini để phân loại.\n\nỨng dụng cũng có thể dùng cảm biến chuyển động trong lúc lắng nghe để đánh giá một va chạm có thể xảy ra, cùng các cài đặt bạn chọn như ngôn ngữ và độ nhạy cảnh báo."
            ),
            PrivacyPolicySection(
                title = "Vị trí và hồ sơ khẩn cấp",
                body = "Vị trí mặc định tắt. Nếu bạn bật, vị trí chỉ được dùng trên thiết bị để hiển thị tốc độ hoặc chuẩn bị liên kết bản đồ trong tin nhắn khẩn cấp; vị trí không được gửi đến Gemini.\n\nTên, nhóm máu, ghi chú y tế và liên hệ khẩn cấp bạn nhập được lưu cục bộ trên điện thoại. Sau va chạm có thể xảy ra, PulsePath chỉ mở ứng dụng gọi điện hoặc nhắn tin với nội dung điền sẵn; ứng dụng không tự gọi hoặc tự gửi tin nhắn."
            ),
            PrivacyPolicySection(
                title = "Chia sẻ với dịch vụ khác",
                body = "Để phân loại âm thanh trên đám mây, đoạn âm thanh ngắn và yêu cầu API liên quan được gửi trực tiếp đến Google Gemini. Google có thể xử lý thông tin kỹ thuật cần để vận hành API theo điều khoản và chính sách riêng của Google.\n\nNếu bạn mở cuộc gọi, SMS, liên kết bản đồ hoặc hành động bên ngoài, nhà cung cấp tương ứng sẽ xử lý thông tin theo chính sách của họ."
            ),
            PrivacyPolicySection(
                title = "Lưu trữ và lựa chọn của bạn",
                body = "Cài đặt và hồ sơ khẩn cấp được lưu trên thiết bị. Bạn có thể xóa hồ sơ khẩn cấp trong Cài đặt khẩn cấp, xóa dữ liệu ứng dụng trong Android hoặc gỡ cài đặt ứng dụng. PulsePath không có hệ thống tài khoản hay máy chủ riêng để lưu âm thanh, vị trí, hồ sơ khẩn cấp hoặc lịch sử cảnh báo của bạn.\n\nBạn có thể ngừng lắng nghe hoặc thu hồi quyền micro để dừng thu âm; tắt hoặc thu hồi quyền vị trí bất cứ lúc nào; và chỉnh sửa hoặc xóa hồ sơ khẩn cấp của mình."
            ),
            PrivacyPolicySection(
                title = "Bảo mật, an toàn và cập nhật",
                body = "PulsePath dùng quyền của Android và kết nối mạng được mã hóa. Tuy nhiên, không có phương thức truyền hoặc lưu trữ dữ liệu nào an toàn tuyệt đối.\n\nPulsePath là công cụ hỗ trợ nhận biết, không phải dịch vụ khẩn cấp, chăm sóc y tế hay hệ thống tự động tránh va chạm. Chúng tôi có thể cập nhật chính sách này khi ứng dụng hoặc cách xử lý dữ liệu thay đổi."
            )
        )
    )
}

object EnStrings : Strings {
    override val appName = "PulsePath"
    override val tagline = "Sees the sounds you can't hear."
    override val splashQuote = "Feel the pulse. Own the path"
    override val btnStart = "Start Monitoring"
    override val btnStop = "Stop Monitoring"
    override val statusListening = "Listening to traffic"
    override val statusPaused = "Paused"
    override val hudPausedChip = "Paused"
    override val statusChecking = "Analyzing sound..."
    override val statusOffline = "Paused"
    override val permTitle = "Microphone Permission Required"
    override val permBody = "PulsePath needs microphone access to detect sirens, horns, and traffic sounds while riding."
    override val permAllow = "Grant Permission"
    override val historyTitle = "Recent Sound History"
    override val historyEmpty = "No alert sounds detected yet."
    override val settingsTitle = "Settings"
    override val langLabel = "Language"
    override val langSwitchToEnglish = "Switch to English"
    override val langSwitchToVietnamese = "Switch to Vietnamese"
    override val sensitivityLabel = "Mic Sensitivity"
    override val testAlertLabel = "Test Alert"
    override val aboutDisclaimer = "PulsePath is a visual and haptic awareness aid. It does not replace visual road awareness."
    override val hudListeningChip = "Listening"
    override val hudReconnectingChip = "Reconnecting"
    override val hudOfflineChip = "Offline - local detection"
    override val hudStopButton = "Stop"
    override val hudHistoryButton = "History"
    override val dismissLabel = "Got it"
    override val onboardingPrimerTitle = "How PulsePath Works"
    override val onboardingLanguageTitle = "Choose your language"
    override val onboardingLanguageSubtitle = "You can change this anytime in Settings."
    override val onboardingContinue = "Continue"
    override val disclaimerTitle = "Safety Disclaimer & Terms"
    override val disclaimerBullet1 = "PulsePath helps you notice sounds. It is not an automatic collision-avoidance system."
    override val disclaimerBullet2 = "You are always responsible for watching the road and your surroundings."
    override val disclaimerBullet3 = "Alerts may be delayed. Please read the Terms for the app’s liability limits."
    override val disclaimerBullet3Prefix = "Alerts may be delayed. Please read the "
    override val disclaimerBullet3Link = "Terms"
    override val disclaimerBullet3Suffix = " for the app’s liability limits."
    override val disclaimerAcceptButton = "I Understand & Accept"
    override val onboardingNextButton = "Next"
    override val onboardingGetStartedButton = "Get Started"
    override val onboardingCard1Title = "Listening to Traffic"
    override val onboardingCard1Body = "PulsePath uses your microphone to listen for sirens, horns, and other traffic sounds you may miss while riding."
    override val onboardingCard2Title = "Audio Data & Privacy"
    override val onboardingCard2Body = "When PulsePath detects an unusually loud sound, it sends a clip of about two seconds to Google Gemini for classification. We do not store audio recordings on your phone or our servers."
    override val onboardingCard3Title = "Limitations & Safety"
    override val onboardingCard3Body = "PulsePath helps you notice sounds; it does not prevent collisions or replace looking at the road. Alerts may take a few seconds and work best for distant sirens and truck air horns. A horn close behind you may arrive too late."
    override val onboardingCard3Highlight = "Always ride as if the app isn’t there."
    override val onboardingPermGrantButton = "Grant Permissions"
    override val onboardingPermDeniedTitle = "Microphone Permission Required"
    override val onboardingPermDeniedBody = "Microphone access is off. Open App Settings and allow it so PulsePath can monitor traffic sounds."
    override val onboardingOpenSettingsButton = "Open Settings"
    override val onboardingReliabilityTitle = "Background Reliability"
    override val onboardingReliabilityBody = "Some manufacturers (Samsung, Xiaomi, Oppo, Vivo) aggressively kill background apps, which would silence critical alerts while riding."
    override val onboardingBatteryButton = "Disable Battery Optimization"
    override val onboardingSkipButton = "Skip"
    override val onboardingMountInstruction = "Clip your phone to the mount, facing you."
    override val onboardingClapInstruction = "Clap your hands once to test the microphone."
    override val onboardingMicOk = "Mic OK"
    override val onboardingStartButton = "Start"
    override val aboutLatencyNote = "Two steps: your phone vibrates as soon as it hears a loud sound, then AI classifies it and adds the result to History within a few seconds."
    override val testAlertSub = "Shows a full-screen test alert and vibration so you can check how it feels."
    override val sensitivityLow = "Low"
    override val sensitivityMedium = "Medium"
    override val sensitivityHigh = "High"
    override val sensitivityMax = "Max"
    override val sensitivityMaxSub = "Demo mode, not for riding. Any loud sound instantly shows the alert screen, even when the label is wrong. Switch back to High or lower before you ride."
    override val a11yIncrease = "Increase"
    override val a11yDecrease = "Decrease"
    override val aboutTitle = "About"
    override val versionLabel = "Version"
    override val permissionRevokedTitle = "Microphone Permission Revoked"
    override val permissionRevokedBody = "PulsePath cannot listen for traffic sounds without microphone access. Please re-enable the permission in App Settings."
    override val micInUseChip = "Mic in use"
    override val setupErrorChip = "Setup error"
    override val dailyQuotaChip = "Daily AI limit reached"
    override val rateLimitedChip = "Rate limited"
    override val buzzOnlyChip = "Buzz only"
    override val demoTagLabel = "demo"
    override val directionCalibrationTitle = "Direction Calibration"
    override val directionCalibrationStatusUncalibrated = "Uncalibrated"
    override val directionCalibrationStatusCalibrated = "Calibrated"
    override val directionCalibrationStatusUnsupported = "Unsupported"
    override val directionCalibrationNeedListener = "Please start monitoring before calibration."
    override val directionCalibrationReturnHome = "Return to Home"
    override val directionCalibrationStep1 = "Place the phone horizontally in its mount. Stand to the LEFT of the screen, about an arm’s length away, and say “AH” loudly for about one second."
    override val directionCalibrationStep2 = "Leave the phone exactly where it is. Move to the RIGHT of the screen and say “AH” loudly for about one second."
    override val directionCalibrationListenBtn = "Listen"
    override val directionCalibrationListening = "Listening for 'AH' shout..."
    override val directionCalibrationTimeout = "Did not hear sound. Try again."
    override val directionCalibrationLowConfidence = "Could not hear clearly, try again and shout louder."
    override val directionCalibrationSuccess = "Calibration complete."
    override val directionCalibrationUnsupportedMsg = "This device cannot determine sound direction. Other features continue to work normally."
    override val directionCalibrationDoneBtn = "Done"
    override val directionCalibrationSkipBtn = "Skip"
    override val directionCalibrationStep1Label = "STEP 1 OF 2"
    override val directionCalibrationStep2Label = "STEP 2 OF 2"
    override val directionCalibrationRetryBtn = "Try again"
    override val directionCalibrationRecalibrateBtn = "Calibrate again"
    override val directionCalibrationCooldown = "Wait %1\$d s before shouting"
    override val directionCalibrationUnusableCapture = "That recording could not be used. Wait a few seconds and try again."
    override val directionCalibrationLagImplausible = "The reading was out of range. Stand level with the phone, about an arm’s length away, and try again."
    override val directionCalibrationLagAmbiguous = "Left and right were too close to tell apart. Move further to one side and try again."
    override val directionCalibrationSameSide = "Both readings came from the same side. Calibrate again, standing to the LEFT of the screen for step 1 and to its RIGHT for step 2."
    override val directionCalibrationNeedLandscape = "Place the phone horizontally, the way it sits in the mount, then calibrate again."
    override val directionCalibrationRotateTitle = "Turn the phone sideways"
    override val directionCalibrationRotateBody = "Fine-tuning only works with the phone horizontal, exactly as it sits in your bike mount. Turn it now and the Listen button wakes up."
    override val directionCalibrationRotatedAway = "The phone turned around mid-recording, so that take was thrown away. Calibration has restarted from step 1 - put the phone back the way it sits in the mount."
    override val directionCalibrationStandLeft = "Stand on the left of the screen"
    override val directionCalibrationStandRight = "Stand on the right of the screen"
    override val directionRecalibrationNotice = "This update requires a new direction calibration before the arrow can work. Recalibrate with the phone horizontal, as it sits in your bike mount."
    override val settingsSectionAlerts = "ALERTS"
    override val settingsSectionStartSafely = "START SAFELY"
    override val settingsSectionDetection = "DETECTION"
    override val settingsSectionGeneral = "GENERAL"
    override val settingsSectionAppearance = "APPEARANCE"
    override val settingsSectionAboutMaintenance = "ABOUT & MAINTENANCE"
    override val vibrationLabel = "Vibration"
    override val vibrationSub = "Vibrate for every alert. If you turn this off, alerts still appear on screen."
    override val takeoverLabel = "Full-screen alerts"
    override val takeoverSub = "Critical alerts fill the screen. If off, PulsePath only vibrates and saves the alert to History."
    override val keepMonitoringInBackgroundLabel = "Keep monitoring in the background"
    override val keepMonitoringInBackgroundSub = "Continue detecting sounds when the screen is off or switching to another app."
    override val sirenAlertInBackgroundLabel = "Siren alert in the background"
    override val sirenAlertInBackgroundSub = "Play an audible siren on the alarm stream for critical events while the app is not on screen."
    override val batteryOptDialogTitle = "Ignore battery optimizations"
    override val batteryOptDialogBody = "To continue monitoring reliably in the background without Android terminating the service, PulsePath needs to be exempted from battery optimizations."
    override val batteryOptDialogConfirm = "Allow"
    override val batteryOptDialogDismiss = "Not now"
    override val notificationListening = "Listening"
    override val notificationPaused = "Paused"
    override val notificationProtectionActive = "Protection active · local"
    override val notificationLocalProtectionOnly = "Local protection only"
    override val notificationNotProtecting = "Protection stopped — hazards may be missed"
    override val hudProtectionActiveChip = "Protection active"
    override val hudLocalProtectionOnlyChip = "Local protection only"
    override val hudNotProtectingChip = "Protection stopped — hazards may be missed"
    override val statusProtectionActive = "Protection active · local"
    override val statusLocalProtectionOnly = "Local protection only"
    override val statusNotProtecting = "Protection stopped — hazards may be missed"
    override val notificationActionPause = "Pause"
    override val notificationActionResume = "Resume"
    override val notificationActionStop = "Stop"
    override val arrowLabel = "Direction arrow"
    override val arrowSub = "Shows whether a sound came from your left or right. Works only when the phone is mounted horizontally."
    override val collisionLabel = "Collision detection"
    override val collisionSub = "Detects a fall with the phone's motion sensors: a hard impact, then the bike lying still on its side. Off means no collision alerts at all."
    override val cloudAiLabel = "Cloud history & captions"
    override val cloudAiSub = "Adds delayed cloud labels to history. On-device detection is what alerts you; this does not make alerts faster or more accurate."
    override val voiceHailAction = "Someone is calling you — move aside"
    override val themeModeLabel = "Appearance"
    override val themeModeSystem = "System"
    override val themeModeLight = "Light"
    override val themeModeDark = "Dark"
    override val contrastBoostLabel = "Contrast boost in harsh light"
    override val contrastBoostSub = "Boosts HUD contrast and screen brightness under direct sunlight."
    override val contrastBoostAuto = "Auto"
    override val contrastBoostAlwaysOn = "Always on"
    override val contrastBoostOff = "Off"
    override val contrastBoostNoSensorSub = "No ambient light sensor found on this device — manual control only."
    override val wallpaperColorsLabel = "Use wallpaper colors"
    override val wallpaperColorsSub = "Use colors from your wallpaper. Safety-alert colors stay the same."
    override val locationSectionLabel = "LOCATION"
    override val locationModeLabel = "Location & speed"
    override val locationModeOff = "Off"
    override val locationModeCollisionOnly = "Auto"
    override val locationModeAlways = "Always on"
    override val locationModeOffSub = "Do not use location while riding. Collision alerts still work; an emergency message can only use a previously saved location if you granted permission earlier."
    override val locationModeCollisionSub = "Updates your location every few minutes while riding and again if a collision is detected, so it can be included in an emergency message. Uses less battery than Always on, but does not show live speed."
    override val locationModeAlwaysSub = "Shows speed on the main screen and saves the measured speed to research data. Uses about %s more battery every 30 minutes of listening."
    override val locationModeAlwaysSubUnmeasured = "Shows speed on the main screen and saves the measured speed to research data. Battery use has not been measured on this device."
    override val locationPermissionDeniedSub = "Location access is off, so this mode is unavailable. Everything else still works, and you can allow location anytime."
    override val speedUnitKmh = "km/h"
    override val speedNoFix = "Waiting for GPS"
    override val speedHeld = "signal lost"
    override val speedReadoutLabel = "Speed number on screen"
    override val speedReadoutSub = "Hide speed from the main screen. PulsePath still measures it for collision messages and research data."
    override val clock24HourLabel = "24-hour clock"
    override val clock24HourSub = "Show time on the main screen in 24-hour format. Turn this off to use 12-hour time with AM/PM."
    override val clockAmLabel = "AM"
    override val clockPmLabel = "PM"
    override val waveStyleLabel = "Wave style"
    override val waveStyleLine = "Line"
    override val waveStyleDots = "Shapes"
    override val waveStyleTapHint = "Tap to switch"
    override val directionLeft = "From your left"
    override val directionRight = "From your right"
    override val arrowStyleLabel = "Arrow style"
    override val arrowStyleChevron = "Chevron"
    override val arrowStyleArrow = "Arrow"
    override val directionLabelLabel = "Spell out the side"
    override val directionLabelSub = "Show “From your left” or “From your right” below the arrow."
    override val crashPreviewLabel = "Preview crash response"
    override val crashPreviewSub = "Preview the screen shown after a real collision. This preview does not save anything to History."
    override val crashPreviewButton = "Preview"
    override val resetDefaultsLabel = "Reset to default"
    override val resetDefaultsSub = "Restore all settings to their original defaults."
    override val resetDefaultsButton = "Reset"
    override val resetDefaultsDialogBody = "Restore default settings? Direction arrow and collision detection will turn off. Your language, emergency profile, and calibration results will be kept."
    override val resetDefaultsCancel = "Cancel"
    override val resetDefaultsDone = "Settings reset"
    override val micPermissionTitle = "Let PulsePath listen"
    override val micPermissionBody = "Allow microphone access so PulsePath can detect sirens, horns, and other traffic sounds while you ride."
    override val micPermissionLater = "Not now"
    override val settingsStateOn = "On"
    override val settingsStateOff = "Off"

    override val emergencyTitle = "Emergency profile"
    override val emergencySub = "Appears after PulsePath detects a collision."
    override val emergencyYourDetails = "Your details"
    override val emergencyEnableLabel = "Enable emergency profile"
    override val emergencyEnableSub = "After a collision, show this information with a call button. Turn this off to keep the profile from appearing."
    override val emergencyRiderName = "Your name"
    override val emergencyBloodType = "Blood type"
    override val emergencyMedicalNotes = "Medical notes"
    override val emergencyMedicalNotesHint = "Allergies, medication, conditions…"
    override val emergencyContact1 = "Contact 1"
    override val emergencyContact2 = "Contact 2 (optional)"
    override val emergencyContactName = "Name"
    override val emergencyContactPhone = "Phone number"
    override val emergencySave = "Save"
    override val emergencySaved = "Saved"
    override val emergencyClear = "Delete all of this"
    override val emergencyClearConfirm = "Emergency profile deleted."
    override val emergencyClearDialogTitle = "Delete emergency profile?"
    override val emergencyClearDialogBody = "This removes your name, blood type, medical notes, and contact numbers from this phone. You cannot undo it."
    override val emergencyClearDialogConfirm = "Delete"
    override val emergencyCancel = "Cancel"
    override val emergencyPrivacyNote = "This information stays on your phone. PulsePath never sends it or adds it to a log."
    override val emergencyNeedsContact = "Add Contact 1’s phone number before the post-collision screen can appear."
    override val emergencyLocationNote = "Location is used only when you tap Send my location. The rest of the feature works without it."
    override val crashTitle = "COLLISION DETECTED"
    override val crashPreviewChip = "PREVIEW - SAMPLE DATA"
    override val crashSampleName = "Sample Rider"
    override val crashSampleContact = "Sample contact"
    override val crashSampleNotes = "Sample medical notes - fill in your emergency profile."
    override val crashBloodLabel = "Blood type"
    override val crashBystanderNote = "If you are helping this person: they are Deaf or hard of hearing. Please use the call button on this screen."
    override val crashCallButton = "Call"
    override val crashSendLocation = "Send my location"
    override val crashImOk = "I'm OK"
    override val crashSmsBody = "%1\$s may have been in an accident. Last known location: %2\$s (recorded %3\$d minutes ago). This message was drafted by PulsePath."
    override val crashSmsBodyNoLocation = "%1\$s may have been in an accident. No location available. This message was drafted by PulsePath."
    override val crashNoAppFound = "No app on this phone can handle that."

    // ── Demo & Diagnostics governance (Step 7 / H6) ──────────────────────────
    override val demoDisclaimerTitle = "Internal Diagnostics & Calibration"
    override val demoDisclaimerBody =
        "This sandbox is intended solely for algorithm tuning, calibration, and diagnostic testing. " +
            "It generates simulated alerts and is NOT safe for use while riding on the road. " +
            "Do you wish to continue?"
    override val demoDisclaimerConfirm = "I Understand & Proceed"
    override val demoDisclaimerDismiss = "Cancel"
    override val demoAlertBadge = "DEMO"
    override val developerRowTitle = "Developer & Diagnostics"
    override val developerRowSubtitle = "Diagnostics, quota telemetry, and sensitivity"
    override val developerModeCountdown = "You are %d steps away from developer mode"
    override val developerModeCountdownSingle = "You are 1 step away from developer mode"
    override val developerModeEnabled = "Developer mode enabled"
    override val developerModeAlreadyEnabled = "Developer mode is already enabled"

    override val privacyPolicyTitle = "Privacy Policy"
    override val privacyPolicyContent = PrivacyPolicyContent(
        introduction = "PulsePath is a visual and haptic traffic-sound awareness aid. This policy explains how the Android app processes information when you use its features.",
        sections = listOf(
            PrivacyPolicySection(
                title = "Information the app processes",
                body = "When you start listening and grant microphone permission, PulsePath captures ambient audio to detect traffic sounds. Most detection happens on your device. When cloud classification is available, short audio clips may be sent to Google Gemini for classification.\n\nWhile listening, the app can also use motion sensors to assess a possible collision, along with settings you choose such as language and alert sensitivity."
            ),
            PrivacyPolicySection(
                title = "Location and emergency profile",
                body = "Location is off by default. If you enable it, it is used on-device to show speed or prepare a map link in a draft emergency message; it is not sent to Gemini.\n\nA name, blood type, medical notes, and emergency contacts you enter are stored locally on your phone. After a possible collision, PulsePath can only open your dialer or messaging app with information pre-filled; it never sends a message or places a call by itself."
            ),
            PrivacyPolicySection(
                title = "Sharing with other services",
                body = "For cloud sound classification, short audio clips and the related API request are sent directly to Google Gemini. Google may process technical information needed to operate the API under Google's own terms and policies.\n\nIf you open a call, SMS, map link, or other external action, the relevant provider processes that information under its own policy."
            ),
            PrivacyPolicySection(
                title = "Storage and your choices",
                body = "Settings and the emergency profile are stored on your device. You can clear the emergency profile in Emergency settings, remove app data in Android settings, or uninstall the app. PulsePath has no user-account system or app-owned server that retains your audio, location, emergency profile, or alert history.\n\nYou can stop listening or revoke microphone permission to stop audio capture; turn off or revoke location permission at any time; and edit or delete your emergency profile."
            ),
            PrivacyPolicySection(
                title = "Security, safety, and updates",
                body = "PulsePath uses Android permission controls and encrypted network connections. However, no method of data transmission or storage is completely secure.\n\nPulsePath is an awareness aid, not emergency services, medical care, or an automated collision-avoidance system. We may update this policy when the app or its data practices change."
            )
        )
    )
}

val LocalStrings = staticCompositionLocalOf<Strings> { ViStrings }

object SoundSightStrings {
    val current: Strings
        @Composable
        @ReadOnlyComposable
        get() = LocalStrings.current
}

fun Strings.developerCountdownText(steps: Int): String =
    if (steps == 1) developerModeCountdownSingle
    else String.format(developerModeCountdown, steps)
