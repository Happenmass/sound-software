package dev.soundsoftware.app.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.device.D3200Protocol
import dev.soundsoftware.core.device.DevicePayloads
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only BLE probe. No binding, recording, download or deletion commands. */
@RunWith(AndroidJUnit4::class)
class DeviceConnectionProbeTest {
    @Test fun connectAndReadInfo() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("connectionProbe")
        assumeTrue("Explicit connectionProbe=saved|scan required", mode in listOf("saved", "scan"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bound = requireNotNull(SettingsStore(context).state.value.boundDevice)
        val report = JSONObject().put("mode", mode).put("stage", "starting")
        val evidence = File(context.getExternalFilesDir(null), "validation/connection-probe-$mode.json")
        evidence.parentFile!!.mkdirs()
        fun save() { evidence.writeText(report.toString(2)) }
        val client = BleDeviceClient(context)
        try {
            var address = bound.address
            if (mode == "scan") {
                val seen = ConcurrentHashMap<String, NearbyRecorder>()
                client.startScan({ seen[it.address] = it }, { report.put("scanError", it) })
                delay(6000); client.stopScan()
                report.put("nearbyCount", seen.size).put("savedAddressAdvertised", seen.containsKey(bound.address))
                val found = seen[bound.address] ?: seen.values.singleOrNull()
                requireNotNull(found) { "No unique nearby recorder" }
                address = found.address
                report.put("selectedAddressMatchesSaved", address == bound.address)
                    .put("advertisedBound", found.boundToOther).put("advertisedWhite", found.white)
            }
            report.put("stage", "gatt"); save()
            client.connect(address) {}
            report.put("stage", "readInfo"); save()
            val info = client.readInfo()
            report.put("stage", "connected").put("firmware", info.firmware)
                .put("serialMatchesSaved", bound.serial?.let { it == info.serial })
            val inventory = client.listFiles()
            val page = client.request(0x1a, 0x0e, D3200Protocol.page(0))
            val firstPage = DevicePayloads.files(page.payload)
            report.put("inventoryCount", inventory.files.size)
                .put("activeRecordingPresent", inventory.activeRecordingFileId != null)
                .put("firstPageEntryCount", firstPage.files.size)
                .put("firstPageSuccess", page.successful)
            save()
        } catch (e: Exception) {
            report.put("errorType", e.javaClass.simpleName).put("error", e.message); save()
            throw e
        } finally { client.close() }
        assertEquals("connected", report.getString("stage"))
    }
}
