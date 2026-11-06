package com.soundsight.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.soundsight.BuildConfig
import com.soundsight.audio.AudioClip
import com.soundsight.core.DailyQuotaGuard
import com.soundsight.core.LogRedactor
import com.soundsight.core.QuotaExhaustedException
import com.soundsight.core.Telemetry
import com.soundsight.data.live.UserKeyCredentialProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerErrorException(val code: Int, message: String) : IOException(message)
class ClientErrorException(val code: Int, message: String) : Exception(message)

object SoundClassifier {
    private const val TAG = "SoundClassifier"
    private val inFlight = AtomicBoolean(false)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(GeminiConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(GeminiConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(GeminiConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(GeminiConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_INSTRUCTION =
        "You are the acoustic classification engine for SoundSight, an awareness aid for\n" +
        "Deaf and hard-of-hearing motorbike riders in Vietnam. Input: one short audio clip\n" +
        "(~2 s) recorded by a phone mounted on a moving motorbike in Vietnamese traffic.\n" +
        "Expect heavy wind noise, the rider's own engine, and dense overlapping traffic.\n" +
        "\n" +
        "TASK: identify the single most safety-relevant sound present. Ignore wind, the\n" +
        "rider's own steady engine, tyre noise, and background chatter.\n" +
        "\n" +
        "OVERRIDING RULE: when several sounds overlap, report the one in the HIGHEST\n" +
        "danger tier, regardless of which is loudest. Loudest does not mean most dangerous.\n" +
        "\n" +
        "ACOUSTIC DISCRIMINATORS — apply these carefully:\n" +
        "- MOTORCYCLE_HORN: discrete short beeps, 1-3 in a row, steady pitch within each\n" +
        "  beep, clear silence between presses. Thin and bright.\n" +
        "- CAR_HORN: same discrete-beep envelope but lower and thicker.\n" +
        "- TRUCK_AIR_HORN: very loud, very low, SUSTAINED for over half a second, often\n" +
        "  with a slight pitch drop at the end. Highest fatality risk for a motorbike\n" +
        "  rider. Always CRITICAL.\n" +
        "- EMERGENCY_SIREN: pitch oscillates continuously up and down over the whole clip\n" +
        "  (wail or yelp). This continuous oscillation is the signature — a repeated beep\n" +
        "  is NOT a siren. Covers ambulance, police and fire. Always CRITICAL.\n" +
        "- TRAIN_HORN: extremely low, extremely long, often two tones together.\n" +
        "- SUDDEN_ACCELERATION: an ENGINE, not a horn. Continuously rising pitch\n" +
        "  (glissando) with a harsh throaty timbre and no discrete beat. If you hear\n" +
        "  separated beeps, it is a horn, not acceleration.\n" +
        "- REVERSING_BEEP: a mechanical beep repeating at a very regular slow interval,\n" +
        "  identical pitch every time, machine-like. Distinct from a human horn press.\n" +
        "- VOICE_SHOUT: a human voice raised sharply, close to the mic.\n" +
        "- COLLISION_IMPACT: a single broadband transient — crunch, scrape or crash — with\n" +
        "  no tonal pitch.\n" +
        "- NORMAL_TRAFFIC: wind, steady engines, distant traffic, talking, or quiet. Use\n" +
        "  this whenever no listed alert sound is clearly identifiable. Prefer this over\n" +
        "  guessing.\n" +
        "\n" +
        "CONFIDENCE: report your true confidence in the range 0.0-1.0. Do not inflate it.\n" +
        "A low confidence value is more useful to this app than a wrong confident answer,\n" +
        "because low-confidence results are deliberately downgraded.\n" +
        "\n" +
        "CAPTIONS: caption_vi and caption_en must each be at most 5 words and must\n" +
        "describe only the SOUND you heard. Never describe an action the rider should\n" +
        "take. Never mention danger levels or colours.\n" +
        "\n" +
        "Fill only the schema fields. Do not decide UI, vibration, or colour — that is\n" +
        "the application's job.\n\n" +
        "You receive one short audio segment at a time. Each segment is a separate, independent event - do not let a previous segment influence your answer.\n\n" +
        "Also report `motion`. If pitch and loudness are rising, or pitch is steady and high while loudness grows, report APPROACHING. If you hear a clear downward pitch shift together with falling loudness, report PASSING. If it is simply getting quieter, report RECEDING. If you cannot tell, report UNKNOWN - do not guess.\n\n" +
        "NEVER report a left/right or front/back direction, and never mention direction in a caption. You are given a single-channel recording; that information is not present in it and the application determines direction by other means."

    private data class HttpResponseWithTrace(
        val body: String,
        val trace: HttpTrace
    )

    suspend fun classifyDebug(context: Context, clip: AudioClip): Result<ClassificationDebugResult> = withContext(Dispatchers.IO) {
        if (!inFlight.compareAndSet(false, true)) {
            Log.w(TAG, "Request already in flight; dropping new clip captured at ${clip.capturedAtMs}")
            Telemetry.recordDrop()
            return@withContext Result.failure(
                IllegalStateException("Clip dropped: classification request already in flight")
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val requestJson = buildRequestBody(clip)
            val httpResponse = executeWithRetry(context, requestJson)
            val (result, usage) = parseResponse(httpResponse.body)
            val elapsed = System.currentTimeMillis() - startTime
            Telemetry.recordLatency(elapsed)
            Result.success(
                ClassificationDebugResult(
                    result = result,
                    trace = httpResponse.trace,
                    usage = usage
                )
            )
        } catch (e: Exception) {
            if (e is QuotaExhaustedException) {
                Telemetry.recordQuotaBlocked()
            } else {
                Telemetry.recordFailure()
            }
            Log.e(TAG, "Classification request failed: ${LogRedactor.redact(e.message)}", LogRedactor.redactThrowable(e))
            Result.failure(e)
        } finally {
            inFlight.set(false)
        }
    }

    suspend fun classify(context: Context, clip: AudioClip): Result<ClassificationResult> {
        return classifyDebug(context, clip).map { it.result }
    }

    private fun buildRequestBody(clip: AudioClip): String {
        val base64Audio = Base64.encodeToString(clip.wav, Base64.NO_WRAP)
        val root = JSONObject()

        // systemInstruction
        val sysInstruction = JSONObject()
        val sysParts = JSONArray()
        sysParts.put(JSONObject().put("text", SYSTEM_INSTRUCTION))
        sysInstruction.put("parts", sysParts)
        root.put("systemInstruction", sysInstruction)

        // contents
        val contents = JSONArray()
        val userContent = JSONObject()
        userContent.put("role", "user")
        val userParts = JSONArray()
        userParts.put(JSONObject().put("text", "Identify the primary sound in this audio clip."))
        userParts.put(
            JSONObject().put(
                "inlineData",
                JSONObject()
                    .put("mimeType", "audio/wav")
                    .put("data", base64Audio)
            )
        )
        userContent.put("parts", userParts)
        contents.put(userContent)
        root.put("contents", contents)

        // generationConfig
        val genConfig = JSONObject()
        genConfig.put("responseMimeType", "application/json")
        genConfig.put("temperature", 0)
        genConfig.put("candidateCount", 1)

        if (GeminiConfig.disableThinking) {
            val thinkingConfig = JSONObject()
            thinkingConfig.put("thinkingBudget", GeminiConfig.THINKING_DISABLED_BUDGET)
            genConfig.put("thinkingConfig", thinkingConfig)
        }

        // responseSchema
        val schema = JSONObject()
        schema.put("type", "OBJECT")
        val props = JSONObject()

        // sound_id
        val soundIdEnum = JSONArray(
            listOf(
                "NORMAL_TRAFFIC",
                "VOICE_SHOUT",
                "MOTORCYCLE_HORN",
                "CAR_HORN",
                "SUDDEN_ACCELERATION",
                "REVERSING_BEEP",
                "TRUCK_AIR_HORN",
                "EMERGENCY_SIREN",
                "TRAIN_HORN",
                "COLLISION_IMPACT"
            )
        )
        props.put(
            "sound_id",
            JSONObject()
                .put("type", "STRING")
                .put("enum", soundIdEnum)
        )

        // tier
        val tierEnum = JSONArray(listOf("SAFE", "WARNING", "CRITICAL"))
        props.put(
            "tier",
            JSONObject()
                .put("type", "STRING")
                .put("enum", tierEnum)
        )

        // confidence
        props.put("confidence", JSONObject().put("type", "NUMBER"))

        // motion
        val motionEnum = JSONArray(listOf("APPROACHING", "PASSING", "RECEDING", "UNKNOWN"))
        props.put(
            "motion",
            JSONObject()
                .put("type", "STRING")
                .put("enum", motionEnum)
        )

        // caption_vi
        props.put("caption_vi", JSONObject().put("type", "STRING"))

        // caption_en
        props.put("caption_en", JSONObject().put("type", "STRING"))

        schema.put("properties", props)
        schema.put(
            "required",
            JSONArray(listOf("sound_id", "tier", "confidence", "motion", "caption_vi", "caption_en"))
        )
        schema.put(
            "propertyOrdering",
            JSONArray(listOf("sound_id", "tier", "confidence", "motion", "caption_vi", "caption_en"))
        )

        genConfig.put("responseSchema", schema)
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    private suspend fun executeWithRetry(context: Context, requestJson: String): HttpResponseWithTrace {
        val apiKey = UserKeyCredentialProvider.getInstance(context).getApiKey()
        val url = "${GeminiConfig.ENDPOINT}/${GeminiConfig.MODEL_ID}:generateContent"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBytes = requestJson.toByteArray(Charsets.UTF_8)
        val bodyBytesSent = requestBytes.size

        val attemptMsList = mutableListOf<Long>()
        var attempts = 0

        suspend fun attempt(): String {
            val limit = DailyQuotaGuard.getDailyLimit(context)
            if (!DailyQuotaGuard.tryConsume(context)) {
                throw QuotaExhaustedException(
                    "Daily Gemini quota reached ($limit calls); Tier-1 pre-alerts still active"
                )
            }
            attempts++
            val attemptStart = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(requestBytes.toRequestBody(mediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    val elapsed = System.currentTimeMillis() - attemptStart
                    attemptMsList.add(elapsed)
                    if (!response.isSuccessful) {
                        val code = response.code
                        if (code >= 500) {
                            throw ServerErrorException(code, "Server error: HTTP $code - $bodyString")
                        } else {
                            throw ClientErrorException(code, "Client error: HTTP $code - $bodyString")
                        }
                    }
                    return bodyString
                }
            } catch (e: Exception) {
                if (attemptMsList.size < attempts) {
                    attemptMsList.add(System.currentTimeMillis() - attemptStart)
                }
                throw e
            }
        }

        val responseBody = try {
            attempt()
        } catch (e: Exception) {
            if (e is IOException || e is ServerErrorException) {
                Log.w(TAG, "Attempt failed (${LogRedactor.redact(e.message)}); retrying once after 400ms backoff...")
                delay(400)
                attempt()
            } else {
                throw e
            }
        }

        return HttpResponseWithTrace(
            body = responseBody,
            trace = HttpTrace(
                attempts = attempts,
                attemptMs = attemptMsList,
                bodyBytesSent = bodyBytesSent
            )
        )
    }

    private fun parseResponse(rawResponse: String): Pair<ClassificationResult, ClassificationTokenUsage> {
        val root = JSONObject(rawResponse)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("No candidates found in Gemini response: $rawResponse")
        if (candidates.length() == 0) {
            throw IllegalStateException("Empty candidates array in response: $rawResponse")
        }
        val candidate = candidates.getJSONObject(0)
        val content = candidate.getJSONObject("content")
        val parts = content.getJSONArray("parts")
        
        var text: String? = null
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val partText = part.optString("text", "")
            if (partText.isNotBlank()) {
                text = partText
                break
            }
        }

        if (text == null) {
            throw IllegalStateException("No text part found in Gemini response: $rawResponse")
        }

        val usageObj = root.optJSONObject("usageMetadata")
        val promptTokens = usageObj?.optInt("promptTokenCount", 0) ?: 0
        val candidatesTokens = usageObj?.optInt("candidatesTokenCount", 0) ?: 0
        val thoughtsTokens = usageObj?.optInt("thoughtsTokenCount", 0) ?: 0
        val totalTokens = usageObj?.optInt("totalTokenCount", 0) ?: 0

        Log.i(
            TAG,
            "UsageMetadata: promptTokenCount=$promptTokens, candidatesTokenCount=$candidatesTokens, thoughtsTokenCount=$thoughtsTokens, totalTokenCount=$totalTokens"
        )

        val usage = ClassificationTokenUsage(
            promptTokenCount = promptTokens,
            candidatesTokenCount = candidatesTokens,
            thoughtsTokenCount = thoughtsTokens,
            totalTokenCount = totalTokens
        )

        val resultJson = JSONObject(text.trim())
        val classification = ClassificationResult(
            soundId = resultJson.getString("sound_id"),
            tier = resultJson.getString("tier"),
            confidence = resultJson.getDouble("confidence"),
            captionVi = resultJson.getString("caption_vi"),
            captionEn = resultJson.getString("caption_en"),
            motion = resultJson.optString("motion", "UNKNOWN")
        )
        return Pair(classification, usage)
    }
}
