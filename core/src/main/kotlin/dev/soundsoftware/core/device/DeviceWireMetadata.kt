package dev.soundsoftware.core.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit diagnostic projection: excludes audio, file keys, nonces and Wi-Fi credentials. */
data class DeviceWireMetadata(val type: Int, val command: Int, val status: Int, val payloadBytes: Int,
    val slices: List<SliceMetadata> = emptyList(), val recordingStatus: List<Int>? = null) {
    companion object {
        fun from(frame: DeviceFrame): DeviceWireMetadata {
            val bytes=frame.payload
            val slices=if(frame.type==0x1a && frame.command in listOf(8,0x12) && bytes.size%166==0) {
                (bytes.indices step 166).map {i->SliceMetadata(
                    ByteBuffer.wrap(bytes,i,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL,
                    bytes[i+4].toInt() and 255,bytes[i+165].toInt() and 255)}
            } else emptyList()
            val control=if((frame.type==0x1a && frame.command==6 || frame.type==0x18 && frame.command==0x82) && bytes.size<=64)
                bytes.map {it.toInt() and 255} else null
            return DeviceWireMetadata(frame.type,frame.command,frame.status,bytes.size,slices,control)
        }
    }
}
data class SliceMetadata(val sequence: Long, val flags: Int, val trailingByte: Int)
