package dev.soundsoftware.core.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Centered windowed-sinc resampling. Keeps a bounded ring; output sample zero maps to input zero. */
class StreamingMonoResampler(private val inputRate: Int, private val outputRate: Int = 16000, private val emit: (Float) -> Unit) {
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate) * 0.94
    private val radius = ceil(24 / cutoff).toInt()
    private val ring = FloatArray(radius * 2 + 8)
    private var received = 0L
    private var produced = 0L
    private var finished = false

    init { require(inputRate in 8000..192000 && outputRate in 8000..48000) }

    // D3200 is 48 kHz -> 16 kHz: every output has the same integer phase. Reuse
    // identical coefficients instead of evaluating three trig functions per tap/sample.
    private val integerCoefficients = if (inputRate % outputRate == 0)
        DoubleArray(radius * 2 + 1) { coefficient((it - radius).toDouble()) } else null
    private val integerWeight = integerCoefficients?.fold(0.0) { sum, value -> sum + value } ?: 0.0

    private fun coefficient(delta: Double): Double {
        val x = PI * cutoff * delta
        val sinc = if (kotlin.math.abs(x) < 1e-12) cutoff else cutoff * sin(x) / x
        val window = 0.42 + 0.5 * cos(PI * delta / radius) + 0.08 * cos(2 * PI * delta / radius)
        return sinc * window
    }

    fun accept(sample: Float) {
        check(!finished)
        require(sample.isFinite()) { "Non-finite PCM sample" }
        ring[(received % ring.size).toInt()] = sample
        received++
        while (produced * inputRate / outputRate + radius < received) writeSample()
    }

    fun finish() {
        check(!finished)
        finished = true
        val finalCount = received * outputRate / inputRate
        while (produced < finalCount) writeSample()
    }

    private fun writeSample() {
        val position = produced.toDouble() * inputRate / outputRate
        val center = position.toLong()
        val kernel = integerCoefficients
        if (kernel != null && center >= radius && center + radius < received) {
            check(received - (center - radius) <= ring.size) { "Resampler history overwritten" }
            // Same tap order and denominator as the original kernel. The interior window
            // needs no per-tap Long modulo, bounds decisions or repeated weight summation.
            var at = ((center - radius) % ring.size).toInt()
            var sum = 0.0
            for (tap in kernel.indices) {
                sum += ring[at] * kernel[tap]
                if (++at == ring.size) at = 0
            }
            emit((sum / integerWeight).toFloat().coerceIn(-1f, 1f))
            produced++
            return
        }
        var sum = 0.0
        var weight = 0.0
        for (index in center - radius..center + radius) {
            val delta = index - position
            if (kotlin.math.abs(delta) > radius) continue
            val coefficient = integerCoefficients?.get((index - center + radius).toInt()) ?: coefficient(delta)
            weight += coefficient
            if (index >= 0 && index < received) {
                check(received - index <= ring.size) { "Resampler history overwritten" }
                sum += ring[(index % ring.size).toInt()] * coefficient
            }
        }
        emit((sum / weight).toFloat().coerceIn(-1f, 1f))
        produced++
    }
}

/** PCM16 writer with bounded buffering and finalized RIFF lengths on close. */
class Pcm16MonoWriter(file: File, private val sampleRate: Int = 16000) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private val buffer = ByteArray(8192)
    private var used = 0
    private var samples = 0L
    private var closed = false

    init {
        require(sampleRate in 8000..192000)
        output.setLength(0)
        output.writeBytes("RIFF"); le32(0); output.writeBytes("WAVEfmt ")
        le32(16); le16(1); le16(1); le32(sampleRate.toLong()); le32(sampleRate * 2L)
        le16(2); le16(16); output.writeBytes("data"); le32(0)
    }

    fun write(value: Float) {
        check(!closed)
        require(value.isFinite())
        require(samples < (0xffffffffL - 36) / 2) { "Audio exceeds RIFF size limit" }
        val sample = (value.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
        buffer[used++] = sample.toByte(); buffer[used++] = (sample ushr 8).toByte()
        samples++
        if (used == buffer.size) flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flush()
            output.seek(4); le32(36 + samples * 2)
            output.seek(40); le32(samples * 2)
            output.fd.sync()
        } finally { output.close() }
    }

    private fun flush() { output.write(buffer, 0, used); used = 0 }
    private fun le16(value: Int) { output.write(value and 255); output.write((value ushr 8) and 255) }
    private fun le32(value: Long) { repeat(4) { output.write(((value ushr (it * 8)) and 255).toInt()) } }
}
