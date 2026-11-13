package dev.soundsoftware.app.device

import android.content.Context
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.device.DeviceInfo
import dev.soundsoftware.core.device.DeviceInfoExtras
import dev.soundsoftware.core.device.DeviceRecording
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Debug builds only. When `files/simulated-device/enabled` exists the app talks to a simulated
 * recorder whose "device recordings" are 16 kHz mono WAV files named `<epochSeconds>.wav` pushed by
 * adb. It exercises the product flows on an emulator; it is never evidence of hardware interop.
 */
object DeviceLinkFactory {
    fun create(context: Context): DeviceLink {
        val dir = File(context.filesDir, "simulated-device")
        return if (File(dir, "enabled").isFile) SimulatedDeviceLink(dir) else BleDeviceClient(context)
    }
}

private class SimulatedDeviceLink(private val dir: File) : DeviceLink {
    override val transportName = "模拟蓝牙"
    private var recording = false
    private var connected = false
    private fun files() = dir.listFiles { f -> f.extension == "wav" && f.nameWithoutExtension.toLongOrNull() != null }.orEmpty().sortedBy { it.name }
    override fun startScan(onFound: (NearbyRecorder) -> Unit, onError: (String) -> Unit) {
        onFound(NearbyRecorder("00:00:00:00:D3:20", "soundcore Work（模拟）", -48, boundToOther = false, white = true))
    }
    override fun stopScan() {}
    override suspend fun connect(address: String, onDisconnected: (String) -> Unit) { delay(1200); connected = true }
    override suspend fun readInfo(): DeviceInfo {
        check(connected) { "设备未连接" }
        val used = files().sumOf { it.length() } / 1024
        return DeviceInfo(70, false, "04.92", "007f1d04cca3sim", 8L * 1024 * 1024, 8L * 1024 * 1024 - 3_200_000 - used, 60, true,
            DeviceInfoExtras("04.10", 0x10, true, 2, true, true, recording, "01.02"))
    }
    override suspend fun bind(onAwaitingPress: () -> Unit): BindOutcome { delay(1500); onAwaitingPress(); delay(2500); return BindOutcome.CONFIRMED }
    override suspend fun unbind() {}
    override suspend fun listFiles(): DeviceInventory = DeviceInventory(files().map { f ->
        DeviceRecording(f.nameWithoutExtension.toLong(), PcmWav.open(f).durationMs / 20 * 20)
    }, null)
    override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit): DownloadedAudioMetadata {
        val source = File(dir, "${recording.fileId}.wav")
        val total = recording.transferBytes
        var sent = 0L
        while (sent < total) { delay(120); sent = minOf(total, sent + 96_000); onProgress(TransferProgress(sent, total)) }
        withContext(Dispatchers.IO) { source.copyTo(target) }
        return DownloadedAudioMetadata(emptyList())
    }
    override suspend fun delete(fileId: Long) { File(dir, "$fileId.wav").delete() }
    override suspend fun setRecording(active: Boolean) { recording = active }
    override suspend fun setFindMy(enabled: Boolean) {}
    override suspend fun factoryReset() { files().forEach { it.delete() } }
    override fun close() { connected = false }
}
