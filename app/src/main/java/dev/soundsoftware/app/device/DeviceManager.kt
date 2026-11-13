package dev.soundsoftware.app.device

import dev.soundsoftware.app.R
import dev.soundsoftware.app.data.BoundDevice
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import dev.soundsoftware.core.device.DeviceInfo
import dev.soundsoftware.core.device.DeviceRecording
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.RecordingSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LinkPhase { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }
enum class DiscoveryPhase { FOUND, CONNECTING, CONFIRM, DONE }
enum class SyncItemStatus { QUEUED, DOWNLOADING, DONE, FAILED }

data class SyncItem(val fileId: Long, val startMs: Long, val durationMs: Long, val bytes: Long,
    val status: SyncItemStatus = SyncItemStatus.QUEUED, val progress: Float = 0f, val recordingId: String? = null,
    val message: String? = null, val deletedFromDevice: Boolean = false)

data class SyncState(
    val running: Boolean = false,
    val items: List<SyncItem> = emptyList(),
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Double? = null,
    val finished: Boolean = false,
    val error: String? = null,
    val transport: SyncTransport = SyncTransport.BLUETOOTH,
    val stage: String? = null,
    val automatic: Boolean = false,
) {
    val fraction: Float get() = if (totalBytes <= 0) (if (finished) 1f else 0f) else (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val remainingSeconds: Long? get() = bytesPerSecond?.takeIf { it > 0 }?.let { ((totalBytes - receivedBytes) / it).toLong() }
}

data class DeviceState(
    val bound: BoundDevice? = null,
    val phase: LinkPhase = LinkPhase.IDLE,
    val nearby: NearbyRecorder? = null,
    val discovery: DiscoveryPhase? = null,
    val info: DeviceInfo? = null,
    val inventory: List<DeviceRecording>? = null,
    val activeRecordingFileId: Long? = null,
    val error: String? = null,
    val sync: SyncState = SyncState(),
    val transport: String = "Bluetooth",
    val commandBusy: Boolean = false,
    val supportsWifi: Boolean = false,
)

/**
 * Owns the recorder lifecycle: discovery of an unbound recorder, binding, automatic reconnection
 * to the bound one, device status, and batch sync into the recording library.
 */
class DeviceManager(
    private val link: DeviceLink,
    private val settings: SettingsStore,
    private val repository: RecordingRepository,
    private val scope: CoroutineScope,
    private val onSynced: (recordingId: String) -> Unit,
) {
    private val mutable = MutableStateFlow(DeviceState(bound = settings.state.value.boundDevice, transport = link.transportName, supportsWifi = link.supportsWifi))
    val state = mutable.asStateFlow()
    private var connectJob: Job? = null
    private var scanJob: Job? = null
    private var pollJob: Job? = null
    private val refreshSignals = Channel<Unit>(Channel.CONFLATED)
    private val statusReads = Mutex()
    private var syncJob: Job? = null
    private var foreground = false
    private var permitted = false
    private var backgroundSyncActive = false
    // Seed from the first inventory, so enabling the service doesn't transfer the old backlog.
    // Keep attempted IDs across reconnects so a failed or cancelled transfer stays manual.
    private var seenCompletedFiles: MutableSet<Long>? = null
    private val dismissed = mutableSetOf<String>()

    init {
        link.onStatusPush { battery, caseBattery, charging, caseCharging ->
            mutable.update { s -> s.copy(info = s.info?.let { i -> i.copy(
                battery = battery ?: i.battery, caseBattery = caseBattery ?: i.caseBattery,
                charging = charging ?: i.charging, caseCharging = caseCharging ?: i.caseCharging) }) }
        }
        link.onRecordingsChanged { refreshSignals.trySend(Unit) }
    }

    /** New = on the device, not the one being recorded, and not yet in the library. */
    fun newRecordings(state: DeviceState = this.state.value, library: List<Recording> = repository.recordings.value): List<DeviceRecording> {
        val synced = library.mapNotNull { recording -> recording.deviceFileId?.let { it to recording.id } }.toMap()
        return state.inventory.orEmpty().filter { file ->
            val local = synced[file.fileId]?.let(repository::audioFile)
            file.fileId != state.activeRecordingFileId && (local == null || !local.isFile || local.length() <= 44)
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            start()
            if (state.value.phase == LinkPhase.CONNECTED) startPolling()
        } else if (scanJob?.isActive == true) {
            scanJob?.cancel()
            link.stopScan()
        }
    }
    fun setPermitted(value: Boolean) {
        permitted = value
        if (value) {
            start()
            if (state.value.phase == LinkPhase.CONNECTED) startPolling()
        }
    }

    /** Called only while Android's connected-device foreground service is available. */
    fun setBackgroundSyncActive(value: Boolean) {
        if (backgroundSyncActive == value) return
        backgroundSyncActive = value
        seenCompletedFiles = if (value) state.value.completedFileIds() else null
        refreshSignals.trySend(Unit)
        if (value) {
            ensureConnected()
            if (state.value.phase == LinkPhase.CONNECTED) startPolling()
        }
    }

    private fun DeviceState.completedFileIds() = inventory?.filter {
        it.fileId != activeRecordingFileId && it.durationMs > 0
    }?.mapTo(mutableSetOf()) { it.fileId }

    private fun start() {
        if (!foreground || !permitted) return
        if (state.value.bound != null) ensureConnected() else scan()
    }

    /** [userInitiated] scans may show a recorder the user closed earlier in this session. */
    fun scan(userInitiated: Boolean = false) {
        if (userInitiated) dismissed.clear()
        if (scanJob?.isActive == true || state.value.bound != null || !permitted) return
        scanJob = scope.launch {
            try {
                mutable.update { it.copy(phase = LinkPhase.SCANNING, error = null) }
                link.startScan({ found ->
                    if (state.value.nearby == null && found.address !in dismissed) {
                        mutable.update { it.copy(nearby = found, discovery = DiscoveryPhase.FOUND) }
                    }
                }, { error -> mutable.update { it.copy(error = error) } })
                delay(20_000)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.update { it.copy(error = e.message) }
            } finally {
                link.stopScan()
                mutable.update { if (it.phase == LinkPhase.SCANNING) it.copy(phase = LinkPhase.IDLE) else it }
            }
        }
    }

    fun dismissDiscovery() {
        val s = state.value
        if (s.discovery == DiscoveryPhase.CONNECTING || s.discovery == DiscoveryPhase.CONFIRM) return
        s.nearby?.let { if (s.discovery != DiscoveryPhase.DONE) dismissed += it.address }
        mutable.update { it.copy(discovery = null, nearby = if (it.bound == null) null else it.nearby) }
    }

    fun bindNearby() {
        val nearby = state.value.nearby ?: return
        scanJob?.cancel(); link.stopScan()
        scope.launch {
            mutable.update { it.copy(discovery = DiscoveryPhase.CONNECTING, phase = LinkPhase.CONNECTING, error = null) }
            try {
                link.connect(nearby.address, ::onDropped)
                val info = link.readInfo()
                val outcome = link.bind { mutable.update { it.copy(discovery = DiscoveryPhase.CONFIRM) } }
                currentCoroutineContext().ensureActive()
                check(outcome == BindOutcome.CONFIRMED) { settings.text(R.string.binding_not_confirmed) }
                val bound = BoundDevice(nearby.address, nearby.name, info.serial, nearby.white ?: true, true)
                settings.update { it.copy(boundDevice = bound) }
                mutable.update { it.copy(bound = bound, info = info, phase = LinkPhase.CONNECTED) }
                refreshInventory()
                mutable.update { it.copy(discovery = DiscoveryPhase.DONE) }
                startPolling()
            } catch (e: Exception) {
                // A transport's own timeout cancels its child coroutine, not this operation.
                // Owner cancellation must still propagate instead of showing an error/retrying.
                currentCoroutineContext().ensureActive()
                link.close()
                mutable.update { it.copy(discovery = DiscoveryPhase.FOUND, phase = LinkPhase.IDLE, error = settings.text(R.string.connection_failed, connectionError(e))) }
            }
        }
    }

    fun ensureConnected() {
        if (connectJob?.isActive == true || state.value.phase == LinkPhase.CONNECTED || state.value.sync.running) return
        if (state.value.bound == null) return
        connectJob = scope.launch {
            var backoff = 3_000L
            while (isActive && permitted && (foreground || backgroundSyncActive) &&
                state.value.bound != null && state.value.phase != LinkPhase.CONNECTED && !state.value.sync.running) {
                val bound = state.value.bound ?: break
                mutable.update { it.copy(phase = LinkPhase.CONNECTING) }
                try {
                    val (address, info) = connectBound(bound)
                    currentCoroutineContext().ensureActive()
                    val refreshed = bound.copy(address = address, serial = info.serial.ifBlank { bound.serial })
                    settings.update { it.copy(boundDevice = refreshed) }
                    mutable.update { it.copy(bound = refreshed, info = info, phase = LinkPhase.CONNECTED, error = null) }
                    refreshInventory()
                    startPolling()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    link.close()
                    mutable.update { it.copy(phase = LinkPhase.DISCONNECTED, error = connectionError(e)) }
                    delay(backoff); backoff = minOf(backoff * 2, 30_000)
                }
            }
        }
    }

    /** BLE addresses can change. Only persist a discovered address after verifying the bound serial. */
    private suspend fun connectBound(bound: BoundDevice): Pair<String, DeviceInfo> {
        val seen = ConcurrentHashMap<String, NearbyRecorder>()
        val savedAddressSeen = CompletableDeferred<Unit>()
        try {
            link.startScan({ found ->
                seen[found.address] = found
                if (found.address == bound.address) savedAddressSeen.complete(Unit)
            }, { savedAddressSeen.completeExceptionally(IllegalStateException(it)) })
            withTimeoutOrNull(6000) { savedAddressSeen.await() }
        } finally { link.stopScan() }
        check(seen.isNotEmpty()) { settings.text(R.string.paired_recorder_not_found) }
        val candidates = seen.values.sortedByDescending { it.address == bound.address }
        var failure: Exception? = null
        for (candidate in candidates) {
            // Legacy bindings without a serial cannot safely migrate to an unrelated address.
            if (candidate.address != bound.address && bound.serial.isNullOrBlank()) continue
            try {
                link.connect(candidate.address, ::onDropped)
                val info = link.readInfo()
                check(bound.serial.isNullOrBlank() || bound.serial == info.serial) { settings.text(R.string.wrong_recorder) }
                return candidate.address to info
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                link.close()
                failure = e
            }
        }
        throw failure ?: IllegalStateException(settings.text(R.string.old_pairing_missing_id))
    }

    private fun connectionError(e: Exception) = if (e is TimeoutCancellationException)
        settings.text(R.string.device_connect_timeout) else e.message ?: settings.text(R.string.move_recorder_closer)

    private fun onDropped(reason: String) {
        pollJob?.cancel()
        if (state.value.sync.running && state.value.sync.transport == SyncTransport.WIFI) {
            mutable.update { it.copy(phase = LinkPhase.DISCONNECTED) }
            return
        }
        mutable.update { it.copy(phase = LinkPhase.DISCONNECTED, error = reason) }
        scope.launch { delay(1500); ensureConnected() }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) { refreshSignals.trySend(Unit); return }
        pollJob = scope.launch {
            while (isActive) {
                if ((!foreground && !backgroundSyncActive) || !permitted) { refreshSignals.receive(); continue }
                if (!state.value.sync.running && state.value.phase == LinkPhase.CONNECTED && !state.value.commandBusy) {
                    try {
                        statusReads.withLock {
                            if (!state.value.sync.running && !state.value.commandBusy) {
                                val info = link.readInfo()
                                mutable.update { it.copy(info = info) }
                                refreshInventory()
                            }
                        }
                        syncNewlyCompleted()
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        // readInfo/listFiles may close a timed-out transport without a drop callback.
                        onDropped(connectionError(e))
                        return@launch
                    }
                }
                // Notifications wake this wait immediately. A slower background refresh covers
                // dropped notifications and the gap between stop and finalizing the file list.
                withTimeoutOrNull(if (foreground) 2_000 else 15_000) { refreshSignals.receive() }
            }
        }
    }

    suspend fun refreshInventory() {
        val inventory = link.listFiles()
        mutable.update { it.copy(inventory = inventory.files.sortedBy { f -> f.fileId }, activeRecordingFileId = inventory.activeRecordingFileId) }
        if (backgroundSyncActive && seenCompletedFiles == null) seenCompletedFiles = state.value.completedFileIds()
    }

    private fun syncNewlyCompleted() {
        val seen = seenCompletedFiles ?: return
        val current = state.value
        if (!backgroundSyncActive || current.phase != LinkPhase.CONNECTED || current.sync.running ||
            current.commandBusy || current.info?.extras?.recording == true || current.activeRecordingFileId != null) return
        val batch = newRecordings(current).filter { it.durationMs > 0 && it.fileId !in seen }
        if (batch.isEmpty()) return
        val transport = if (link.supportsWifi && batch.any { it.transferBytes >= LARGE_RECORDING_BYTES })
            SyncTransport.WIFI else SyncTransport.BLUETOOTH
        val ids = batch.mapTo(mutableSetOf()) { it.fileId }
        seen.addAll(ids)
        startSync(ids, transport, highlightsOnly = false, automatic = true)
    }

    fun refresh() = command(settings.text(R.string.refresh_failed)) {
        val info = link.readInfo(); mutable.update { it.copy(info = info) }; refreshInventory()
    }

    fun toggleRecording() = command(settings.text(R.string.recording_control_failed)) {
        val recording = state.value.info?.extras?.recording ?: (state.value.activeRecordingFileId != null)
        link.setRecording(!recording)
        delay(600)
        mutable.update { it.copy(info = link.readInfo()) }
        refreshInventory()
    }

    fun setFindMy(enabled: Boolean) = command(settings.text(R.string.device_setting_failed)) {
        link.setFindMy(enabled); settings.update { it.copy(findMyEnabled = enabled) }
    }

    fun deleteFromDevice(fileId: Long) = command(settings.text(R.string.delete_failed)) {
        link.delete(fileId)
        mutable.update { s -> s.copy(inventory = s.inventory?.filterNot { it.fileId == fileId },
            sync = s.sync.copy(items = s.sync.items.map { if (it.fileId == fileId) it.copy(deletedFromDevice = true) else it })) }
    }

    fun unbind() = scope.launch {
        if (state.value.sync.running || state.value.commandBusy) {
            mutable.update { it.copy(error = settings.text(R.string.wait_before_unpair)) }; return@launch
        }
        if (state.value.phase != LinkPhase.CONNECTED) { forget(); return@launch }
        mutable.update { it.copy(commandBusy = true) }
        try {
            // 04.92 clears recorder storage on unbind. Refresh instead of trusting a stale UI list.
            val info = link.readInfo()
            mutable.update { it.copy(info = info) }
            refreshInventory()
            check(info.extras?.recording != true && state.value.activeRecordingFileId == null) {
                settings.text(R.string.stop_and_sync_before_unpair)
            }
            check(newRecordings().isEmpty()) { settings.text(R.string.sync_before_unpair) }
            link.unbind()
            forget()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(error = settings.text(R.string.unpair_failed, connectionError(e))) }
        } finally { mutable.update { it.copy(commandBusy = false) } }
    }

    fun factoryReset() = command(settings.text(R.string.factory_reset_failed)) {
        link.factoryReset(); forget()
    }

    private fun forget() {
        connectJob?.cancel(); pollJob?.cancel(); syncJob?.cancel(); link.close()
        setBackgroundSyncActive(false)
        settings.update { it.copy(boundDevice = null, findMyEnabled = null) }
        mutable.value = DeviceState(transport = link.transportName, supportsWifi = link.supportsWifi)
        dismissed.clear()
        start()
    }

    private fun command(failure: String, block: suspend () -> Unit) = scope.launch {
        if (state.value.phase != LinkPhase.CONNECTED) { mutable.update { it.copy(error = settings.text(R.string.recorder_disconnected)) }; return@launch }
        if (state.value.sync.running || state.value.commandBusy) { mutable.update { it.copy(error = settings.text(R.string.sync_in_progress)) }; return@launch }
        mutable.update { it.copy(commandBusy = true) }
        try { block() } catch (e: CancellationException) { throw e
        } catch (e: Exception) { mutable.update { it.copy(error = "$failure：${e.message ?: "请重试"}") }
        } finally { mutable.update { it.copy(commandBusy = false) } }
    }

    fun clearError() = mutable.update { it.copy(error = null) }

    /** Syncs [fileIds] (default: every new recording) one by one. Device copies stay unless the user opted in. */
    fun readHardwareHighlights(recordingId: String,transport: SyncTransport) {
        val fileId=repository.get(recordingId)?.deviceFileId
        if(fileId==null) {mutable.update {it.copy(error=settings.text(R.string.no_device_source))};return}
        sync(setOf(fileId),transport,highlightsOnly=true)
    }

    fun sync(fileIds: Set<Long>? = null, transport: SyncTransport = SyncTransport.BLUETOOTH, highlightsOnly: Boolean = false) =
        startSync(fileIds, transport, highlightsOnly, automatic = false)

    private fun startSync(fileIds: Set<Long>?, transport: SyncTransport, highlightsOnly: Boolean, automatic: Boolean) {
        // running stays true through cleanup/reconnection. Once false is published the user
        // may immediately retry, even if the previous coroutine is returning from finally.
        if (state.value.sync.running || state.value.commandBusy) return
        if (state.value.phase != LinkPhase.CONNECTED) { mutable.update { it.copy(error = settings.text(R.string.recorder_disconnected)) }; return }
        if (transport == SyncTransport.WIFI && !link.supportsWifi) {
            mutable.update { it.copy(error = settings.text(R.string.wifi_unsupported_phone)) }; return
        }
        val candidates=if(highlightsOnly) state.value.inventory.orEmpty().filter {file->file.fileId!=state.value.activeRecordingFileId && repository.get("device-${file.fileId}")!=null} else newRecordings()
        val targets = candidates.filter { fileIds == null || it.fileId in fileIds }
        if(highlightsOnly && targets.isEmpty()) {mutable.update {it.copy(error=settings.text(R.string.missing_device_highlight_source))};return}
        if (targets.isEmpty()) { mutable.update { it.copy(sync = SyncState(finished = true)) }; return }
        val items = targets.map { SyncItem(it.fileId, it.startEpochMs, it.durationMs, it.transferBytes) }
        val deleteAfterSync = !automatic && settings.state.value.deleteAfterSync
        mutable.update { it.copy(error = null, sync = SyncState(running = true, items = items,
            totalBytes = items.sumOf { i -> i.bytes }, transport = transport, stage = settings.text(R.string.checking_recorder), automatic = automatic)) }
        syncJob = scope.launch {
            var transfer: DeviceTransfer? = null
            var doneBytes = 0L
            var speedStart = System.nanoTime(); var speedBytes = 0L
            var failure: String? = null
            var cancelled = false
            var wifiPrepared = false
            var transferAttempted = false
            suspend fun finishTransfer() {
                val current = transfer ?: return
                transfer = null
                mutable.update { s -> s.copy(sync = s.sync.copy(stage = settings.text(R.string.restoring_bluetooth))) }
                withContext(NonCancellable) { withTimeout(8000) { current.finish() } }
            }
            suspend fun reconnectAfterWifi() {
                if (transport != SyncTransport.WIFI) return
                // Verify serial again; never bind/unbind to recover a transport switch.
                link.close()
                mutable.update { it.copy(phase = LinkPhase.CONNECTING) }
                val bound = state.value.bound ?: error(settings.text(R.string.pairing_removed))
                val (address, info) = connectBound(bound)
                val refreshed = bound.copy(address = address)
                settings.update { it.copy(boundDevice = refreshed) }
                mutable.update { it.copy(bound = refreshed, info = info, phase = LinkPhase.CONNECTED) }
                refreshInventory()
            }
            try {
                // Drain an in-flight read instead of cancelling it: BLE request cancellation
                // closes GATT. No status/list requests may leak into the transfer session.
                statusReads.withLock { }
                val info = link.readInfo()
                mutable.update { it.copy(info = info) }
                refreshInventory()
                if (automatic) {
                    check(info.extras?.recording != true && state.value.activeRecordingFileId == null) {
                        settings.text(R.string.recorder_busy_recording)
                    }
                }
                if (transport == SyncTransport.WIFI) {
                    check(info.extras?.recording != true && state.value.activeRecordingFileId == null) { settings.text(R.string.stop_recording_for_wifi) }
                    check(info.battery >= 30 || info.charging) { settings.text(R.string.charge_for_wifi) }
                }
                check(targets.all { target -> state.value.inventory.orEmpty().any { it == target } }) { settings.text(R.string.inventory_changed) }
                wifiPrepared = transport == SyncTransport.WIFI
                transferAttempted = true
                transfer = link.beginTransfer(transport) { stage -> mutable.update { s -> s.copy(sync = s.sync.copy(stage = stage)) } }
                for (file in targets) {
                    setItem(file.fileId) { it.copy(status = SyncItemStatus.DOWNLOADING) }
                    mutable.update { s -> s.copy(sync = s.sync.copy(stage = null)) }
                    val id = "device-${file.fileId}"
                    val target = repository.audioFile(id)
                    val partial = File(repository.audioDir, "$id.wav.partial")
                    try {
                        partial.delete()
                        val metadata=requireNotNull(transfer).download(file, partial) { p ->
                            val now = System.nanoTime()
                            val elapsed = (now - speedStart) / 1e9
                            val received = doneBytes + p.receivedBytes.coerceIn(0, file.transferBytes)
                            val speed = if (elapsed > 1) (received - speedBytes) / elapsed else null
                            mutable.update { s -> s.copy(sync = s.sync.copy(receivedBytes = received,
                                stage = p.stage, bytesPerSecond = if (p.stage != null) null else speed ?: s.sync.bytesPerSecond,
                                items = s.sync.items.map { if (it.fileId == file.fileId) it.copy(progress =
                                    (p.receivedBytes.toFloat() / p.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)) else it })) }
                            if (elapsed > 4) { speedStart = now; speedBytes = received }
                        }
                        val wav = withContext(Dispatchers.IO) { PcmWav.open(partial) }
                        check(wav.durationMs > 0 && wav.durationMs == file.durationMs) { settings.text(R.string.audio_duration_mismatch) }
                        if(highlightsOnly) {
                            val highlights=metadata.hardwareHighlights ?: error(settings.text(R.string.highlights_unsupported))
                            withContext(Dispatchers.IO) {check(target.isFile && target.sha256()==partial.sha256()) {settings.text(R.string.source_audio_mismatch)}}
                            currentCoroutineContext().ensureActive()
                            repository.update(id) {it.copy(hardwareHighlights=highlights)}
                            partial.delete()
                        } else {
                            val waveform = withContext(Dispatchers.IO) { RecordingRepository.waveform(partial) }
                            currentCoroutineContext().ensureActive()
                            withContext(Dispatchers.IO) { require(partial.renameTo(target)) { settings.text(R.string.cannot_save_recording) } }
                            repository.add(Recording(id, file.startEpochMs, wav.durationMs, RecordingSource.DEVICE, file.fileId,
                                audioBytes = target.length(), waveform = waveform,hardwareHighlights=metadata.hardwareHighlights))
                        }
                        doneBytes += file.transferBytes
                        setItem(file.fileId) { it.copy(status = SyncItemStatus.DONE, progress = 1f, recordingId = id) }
                        mutable.update { s -> s.copy(sync = s.sync.copy(receivedBytes = doneBytes)) }
                        if(!highlightsOnly) onSynced(id)
                    } catch (e: Exception) {
                        partial.delete()
                        currentCoroutineContext().ensureActive()
                        setItem(file.fileId) { it.copy(status = SyncItemStatus.FAILED, message = e.message ?: settings.text(R.string.sync_failed)) }
                        throw e
                    }
                }
                finishTransfer()
                reconnectAfterWifi()
                if (deleteAfterSync && !highlightsOnly) {
                    for (file in targets) {
                        // Only after WAV validation, publication and transport restoration.
                        try {
                            link.delete(file.fileId)
                            setItem(file.fileId) { it.copy(deletedFromDevice = true) }
                            mutable.update { s -> s.copy(inventory = s.inventory?.filterNot { it.fileId == file.fileId }) }
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            setItem(file.fileId) { it.copy(message = settings.text(R.string.device_copy_not_deleted, e.message)) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (transferAttempted) {
                    link.close()
                    mutable.update { it.copy(phase = LinkPhase.DISCONNECTED) }
                }
                cancelled = !currentCoroutineContext().isActive
                failure = if (cancelled) settings.text(R.string.sync_cancelled) else
                    if (e is TimeoutCancellationException) settings.text(R.string.sync_timeout) else e.message ?: settings.text(R.string.sync_incomplete)
            } finally {
                try { finishTransfer() } catch (_: Exception) { if (failure == null) failure = settings.text(R.string.reconnect_after_wifi) }
                if (wifiPrepared && state.value.phase != LinkPhase.CONNECTED) {
                    try {
                        withContext(NonCancellable) { withTimeout(30_000) { reconnectAfterWifi() } }
                    } catch (_: Exception) {
                        mutable.update { it.copy(phase = LinkPhase.DISCONNECTED) }
                        failure = (failure?.plus("；") ?: "") + settings.text(R.string.bluetooth_not_restored)
                    }
                }
                mutable.update { s -> s.copy(sync = s.sync.copy(running = false, finished = !cancelled,
                    stage = null, bytesPerSecond = null, error = failure,
                    items = s.sync.items.map { if (it.status == SyncItemStatus.DOWNLOADING)
                        it.copy(status = SyncItemStatus.FAILED, message = failure) else it })) }
                // A manual transfer may already have handled these newly seen files.
                if (!highlightsOnly) seenCompletedFiles?.addAll(targets.map { it.fileId })
                if (!state.value.sync.running) {
                    if (state.value.phase == LinkPhase.CONNECTED) startPolling() else ensureConnected()
                }
            }
        }
    }

    fun cancelSync() { syncJob?.cancel() }

    private fun setItem(fileId: Long, transform: (SyncItem) -> SyncItem) =
        mutable.update { s -> s.copy(sync = s.sync.copy(items = s.sync.items.map { if (it.fileId == fileId) transform(it) else it })) }

    private companion object {
        const val LARGE_RECORDING_BYTES = 2L * 1024 * 1024
    }
}
