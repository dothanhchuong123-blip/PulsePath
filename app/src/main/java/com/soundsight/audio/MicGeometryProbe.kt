package com.soundsight.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

data class MicInfo(
    val id: Int,
    val description: String,
    val address: String,
    val type: Int,                    // MicrophoneInfo.getType()
    val location: Int,                // getLocation(): 0=UNKNOWN 1=MAINBODY 2=MAINBODY_MOVABLE 3=PERIPHERAL
    val directionality: Int,          // getDirectionality()
    val positionX: Float,             // getPosition().x, metres, or Float.NaN if unknown
    val positionY: Float,
    val positionZ: Float,
    val positionKnown: Boolean,       // false when getPosition() is POSITION_UNKNOWN
    val orientationX: Float,
    val orientationY: Float,
    val orientationZ: Float,
    val channelMapping: List<Pair<Int, Int>>  // getChannelMapping(): (channelIndex, mappingType)
)

data class MicGeometry(
    val mics: List<MicInfo>,
    val captureChannels: Int,
    val sampleRate: Int,
    // Derived, only when at least two mics report a known position AND their
    // channel mappings cover two distinct channels:
    val baselineMetres: Float?,        // distance between the two mapped mics
    val baselineVector: Triple<Float, Float, Float>?,  // mic(channel1) - mic(channel0)
    val maxLagSamplesFromGeometry: Double?,  // baselineMetres / 343.0 * sampleRate
    val geometryUsable: Boolean        // true when the above could all be computed
)

object MicGeometryProbe {
    private const val TAG = "MicGeometryProbe"

    suspend fun probe(context: Context): MicGeometry = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "API level ${Build.VERSION.SDK_INT} < 28 (Android P). MicrophoneInfo query unsupported.")
            return@withContext MicGeometry(
                mics = emptyList(),
                captureChannels = 0,
                sampleRate = 0,
                baselineMetres = null,
                baselineVector = null,
                maxLagSamplesFromGeometry = null,
                geometryUsable = false
            )
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return@withContext MicGeometry(
                mics = emptyList(),
                captureChannels = 0,
                sampleRate = 0,
                baselineMetres = null,
                baselineVector = null,
                maxLagSamplesFromGeometry = null,
                geometryUsable = false
            )
        }

        data class RecordCandidate(
            val source: Int,
            val sampleRate: Int,
            val channelConfig: Int,
            val channelCount: Int,
            val name: String
        )

        val ladder = listOf(
            RecordCandidate(
                source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC,
                sampleRate = 48000,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                channelCount = 2,
                name = "UNPROCESSED 48kHz Stereo"
            ),
            RecordCandidate(
                source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate = 48000,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                channelCount = 2,
                name = "VOICE_RECOGNITION 48kHz Stereo"
            ),
            RecordCandidate(
                source = MediaRecorder.AudioSource.MIC,
                sampleRate = 48000,
                channelConfig = AudioFormat.CHANNEL_IN_STEREO,
                channelCount = 2,
                name = "MIC 48kHz Stereo"
            ),
            RecordCandidate(
                source = MediaRecorder.AudioSource.MIC,
                sampleRate = 16000,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                channelCount = 1,
                name = "MIC 16kHz Mono Fallback"
            )
        )

        var record: AudioRecord? = null
        var selectedCandidate: RecordCandidate? = null

        for (candidate in ladder) {
            val minBuf = AudioRecord.getMinBufferSize(
                candidate.sampleRate,
                candidate.channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue

            val bufSize = minBuf * 4
            val testRecord = AudioRecord(
                candidate.source,
                candidate.sampleRate,
                candidate.channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            if (testRecord.state == AudioRecord.STATE_INITIALIZED) {
                record = testRecord
                selectedCandidate = candidate
                break
            } else {
                testRecord.release()
            }
        }

        if (record == null || selectedCandidate == null) {
            Log.e(TAG, "Failed to initialize any AudioRecord candidate from ladder")
            return@withContext MicGeometry(
                mics = emptyList(),
                captureChannels = 0,
                sampleRate = 0,
                baselineMetres = null,
                baselineVector = null,
                maxLagSamplesFromGeometry = null,
                geometryUsable = false
            )
        }

        val micInfoList = mutableListOf<MicInfo>()

        try {
            record.startRecording()
            val activeMics = record.activeMicrophones

            for (m in activeMics) {
                val pos = m.position
                val posUnknown = (pos == null ||
                        pos == MicrophoneInfo.POSITION_UNKNOWN ||
                        pos.x.isNaN() ||
                        pos.x == MicrophoneInfo.POSITION_UNKNOWN.x &&
                        pos.y == MicrophoneInfo.POSITION_UNKNOWN.y &&
                        pos.z == MicrophoneInfo.POSITION_UNKNOWN.z)

                val posX = if (posUnknown) Float.NaN else pos.x
                val posY = if (posUnknown) Float.NaN else pos.y
                val posZ = if (posUnknown) Float.NaN else pos.z

                val orient = m.orientation
                val orientUnknown = (orient == null ||
                        orient == MicrophoneInfo.ORIENTATION_UNKNOWN ||
                        orient.x.isNaN() ||
                        orient.x == MicrophoneInfo.ORIENTATION_UNKNOWN.x &&
                        orient.y == MicrophoneInfo.ORIENTATION_UNKNOWN.y &&
                        orient.z == MicrophoneInfo.ORIENTATION_UNKNOWN.z)

                val orientX = if (orientUnknown) Float.NaN else orient.x
                val orientY = if (orientUnknown) Float.NaN else orient.y
                val orientZ = if (orientUnknown) Float.NaN else orient.z

                val mapping = m.channelMapping?.map { Pair(it.first, it.second) } ?: emptyList()

                micInfoList.add(
                    MicInfo(
                        id = m.id,
                        description = m.description ?: "",
                        address = m.address ?: "",
                        type = m.type,
                        location = m.location,
                        directionality = m.directionality,
                        positionX = posX,
                        positionY = posY,
                        positionZ = posZ,
                        positionKnown = !posUnknown,
                        orientationX = orientX,
                        orientationY = orientY,
                        orientationZ = orientZ,
                        channelMapping = mapping
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error probing active microphones: ${e.message}", e)
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {}
            try {
                record.release()
            } catch (_: Exception) {}
        }

        // Derive geometry if at least two mics have known positions mapped to distinct channels
        // Try channel 0 and channel 1 first
        var micCh0 = micInfoList.firstOrNull { it.positionKnown && it.channelMapping.any { pair -> pair.first == 0 } }
        var micCh1 = micInfoList.firstOrNull { it.positionKnown && it.channelMapping.any { pair -> pair.first == 1 } }

        // If not found with exact channel 0 and 1, check any pair of distinct channels
        if (micCh0 == null || micCh1 == null || micCh0.id == micCh1.id) {
            val knownWithChannels = micInfoList.filter { it.positionKnown && it.channelMapping.isNotEmpty() }
            if (knownWithChannels.size >= 2) {
                val first = knownWithChannels[0]
                val second = knownWithChannels[1]
                val chFirst = first.channelMapping.first().first
                val chSecond = second.channelMapping.first().first
                if (chFirst != chSecond) {
                    if (chFirst < chSecond) {
                        micCh0 = first
                        micCh1 = second
                    } else {
                        micCh0 = second
                        micCh1 = first
                    }
                }
            }
        }

        val hasValidPair = (micCh0 != null && micCh1 != null && micCh0.id != micCh1.id && micCh0.positionKnown && micCh1.positionKnown)

        val baselineVector: Triple<Float, Float, Float>?
        val baselineMetres: Float?
        val maxLagSamples: Double?
        val geometryUsable: Boolean

        if (hasValidPair) {
            val dx = micCh1!!.positionX - micCh0!!.positionX
            val dy = micCh1.positionY - micCh0.positionY
            val dz = micCh1.positionZ - micCh0.positionZ
            baselineVector = Triple(dx, dy, dz)
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            baselineMetres = dist
            maxLagSamples = (dist.toDouble() / 343.0) * selectedCandidate.sampleRate
            geometryUsable = true
        } else {
            baselineVector = null
            baselineMetres = null
            maxLagSamples = null
            geometryUsable = false
        }

        val geometry = MicGeometry(
            mics = micInfoList,
            captureChannels = selectedCandidate.channelCount,
            sampleRate = selectedCandidate.sampleRate,
            baselineMetres = baselineMetres,
            baselineVector = baselineVector,
            maxLagSamplesFromGeometry = maxLagSamples,
            geometryUsable = geometryUsable
        )

        // Log one field per line under tag "MicGeometryProbe"
        logGeometry(geometry, selectedCandidate.name)

        geometry
    }

    private fun logGeometry(geometry: MicGeometry, candidateName: String) {
        Log.i(TAG, "=== MicGeometryProbe Report ===")
        Log.i(TAG, "Active Record Candidate: $candidateName")
        Log.i(TAG, "Capture Channels: ${geometry.captureChannels}")
        Log.i(TAG, "Sample Rate: ${geometry.sampleRate} Hz")
        Log.i(TAG, "Active Microphones Count: ${geometry.mics.size}")

        geometry.mics.forEachIndexed { idx, mic ->
            Log.i(TAG, "--- Microphone #$idx ---")
            Log.i(TAG, "id: ${mic.id}")
            Log.i(TAG, "description: ${mic.description}")
            Log.i(TAG, "address: ${mic.address}")
            Log.i(TAG, "type: ${mic.type}")
            Log.i(TAG, "location: ${mic.location} (${locationName(mic.location)})")
            Log.i(TAG, "directionality: ${mic.directionality}")
            Log.i(TAG, "positionKnown: ${mic.positionKnown}")
            Log.i(TAG, "positionX: ${mic.positionX}")
            Log.i(TAG, "positionY: ${mic.positionY}")
            Log.i(TAG, "positionZ: ${mic.positionZ}")
            Log.i(TAG, "orientationX: ${mic.orientationX}")
            Log.i(TAG, "orientationY: ${mic.orientationY}")
            Log.i(TAG, "orientationZ: ${mic.orientationZ}")
            Log.i(TAG, "channelMapping: ${mic.channelMapping.joinToString { "(ch=${it.first}, type=${it.second})" }}")
        }

        Log.i(TAG, "--- Derived Geometry ---")
        Log.i(TAG, "geometryUsable: ${geometry.geometryUsable}")
        Log.i(TAG, "baselineMetres: ${geometry.baselineMetres}")
        Log.i(TAG, "baselineVector: ${geometry.baselineVector}")
        Log.i(TAG, "maxLagSamplesFromGeometry: ${geometry.maxLagSamplesFromGeometry}")

        // Vietnamese Interpretation
        Log.i(TAG, "--- Vietnamese Interpretation ---")
        if (geometry.baselineMetres != null) {
            Log.i(TAG, String.format(Locale.US, "1. Khoảng cách hai micro: %.2f cm", geometry.baselineMetres * 100.0f))
        } else {
            Log.i(TAG, "1. Máy không khai báo vị trí micro")
        }

        if (geometry.baselineVector != null) {
            val (vx, vy, vz) = geometry.baselineVector
            val ax = abs(vx)
            val ay = abs(vy)
            val az = abs(vz)
            val axisStr = if (ax >= ay && ax >= az) {
                "chiều ngang màn hình"
            } else if (ay >= ax && ay >= az) {
                "chiều dọc màn hình"
            } else {
                "chiều trước-sau máy"
            }
            Log.i(TAG, "2. Trục hai micro nằm chủ yếu theo: $axisStr")
        } else {
            Log.i(TAG, "2. Trục hai micro: Không xác định được từ phần cứng")
        }

        if (geometry.maxLagSamplesFromGeometry != null) {
            Log.i(TAG, String.format(Locale.US, "3. Độ trễ tối đa về mặt vật lý: %.1f mẫu ở %d kHz", geometry.maxLagSamplesFromGeometry, geometry.sampleRate / 1000))
        } else {
            Log.i(TAG, "3. Độ trễ tối đa về mặt vật lý: Không xác định được từ phần cứng")
        }
        Log.i(TAG, "===============================")
    }

    fun locationName(location: Int): String = when (location) {
        MicrophoneInfo.LOCATION_MAINBODY -> "MAINBODY (1)"
        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "MAINBODY_MOVABLE (2)"
        MicrophoneInfo.LOCATION_PERIPHERAL -> "PERIPHERAL (3)"
        MicrophoneInfo.LOCATION_UNKNOWN -> "UNKNOWN (0)"
        else -> "UNKNOWN ($location)"
    }
}
