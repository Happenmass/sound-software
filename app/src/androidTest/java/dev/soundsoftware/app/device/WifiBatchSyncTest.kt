package dev.soundsoftware.app.device

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.*
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.device.*
import dev.soundsoftware.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated files/preferences and synthetic transport, never the real recorder. */
@RunWith(AndroidJUnit4::class)
class WifiBatchSyncTest {
    @Test fun backgroundPushSyncsOnlyCompletedNewRecordingsOverBluetooth() = runBlocking {
        fixture { manager, link, repository, settings ->
            withContext(Dispatchers.Main) {
                settings.update { it.copy(deleteAfterSync = true) }
                manager.setBackgroundSyncActive(true)
                manager.setForeground(false)
                link.activeFileId = 4
                link.files.add(DeviceRecording(4, 20))
                link.pushRecordingsChanged()
            }
            withTimeout(1_200) { manager.state.first { it.activeRecordingFileId == 4L } }
            assertEquals(emptyList<SyncTransport>(), link.transports)
            withContext(Dispatchers.Main) {
                link.activeFileId = null
                link.pushRecordingsChanged()
            }
            val done = withTimeout(3_000) { manager.state.first { it.sync.automatic && it.sync.finished } }
            assertNull(done.sync.error)
            assertEquals(listOf(4L), link.downloads)
            assertEquals(listOf(SyncTransport.BLUETOOTH), link.transports)
            assertEquals(listOf(1L, 2L, 3L), manager.newRecordings().map { it.fileId })
            assertTrue(repository.audioFile("device-4").isFile)
            assertEquals(0, link.deletes)
        }
    }

    @Test fun mixedBackgroundBatchPrefersWifiAndFailureLeavesOnlyUnfinishedFileForManualSync() = runBlocking {
        fixture { manager, link, repository, _ ->
            withContext(Dispatchers.Main) {
                manager.setBackgroundSyncActive(true)
                manager.setForeground(false)
                link.failId = 5
                link.files.add(DeviceRecording(4, 20))
                link.files.add(DeviceRecording(5, 252_680)) // Just above 2 MiB on the wire.
                link.pushRecordingsChanged()
            }
            val failed = withTimeout(5_000) { manager.state.first { it.sync.automatic && it.sync.finished } }
            assertNotNull(failed.sync.error)
            assertEquals(listOf(SyncTransport.WIFI), link.transports)
            assertEquals(listOf(4L, 5L), link.downloads)
            assertNotNull(repository.get("device-4"))
            assertFalse(File(repository.audioDir, "device-5.wav.partial").exists())
            assertEquals(listOf(1L, 2L, 3L, 5L), manager.newRecordings().map { it.fileId })
            withContext(Dispatchers.Main) {
                link.failId = null
                link.pushRecordingsChanged()
            }
            delay(300)
            assertEquals(listOf(SyncTransport.WIFI), link.transports)
            withContext(Dispatchers.Main) { manager.sync(setOf(5L), SyncTransport.WIFI) }
            val retried = withTimeout(5_000) { manager.state.first { !it.sync.automatic && it.sync.finished } }
            assertNull(retried.sync.error)
            assertEquals(listOf(4L, 5L, 5L), link.downloads)
            assertEquals(listOf(1L, 2L, 3L), manager.newRecordings().map { it.fileId })
        }
    }

    @Test fun metadataRefreshPreservesUserContentAndNeverDeletesDeviceCopy()=runBlocking {fixture {manager,link,repo,settings->
        val file=link.files.first();val id="device-${file.fileId}"
        PcmWav.writeMono(repo.audioFile(id),FloatArray(320) {0.1f})
        val original=Recording(id,file.startEpochMs,20,RecordingSource.DEVICE,file.fileId,title="用户标题",titleEditedByUser=true,
            transcript=Transcript(listOf(Segment("s","保留原文",0,20)),"fixture","test","SEGMENT",0),
            summary=MeetingSummary(abstract="保留纪要",todos=listOf(TodoItem("t","保留勾选",done=true))))
        repo.add(original);val audio=repo.audioFile(id).readBytes()
        link.highlights=listOf(RecordingHighlight("device-0",0));settings.update {it.copy(deleteAfterSync=true)}
        manager.readHardwareHighlights(id,SyncTransport.WIFI)
        withTimeout(5000) {manager.state.first {it.sync.finished}}
        assertNull(manager.state.value.sync.error)
        assertEquals(original.copy(hardwareHighlights=link.highlights),repo.get(id))
        assertTrue(audio.contentEquals(repo.audioFile(id).readBytes()));assertEquals(0,link.deletes)
        assertTrue(link.files.any {it.fileId==file.fileId})
        val restored=RecordingRepository(repo.audioDir.parentFile!!);restored.load()
        assertEquals(link.highlights,restored.get(id)!!.hardwareHighlights)
    }}
    @Test fun metadataRefreshRejectsAudioMismatchWithoutChangingExistingRecord()=runBlocking {fixture {manager,link,repo,_->
        val file=link.files.first();val id="device-${file.fileId}";PcmWav.writeMono(repo.audioFile(id),FloatArray(320) {-0.5f})
        val original=Recording(id,file.startEpochMs,20,RecordingSource.DEVICE,file.fileId,title="保留")
        repo.add(original);val bytes=repo.audioFile(id).readBytes()
        manager.readHardwareHighlights(id,SyncTransport.BLUETOOTH)
        withTimeout(5000) {manager.state.first {it.sync.finished}}
        assertNotNull(manager.state.value.sync.error);assertEquals(original,repo.get(id));assertTrue(bytes.contentEquals(repo.audioFile(id).readBytes()))
        assertEquals(0,link.deletes)
    }}
    private suspend fun fixture(run: suspend (DeviceManager, BatchLink, RecordingRepository, SettingsStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "wifi-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences(name + n, mode)
        }
        val dir = File(base.cacheDir, name).apply { mkdirs() }
        val settings = SettingsStore(context)
        settings.update { it.copy(boundDevice = BoundDevice("00:00:00:00:00:01", "fixture", "fixture")) }
        val repository = RecordingRepository(dir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val link = BatchLink()
        val manager = DeviceManager(link, settings, repository, scope) {}
        try {
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(3000) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            run(manager, link, repository, settings)
        } finally { scope.cancel(); base.deleteSharedPreferences(name + "app-settings"); dir.deleteRecursively() }
    }

    @Test fun threeFilesShareSessionSurviveBleDropAndSkipDuplicatesAfterRestart() = runBlocking {
        fixture { manager, link, repository, _ ->
            link.dropBle = true
            manager.sync(transport = SyncTransport.WIFI)
            val done = withTimeout(5000) { manager.state.first { !it.sync.running && it.sync.finished } }
            assertNull(done.sync.error)
            assertEquals(3, done.sync.items.count { it.status == SyncItemStatus.DONE })
            assertEquals(listOf(1L, 2L, 3L), link.downloads)
            assertEquals(1, link.sessions); assertEquals(1, link.finishes)
            assertEquals(2, link.connects); assertEquals(0, link.deletes)
            repository.load()
            assertEquals(3, repository.recordings.value.size)
            assertTrue(manager.newRecordings().isEmpty())
            manager.sync(transport = SyncTransport.WIFI)
            delay(50)
            assertEquals(1, link.sessions)
        }
    }

    @Test fun secondFileFailureKeepsFirstAndRetryOnlyDownloadsPending() = runBlocking {
        fixture { manager, link, repository, _ ->
            link.failId = 2
            manager.sync(transport = SyncTransport.WIFI)
            val failed = withTimeout(5000) { manager.state.first { !it.sync.running && it.sync.error != null } }
            assertEquals(1, failed.sync.items.count { it.status == SyncItemStatus.DONE })
            assertEquals(1, repository.recordings.value.size)
            assertEquals(listOf(2L, 3L), manager.newRecordings().map { it.fileId })
            assertFalse(File(repository.audioDir, "device-2.wav.partial").exists())
            assertEquals(1, link.finishes)
            link.failId = null
            manager.sync(transport = SyncTransport.WIFI)
            withTimeout(5000) { manager.state.first { !it.sync.running && it.sync.error == null } }
            assertEquals(listOf(1L, 2L, 2L, 3L), link.downloads)
            assertEquals(3, repository.recordings.value.size)
        }
    }

    @Test fun cancellationCleansSessionAndPartialAndBlocksUnbindDuringWifiBleDrop() = runBlocking {
        fixture { manager, link, repository, _ ->
            link.dropBle = true; link.stallId = 2
            manager.sync(transport = SyncTransport.WIFI)
            withTimeout(3000) { manager.state.first { it.sync.items.any { i -> i.fileId == 2L && i.status == SyncItemStatus.DOWNLOADING } } }
            manager.unbind().join()
            assertNotNull(manager.state.value.bound); assertEquals(0, link.unbinds)
            manager.cancelSync()
            withTimeout(5000) { manager.state.first { !it.sync.running } }
            assertTrue(manager.state.value.sync.error.orEmpty().contains("取消"))
            assertEquals(1, link.finishes)
            assertEquals(1, repository.recordings.value.size)
            assertFalse(File(repository.audioDir, "device-2.wav.partial").exists())
        }
    }

    @Test fun selectedSubsetAndOptedInDeletionRunOnlyAfterTransportRestoration() = runBlocking {
        fixture { manager, link, repository, settings ->
            settings.update { it.copy(deleteAfterSync = true) }
            link.dropBle = true
            manager.sync(setOf(1, 3), SyncTransport.WIFI)
            withTimeout(5000) { manager.state.first { !it.sync.running } }
            assertEquals(listOf(1L, 3L), link.downloads)
            assertEquals(2, repository.recordings.value.size)
            assertEquals(2, link.deletes)
            assertEquals(listOf(2L), manager.newRecordings().map { it.fileId })
            assertTrue(manager.state.value.sync.items.all { it.deletedFromDevice })
        }
    }

    @Test fun activeRecordingAndLowBatteryDoNotStartHotspot() = runBlocking {
        fixture { manager, link, _, _ ->
            link.active = true
            manager.sync(transport = SyncTransport.WIFI)
            withTimeout(3000) { manager.state.first { !it.sync.running } }
            assertEquals(0, link.sessions)
            link.active = false; link.battery = 20
            manager.sync(transport = SyncTransport.WIFI)
            withTimeout(3000) { manager.state.first { !it.sync.running } }
            assertEquals(0, link.sessions)
            assertTrue(manager.state.value.sync.error.orEmpty(), manager.state.value.sync.error.orEmpty().contains("电量"))
        }
    }

    private class BatchLink : DeviceLink {
        var highlights: List<RecordingHighlight>? = emptyList()
        override val transportName = "fixture"
        override val supportsWifi = true
        val files = (1L..3L).map { DeviceRecording(it, 20) }.toMutableList()
        var connects = 0; var sessions = 0; var finishes = 0; var deletes = 0; var unbinds = 0
        var active = false; var battery = 80
        var activeFileId: Long? = null
        var dropBle = false; var failId: Long? = null; var stallId: Long? = null
        val downloads = mutableListOf<Long>()
        val transports = mutableListOf<SyncTransport>()
        private var recordingsChanged: (() -> Unit)? = null
        override fun onRecordingsChanged(listener: () -> Unit) { recordingsChanged = listener }
        fun pushRecordingsChanged() { recordingsChanged?.invoke() }
        private var disconnected: ((String) -> Unit)? = null
        override fun startScan(onFound: (NearbyRecorder) -> Unit, onError: (String) -> Unit) = onFound(NearbyRecorder("00:00:00:00:00:01", "fixture", -30, false, false))
        override fun stopScan() {}
        override suspend fun connect(address: String, onDisconnected: (String) -> Unit) { connects++; disconnected = onDisconnected }
        override suspend fun readInfo() = DeviceInfo(battery, false, "fixture", "fixture", 100, 90, 80, false,
            DeviceInfoExtras("", 0, false, 0, false, false, active, ""))
        override suspend fun listFiles() = DeviceInventory(files.toList(), activeFileId)
        override suspend fun beginTransfer(transport: SyncTransport, onStage: (String) -> Unit): DeviceTransfer {
            sessions++
            transports += transport
            if (dropBle) disconnected?.invoke("expected transport switch")
            return object : DeviceTransfer {
                override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit): DownloadedAudioMetadata {
                    downloads += recording.fileId
                    if (recording.fileId == stallId) { target.writeBytes(ByteArray(50)); awaitCancellation() }
                    if (recording.fileId == failId) { target.writeBytes(ByteArray(50)); error("synthetic network failure") }
                    PcmWav.writeMono(target, FloatArray((recording.durationMs * 16).toInt()) { 0.1f })
                    onProgress(TransferProgress(recording.transferBytes, recording.transferBytes))
                    return DownloadedAudioMetadata(highlights)
                }
                override suspend fun finish() { finishes++ }
            }
        }
        override suspend fun delete(fileId: Long) { check(finishes > 0 && connects > 1); deletes++; files.removeAll { it.fileId == fileId } }
        override suspend fun unbind() { unbinds++ }
        override suspend fun bind(onAwaitingPress: () -> Unit) = BindOutcome.NOT_CONFIRMED
        override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit) = error("must use batch session")
        override suspend fun setRecording(active: Boolean) = error("unused")
        override suspend fun setFindMy(enabled: Boolean) = error("unused")
        override suspend fun factoryReset() = error("unused")
        override fun close() {}
    }
}
