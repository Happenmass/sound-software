package dev.soundsoftware.app.device

import dev.soundsoftware.core.device.DeviceInfo
import dev.soundsoftware.core.device.DeviceRecording
import dev.soundsoftware.core.model.RecordingHighlight
import java.io.File

data class NearbyRecorder(val address: String, val name: String, val rssi: Int, val boundToOther: Boolean?, val white: Boolean?)

/** Byte progress of one transfer on the device wire. */
data class TransferProgress(val receivedBytes: Long, val totalBytes: Long, val stage: String? = null)
data class DownloadedAudioMetadata(val hardwareHighlights: List<RecordingHighlight>? = null)

enum class BindOutcome { CONFIRMED, NOT_CONFIRMED }
enum class SyncTransport(val label: String) { BLUETOOTH("蓝牙"), WIFI("Wi-Fi 快传") }

interface DeviceTransfer {
    suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit): DownloadedAudioMetadata
    suspend fun finish()
}

/**
 * Everything the product needs from a recorder. The BLE implementation is the verified path;
 * debug builds may substitute a simulated recorder for emulator testing.
 */
interface DeviceLink : AutoCloseable {
    val transportName: String
    val supportsWifi: Boolean get() = false
    /** One transfer session per batch. Wi-Fi implementations own hotspot/socket cleanup. */
    suspend fun beginTransfer(transport: SyncTransport, onStage: (String) -> Unit): DeviceTransfer {
        require(transport == SyncTransport.BLUETOOTH) { "此设备不支持 Wi-Fi 快传" }
        return object : DeviceTransfer {
            override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit) =
                this@DeviceLink.download(recording, target, onProgress)
            override suspend fun finish() {}
        }
    }
    fun startScan(onFound: (NearbyRecorder) -> Unit, onError: (String) -> Unit)
    fun stopScan()
    /** [onDisconnected] fires once if an established link drops later. */
    suspend fun connect(address: String, onDisconnected: (String) -> Unit)
    suspend fun readInfo(): DeviceInfo
    /** [onAwaitingPress] fires when the device appears to wait for a button press. */
    suspend fun bind(onAwaitingPress: () -> Unit): BindOutcome
    suspend fun unbind()
    suspend fun listFiles(): DeviceInventory
    /** Downloads, decrypts and decodes into a new canonical WAV at [target]. */
    suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit): DownloadedAudioMetadata
    suspend fun delete(fileId: Long)
    suspend fun setRecording(active: Boolean)
    suspend fun setFindMy(enabled: Boolean)
    suspend fun factoryReset()
    /** Unsolicited battery/charging pushes. */
    fun onStatusPush(listener: (battery: Int?, caseBattery: Int?, charging: Boolean?, caseCharging: Boolean?) -> Unit) {}
    /** A recording/file notification is an invalidation hint; read authoritative status/list. */
    fun onRecordingsChanged(listener: () -> Unit) {}
}

data class DeviceInventory(val files: List<DeviceRecording>, val activeRecordingFileId: Long?)
