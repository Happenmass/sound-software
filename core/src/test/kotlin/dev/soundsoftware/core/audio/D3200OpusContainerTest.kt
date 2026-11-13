package dev.soundsoftware.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class D3200OpusContainerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oggPagesHaveIndependentValidCrcHeadersAndExactGranuleTimeline() {
        val input = fixture(367)
        val output = File(temporary.root, "audio.ogg")
        D3200OpusContainer.wrap(input, output, 367, 58720, input.sha256())
        val data = output.readBytes()
        var offset = 0
        var pageCount = 0
        var serial: Int? = null
        while (offset < data.size) {
            assertEquals("OggS", data.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII))
            val segments = data[offset + 26].toInt() and 255
            val bodyLength = (0 until segments).sumOf { data[offset + 27 + it].toInt() and 255 }
            val pageLength = 27 + segments + bodyLength
            val page = data.copyOfRange(offset, offset + pageLength)
            val fields = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0, page[4].toInt())
            assertEquals(pageCount, fields.getInt(18))
            if (serial == null) serial = fields.getInt(14) else assertEquals(serial, fields.getInt(14))
            val crc = fields.getInt(22)
            repeat(4) { page[22 + it] = 0 }
            assertEquals(crc, referenceCrc(page))
            val packet = page.copyOfRange(27 + segments, page.size)
            when (pageCount) {
                0 -> {
                    assertEquals(2, page[5].toInt())
                    assertEquals("OpusHead", packet.copyOfRange(0, 8).toString(Charsets.US_ASCII))
                    assertEquals(19, packet.size)
                    assertEquals(2, packet[9].toInt())
                    assertEquals(0, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
                    assertEquals(0L, fields.getLong(6))
                }
                1 -> {
                    assertEquals("OpusTags", packet.copyOfRange(0, 8).toString(Charsets.US_ASCII))
                    assertEquals(0L, fields.getLong(6))
                }
                else -> {
                    assertEquals(160, bodyLength)
                    assertEquals((pageCount - 1) * 960L, fields.getLong(6))
                    assertEquals(if (pageCount == 368) 4 else 0, page[5].toInt())
                    assertArrayEquals(input.readBytes().copyOfRange((pageCount - 2) * 160, (pageCount - 1) * 160), packet)
                }
            }
            offset += pageLength; pageCount++
        }
        assertEquals(369, pageCount)
    }

    @Test fun invalidTocOrChangedHashCannotPublishContainer() {
        for (toc in listOf(0x4d, 0x48, 0x44)) { // multiple frames, mono, 10ms
            val input = fixture(1, toc)
            val output = File(temporary.root, "invalid-$toc.ogg")
            assertThrows(IllegalArgumentException::class.java) { D3200OpusContainer.wrap(input, output, 1, 160, input.sha256()) }
            assertFalse(output.exists())
        }
        val input = fixture(1)
        val output = File(temporary.root, "changed.ogg")
        assertThrows(IllegalArgumentException::class.java) { D3200OpusContainer.wrap(input, output, 1, 160, "0".repeat(64)) }
        assertFalse(output.exists())
        assertFalse(temporary.root.listFiles()!!.any { it.extension == "partial" })
    }

    @Test fun truncatedOrUnverifiedLengthAndCancellationLeaveNoOutput() {
        val input = fixture(2)
        val output = File(temporary.root, "out.ogg")
        assertThrows(IllegalArgumentException::class.java) { D3200OpusContainer.wrap(input, output, 3, 480, input.sha256()) }
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            D3200OpusContainer.wrap(input, output, 2, 320, input.sha256()) { if (++calls == 2) error("cancelled fixture") }
        }
        assertFalse(output.exists())
        assertFalse(temporary.root.listFiles()!!.any { it.extension == "partial" })
    }

    // Container-only synthetic data; not claimed to be a decodable speech fixture.
    private fun fixture(count: Int, toc: Int = 0x4c): File = File(temporary.root, "packet-$count-$toc.bin").apply {
        writeBytes(ByteArray(count * 160) { if (it % 160 == 0) toc.toByte() else (it % 251).toByte() })
    }

    // Independent bit-at-a-time polynomial implementation; production uses a lookup table.
    private fun referenceCrc(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            for (bit in 7 downTo 0) {
                val top = crc ushr 31
                crc = crc shl 1
                if (top xor ((byte.toInt() ushr bit) and 1) != 0) crc = crc xor 0x04c11db7
            }
        }
        return crc
    }
}
