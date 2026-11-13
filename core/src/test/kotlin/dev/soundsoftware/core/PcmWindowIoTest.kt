package dev.soundsoftware.core

import dev.soundsoftware.core.audio.PcmWav
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PcmWindowIoTest {
    @Test fun everyPcm16ValueRoundTripsAcrossBufferBoundaries() {
        val file=Files.createTempFile("pcm-bulk-",".wav").toFile()
        try {
            val samples=FloatArray(65536){(it-32768)/32768f};PcmWav.writeMono(file,samples)
            assertArrayEquals(samples,PcmWav.open(file).readMono(0,samples.size),0f)
            assertArrayEquals(samples.copyOfRange(32760,32780),PcmWav.open(file).readMono(32760,20),0f)
            val expected=ByteBuffer.allocate(65536*2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(65536){expected.putShort((it-32768).toShort())}
            assertArrayEquals(expected.array(),file.readBytes().copyOfRange(44,file.length().toInt()))
        } finally {file.delete()}
    }
    @Test fun clippingAndLittleEndianEncodingRemainExact() {
        val file=Files.createTempFile("pcm-clipping-",".wav").toFile()
        try {
            PcmWav.writeMono(file,floatArrayOf(-2f,-1f,-.5f,-1/32768f,0f,1/32768f,.5f,1f,2f))
            val expected=ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            for(v in listOf(-32768,-32768,-16384,-1,0,1,16384,32767,32767))expected.putShort(v.toShort())
            assertArrayEquals(expected.array(),file.readBytes().copyOfRange(44,62))
            assertEquals(0,PcmWav.open(file).readMono(9,0).size)
            assertTrue(runCatching{PcmWav.open(file).readMono(9,1)}.isFailure)
            val opened=PcmWav.open(file);java.io.RandomAccessFile(file,"rw").use{it.setLength(45)}
            assertTrue(runCatching{opened.readMono(0,9)}.exceptionOrNull() is java.io.EOFException)
        } finally {file.delete()}
    }
}
