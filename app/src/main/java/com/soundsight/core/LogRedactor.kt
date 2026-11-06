package com.soundsight.core

/**
 * Utility to ensure no API keys or sensitive query parameters leak into Logcat, exception
 * messages, or telemetry.
 */
object LogRedactor {
    private val KEY_PARAM_REGEX = Regex("""([?&]key=)[^&\s]+""")
    private val AIZA_KEY_REGEX = Regex("""AIza[0-9A-Za-z_-]+""")

    /**
     * Sanitizes [text] by removing any API key patterns or 'key=' query parameter values.
     */
    fun redact(text: String?): String {
        if (text == null) return ""
        val withoutUrlKey = text.replace(KEY_PARAM_REGEX, "$1[REDACTED]")
        return withoutUrlKey.replace(AIZA_KEY_REGEX, "[REDACTED_API_KEY]")
    }

    /**
     * Returns a [Throwable] whose message and cause messages have been sanitized.
     */
    fun redactThrowable(t: Throwable?): Throwable? {
        if (t == null) return null
        val cleanMsg = redact(t.message)
        val cleanCause = t.cause?.let { redactThrowable(it) }
        val wrapper = Exception("${t::class.java.name}: $cleanMsg", cleanCause)
        wrapper.stackTrace = t.stackTrace
        return wrapper
    }
}
