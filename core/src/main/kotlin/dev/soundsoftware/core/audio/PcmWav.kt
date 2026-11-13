package dev.soundsoftware.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** RIFF PCM16 reader. Data is read in bounded windows, never as a complete meeting. */
class PcmWav private constructor(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val sampleCount: Long,
    private val dataOffset: Long,
) {
    val durationMs: Long get() = sampleCount * 1000 / sampleRate

    fun readMono(startSample: Long, count: Int): FloatArray {
        require(channels == 1) { "Only mono analysis audio is supported" }
        require(count in 0..MAX_WINDOW_SAMPLES) { "Audio window exceeds bounded buffer" }
        require(startSample >= 0 && startSample <= sampleCount && count <= sampleCount - startSample)
        return RandomAccessFile(file, "r").use { input ->
            input.seek(dataOffset + startSample * 2)
            val bytes=ByteArray(count*2)
            input.readFully(bytes)
            FloatArray(count) { i ->
                val lo=bytes[i*2].toInt() and 255
                val hi=bytes[i*2+1].toInt()
                ((hi shl 8) or lo).toShort().toFloat()/32768f
            }
        }
    }

    companion object {
        const val MAX_WINDOW_SAMPLES = 16000 * 60

        fun open(file: File): PcmWav = RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12) { "Truncated WAV header" }
            require(input.fourCc() == "RIFF") { "Expected RIFF WAV" }
            val riffEnd = input.u32() + 8
            require(riffEnd <= input.length() && riffEnd >= 12) { "Truncated RIFF data" }
            require(input.fourCc() == "WAVE") { "Expected WAVE" }
            var rate = 0
            var channels = 0
            var align = 0
            var dataOffset = -1L
            var dataSize = 0L
            while (input.filePointer + 8 <= riffEnd) {
                val kind = input.fourCc()
                val size = input.u32()
                val begin = input.filePointer
                require(size <= riffEnd - begin) { "Truncated WAV chunk" }
                when (kind) {
                    "fmt " -> {
                        require(rate == 0 && size >= 16) { "Invalid or duplicate format chunk" }
                        require(input.u16() == 1) { "Only PCM WAV is supported" }
                        channels = input.u16()
                        val rateLong = input.u32()
                        require(rateLong in 8000..192000 && channels in 1..8) { "Invalid WAV format" }
                        rate = rateLong.toInt()
                        val byteRate = input.u32()
                        align = input.u16()
                        require(input.u16() == 16 && align == channels * 2 && byteRate == rate.toLong() * align) {
                            "Expected consistent PCM16 WAV"
                        }
                    }
                    "data" -> {
                        require(dataOffset < 0) { "Multiple data chunks are not supported" }
                        dataOffset = begin
                        dataSize = size
                    }
                }
                val next = begin + size + (size and 1)
                require(next <= riffEnd) { "Missing chunk padding" }
                input.seek(next)
            }
            require(rate > 0 && dataOffset >= 0 && dataSize > 0 && dataSize % align == 0L) { "Missing or invalid PCM data" }
            PcmWav(file, rate, channels, dataSize / align, dataOffset)
        }

        /** Writes bounded PCM input; useful for imported/test clips, not full-file in-memory decoding. */
        fun writeMono(file: File, samples: FloatArray, sampleRate: Int = 16000) {
            require(sampleRate in 8000..192000)
            require(samples.all { it.isFinite() })
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.writeBytes("RIFF"); out.le32(36L + samples.size * 2L); out.writeBytes("WAVEfmt ")
                out.le32(16); out.le16(1); out.le16(1); out.le32(sampleRate.toLong())
                out.le32(sampleRate * 2L); out.le16(2); out.le16(16)
                out.writeBytes("data"); out.le32(samples.size * 2L)
                val buffer=ByteArray(65536)
                var offset=0
                while(offset<samples.size) {
                    val n=minOf(buffer.size/2,samples.size-offset)
                    repeat(n) { i ->
                        val sample=(samples[offset+i].coerceIn(-1f,1f)*32768f).toInt().coerceIn(-32768,32767)
                        buffer[i*2]=sample.toByte();buffer[i*2+1]=(sample ushr 8).toByte()
                    }
                    out.write(buffer,0,n*2);offset+=n
                }
            }
        }
    }
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun RandomAccessFile.fourCc(): String = ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)
private fun RandomAccessFile.u16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
private fun RandomAccessFile.u32(): Long = u16().toLong() or (u16().toLong() shl 16)
private fun RandomAccessFile.le16(value: Int) { write(value and 255); write((value ushr 8) and 255) }
private fun RandomAccessFile.le32(value: Long) { repeat(4) { write(((value ushr (it * 8)) and 255).toInt()) } }
