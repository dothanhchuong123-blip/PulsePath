package com.soundsight.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guard against a repeat of the AI Studio export corruption: every raster
 * launcher webp was passed through a UTF-8 text encoder, expanding every
 * byte >= 0x80 into a two-byte sequence (0xFC became C3 BC), displacing the
 * "WEBP" fourcc and invalidating the RIFF size field. aapt2 copies .webp
 * through without validating, so only this test catches it at build time.
 */
class LauncherWebpIntegrityTest {

    private val resDir: File = locateResDir()

    private fun locateResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/res from ${System.getProperty("user.dir")}")
    }

    private fun mipmapWebpFiles(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".webp") }.orEmpty().toList() }
            .sortedBy { it.path }

    private fun assertValidWebpHeader(file: File, bytes: ByteArray) {
        assertTrue("${file.path}: too short for a RIFF header", bytes.size >= 12)
        assertEquals(
            "${file.path}: missing 'RIFF' magic",
            "RIFF",
            String(bytes, 0, 4, Charsets.US_ASCII)
        )
        assertEquals(
            "${file.path}: 'WEBP' fourcc not at chunk offset 8",
            "WEBP",
            String(bytes, 8, 4, Charsets.US_ASCII)
        )
        val riffSize = (bytes[4].toInt() and 0xFF) or
            ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(
            "${file.path}: RIFF size field does not match file length - 8",
            file.length().toInt() - 8,
            riffSize
        )
        var i = 0
        while (i < 11) {
            val lead = bytes[i].toInt() and 0xFF
            val next = bytes[i + 1].toInt() and 0xFF
            val isUtf8ExpansionPair = (lead == 0xC2 || lead == 0xC3) && next in 0x80..0xBF
            assertTrue(
                "${file.path}: UTF-8 expansion signature at header byte $i " +
                    "(0x${lead.toString(16)} 0x${next.toString(16)})",
                !isUtf8ExpansionPair
            )
            i++
        }
    }

    @Test
    fun everyMipmapWebpCarriesAValidRiffWebpHeader() {
        val files = mipmapWebpFiles()
        assumeTrue("No mipmap webp rasters found; nothing to guard", files.isNotEmpty())
        for (file in files) {
            assertValidWebpHeader(file, file.readBytes())
        }
    }

    @Test
    fun validatorRejectsTheHistoricalUtf8ExpandedHeader() {
        val corrupted = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0xC3.toByte(), 0xBC.toByte(), 0x0E, 0x00, // expanded 0xFC -> C3 BC; size displaced
            0x00, 0x57, 0x45, 0x42 // "WEBP" shifted one byte late
        )
        try {
            assertValidWebpHeader(File("historical-corrupt-header"), corrupted)
            throw AssertionError("Corrupted header was accepted")
        } catch (expected: AssertionError) {
            val message = expected.message.orEmpty()
            assertTrue(message.contains("WEBP") || message.contains("expansion"))
        }
    }

    @Test
    fun validatorAcceptsAWellFormedMinimalHeaderShape() {
        val payload = ByteArray(20) { it.toByte() }
        val valid = ByteArray(12 + payload.size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(valid)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(valid, 8)
        payload.copyInto(valid, 12)
        val riffSize = valid.size - 8
        valid[4] = (riffSize and 0xFF).toByte()
        valid[5] = ((riffSize shr 8) and 0xFF).toByte()
        valid[6] = ((riffSize shr 16) and 0xFF).toByte()
        valid[7] = ((riffSize shr 24) and 0xFF).toByte()
        val tmp = File.createTempFile("synthetic-valid", ".webp")
        try {
            tmp.writeBytes(valid)
            assertValidWebpHeader(tmp, valid)
        } finally {
            tmp.delete()
        }
    }
}
