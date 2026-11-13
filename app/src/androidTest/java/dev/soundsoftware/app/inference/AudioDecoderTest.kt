package dev.soundsoftware.app.inference

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.soundsoftware.core.audio.PcmWav
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {
    @Test fun systemDecoderConverts44100HzToCanonicalAudio() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val input = File.createTempFile("source-", ".wav", cache)
        val output = File(cache, "decoded-${System.nanoTime()}.wav")
        try {
            PcmWav.writeMono(input, FloatArray(44100) { (0.5 * sin(it * 2.0 * Math.PI * 1000 / 44100)).toFloat() }, 44100)
            val decoded = AndroidMediaAudioDecoder().decodeToCanonicalWav(input, output)
            assertEquals(16000, decoded.sampleRate)
            assertEquals(1, decoded.channels)
            assertEquals(16000L, decoded.sampleCount)
            assertTrue(decoded.readMono(100, 1000).any { it > 0.4f })
        } finally { input.delete(); output.delete() }
    }
    @Test fun invalidDeviceBytesNeverProducePlayableArtifact() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val input = File.createTempFile("invalid-", ".raw", cache).apply { writeBytes(ByteArray(160)) }
        val output = File(cache, "invalid-output-${System.nanoTime()}.wav")
        try {
            assertThrows(IllegalArgumentException::class.java) { AndroidMediaAudioDecoder().decodeToCanonicalWav(input, output) }
            assertFalse(output.exists())
        } finally { input.delete(); output.delete() }
    }
}
