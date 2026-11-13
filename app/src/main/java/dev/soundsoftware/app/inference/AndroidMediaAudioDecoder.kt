package dev.soundsoftware.app.inference

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import dev.soundsoftware.core.audio.AudioDecoder
import dev.soundsoftware.core.audio.Pcm16MonoWriter
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.StreamingMonoResampler
import dev.soundsoftware.core.audio.UnsupportedAudioCodecException
import java.io.File
import java.nio.ByteOrder
import java.nio.file.Files

/** System container codecs only. Invoke on Dispatchers.IO; unknown device framing is never guessed. */
class AndroidMediaAudioDecoder(private val checkCancelled: () -> Unit = {}) : AudioDecoder {
    override fun decodeToCanonicalWav(input: File, output: File): PcmWav {
        require(input.isFile && input.length() > 0) { "音频文件为空或不存在" }
        require(input.canonicalFile != output.canonicalFile && !output.exists()) { "解码输出必须是新文件" }
        output.parentFile?.mkdirs()
        val temporary = File.createTempFile("audio-", ".wav", output.absoluteFile.parentFile)
        try {
            val canonical = runCatching { PcmWav.open(input) }.getOrNull()
            if (canonical?.sampleRate == 16000 && canonical.channels == 1) {
                input.inputStream().buffered().use { source ->
                    temporary.outputStream().buffered().use { sink ->
                        val bytes = ByteArray(65536)
                        while (true) { checkCancelled(); val n = source.read(bytes); if (n < 0) break; sink.write(bytes, 0, n) }
                    }
                }
            } else decodeContainer(input, temporary)
            checkCancelled()
            PcmWav.open(temporary) // Reject empty, incomplete or malformed output before publishing.
            Files.move(temporary.toPath(), output.toPath())
            return PcmWav.open(output)
        } finally { temporary.delete() }
    }

    private fun decodeContainer(input: File, output: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            try { extractor.setDataSource(input.absolutePath) } catch (e: java.io.IOException) {
                throw UnsupportedAudioCodecException("系统无法识别此音频容器；设备裸 Opus 包需要独立协议解码器")
            }
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (tracks.size != 1) throw UnsupportedAudioCodecException("导入音频需要且只能包含一条音轨")
            val track = tracks.single()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val decoder = try { MediaCodec.createDecoderByType(mime) } catch (e: java.io.IOException) {
                throw UnsupportedAudioCodecException("设备没有 $mime 解码器")
            }
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            Pcm16MonoWriter(output).use { writer ->
                var resampler: StreamingMonoResampler? = null
                var sampleRate = 0
                var channels = 0
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var inputEnded = false
                var outputEnded = false
                var lastProgress = SystemClock.elapsedRealtime()
                val info = MediaCodec.BufferInfo()
                fun updateFormat(decoded: MediaFormat) {
                    val nextRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val nextChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(nextRate in 8000..192000 && nextChannels in 1..8) { "不支持的 PCM 采样率或声道数" }
                    require(sampleRate == 0 || (sampleRate == nextRate && channels == nextChannels)) { "不支持录音中途改变 PCM 格式" }
                    sampleRate = nextRate; channels = nextChannels
                    encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "只支持 PCM16 或浮点 PCM 解码输出" }
                    if (resampler == null) resampler = StreamingMonoResampler(sampleRate, emit = writer::write)
                }
                while (!outputEnded) {
                    checkCancelled()
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                require(size <= buffer.capacity()) { "压缩音频帧超过解码缓冲区" }
                                require(extractor.sampleTime >= 0) { "音轨时间戳无效" }
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { updateFormat(decoder.outputFormat); lastProgress = SystemClock.elapsedRealtime() }
                        index >= 0 -> {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    if (resampler == null) updateFormat(decoder.outputFormat)
                                    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                    require(info.size % (channels * bytesPerSample) == 0) { "解码器输出不完整 PCM 帧" }
                                    val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                    buffer.limit(info.offset + info.size); buffer.position(info.offset)
                                    while (buffer.hasRemaining()) {
                                        var mono = 0f
                                        repeat(channels) { mono += if (bytesPerSample == 4) buffer.float else buffer.short / 32768f }
                                        requireNotNull(resampler).accept(mono / channels)
                                    }
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { decoder.releaseOutputBuffer(index, false) }
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    check(SystemClock.elapsedRealtime() - lastProgress < 10000) { "音频解码器无响应" }
                }
                resampler?.finish()
            }
        } finally {
            try { if (started) codec?.stop() } finally { codec?.release(); extractor.release() }
        }
    }
}
