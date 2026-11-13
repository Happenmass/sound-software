package dev.soundsoftware.core.device

import dev.soundsoftware.core.audio.sha256
import dev.soundsoftware.core.model.RecordingHighlight
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Contains only lengths, status and sequence metadata. Never contains wire payload or key material. */
data class DownloadDiagnostic(
    val stage: String,
    val handshakePayloadBytes: Int? = null,
    val headerPayloadBytes: Int? = null,
    val advertisedListValue: Long? = null,
    val headerBytes: Long? = null,
    val slicePackets: Int = 0,
    val slices: Long = 0,
    val firstSequence: Long? = null,
    val maximumSequence: Long? = null,
    val contiguousBytes: Long = 0,
    val lastCommand: Int? = null,
    val lastStatus: Int? = null,
    val lastPayloadBytes: Int? = null,
    val sliceFlags: Set<Int> = emptySet(),
    val slicePadding: Set<Int> = emptySet(),
    val cryptoHandshake: CryptoHandshakeDiagnostic? = null,
    val negotiatedMtu: Int? = null,
    val headerFileIdMatches: Boolean? = null,
    val headerErrorCode: Int? = null,
    val sizeLayout: String? = null,
    val expectedSlices: Long? = null,
    val expectedPayloadBytes: Long? = null,
)

data class FileHeaderMetadata(val declaredBytes: Long?, val fileIdMatches: Boolean?, val errorCode: Int?)

/** 04.92 evidence: listValue=7340, header=60922=367*166; unit semantics beyond this relation remain unclaimed. */
enum class TransferSizeLayout { FRAMED_166_LIST_20, DIRECT_PAYLOAD_BYTES }

data class TransferLength(val layout: TransferSizeLayout, val listValue: Long, val headerBytes: Long,
    val expectedSlices: Long, val payloadBytes: Long) {
    companion object {
        fun resolve(listValue: Long, headerBytes: Long, layout: TransferSizeLayout): TransferLength {
            require(listValue in 1..0xffffffffL && headerBytes in 1..0xffffffffL) { "文件长度字段无效" }
            return when (layout) {
                TransferSizeLayout.FRAMED_166_LIST_20 -> {
                    require(headerBytes % 166 == 0L && headerBytes / 166 * 20 == listValue) { "文件头不符合已验证的 166/20 长度关系" }
                    val count = headerBytes / 166
                    TransferLength(layout, listValue, headerBytes, count, count * 160)
                }
                TransferSizeLayout.DIRECT_PAYLOAD_BYTES -> {
                    require(headerBytes == listValue) { "文件大小与清单不一致，请刷新后重试" }
                    TransferLength(layout, listValue, headerBytes, (headerBytes + 159) / 160, headerBytes)
                }
            }
        }
    }
}

class DeviceDownloadException(message: String, val diagnostic: DownloadDiagnostic) : IOException(message)

data class DeviceDownloadResult(
    val encryptedFile: File,
    val decryptedFile: File,
    val encryptedSha256: String,
    val decryptedSha256: String,
    val diagnostic: DownloadDiagnostic,
    // AES-CTR is unauthenticated. Transport checksum/coverage does not prove audio decodability.
    val integrity: String = "frame-checksum+zero-based-sequence-coverage+declared-length",
    val playable: Boolean = false,
    val decoded: Boolean = false,
    val hardwareHighlights: List<RecordingHighlight>? = null,
)

/** Encrypted header layout from protocol §5.6. Secrets are never data-class properties or logged. */
class EncryptedFileHeader private constructor(
    val fileId: Long,
    val size: Long,
    val length: TransferLength,
    private val nonce: ByteArray,
    private val encryptedKey: ByteArray,
    private val sessionNonce: ByteArray,
) : AutoCloseable {
    fun unlock(session: DeviceCrypto): ByteArray = session.fileKey(encryptedKey, sessionNonce)
    fun decrypt(key: ByteArray, slice: FileSlice): ByteArray = DeviceCrypto.decryptSlice(key, nonce, slice.sequence, slice.data)
    override fun close() { nonce.fill(0); encryptedKey.fill(0); sessionNonce.fill(0) }

    companion object {
        /** Inspect known offsets before validation so a rejected header remains diagnosable without secrets. */
        fun inspect(frame: DeviceFrame, expected: DeviceRecording): FileHeaderMetadata {
            val data = frame.payload
            if (frame.type != 0x1a || frame.command != 7 || data.size < 8) return FileHeaderMetadata(null, null, null)
            fun u32(offset: Int) = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            return FileHeaderMetadata(u32(4), u32(0) == expected.fileId,
                if (data.size == 87) data[86].toInt() and 255 else null)
        }
        fun parse(frame: DeviceFrame, expected: DeviceRecording,
            sizeLayout: TransferSizeLayout = TransferSizeLayout.FRAMED_166_LIST_20): EncryptedFileHeader {
            require(frame.type == 0x1a && frame.command == 7 && frame.successful) { "设备拒绝文件头请求" }
            val data = frame.payload
            require(data.size == 87) { "未知加密文件头布局：${data.size} bytes" }
            fun u32(offset: Int) = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            require(data[86].toInt() and 255 == 0) { "设备文件头错误码：${data[86].toInt() and 255}" }
            require(u32(0) == expected.fileId) { "文件头 ID 与请求不一致" }
            val length = TransferLength.resolve(expected.listValue, u32(4), sizeLayout)
            return EncryptedFileHeader(u32(0), u32(4), length, data.copyOfRange(8, 24), data.copyOfRange(24, 70), data.copyOfRange(70, 86))
        }
    }
}

/** One bounded, fresh download. Resume/sequence-base heuristics are intentionally not implicit. */
class DeviceDownloadAssembler(
    private val directory: File,
    private val header: EncryptedFileHeader,
    session: DeviceCrypto,
    private val initial: DownloadDiagnostic,
    readHardwareHighlights: Boolean = false,
) : AutoCloseable {
    private val key: ByteArray
    private val encryptedPartial = File(directory, "encrypted.partial")
    private val decryptedPartial = File(directory, "decrypted.partial")
    private val encrypted: SliceCollector
    private val decrypted: SliceCollector
    private var done = false
    private var closed = false
    private var packets = 0
    private var slices = 0L
    private var first: Long? = null
    private var maximum: Long? = null
    private var lastCommand: Int? = null
    private var lastStatus: Int? = null
    private var lastSize: Int? = null
    private val flags = mutableSetOf<Int>()
    private val padding = mutableSetOf<Int>()
    // Firmware 04.92: bit 0 in each 166-byte unit marks that unit's 20ms position.
    private val highlights = if(readHardwareHighlights && header.length.layout==TransferSizeLayout.FRAMED_166_LIST_20)
        sortedMapOf<Long,RecordingHighlight>() else null

    init {
        require(header.length.payloadBytes in 1..256L * 1024 * 1024) { "首版 BLE 下载限制为 256 MiB" }
        require(directory.isDirectory || directory.mkdirs())
        require(!encryptedPartial.exists() && !decryptedPartial.exists()) { "下载目录已被使用" }
        key = header.unlock(session)
        var opened: SliceCollector? = null
        try {
            encrypted = SliceCollector(encryptedPartial, header.length.payloadBytes).also { opened = it }
            decrypted = SliceCollector(decryptedPartial, header.length.payloadBytes)
        } catch (error: Throwable) {
            try { opened?.close() } finally { key.fill(0); header.close(); directory.deleteRecursively() }
            throw error
        }
    }

    fun diagnostic(stage: String = "receiving") = initial.copy(
        stage = stage, headerBytes = header.size, slicePackets = packets, slices = slices,
        firstSequence = first, maximumSequence = maximum, contiguousBytes = decrypted.contiguousBytes,
        lastCommand = lastCommand, lastStatus = lastStatus, lastPayloadBytes = lastSize,
        sliceFlags = flags.toSet(), slicePadding = padding.toSet(),
        sizeLayout = header.length.layout.name, expectedSlices = header.length.expectedSlices,
        expectedPayloadBytes = header.length.payloadBytes,
    )

    fun accept(frame: DeviceFrame): DeviceDownloadResult? {
        check(!closed && !done)
        lastCommand = frame.command; lastStatus = frame.status; lastSize = frame.payload.size
        require(frame.type == 0x1a && frame.successful) { "传输帧状态失败" }
        when (frame.command) {
            8, 0x12 -> {
                packets++
                val parsed = DevicePayloads.slices(frame.payload)
                try {
                    for ((index, slice) in parsed.withIndex()) {
                        if (first == null) first = slice.sequence
                        maximum = maxOf(maximum ?: slice.sequence, slice.sequence)
                        flags += slice.flags; padding += frame.payload[index * 166 + 165].toInt() and 255
                        slices++
                        // A missing seq=0 remains a gap; never infer seq=1 or truncate a missing first unit.
                        encrypted.accept(slice.sequence, slice.data)
                        val plain = header.decrypt(key, slice)
                        try { decrypted.accept(slice.sequence, plain) } finally { plain.fill(0) }
                        if(slice.flags and 1 != 0) highlights?.put(slice.sequence,RecordingHighlight("device-${slice.sequence}",slice.sequence*20))
                    }
                } finally { parsed.forEach { it.data.fill(0) } }
                return null
            }
            0x0a -> {
                require(frame.payload.size == 9) { "未知传输完成帧布局：${frame.payload.size} bytes" }
                // Firmware 04.92 emits 9 bytes. Their field meanings are not inferred here.
                encrypted.finish(); decrypted.finish()
                try { encrypted.close() } finally { decrypted.close(); closed = true }
                val encryptedFile = File(directory, "encrypted.bin")
                val decryptedFile = File(directory, "device-audio.bin")
                Files.move(encryptedPartial.toPath(), encryptedFile.toPath())
                Files.move(decryptedPartial.toPath(), decryptedFile.toPath())
                val result = DeviceDownloadResult(encryptedFile, decryptedFile, encryptedFile.sha256(), decryptedFile.sha256(), diagnostic("complete"),hardwareHighlights=highlights?.values?.toList())
                done = true
                return result
            }
            else -> error("未知传输帧命令：${frame.command}")
        }
    }

    override fun close() {
        try {
            if (!closed) { try { encrypted.close() } finally { decrypted.close(); closed = true } }
        } finally {
            key.fill(0); header.close()
            if (!done) directory.deleteRecursively()
        }
    }
}
