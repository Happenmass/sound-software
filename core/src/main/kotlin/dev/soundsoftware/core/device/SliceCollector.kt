package dev.soundsoftware.core.device

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** A raw transport artifact, never marked playable before codec validation. */
class SliceCollector(file: File, private val expectedBytes: Long, private val firstSequence: Long = 0) : Closeable {
    private val sink = RandomAccessFile(file, "rw")
    private val received = mutableSetOf<Long>()
    var contiguousSlices = 0L
        private set
    val contiguousBytes: Long get() = minOf(contiguousSlices * 160, expectedBytes)
    init { require(expectedBytes in 1..(8L * 1024 * 1024 * 1024)); sink.setLength(0) }
    @Synchronized fun accept(sequence: Long, data: ByteArray) {
        require(data.size == 160)
        val index = sequence - firstSequence
        require(index >= 0 && index < (expectedBytes + 159) / 160) { "分片序号超出文件范围" }
        val count = minOf(160L, expectedBytes - index * 160).toInt()
        sink.seek(index * 160)
        if (index in received) {
            val prior = ByteArray(count); sink.readFully(prior)
            require(prior.contentEquals(data.copyOf(count))) { "重复分片内容冲突" }
            return
        }
        sink.write(data, 0, count); received += index
        while (contiguousSlices in received) contiguousSlices++
    }
    @Synchronized fun finish() {
        check(contiguousBytes == expectedBytes) { "文件传输结束但存在缺片" }
        sink.fd.sync()
    }
    override fun close() = sink.close()
}
