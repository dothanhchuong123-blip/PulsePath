package com.soundsight.data

object GeminiConfig {
    const val DEFAULT_LIVE_MODEL_ID = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_MODEL_ID = "gemini-3.5-flash-lite"   // unchanged REST fallback

    /**
     * Fallback Live model identifier.
     *
     * TODO(verify): Confirm active GA / release Live bidirectional audio model ID against Google AI
     * Studio or the official Gemini API model list before overriding in production. Guessing an
     * unverified model ID risks instant connection rejection at runtime.
     */
    const val FALLBACK_LIVE_MODEL_ID = "gemini-2.0-flash-exp"

    /** Active Live model identifier. Overridable at runtime for testing/fallbacks. */
    var LIVE_MODEL_ID: String = DEFAULT_LIVE_MODEL_ID

    /** Active REST fallback model identifier. Overridable at runtime. */
    var MODEL_ID: String = DEFAULT_MODEL_ID

    const val LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    const val TIMEOUT_SECONDS = 9L
    const val THINKING_DISABLED_BUDGET = 0
    var disableThinking: Boolean = true   // debug-togglable
}
