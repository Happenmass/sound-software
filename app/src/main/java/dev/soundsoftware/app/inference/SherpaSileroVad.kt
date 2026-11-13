package dev.soundsoftware.app.inference

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.soundsoftware.core.asr.AsrException
import dev.soundsoftware.core.asr.ErrorCode
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.vad.SpeechRange
import dev.soundsoftware.core.vad.VoiceActivityDetector
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Neural VAD with bounded audio buffering and original sample offsets. */
class SherpaSileroVad(private val model: File, private val modelRevision: String) : VoiceActivityDetector {
    override val id = "silero-sherpa-2.0.1:$modelRevision"
    override suspend fun detect(wav: PcmWav): List<SpeechRange> {
        if (!model.isFile) throw AsrException(ErrorCode.MODEL_NOT_READY, "Silero VAD 模型未准备")
        require(wav.sampleRate == 16000 && wav.channels == 1)
        val detector = try {
            Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = model.absolutePath, minSilenceDuration = 0.5f, minSpeechDuration = 0.15f,
                maxSpeechDuration = 20f, windowSize = 512,
            ), sampleRate = 16000, numThreads = 1))
        } catch (e: LinkageError) {
            throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, "Silero 原生运行库不可用")
        }
        val ranges = mutableListOf<SpeechRange>()
        fun drain() {
            while (!detector.empty()) {
                val segment = detector.front()
                val start = maxOf(0, segment.start - 3840)
                val end = minOf(wav.sampleCount, segment.start + segment.samples.size + 3840)
                if (end > start) {
                    val previous = ranges.lastOrNull()
                    if (previous != null && previous.endSample >= start) {
                        ranges[ranges.lastIndex] = SpeechRange(previous.startSample, maxOf(previous.endSample, end))
                    } else ranges += SpeechRange(start, end)
                }
                detector.pop()
            }
        }
        try {
            var pos = 0L
            while (pos < wav.sampleCount) {
                currentCoroutineContext().ensureActive()
                val count = minOf(512L, wav.sampleCount - pos).toInt()
                detector.acceptWaveform(wav.readMono(pos, count).copyOf(512))
                pos += count
                drain()
            }
            detector.flush(); drain()
            return ranges
        } finally { detector.release() }
    }
}
