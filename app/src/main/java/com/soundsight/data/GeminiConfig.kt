package com.soundsight.data

object GeminiConfig {
    const val MODEL_ID = "gemini-3.5-flash-lite"
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    const val TIMEOUT_SECONDS = 9L
    const val THINKING_DISABLED_BUDGET = 0
    var disableThinking: Boolean = true   // debug-togglable
}
