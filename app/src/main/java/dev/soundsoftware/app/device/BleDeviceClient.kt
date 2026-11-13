package dev.soundsoftware.app.device

import dev.soundsoftware.app.R
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.soundsoftware.app.data.SecretStore
import dev.soundsoftware.app.inference.D3200AudioDecoder
import dev.soundsoftware.core.device.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** D3200 over BLE GATT. The owner must hold Android Bluetooth permissions before calling. */
@SuppressLint("MissingPermission")
class BleDeviceClient(private val context: Context) : DeviceLink {
    /** Opt-in metadata only; production transfers do not allocate/write per-frame diagnostics. */
    var onWifiDiagnostic: ((Map<String, Any>) -> Unit)? = null
    /** Opt-in safe projection for firmware validation, never raw payloads or audio. */
    var onProtocolMetadata: ((DeviceWireMetadata) -> Unit)? = null
    override val transportName get() = context.getString(R.string.bluetooth)
    override val supportsWifi: Boolean get() = Build.VERSION.SDK_INT >= 29
    @Volatile private var wifiCleanupPending = false
    @Volatile private var knownFirmware: String? = null
    @Volatile private var knownSerial: String? = null
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private val decoder = DeviceFrameDecoder()
    private val commands = Mutex()
    private var connection = CompletableDeferred<Unit>()
    private var writeAck: CompletableDeferred<Unit>? = null
    private class Waiter(val type: Int, val commands: Set<Int>, val result: CompletableDeferred<DeviceFrame>)
    @Volatile private var pending: Waiter? = null
    @Volatile private var bindingConfirmation: DeviceBindingConfirmation? = null
    @Volatile private var mtu = 23
    @Volatile private var onDisconnected: ((String) -> Unit)? = null
    @Volatile private var statusListener: ((Int?, Int?, Boolean?, Boolean?) -> Unit)? = null
    @Volatile private var recordingsChanged: (() -> Unit)? = null
    private var scanFound: ((NearbyRecorder) -> Unit)? = null
    private var scanError: ((String) -> Unit)? = null
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val data = result.scanRecord?.manufacturerSpecificData
            val ad = if (data != null && data.size() > 0) DeviceAdvertisement.parse(data.keyAt(0), data.valueAt(0)) else null
            scanFound?.invoke(NearbyRecorder(result.device.address, result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: "soundcore Work",
                result.rssi, ad?.bound, ad?.white))
        }
        override fun onScanFailed(errorCode: Int) { scanError?.invoke(context.getString(R.string.scan_failed_code, errorCode)) }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (gatt !== g) return
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) fail(context.getString(R.string.device_services_missing))
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                fail(context.getString(R.string.device_disconnected_code, status))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(context.getString(R.string.service_discovery_failed, status)); return }
            val service = g.getService(UUID.fromString(D3200Protocol.SERVICE))
            writer = service?.getCharacteristic(UUID.fromString(D3200Protocol.WRITE))
            val notify = service?.getCharacteristic(UUID.fromString(D3200Protocol.NOTIFY))
            if (writer == null || notify == null) { fail(context.getString(R.string.unknown_device_service)); return }
            val cccd = notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd == null || !g.setCharacteristicNotification(notify, true)) { fail(context.getString(R.string.cannot_subscribe)); return }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!started) fail(context.getString(R.string.subscription_not_sent))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(context.getString(R.string.subscription_failed, status)); return }
            // ECDH command is 75 bytes. Do not assume the firmware reassembles multiple ATT writes.
            if (!g.requestMtu(247)) fail(context.getString(R.string.ble_mtu_failed))
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS || value < 78) {
                fail(context.getString(R.string.ble_mtu_small, value, status))
                return
            }
            mtu = value
            connection.complete(Unit)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== g) return
            if (status == BluetoothGatt.GATT_SUCCESS) writeAck?.complete(Unit)
            else writeAck?.completeExceptionally(IllegalStateException(context.getString(R.string.device_write_failed, status)))
        }
        @Deprecated("Used on Android before API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) receive(g, c.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = receive(g, value)
    }
    @Volatile private var transferFrames: Channel<DeviceFrame>? = null
    private fun receive(g: BluetoothGatt, bytes: ByteArray) {
        if (gatt !== g) return
        for (frame in decoder.accept(bytes)) {
            onProtocolMetadata?.invoke(DeviceWireMetadata.from(frame))
            if (bindingConfirmation?.accept(frame) == true) { frame.payload.fill(0); continue }
            val waiter = pending
            if (waiter != null && waiter.type == frame.type && frame.command in waiter.commands) {
                if (!waiter.result.complete(frame)) frame.payload.fill(0)
            }
            else if (frame.type == 1 && frame.command == 3 && frame.payload.size >= 2) {
                statusListener?.invoke(DevicePayloads.battery(frame.payload[0].toInt() and 255), DevicePayloads.battery(frame.payload[1].toInt() and 255), null, null)
            }
            else if (frame.type == 1 && frame.command == 4 && frame.payload.size >= 2) {
                statusListener?.invoke(null, null, frame.payload[0].toInt() == 1, frame.payload[1].toInt() == 1)
            }
            else if ((frame.type == 0x1a && frame.command == 6) || (frame.type == 0x18 && frame.command == 0x82)) {
                // Do not guess a firmware-specific notification body. Re-read status and inventory.
                recordingsChanged?.invoke()
                frame.payload.fill(0)
            }
            else if (frame.type == 0x1a && frame.command in listOf(8, 0x12, 0x0a)) {
                val channel = transferFrames
                if (channel == null) frame.payload.fill(0)
                else if (channel.trySend(frame).isFailure) {
                    frame.payload.fill(0)
                    channel.close(IllegalStateException(context.getString(R.string.transfer_buffer_full)))
                }
            } else frame.payload.fill(0)
        }
    }
    override fun startScan(onFound: (NearbyRecorder) -> Unit, onError: (String) -> Unit) {
        val adapter = manager?.adapter ?: error(context.getString(R.string.no_bluetooth))
        check(adapter.isEnabled) { context.getString(R.string.turn_on_bluetooth) }
        scanFound = onFound; scanError = onError
        adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(D3200Protocol.SERVICE))).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }
    override fun stopScan() { runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } }
    override fun onStatusPush(listener: (Int?, Int?, Boolean?, Boolean?) -> Unit) { statusListener = listener }
    override fun onRecordingsChanged(listener: () -> Unit) { recordingsChanged = listener }
    override suspend fun connect(address: String, onDisconnected: (String) -> Unit) {
        close(); decoder.reset(); connection = CompletableDeferred(); mtu = 23
        val device = manager?.adapter?.getRemoteDevice(address) ?: error(context.getString(R.string.bluetooth_unavailable))
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try { withTimeout(20000) { connection.await() } } catch (e: Exception) { close(); throw e }
        this.onDisconnected = onDisconnected
        if (wifiCleanupPending) stopWifi()
    }

    private suspend fun stopWifi() {
        if (!wifiCleanupPending || gatt == null) return
        check(request(0x1a, 2, timeoutMs = 4000).successful) { context.getString(R.string.wifi_exit_failed) }
        wifiCleanupPending = false
    }

    override suspend fun beginTransfer(transport: SyncTransport, onStage: (String) -> Unit): DeviceTransfer {
        if (transport == SyncTransport.BLUETOOTH) return super.beginTransfer(transport, onStage)
        check(supportsWifi) { context.getString(R.string.wifi_os_required) }
        val readHardwareHighlights=knownFirmware=="04.92"
        val serial = knownSerial
        val session = DeviceCrypto()
        val lease = RecorderWifiNetwork(context)
        var socket: DeviceWifiSocket? = null
        var networkClient: okhttp3.OkHttpClient? = null
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            lease.checkPermissionAndWifi()
            onStage(context.getString(R.string.starting_hotspot))
            val profiles = withContext(Dispatchers.IO) { hotspotProfiles(serial) }
            val endpoint = commands.withLock {
                val handshake = requestLocked(0x2e, 1, session.publicKey())
                try {
                    check(handshake.successful && handshake.payload.size == 97) { context.getString(R.string.unknown_handshake_reply) }
                    val public = handshake.payload.copyOfRange(0, 65)
                    val check = handshake.payload.copyOfRange(65, 97)
                    try { session.establish(public, check) } finally { public.fill(0); check.fill(0) }
                } finally { handshake.payload.fill(0) }
                wifiCleanupPending = true
                var profile = profiles.first()
                var response = configureHotspot(profile)
                // 04.92 can acknowledge reusing the previous name without opening the hotspot.
                if (response.successful && response.payload.isEmpty() && profiles.size == 2) {
                    profile = profiles[1]
                    response = configureHotspot(profile)
                }
                try { DeviceWifiProtocol.endpoint(response) to profile } finally { response.payload.fill(0) }
            }
            val (address, profile) = endpoint
            onStage(context.getString(R.string.allow_hotspot))
            val network = lease.join(profile.ssid, profile.password)
            lease.checkAvailable()
            onStage(context.getString(R.string.connecting_wifi_transfer))
            val client = lease.client(network, address).also { networkClient = it }
            val connection = DeviceWifiSocket(client, "wss://${address.host}:${address.port}", onWifiDiagnostic).also { socket = it }
            lease.onLost = {
                onWifiDiagnostic?.invoke(mapOf("stage" to "network-lost"))
                connection.close()
            }
            lease.checkAvailable()
            connection.open()
            connection.send("ping")
            workers.launch {
                try { lease.keepAlive(network, address.host) { connection.pingIfIdle() } }
                catch (e: Exception) {
                    if (isActive) {
                        val causes = generateSequence<Throwable>(e) { it.cause }.take(5).joinToString(" / ") {
                            it.javaClass.simpleName + if (it is android.system.ErrnoException) ":errno=${it.errno}" else ""
                        }
                        connection.abort(context.getString(R.string.wifi_keepalive_failed, causes))
                    }
                }
            }
            onStage(context.getString(R.string.wifi_transfer_connected))
            return object : DeviceTransfer {
                override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit): DownloadedAudioMetadata {
                    lease.checkAvailable()
                    val directory = File(context.filesDir, "device-downloads/${UUID.randomUUID()}")
                    val result = connection.download(recording, session, directory,readHardwareHighlights=readHardwareHighlights) { bytes ->
                        onProgress(TransferProgress(bytes * 166 / 160, recording.transferBytes))
                    }
                    try {
                        onProgress(TransferProgress(recording.transferBytes, recording.transferBytes, context.getString(R.string.decoding_audio)))
                        withContext(Dispatchers.IO) {
                            val coroutine = currentCoroutineContext()
                            D3200AudioDecoder { coroutine.ensureActive() }.decode(result, target)
                        }
                        return DownloadedAudioMetadata(result.hardwareHighlights)
                    } finally { directory.deleteRecursively() }
                }
                override suspend fun finish() {
                    try {
                        workers.coroutineContext[Job]?.cancelAndJoin()
                        connection.finish()
                    }
                    finally {
                        workers.cancel(); lease.close(); session.close()
                        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                        if (gatt != null) stopWifi()
                    }
                }
            }
        } catch (e: Exception) {
            workers.cancel(); socket?.close(); lease.close(); session.close()
            networkClient?.connectionPool?.evictAll(); networkClient?.dispatcher?.executorService?.shutdown()
            withContext(NonCancellable) { runCatching { stopWifi() } }
            throw lease.explainFailure(e)
        }
    }
    suspend fun request(type: Int, command: Int, payload: ByteArray = byteArrayOf(), replies: Set<Int> = setOf(command), timeoutMs: Long = 12000,
        onSlow: (() -> Unit)? = null): DeviceFrame = commands.withLock {
        requestLocked(type, command, payload, replies, timeoutMs, onSlow)
    }
    private suspend fun requestLocked(type: Int, command: Int, payload: ByteArray = byteArrayOf(), replies: Set<Int> = setOf(command),
        timeoutMs: Long = 12000, onSlow: (() -> Unit)? = null): DeviceFrame {
        val live = gatt ?: error(context.getString(R.string.device_disconnected_error))
        val characteristic = writer ?: error(context.getString(R.string.device_not_ready))
        val response = CompletableDeferred<DeviceFrame>()
        pending = Waiter(type, replies, response)
        return try {
            withTimeout(timeoutMs) {
                for (chunk in D3200Protocol.command(type, command, payload).asList().chunked(mtu - 3)) {
                    val ack = CompletableDeferred<Unit>(); writeAck = ack
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        live.writeCharacteristic(characteristic, chunk.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk.toByteArray()
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        live.writeCharacteristic(characteristic)
                    }
                    check(started) { context.getString(R.string.device_busy) }; ack.await()
                }
                if (onSlow == null) return@withTimeout response.await()
                withTimeoutOrNull(1500) { response.await() } ?: run { onSlow(); response.await() }
            }
        } catch (e: Exception) {
            // No request identifier on wire: reconnect after timeout/cancellation so a late reply cannot satisfy a later request.
            close(); throw e
        } finally { pending = null; writeAck = null; delay(500) }
    }
    override suspend fun readInfo(): DeviceInfo {
        val frame = request(1, 1); check(frame.successful) { context.getString(R.string.device_info_denied) }
        return DevicePayloads.info(frame.payload).also {
            knownFirmware = it.firmware
            knownSerial = it.serial
        }
    }

    private class HotspotProfile(val ssid: String, val password: String)

    /** Two stable, encrypted profiles so Android can remember approval for each name. */
    private fun hotspotProfiles(serial: String?): List<HotspotProfile> {
        fun newName() = "WiFi-" + UUID.randomUUID().toString().replace("-", "").take(12)
        fun newPassword() = UUID.randomUUID().toString().replace("-", "").take(24)
        if (serial.isNullOrBlank()) return listOf(HotspotProfile(newName(), newPassword()))

        val secrets = SecretStore(context)
        val key = "recorder-hotspot"
        val saved = secrets.get(key)?.split('\n')?.takeIf { it.firstOrNull() == serial }
        if (saved?.size == 5) {
            return listOf(HotspotProfile(saved[1], saved[2]), HotspotProfile(saved[3], saved[4]))
        }
        val primary = HotspotProfile(saved?.getOrNull(1) ?: newName(), saved?.getOrNull(2) ?: newPassword())
        val backup = HotspotProfile(newName(), newPassword())
        secrets.put(key, listOf(serial, primary.ssid, primary.password, backup.ssid, backup.password).joinToString("\n"))
        return listOf(primary, backup)
    }

    private suspend fun configureHotspot(profile: HotspotProfile): DeviceFrame {
        val credentials = DeviceWifiProtocol.credentials(profile.ssid, profile.password)
        return try { requestLocked(0x1a, 5, credentials, timeoutMs = 20_000) }
        finally { credentials.fill(0) }
    }
    /** The request ACK is not binding completion: keep the 0x88 device confirmation subscribed. */
    override suspend fun bind(onAwaitingPress: () -> Unit): BindOutcome = commands.withLock {
        val session = DeviceBindingConfirmation()
        bindingConfirmation = session
        try {
            withTimeout(60_000) {
                val ack = requestLocked(0x0b, 0x87, byteArrayOf(1), setOf(0x87), 30_000, onAwaitingPress)
                if (session.awaitAfter(ack, onAwaitingPress)) BindOutcome.CONFIRMED else BindOutcome.NOT_CONFIRMED
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            close()
            BindOutcome.NOT_CONFIRMED
        } finally { if (bindingConfirmation === session) bindingConfirmation = null }
    }
    override suspend fun unbind() { check(request(0x0b, 0x87, byteArrayOf(0)).successful) { context.getString(R.string.device_unpair_denied) } }
    override suspend fun listFiles(): DeviceInventory {
        val result = linkedMapOf<Long, DeviceRecording>()
        var active: Long? = null
        for (page in 0..255) {
            val frame = request(0x1a, 0x0e, D3200Protocol.page(page)); check(frame.successful) { context.getString(R.string.device_inventory_denied) }
            val parsed = DevicePayloads.files(frame.payload)
            if (parsed.recordingFileId != 0L) active = parsed.recordingFileId
            if (parsed.files.isEmpty()) return DeviceInventory(result.values.toList(), active)
            val newFiles = parsed.files.filter { it.fileId != parsed.recordingFileId && it.fileId !in result }
            if (newFiles.isEmpty()) return DeviceInventory(result.values.toList(), active)
            newFiles.forEach { result[it.fileId] = it }
        }
        error(context.getString(R.string.inventory_incomplete))
    }
    override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit): DownloadedAudioMetadata {
        val downloaded = downloadEncrypted(recording) { d ->
            onProgress(TransferProgress(d.contiguousBytes * 166 / 160, recording.transferBytes))
        }
        try {
            onProgress(TransferProgress(recording.transferBytes, recording.transferBytes, context.getString(R.string.decoding_audio)))
            withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                D3200AudioDecoder { coroutine.ensureActive() }.decode(downloaded, target)
            }
            return DownloadedAudioMetadata(downloaded.hardwareHighlights)
        } finally { downloaded.decryptedFile.parentFile?.deleteRecursively() }
    }
    override suspend fun delete(fileId: Long) {
        val id = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileId.toInt()).array()
        check(request(0x1a, 0x10, id).successful) { context.getString(R.string.device_delete_denied) }
    }
    override suspend fun setRecording(active: Boolean) {
        check(request(0x18, 0x82, byteArrayOf(if (active) 1 else 2)).successful) { if (active) context.getString(R.string.device_start_denied) else context.getString(R.string.device_stop_denied) }
    }
    override suspend fun setFindMy(enabled: Boolean) {
        check(request(0x10, 0xa2, byteArrayOf(if (enabled) 1 else 0)).successful) { context.getString(R.string.find_device_denied) }
    }
    override suspend fun factoryReset() { check(request(0x01, 0xb8).successful) { context.getString(R.string.device_reset_denied) } }

    /** Direct BLE attempt. Does not switch transport, bind, delete or alter any recording. */
    suspend fun downloadEncrypted(
        recording: DeviceRecording,
        onDiagnostic: (DownloadDiagnostic) -> Unit = {},
    ): DeviceDownloadResult = commands.withLock {
        withContext(Dispatchers.IO) {
            var diagnostic = DownloadDiagnostic("handshake", advertisedListValue = recording.listValue, negotiatedMtu = mtu)
            val directory = File(context.filesDir, "device-downloads/${UUID.randomUUID()}")
            var completed = false
            var assembler: DeviceDownloadAssembler? = null
            // Covers observed BLE notification bursts while storage/crypto briefly yields.
            val channel = Channel<DeviceFrame>(1024, onUndeliveredElement = { it.payload.fill(0) })
            try {
                require(recording.listValue in 1..0xffffffffL) { context.getString(R.string.invalid_inventory_length) }
                onDiagnostic(diagnostic)
                DeviceCrypto().use { session ->
                    val handshake = requestLocked(0x2e, 1, session.publicKey())
                    diagnostic = diagnostic.copy(handshakePayloadBytes = handshake.payload.size,
                        lastCommand = handshake.command, lastStatus = handshake.status, lastPayloadBytes = handshake.payload.size)
                    onDiagnostic(diagnostic)
                    try {
                        check(handshake.successful && handshake.payload.size == 97) { context.getString(R.string.invalid_handshake_response) }
                        val devicePublic = handshake.payload.copyOfRange(0, 65)
                        val deviceCheck = handshake.payload.copyOfRange(65, 97)
                        try {
                            session.establish(devicePublic, deviceCheck) { crypto ->
                                diagnostic = diagnostic.copy(cryptoHandshake = crypto)
                                onDiagnostic(diagnostic)
                            }
                        } finally { devicePublic.fill(0); deviceCheck.fill(0) }
                    } finally { handshake.payload.fill(0) }
                    diagnostic = diagnostic.copy(stage = "requesting-header")
                    onDiagnostic(diagnostic)
                    transferFrames = channel
                    val head = requestLocked(0x1a, 7, D3200Protocol.fileRequest(recording.fileId))
                    val metadata = EncryptedFileHeader.inspect(head, recording)
                    diagnostic = diagnostic.copy(stage = "validating-header", headerPayloadBytes = head.payload.size,
                        headerBytes = metadata.declaredBytes, headerFileIdMatches = metadata.fileIdMatches, headerErrorCode = metadata.errorCode,
                        lastCommand = head.command, lastStatus = head.status, lastPayloadBytes = head.payload.size)
                    onDiagnostic(diagnostic)
                    val header = try { EncryptedFileHeader.parse(head, recording) } finally { head.payload.fill(0) }
                    diagnostic = diagnostic.copy(sizeLayout = header.length.layout.name,
                        expectedSlices = header.length.expectedSlices, expectedPayloadBytes = header.length.payloadBytes)
                    onDiagnostic(diagnostic)
                    try {
                        assembler = DeviceDownloadAssembler(directory, header, session, diagnostic,readHardwareHighlights=knownFirmware=="04.92")
                    } catch (e: Exception) { header.close(); throw e }
                    val receiving = requireNotNull(assembler)
                    withTimeout(maxOf(5 * 60 * 1000L, recording.transferBytes / 2)) downloadWindow@ {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val frame = withTimeout(15000) { channel.receive() }
                            try {
                                val result = receiving.accept(frame)
                                diagnostic = receiving.diagnostic(if (result == null) "receiving" else "complete")
                                onDiagnostic(diagnostic)
                                if (result != null) { completed = true; return@downloadWindow result }
                            } finally { frame.payload.fill(0) }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        error(context.getString(R.string.transfer_not_completed))
                    }
                }
            } catch (e: TimeoutCancellationException) {
                diagnostic = assembler?.diagnostic("timeout") ?: diagnostic.copy(stage = "timeout")
                onDiagnostic(diagnostic)
                close()
                throw DeviceDownloadException(context.getString(R.string.device_download_timeout), diagnostic)
            } catch (e: CancellationException) {
                diagnostic = assembler?.diagnostic("cancelled") ?: diagnostic.copy(stage = "cancelled")
                onDiagnostic(diagnostic)
                close(); throw e
            } catch (e: Exception) {
                diagnostic = assembler?.diagnostic("failed") ?: diagnostic.copy(stage = "failed")
                onDiagnostic(diagnostic)
                close()
                throw DeviceDownloadException(e.message ?: context.getString(R.string.device_download_failed), diagnostic)
            } finally {
                transferFrames = null
                channel.cancel()
                assembler?.close()
                if (!completed) directory.deleteRecursively()
            }
        }
    }
    private fun fail(message: String) {
        val error = IllegalStateException(message)
        val established = connection.isCompleted && !connection.isCancelled && runCatching { connection.getCompleted() }.isSuccess
        connection.completeExceptionally(error); pending?.result?.completeExceptionally(error); writeAck?.completeExceptionally(error)
        transferFrames?.close(error)
        val listener = onDisconnected
        close(); if (established) listener?.invoke(message)
    }
    override fun close() {
        knownFirmware=null
        knownSerial=null
        stopScan()
        val closed = IllegalStateException(context.getString(R.string.device_connection_closed))
        bindingConfirmation?.fail(closed); bindingConfirmation = null
        connection.completeExceptionally(closed)
        pending?.result?.completeExceptionally(closed)
        onDisconnected = null
        writeAck?.completeExceptionally(closed)
        transferFrames?.close(closed); transferFrames = null
        val previous = gatt; gatt = null; writer = null
        runCatching { previous?.disconnect(); previous?.close() }
        decoder.reset()
    }
}
