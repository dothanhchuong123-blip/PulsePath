package com.soundsight.audio.local

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions
import android.os.ParcelFileDescriptor
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.SoundId
import com.soundsight.domain.Tier
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class LocalResult(
    val soundId: SoundId,
    val score: Float,
    val rawLabel: String
)

object LocalClassifier {
    private const val TAG = "LocalClassifier"
    private const val MODEL_PATH = "yamnet.tflite"

    private var audioClassifier: AudioClassifier? = null
    private var initFailed = false
    private val initLock = Any()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun loadModelBuffer(context: Context): MappedByteBuffer {
        // The model remains memory-mapped and read-only, but every file descriptor
        // used during loading is closed after the mapping is created.
        return context.assets.openFd(MODEL_PATH).use { afd ->
            val duplicated = ParcelFileDescriptor.dup(afd.fileDescriptor)

            ParcelFileDescriptor.AutoCloseInputStream(duplicated).use { inputStream ->
                inputStream.channel.use { fileChannel ->
                    fileChannel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength
                    )
                }
            }
        }
    }

    private fun getOrInitClassifier(context: Context?): AudioClassifier? {
        if (initFailed) return null
        synchronized(initLock) {
            if (audioClassifier != null) return audioClassifier
            if (initFailed) return null

            val ctx = context ?: appContext
            if (ctx == null) {
                Log.w(TAG, "Cannot initialize LocalClassifier: context is null")
                return null
            }

            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetBuffer(loadModelBuffer(ctx))
                    .build()

                val options = AudioClassifierOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.AUDIO_CLIPS)
                    .setScoreThreshold(0.3f)
                    .setMaxResults(5)
                    .build()

                audioClassifier = AudioClassifier.createFromOptions(ctx, options)
                Log.i(TAG, "MediaPipe AudioClassifier (YAMNet) initialized successfully.")
                return audioClassifier
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize MediaPipe AudioClassifier: ${t.message}", t)
                initFailed = true
                audioClassifier = null
                return null
            }
        }
    }

    @Volatile
    var testOperationalOverride: Boolean? = null

    /**
     * Verifies that the local classifier is operational and able to classify PCM audio.
     * Required by W-H8-3 to renew the liveness lease.
     */
    fun isOperational(context: Context? = null): Boolean {
        testOperationalOverride?.let { return it }
        val ctx = context ?: appContext ?: return false
        return synchronized(initLock) {
            if (initFailed) false else (audioClassifier != null || getOrInitClassifier(ctx) != null)
        }
    }

    fun classify(mono16k: ShortArray): LocalResult? {
        return classify(appContext, mono16k)
    }

    fun classify(context: Context?, mono16k: ShortArray): LocalResult? {
        if (mono16k.isEmpty()) return null
        val classifier = getOrInitClassifier(context) ?: return null

        try {
            val audioFormat = AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(16000f)
                .build()

            val audioData = AudioData.create(audioFormat, mono16k.size)
            audioData.load(mono16k)

            val result = classifier.classify(audioData)
            val classificationResults = result.classificationResults() ?: return null

            // Collect all categories across classification results and heads, sorted by score descending
            val categories = classificationResults
                .flatMap { it.classifications() ?: emptyList() }
                .flatMap { it.categories() ?: emptyList() }
                .sortedByDescending { it.score() }

            Log.i(TAG, "YAMNet top-5: " + categories.take(5).joinToString(", ") {
                "${it.categoryName() ?: it.displayName()}=${String.format("%.2f", it.score())}"
            })

            return pickResult(categories.map { (it.categoryName() ?: it.displayName() ?: "") to it.score() })
        } catch (t: Throwable) {
            Log.e(TAG, "Error during local audio classification: ${t.message}", t)
            return null
        }
    }

    /**
     * The model's own top-5 (category, score) before any mapping, for measurement. Not used on
     * the rider path - see [classify] for the shipping behaviour.
     */
    fun rawTop5(context: Context?, mono16k: ShortArray): List<Pair<String, Float>> {
        if (mono16k.isEmpty()) return emptyList()
        val classifier = getOrInitClassifier(context) ?: return emptyList()
        return try {
            val audioFormat = AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(16000f).build()
            val audioData = AudioData.create(audioFormat, mono16k.size)
            audioData.load(mono16k)
            val result = classifier.classify(audioData)
            (result.classificationResults() ?: emptyList())
                .flatMap { it.classifications() ?: emptyList() }
                .flatMap { it.categories() ?: emptyList() }
                .sortedByDescending { it.score() }
                .take(5)
                .map { (it.categoryName() ?: it.displayName() ?: "") to it.score() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Chooses the rider-facing result from the model's scored labels: the highest-scoring label
     * that maps to a HAZARD, and only if there is none, the highest-scoring SAFE mapping.
     *
     * MEASURED 2026-09-14 on the pp_device emulator (`measurements/local-classifier/
     * 2026-09-14-emulator/survey-after-hazard-first-mapper.csv`, 40 D4 clips through this exact
     * model and mapper): YAMNet is multi-label, and its generic parent labels routinely outscore
     * the specific hazard beneath them - a fire-engine siren came back `Vehicle=0.74, Fire engine
     * (siren)=0.67, Emergency vehicle=0.59`; three of four train horns resolved to NORMAL_TRAFFIC
     * behind `Vehicle=0.98` (two of them with `Train horn=0.85..0.92`, the third at 0.74 or
     * absent from the top-5). The previous rule took the first mapped label by score, so every
     * one of those resolved to NORMAL_TRAFFIC (SAFE) and the hazard was thrown away before
     * policy ever saw it. "Vehicle" being loud is not evidence against a siren; it is what a
     * siren on a vehicle sounds like. Hazard-first mapping fixed those cases and produced no new
     * alert on any of the seven non-hazard clips.
     */
    fun pickResult(scoredLabels: List<Pair<String, Float>>): LocalResult? {
        var bestHazard: LocalResult? = null
        var bestSafe: LocalResult? = null
        for ((label, score) in scoredLabels) {
            val soundId = mapAudioSetLabelToSoundId(label) ?: continue
            val candidate = LocalResult(soundId = soundId, score = score, rawLabel = label)
            if (SoundEvent.canonicalTier(soundId) == Tier.SAFE) {
                if (bestSafe == null || score > bestSafe.score) bestSafe = candidate
            } else {
                if (bestHazard == null || score > bestHazard.score) bestHazard = candidate
            }
        }
        return bestHazard ?: bestSafe
    }

    fun mapAudioSetLabelToSoundId(label: String): SoundId? {
        val trimmed = label.trim()
        return when {
            trimmed.equals("Vehicle horn, car horn, honking", ignoreCase = true) ||
            trimmed.equals("Honk", ignoreCase = true) -> SoundId.CAR_HORN

            trimmed.equals("Toot", ignoreCase = true) -> SoundId.MOTORCYCLE_HORN

            trimmed.equals("Air horn, truck horn", ignoreCase = true) -> SoundId.TRUCK_AIR_HORN

            trimmed.equals("Siren", ignoreCase = true) ||
            trimmed.equals("Ambulance (siren)", ignoreCase = true) ||
            trimmed.equals("Police car (siren)", ignoreCase = true) ||
            trimmed.equals("Fire engine, fire truck (siren)", ignoreCase = true) ||
            trimmed.equals("Emergency vehicle", ignoreCase = true) -> SoundId.EMERGENCY_SIREN

            trimmed.equals("Train horn", ignoreCase = true) ||
            trimmed.equals("Train whistle", ignoreCase = true) -> SoundId.TRAIN_HORN

            trimmed.equals("Reversing beeps", ignoreCase = true) -> SoundId.REVERSING_BEEP

            trimmed.equals("Accelerating, revving, vroom", ignoreCase = true) -> SoundId.SUDDEN_ACCELERATION

            trimmed.equals("Screaming", ignoreCase = true) ||
            trimmed.equals("Shout", ignoreCase = true) ||
            trimmed.equals("Yell", ignoreCase = true) ||
            trimmed.equals("Children shouting", ignoreCase = true) -> SoundId.VOICE_SHOUT

            // MEASURED 2026-09-15 on the pp_device emulator (D9, `measurements/local-classifier/
            // 2026-09-15-collision/`, 35 definite impact recordings + 122 definite confounders,
            // app windows, floor 0.60): this model returns no impact label on any of the 4 real
            // vehicle collisions, 5 body falls or 1 bicycle fall - the only impact it can hear is
            // breaking glass. `Skidding` and `Glass` used to map here too, and they were the ONLY
            // labels that fired on a confounder: `Skidding` on 3 of 8 tyre squeals with no crash
            // (up to 0.74, i.e. CRITICAL and the crash screen without the motion gate) and
            // `Glass` on a clunky metal noise (0.80). Dropping both takes the confounder rate
            // from 4/122 to 0/122 and costs nothing that was real: non-glass impact recall was
            // 1/15 before and is 0/15 after (the one hit was a spanner on concrete heard as
            // `Glass`), while `Shatter` still catches 10 of the 20 glass impacts.
            trimmed.equals("Crash", ignoreCase = true) ||
            trimmed.equals("Smash, crash", ignoreCase = true) ||
            trimmed.equals("Bang", ignoreCase = true) ||
            trimmed.equals("Shatter", ignoreCase = true) -> SoundId.COLLISION_IMPACT

            trimmed.equals("Traffic noise, roadway noise", ignoreCase = true) ||
            trimmed.equals("Engine", ignoreCase = true) ||
            trimmed.equals("Wind", ignoreCase = true) ||
            trimmed.equals("Vehicle", ignoreCase = true) ||
            trimmed.equals("Motorcycle", ignoreCase = true) ||
            trimmed.equals("Car", ignoreCase = true) -> SoundId.NORMAL_TRAFFIC

            else -> null
        }
    }
}
