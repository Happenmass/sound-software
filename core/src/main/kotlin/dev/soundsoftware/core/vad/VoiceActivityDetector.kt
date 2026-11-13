package dev.soundsoftware.core.vad

import dev.soundsoftware.core.audio.PcmWav
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

data class SpeechRange(val startSample: Long, val endSample: Long) {
    init { require(startSample >= 0 && endSample > startSample) }
}

interface VoiceActivityDetector {
    val id: String
    suspend fun detect(wav: PcmWav): List<SpeechRange>
}

/** Conservative baseline for plumbing experiments. Not a substitute for validated Silero VAD. */
class EnergyVad(
    private val rmsThreshold: Float = 0.006f,
    private val silenceMs: Int = 500,
    private val paddingMs: Int = 240,
) : VoiceActivityDetector {
    init { require(rmsThreshold > 0 && rmsThreshold < 1 && silenceMs >= 20 && paddingMs >= 0) }
    override val id = "energy-v1-$rmsThreshold-$silenceMs-$paddingMs"

    override suspend fun detect(wav: PcmWav): List<SpeechRange> {
        require(wav.sampleRate == 16000 && wav.channels == 1)
        val frame = 320
        val silence = silenceMs * 16L
        val pad = paddingMs * 16L
        val output = mutableListOf<SpeechRange>()
        var start = -1L
        var lastVoice = 0L
        var pos = 0L
        while (pos < wav.sampleCount) {
            currentCoroutineContext().ensureActive()
            val samples = wav.readMono(pos, minOf(frame.toLong(), wav.sampleCount - pos).toInt())
            val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()
            if (rms >= rmsThreshold) {
                if (start < 0) start = maxOf(0, pos - pad)
                lastVoice = pos + samples.size
            } else if (start >= 0 && pos + samples.size - lastVoice >= silence) {
                appendMerged(output, SpeechRange(start, minOf(wav.sampleCount, lastVoice + pad)))
                start = -1
            }
            pos += samples.size
        }
        if (start >= 0) appendMerged(output, SpeechRange(start, minOf(wav.sampleCount, lastVoice + pad)))
        return output
    }
}

/** Safe initial default: keeps every sample, including quiet voices, until neural VAD is verified. */
class WholeRecordingVad : VoiceActivityDetector {
    override val id = "whole-recording-v1"
    override suspend fun detect(wav: PcmWav) = listOf(SpeechRange(0, wav.sampleCount))
}

fun splitSpeechRanges(ranges: List<SpeechRange>, maxSamples: Int): List<SpeechRange> {
    require(maxSamples > 0)
    val result = mutableListOf<SpeechRange>()
    ranges.forEach { range ->
        var start = range.startSample
        while (start < range.endSample) {
            val end = minOf(range.endSample, start + maxSamples)
            result += SpeechRange(start, end)
            start = end
        }
    }
    return result
}

private fun appendMerged(output: MutableList<SpeechRange>, range: SpeechRange) {
    val previous = output.lastOrNull()
    if (previous != null && previous.endSample >= range.startSample) {
        output[output.lastIndex] = SpeechRange(previous.startSample, maxOf(previous.endSample, range.endSample))
    } else output += range
}
