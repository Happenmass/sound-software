package dev.soundsoftware.core.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamingAudioTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun nonIntegerRatePreservesDurationAndVoiceBand() {
        val output = resample(44100, 1000.0)
        assertEquals(16000, output.size)
        assertEquals(0.5 / sqrt(2.0), rms(output.drop(100).dropLast(100)), 0.002)
    }

    @Test fun rejectsAliasedOutOfBandTone() {
        val output = resample(48000, 12000.0)
        assertEquals(16000, output.size)
        assertTrue(rms(output.drop(100).dropLast(100)) < 0.001)
    }

    @Test fun veryShortAndUpsampledClipsHaveExactSampleCount() {
        val output = mutableListOf<Float>()
        val resampler = StreamingMonoResampler(8000, emit = output::add)
        repeat(7) { resampler.accept(0.5f) }
        resampler.finish()
        assertEquals(14, output.size)
        assertTrue(output.all { it.isFinite() })
        assertThrows(IllegalStateException::class.java) { resampler.accept(0f) }
    }

    @Test fun streamWriterFinalizesLengthAndClips() {
        val file = File(folder.root, "stream.wav")
        Pcm16MonoWriter(file).use { writer ->
            repeat(48001) { writer.write(if (it == 0) 2f else -0.5f) }
        }
        val wav = PcmWav.open(file)
        assertEquals(48001L, wav.sampleCount)
        assertArrayEquals(floatArrayOf(32767f / 32768, -0.5f), wav.readMono(0, 2), 0.00001f)
    }

    @Test fun integerRateResamplingMatchesOriginalKernelSampleForSampleIncludingEdges() {
        for (rate in listOf(16000, 48000, 96000, 192000)) {
            val input = FloatArray(4099) { ((it * 73 % 509 - 254) / 317f) }
            val output = mutableListOf<Float>()
            val resampler = StreamingMonoResampler(rate, emit = output::add)
            input.forEach(resampler::accept); resampler.finish()
            val cutoff = kotlin.math.min(1.0, 16000.0 / rate) * 0.94
            val radius = kotlin.math.ceil(24 / cutoff).toInt()
            val original = IntArray(input.size * 16000 / rate) { produced ->
                val position = produced.toDouble() * rate / 16000
                val center = position.toLong()
                var sum = 0.0; var weight = 0.0
                for (index in center - radius..center + radius) {
                    val delta = index - position
                    if (kotlin.math.abs(delta) > radius) continue
                    val x = PI * cutoff * delta
                    val sinc = if (kotlin.math.abs(x) < 1e-12) cutoff else cutoff * sin(x) / x
                    val window = 0.42 + 0.5 * kotlin.math.cos(PI * delta / radius) + 0.08 * kotlin.math.cos(2 * PI * delta / radius)
                    val coefficient = sinc * window
                    weight += coefficient
                    if (index >= 0 && index < input.size) sum += input[index.toInt()] * coefficient
                }
                (sum / weight).toFloat().coerceIn(-1f, 1f).toRawBits()
            }
            assertArrayEquals("rate=$rate", original, output.map { it.toRawBits() }.toIntArray())
        }
    }

    private fun resample(rate: Int, tone: Double): List<Float> {
        val output = mutableListOf<Float>()
        val resampler = StreamingMonoResampler(rate, emit = output::add)
        repeat(rate) { resampler.accept((0.5 * sin(2 * PI * tone * it / rate)).toFloat()) }
        resampler.finish()
        return output
    }

    private fun rms(samples: List<Float>) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
