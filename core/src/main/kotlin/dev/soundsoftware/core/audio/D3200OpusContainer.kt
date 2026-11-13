package dev.soundsoftware.core.audio

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Independent Ogg encapsulation of the verified D3200 04.92 packet layout, not an arbitrary raw decoder.
 * RFC 6716 §3.1–3.2: TOC; RFC 7845 §3–5: OpusHead/Tags/granules; RFC 3533 §6: pages and CRC.
 * https://www.rfc-editor.org/rfc/rfc6716.html#section-3.1
 * https://www.rfc-editor.org/rfc/rfc7845.html#section-5
 * https://www.rfc-editor.org/rfc/rfc3533.html#section-6
 */
object D3200OpusContainer {
    const val PACKET_BYTES = 160
    const val SAMPLES_PER_PACKET_48K = 960
    const val SAMPLES_PER_PACKET_16K = 320
    const val PRE_SKIP = 0 // Encoder delay is unknown; preserve decoded samples without claiming exact acoustic onset.

    fun wrap(
        input: File,
        output: File,
        expectedPackets: Long,
        expectedPayloadBytes: Long,
        expectedSha256: String,
        checkCancelled: () -> Unit = {},
    ) {
        require(expectedPackets in 1..(256L * 1024 * 1024 / PACKET_BYTES)) { "Unsupported packet count" }
        require(expectedPayloadBytes == expectedPackets * PACKET_BYTES && input.length() == expectedPayloadBytes) { "Downloaded packet length differs from transfer evidence" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Missing verified input SHA-256" }
        require(input.isFile && input.canonicalFile != output.canonicalFile && !output.exists()) { "Output must be a new file" }
        output.absoluteFile.parentFile.mkdirs()
        val temporary = File.createTempFile("d3200-opus-", ".partial", output.absoluteFile.parentFile)
        val serial = UUID.randomUUID().leastSignificantBits.toInt()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            temporary.outputStream().use { fileSink ->
                val sink = fileSink.buffered()
                sink.write(page(opusHead(), serial, 0, 0, 2))
                sink.write(page(opusTags(), serial, 1, 0, 0))
                DataInputStream(input.inputStream().buffered()).use { source ->
                    val packet = ByteArray(PACKET_BYTES)
                    try {
                        for (index in 0 until expectedPackets) {
                            checkCancelled()
                            source.readFully(packet)
                            validatePacket(packet)
                            digest.update(packet)
                            val encoded = page(packet, serial, (index + 2).toInt(), (index + 1) * SAMPLES_PER_PACKET_48K,
                                if (index + 1 == expectedPackets) 4 else 0)
                            try { sink.write(encoded) } finally { encoded.fill(0) }
                        }
                        require(source.read() == -1) { "Input changed while encapsulating" }
                    } finally { packet.fill(0) }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(actualHash == expectedSha256) { "Downloaded audio hash changed" }
                sink.flush(); fileSink.fd.sync()
            }
            checkCancelled()
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    /** Only the observed one-frame, 20ms, stereo format is accepted; no stripping or padding guesses. */
    internal fun validatePacket(packet: ByteArray) {
        require(packet.size == PACKET_BYTES)
        val toc = packet[0].toInt() and 255
        val config = toc ushr 3
        require(toc and 3 == 0 && toc and 4 != 0) { "Expected single-frame stereo Opus packet" }
        val is20ms = when {
            config < 12 -> config and 3 == 1
            config < 16 -> config and 1 == 1
            else -> config and 3 == 3
        }
        require(is20ms) { "Expected 20ms Opus frame" }
    }

    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(2).putShort(PRE_SKIP.toShort())
        .putInt(48000) // Informational input-rate hint; granule clock is always 48kHz.
        .putShort(0).put(0).array() // 0dB gain, mapping family 0 (stereo).

    private fun opusTags(): ByteArray {
        val vendor = "sound-software-d3200".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII)).putInt(vendor.size).put(vendor).putInt(0).array()
    }

    // All packets in this constrained stream fit in one lacing entry and one page.
    private fun page(packet: ByteArray, serial: Int, sequence: Int, granule: Long, flags: Int): ByteArray {
        require(packet.size in 1..254)
        val data = ByteBuffer.allocate(28 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(flags.toByte()).putLong(granule)
            .putInt(serial).putInt(sequence).putInt(0).put(1).put(packet.size.toByte()).put(packet).array()
        var crc = 0
        data.forEach { crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (it.toInt() and 255)) and 255] }
        ByteBuffer.wrap(data, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        return data
    }

    private val CRC_TABLE = IntArray(256) { byte ->
        var value = byte shl 24
        repeat(8) { value = if (value < 0) (value shl 1) xor 0x04c11db7 else value shl 1 }
        value
    }
}
