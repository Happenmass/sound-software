package dev.soundsoftware.core.audio

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmWavTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun roundTripReadsExactWindowAndSampleTimeline() {
        val file = File(folder.root, "audio.wav")
        val samples = FloatArray(32000) { if (it < 16000) 0.25f else -0.5f }
        PcmWav.writeMono(file, samples)
        val wav = PcmWav.open(file)
        assertEquals(2000, wav.durationMs)
        assertEquals(32000, wav.sampleCount)
        assertArrayEquals(floatArrayOf(0.25f, -0.5f, -0.5f), wav.readMono(15999, 3), 0.0001f)
    }

    @Test fun refusesTruncatedDataInsteadOfReturningShortRecording() {
        val file = File(folder.root, "audio.wav")
        PcmWav.writeMono(file, FloatArray(1600))
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
        assertThrows(IllegalArgumentException::class.java) { PcmWav.open(file) }
    }

    @Test fun skipsUnknownOddSizedChunks() {
        val original = File(folder.root, "original.wav")
        PcmWav.writeMono(original, floatArrayOf(0.5f, -0.5f))
        val bytes = original.readBytes()
        val result = bytes.copyOfRange(0, 12) + byteArrayOf(74, 85, 78, 75, 1, 0, 0, 0, 7, 0) + bytes.copyOfRange(12, bytes.size)
        val length = result.size - 8
        repeat(4) { result[4 + it] = (length ushr (it * 8)).toByte() }
        val file = File(folder.root, "odd.wav").apply { writeBytes(result) }
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), PcmWav.open(file).readMono(0, 2), 0.0001f)
    }

    @Test fun refusesOpaqueDevicePayloadRatherThanGuessingOpusFraming() {
        val file = File(folder.root, "device.bin").apply { writeBytes(ByteArray(160)) }
        assertThrows(UnsupportedAudioCodecException::class.java) {
            CanonicalWavDecoder().decodeToCanonicalWav(file, File(folder.root, "out.wav"))
        }
    }

    @Test fun windowCannotReadOutsideVerifiedData() {
        val file = File(folder.root, "audio.wav")
        PcmWav.writeMono(file, FloatArray(1600))
        val wav = PcmWav.open(file)
        assertThrows(IllegalArgumentException::class.java) { wav.readMono(1599, 2) }
    }
}
