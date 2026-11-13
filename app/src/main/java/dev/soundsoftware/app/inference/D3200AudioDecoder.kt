package dev.soundsoftware.app.inference

import dev.soundsoftware.core.audio.D3200OpusContainer
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.device.DeviceDownloadResult
import dev.soundsoftware.core.device.TransferSizeLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** IO-thread operation for a completed, verified download; never accepts arbitrary .bin files. */
class D3200AudioDecoder(private val checkCancelled: () -> Unit = {}) {
    fun decode(downloaded: DeviceDownloadResult, target: File): PcmWav {
        val evidence = downloaded.diagnostic
        require(evidence.stage == "complete" && evidence.sizeLayout == TransferSizeLayout.FRAMED_166_LIST_20.name) { "缺少已验证的 D3200 下载布局" }
        val packets = requireNotNull(evidence.expectedSlices)
        val bytes = requireNotNull(evidence.expectedPayloadBytes)
        require(evidence.contiguousBytes == bytes && evidence.headerBytes == packets * 166 && evidence.advertisedListValue == packets * 20) { "设备下载完整性证据不一致" }
        require(!target.exists() && target.canonicalFile != downloaded.decryptedFile.canonicalFile) { "解码目标必须是新文件" }
        target.absoluteFile.parentFile.mkdirs()
        val work = Files.createTempDirectory(target.absoluteFile.parentFile.toPath(), "d3200-decode-").toFile()
        try {
            val container = File(work, "audio.ogg")
            D3200OpusContainer.wrap(downloaded.decryptedFile, container, packets, bytes, downloaded.decryptedSha256, checkCancelled)
            val canonical = AndroidMediaAudioDecoder(checkCancelled).decodeToCanonicalWav(container, File(work, "audio.wav"))
            require(canonical.sampleRate == 16000 && canonical.channels == 1 && canonical.sampleCount == packets * D3200OpusContainer.SAMPLES_PER_PACKET_16K) { "系统解码样本数与已验证帧时长不一致" }
            checkCancelled()
            Files.move(canonical.file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return PcmWav.open(target)
        } finally { work.deleteRecursively() }
    }
}
