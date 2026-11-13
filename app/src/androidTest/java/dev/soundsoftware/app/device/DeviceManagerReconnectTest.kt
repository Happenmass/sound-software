package dev.soundsoftware.app.device

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.BoundDevice
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.device.DeviceInfo
import dev.soundsoftware.core.device.DeviceInfoExtras
import dev.soundsoftware.core.device.DeviceRecording
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.RecordingSource
import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses separate preferences/files and a transport fixture; never changes the user's binding. */
@RunWith(AndroidJUnit4::class)
class DeviceManagerReconnectTest {
    private suspend fun fixture(bound: Boolean, run: suspend (DeviceManager, TimeoutLink, CoroutineScope, RecordingRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "reconnect-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val dir = File(base.cacheDir, prefix).apply { mkdirs() }
        val settings = SettingsStore(context)
        if (bound) settings.update { it.copy(boundDevice = BoundDevice(OLD, "fixture", "fixture")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val link = TimeoutLink()
        val repository = RecordingRepository(dir)
        val manager = DeviceManager(link, settings, repository, scope) {}
        try {
            run(manager, link, scope, repository)
            assertEquals(manager.state.value.bound, SettingsStore(context).state.value.boundDevice)
        } finally {
            scope.cancel()
            base.deleteSharedPreferences(prefix + "app-settings")
            dir.deleteRecursively()
        }
    }

    @Test fun nestedConnectionTimeoutIsVisibleAndRetried() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            manager.setForeground(true); manager.setPermitted(true)
            val failed = withTimeout(1200) { manager.state.first { it.phase == LinkPhase.DISCONNECTED } }
            assertNotNull(failed.error)
            withTimeout(5000) { manager.state.first { it.phase == LinkPhase.CONNECTED } }
            assertEquals(2, link.attempts)
        }
    }

    @Test fun hardwareRecordingAndStopRefreshStatusAndNewInventoryWithoutManualRefresh() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            // Simulate the physical recorder button, without any App command or battery push.
            link.recordingFlag = true
            link.inventory = DeviceInventory(emptyList(), 1000)
            withTimeout(4500) { manager.state.first { it.info?.extras?.recording == true && it.activeRecordingFileId == 1000L } }
            assertTrue(manager.newRecordings().isEmpty())
            link.recordingFlag = false
            link.inventory = DeviceInventory(listOf(DeviceRecording(1000, 1000)), null)
            withTimeout(4500) { manager.state.first { it.info?.extras?.recording == false && it.activeRecordingFileId == null && manager.newRecordings(it).size == 1 } }
        }
    }

    @Test fun returningToForegroundRefreshesAnAlreadyConnectedRecorder() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            manager.setForeground(false)
            link.inventory = DeviceInventory(listOf(DeviceRecording(1000, 1000)), null)
            manager.setForeground(true)
            withTimeout(1200) { manager.state.first { manager.newRecordings(it).size == 1 } }
        }
    }

    @Test fun recordingPushRefreshesImmediatelyWithoutWaitingForPoll() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            delay(100)
            link.recordingFlag = true
            link.inventory = DeviceInventory(emptyList(), 1000)
            link.recordingsChanged?.invoke()
            withTimeout(700) { manager.state.first { it.info?.extras?.recording == true && it.activeRecordingFileId == 1000L } }
        }
    }

    @Test fun discoveryConnectionTimeoutReturnsToFound() = runBlocking {
        fixture(false) { manager, _, _, _ ->
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.discovery == DiscoveryPhase.FOUND } }
            manager.bindNearby()
            val failed = withTimeout(1200) { manager.state.first { it.discovery == DiscoveryPhase.FOUND && it.error != null } }
            assertEquals(LinkPhase.IDLE, failed.phase)
            assertNull(failed.bound)
        }
    }

    @Test fun ownerCancellationDoesNotRetryConnection() = runBlocking {
        fixture(true) { manager, link, scope, _ ->
            link.blockUntilCancelled = true
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { while (link.attempts == 0) delay(10) }
            scope.cancel(); delay(150)
            assertEquals(1, link.attempts)
        }
    }

    @Test fun rotatedAddressIsRediscoveredAndPersistedAfterSerialMatch() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            link.advertised = listOf(NEW)
            link.serials = mapOf(NEW to "fixture")
            manager.setForeground(true); manager.setPermitted(true)
            val connected = withTimeout(9000) { manager.state.first { it.phase == LinkPhase.CONNECTED } }
            assertEquals(NEW, connected.bound?.address)
            assertEquals("fixture", connected.bound?.serial)
            assertEquals(listOf(NEW), link.addresses)
        }
    }

    @Test fun differentSerialIsRejectedEvenAtSavedAddress() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            link.serials = mapOf(OLD to "another-recorder")
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.DISCONNECTED && it.error != null } }
            assertEquals("fixture", manager.state.value.bound?.serial)
            assertNull(manager.state.value.info)
        }
    }

    @Test fun unconfirmedBindingDoesNotPersistOrShowDone() = runBlocking {
        fixture(false) { manager, link, _, _ ->
            link.failFirst = false
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.discovery == DiscoveryPhase.FOUND } }
            manager.bindNearby()
            withTimeout(1200) { manager.state.first { it.discovery == DiscoveryPhase.FOUND && it.error != null } }
            assertNull(manager.state.value.bound)
        }
    }

    @Test fun unsyncedRecordingBlocksDestructiveUnbind() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            // A recording appeared since the UI last saw an empty device.
            link.inventory = DeviceInventory(listOf(DeviceRecording(1000, 1000)), null)
            manager.unbind().join()
            assertNotNull(manager.state.value.bound)
            assertEquals(0, link.unbindCalls)
            assertTrue(manager.state.value.error.orEmpty().contains("同步"))
        }
    }

    @Test fun activeRecordingBlocksUnbindEvenWhenNoCompletedFilesArePending() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            link.inventory = DeviceInventory(listOf(DeviceRecording(1000, 1000)), 1000)
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            assertTrue(manager.newRecordings().isEmpty())
            manager.unbind().join()
            assertNotNull(manager.state.value.bound)
            assertEquals(0, link.unbindCalls)
        }
    }

    @Test fun freshRecordingFlagBlocksUnbindBeforeTheFileAppearsInTheList() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            link.recordingFlag = true
            manager.unbind().join()
            assertNotNull(manager.state.value.bound)
            assertEquals(0, link.unbindCalls)
        }
    }

    @Test fun metadataWithoutLocalAudioIsNotABackup() = runBlocking {
        fixture(true) { manager, link, _, repository ->
            link.failFirst = false
            link.inventory = DeviceInventory(listOf(DeviceRecording(1000, 1000)), null)
            repository.add(Recording("missing-audio", 0, 1000, RecordingSource.DEVICE, 1000))
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            assertEquals(1, manager.newRecordings().size)
            manager.unbind().join()
            assertNotNull(manager.state.value.bound)
            assertEquals(0, link.unbindCalls)
        }
    }

    @Test fun backedUpRecordingAllowsUnbindAndRetainsLocalAudio() = runBlocking {
        fixture(true) { manager, link, _, repository ->
            link.failFirst = false
            link.inventory = DeviceInventory(listOf(DeviceRecording(1000, 1000)), null)
            val file = repository.audioFile("backed-up")
            PcmWav.writeMono(file, FloatArray(16000))
            repository.add(Recording("backed-up", 0, 1000, RecordingSource.DEVICE, 1000))
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            manager.unbind().join()
            assertNull(manager.state.value.bound)
            assertEquals(1, link.unbindCalls)
            assertEquals(1000L, PcmWav.open(file).durationMs)
        }
    }

    @Test fun rejectedUnbindKeepsLocalBinding() = runBlocking {
        fixture(true) { manager, link, _, _ ->
            link.failFirst = false; link.rejectUnbind = true
            manager.setForeground(true); manager.setPermitted(true)
            withTimeout(1200) { manager.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
            manager.unbind().join()
            assertNotNull(manager.state.value.bound)
            assertEquals(1, link.unbindCalls)
            assertTrue(manager.state.value.error.orEmpty().contains("解绑失败"))
        }
    }

    private class TimeoutLink : DeviceLink {
        override val transportName = "fixture"
        @Volatile var attempts = 0
        var blockUntilCancelled = false
        var failFirst = true
        var advertised = listOf(OLD)
        var serials = mapOf(OLD to "fixture")
        val addresses = mutableListOf<String>()
        var inventory = DeviceInventory(emptyList(), null)
        var unbindCalls = 0
        var rejectUnbind = false
        var recordingFlag = false
        var recordingsChanged: (() -> Unit)? = null
        private var connectedAddress = OLD
        override fun startScan(onFound: (NearbyRecorder) -> Unit, onError: (String) -> Unit) {
            advertised.forEach { onFound(NearbyRecorder(it, "fixture", -40, false, false)) }
        }
        override fun stopScan() {}
        override suspend fun connect(address: String, onDisconnected: (String) -> Unit) {
            attempts++
            addresses += address
            if (blockUntilCancelled) awaitCancellation()
            if (failFirst && attempts == 1) withTimeout(30) { awaitCancellation() }
            check(address in serials) { "Obsolete address" }
            connectedAddress = address
        }
        override suspend fun readInfo() = DeviceInfo(80, false, "fixture", serials.getValue(connectedAddress), 100, 90, 80, false,
            DeviceInfoExtras("", 0, false, 0, false, false, recordingFlag, ""))
        override suspend fun listFiles() = inventory
        override fun onRecordingsChanged(listener: () -> Unit) { recordingsChanged = listener }
        override suspend fun bind(onAwaitingPress: () -> Unit) = BindOutcome.NOT_CONFIRMED
        override suspend fun unbind() { unbindCalls++; check(!rejectUnbind) { "Device refused unbind" } }
        override suspend fun download(recording: DeviceRecording, target: File, onProgress: (TransferProgress) -> Unit) = error("unused")
        override suspend fun delete(fileId: Long) = error("unused")
        override suspend fun setRecording(active: Boolean) = error("unused")
        override suspend fun setFindMy(enabled: Boolean) = error("unused")
        override suspend fun factoryReset() = error("unused")
        override fun close() {}
    }

    private companion object {
        const val OLD = "00:00:00:00:00:01"
        const val NEW = "00:00:00:00:00:02"
    }
}
