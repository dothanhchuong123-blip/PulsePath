package com.soundsight.data

data class ClassificationResult(
    val soundId: String,
    val tier: String,            // SAFE | WARNING | CRITICAL
    val confidence: Double,
    val captionVi: String,
    val captionEn: String,
    val motion: String = "UNKNOWN"  // APPROACHING | PASSING | RECEDING | UNKNOWN
)

data class HttpTrace(
    val attempts: Int,
    val attemptMs: List<Long>,
    val bodyBytesSent: Int
)

data class ClassificationTokenUsage(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val thoughtsTokenCount: Int = 0,
    val totalTokenCount: Int = 0
)

data class ClassificationDebugResult(
    val result: ClassificationResult,
    val trace: HttpTrace,
    val usage: ClassificationTokenUsage
)
