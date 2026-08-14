package com.soundsight.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun encode(pcm16: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val totalDataLen = pcm16.size
        val totalAudioLen = totalDataLen + 36

        val buffer = ByteBuffer.allocate(44 + totalDataLen).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(totalAudioLen)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())

            // "fmt " sub-chunk
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // Subchunk1Size for PCM
            putShort(1.toShort()) // AudioFormat 1 = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())

            // "data" sub-chunk
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(totalDataLen)

            // PCM audio bytes
            put(pcm16)
        }

        return buffer.array()
    }
}
