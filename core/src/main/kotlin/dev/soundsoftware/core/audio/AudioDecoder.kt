package dev.soundsoftware.core.audio

import java.io.File

/** Encoded device payloads are not assumed to be fixed-size Opus packets. */
interface AudioDecoder {
    fun decodeToCanonicalWav(input: File, output: File): PcmWav
}

class UnsupportedAudioCodecException(message: String) : IllegalArgumentException(message)

class CanonicalWavDecoder : AudioDecoder {
    override fun decodeToCanonicalWav(input: File, output: File): PcmWav {
        val wav = try { PcmWav.open(input) } catch (e: IllegalArgumentException) {
            throw UnsupportedAudioCodecException("需要已验证的 PCM WAV；设备裸 Opus 包尚需验证，不能假解码")
        }
        if (wav.sampleRate != 16000 || wav.channels != 1) {
            throw UnsupportedAudioCodecException("当前分析入口要求 16kHz 单声道 PCM16 WAV")
        }
        if (input.canonicalFile != output.canonicalFile) {
            output.parentFile?.mkdirs()
            input.copyTo(output, overwrite = false)
        }
        return PcmWav.open(output)
    }
}
