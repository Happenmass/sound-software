package dev.soundsoftware.core.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DeviceFrame(val type: Int, val command: Int, val status: Int, val payload: ByteArray) {
    val successful: Boolean get() = status and 0x0f == 1
}

/** Independently implemented from docs/references/PROTOCOL.md, not application code. */
object D3200Protocol {
    const val SERVICE = "020cf5da-0000-1000-8000-00805f9b34fb"
    const val WRITE = "00007777-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "00008888-0000-1000-8000-00805f9b34fb"
    fun command(type: Int, command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(type in 0..255 && command in 0..255 && payload.size <= 65525)
        val packet = ByteArray(10 + payload.size)
        packet[0] = 8; packet[1] = 0xee.toByte()
        packet[5] = type.toByte(); packet[6] = command.toByte()
        packet[7] = packet.size.toByte(); packet[8] = (packet.size ushr 8).toByte()
        payload.copyInto(packet, 9)
        packet[packet.lastIndex] = checksum(packet, packet.lastIndex).toByte()
        return packet
    }
    fun checksum(bytes: ByteArray, endExclusive: Int = bytes.size): Int =
        bytes.take(endExclusive).sumOf { it.toInt() and 255 } and 255
    fun fileRequest(fileId: Long, transferredBytes: Long = 0): ByteArray {
        require(fileId in 0..0xffffffffL && transferredBytes in 0..0xffffffffL)
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(transferredBytes.toInt()).putInt(fileId.toInt()).put(0).array()
    }
    fun page(index: Int): ByteArray {
        require(index in 0..65535)
        return byteArrayOf(index.toByte(), (index ushr 8).toByte())
    }
}

/** Bounded framing buffer. Notifications may contain part of a packet or multiple packets. */
class DeviceFrameDecoder(private val maxFrameBytes: Int = 65535) {
    private var pending = byteArrayOf()
    init { require(maxFrameBytes in 10..65535) }
    @Synchronized fun reset() { pending = byteArrayOf() }
    @Synchronized fun accept(bytes: ByteArray): List<DeviceFrame> {
        val frames = mutableListOf<DeviceFrame>()
        // Slice the incoming buffer to bound allocation even for an adversarial notification.
        for (part in bytes.asList().chunked(maxFrameBytes)) {
            pending += part.toByteArray()
            var cursor = 0
            while (pending.size - cursor >= 2) {
                if (pending[cursor] != 9.toByte() || pending[cursor + 1] != 0xff.toByte()) { cursor++; continue }
                if (pending.size - cursor < 9) break
                val length = (pending[cursor + 7].toInt() and 255) or ((pending[cursor + 8].toInt() and 255) shl 8)
                if (length !in 10..maxFrameBytes) { cursor++; continue }
                if (pending.size - cursor < length) break
                val packet = pending.copyOfRange(cursor, cursor + length)
                if (D3200Protocol.checksum(packet, length - 1) != (packet.last().toInt() and 255)) { cursor++; continue }
                frames += DeviceFrame(packet[5].toInt() and 255, packet[6].toInt() and 255,
                    packet[4].toInt() and 255, packet.copyOfRange(9, length - 1))
                cursor += length
            }
            pending = pending.copyOfRange(cursor, pending.size)
            check(pending.size <= maxFrameBytes)
        }
        return frames
    }
}

data class DeviceInfo(val battery: Int, val charging: Boolean, val firmware: String, val serial: String,
    val totalKb: Long, val freeKb: Long, val caseBattery: Int, val caseCharging: Boolean,
    /** Read-only fields after the case battery; null when the firmware frame is shorter. */
    val extras: DeviceInfoExtras? = null)

data class DeviceInfoExtras(val caseFirmware: String, val color: Int, val autoPowerOff: Boolean, val autoPowerOffIndex: Int,
    val beanLight: Boolean, val caseLight: Boolean, val recording: Boolean, val wifiFirmware: String)

/**
 * 04.92 evidence: listValue 7340 = 7.340 s of 20 ms Opus packets (367 × 166 B framed transfer).
 * fileId values observed so far are UTC epoch seconds of the recording start.
 */
data class DeviceRecording(val fileId: Long, val listValue: Long, val endTime: Long? = null) {
    val durationMs: Long get() = listValue
    val transferBytes: Long get() = listValue / 20 * 166
    val startEpochMs: Long get() = fileId * 1000
}

/** Advertisement identity: manufacturer buffer = companyId (u16 LE) ‖ payload; flags at byte 6. */
data class DeviceAdvertisement(val mac: String?, val bound: Boolean, val white: Boolean) {
    companion object {
        fun parse(companyId: Int, payload: ByteArray): DeviceAdvertisement? {
            val buffer = byteArrayOf(companyId.toByte(), (companyId ushr 8).toByte()) + payload
            if (buffer.size < 7) return null
            val macBytes = buffer.copyOfRange(0, 6)
            val mac = macBytes.takeUnless { b -> b.all { it == b[0] } }?.joinToString(":") { "%02X".format(it.toInt() and 255) }
            val flags = buffer[6].toInt() and 255
            return DeviceAdvertisement(mac, flags and 0x80 != 0, flags and 0x70 == 0x10)
        }
    }
}
data class DeviceFilePage(val files: List<DeviceRecording>, val recordingFileId: Long, val recordingDuration: Int)
data class FileSlice(val sequence: Long, val flags: Int, val data: ByteArray)

object DevicePayloads {
    private fun ByteArray.u8(i: Int) = this[i].toInt() and 255
    private fun ByteArray.u32(i: Int) = ByteBuffer.wrap(this, i, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    fun battery(value: Int): Int = if (value in 0..9) (value + 1) * 10 else value.coerceIn(0, 100)
    fun info(data: ByteArray): DeviceInfo {
        require(data.size >= 39) { "设备信息帧过短" }
        fun ascii(start: Int, len: Int) = data.copyOfRange(start, start + len).toString(Charsets.US_ASCII).trim('\u0000', ' ')
        val extras = if (data.size >= 56) DeviceInfoExtras(ascii(33, 5), data.u8(45), data.u8(46) == 1, data.u8(47),
            data.u8(48) == 1, data.u8(49) == 1, data.u8(50) == 1, ascii(51, 5)) else null
        return DeviceInfo(battery(data.u8(1)), data.u8(2) == 1, ascii(3, 5), ascii(8, 16),
            data.u32(24), data.u32(28), battery(data.u8(38)), data.u8(32) == 1, extras)
    }
    fun files(data: ByteArray, withEndTime: Boolean = false): DeviceFilePage {
        require(data.size >= 10) { "文件清单过短" }
        val count = data.u8(0) or (data.u8(1) shl 8)
        val stride = if (withEndTime) 12 else 8
        require(data.size == 2 + count * stride + 8) { "文件清单布局不符合已知协议" }
        val files = (0 until count).map {
            val at = 2 + it * stride
            DeviceRecording(data.u32(at), data.u32(at + if (withEndTime) 8 else 4), if (withEndTime) data.u32(at + 4) else null)
        }
        val tail = 2 + count * stride
        return DeviceFilePage(files, data.u32(tail), data.u32(tail + 4).toInt())
    }
    fun slices(data: ByteArray): List<FileSlice> {
        require(data.isNotEmpty() && data.size % 166 == 0) { "未知分片布局，保留原始数据待设备验证" }
        return (data.indices step 166).map { FileSlice(data.u32(it), data.u8(it + 4), data.copyOfRange(it + 5, it + 165)) }
    }
}
