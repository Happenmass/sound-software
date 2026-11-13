package dev.soundsoftware.app.device

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.MainActivity
import dev.soundsoftware.app.SoundApplication
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, read-only observation of physical recorder buttons through the production manager.
 * Exports status/counts only. Does not start/stop, sync, bind, delete or reset the recorder. */
@RunWith(AndroidJUnit4::class)
class DeviceLiveStateProbeTest {
    @Test fun physicalButtonsUpdateProductionStatusAndNewRecording() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveStateProbe") == "observe")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val output = File(context.getExternalFilesDir(null), "validation/device-live-state.json").apply { parentFile!!.mkdirs() }
        val report = JSONObject().put("status", "connecting")
        val observations = JSONArray().also { report.put("observations", it) }
        val start = android.os.SystemClock.elapsedRealtime()
        fun save() { output.writeText(report.toString(2)) }
        save()
        ActivityScenario.launch(MainActivity::class.java).use {
            val devices = app.devices
            val watch = launch(Dispatchers.Main.immediate) {
                var previous: String? = null
                devices.state.collect { state ->
                    val sample = JSONObject().put("phase", state.phase.name)
                        .put("recording", state.info?.extras?.recording ?: false)
                        .put("activeFile", state.activeRecordingFileId != null)
                        .put("inventoryCount", state.inventory?.size ?: -1)
                        .put("newCount", devices.newRecordings(state).size)
                    val signature = sample.toString()
                    if (signature != previous) {
                        previous = signature
                        sample.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                        observations.put(sample); save()
                    }
                }
            }
            try {
                val initial = withTimeout(30_000) { devices.state.first { it.phase == LinkPhase.CONNECTED && it.inventory != null } }
                val initialIds = initial.inventory.orEmpty().map { it.fileId }.toSet()
                check(initial.info?.extras?.recording != true && initial.activeRecordingFileId == null) { "Recorder already recording; leave it untouched" }
                report.put("status", "ready-for-physical-buttons").put("initialInventoryCount", initialIds.size); save()
                withTimeout(180_000) { devices.state.first { it.info?.extras?.recording == true || it.activeRecordingFileId != null } }
                report.put("status", "physical-start-observed"); save()
                val stopped = withTimeout(180_000) { devices.state.first { state ->
                    state.info?.extras?.recording != true && state.activeRecordingFileId == null &&
                        state.inventory.orEmpty().any { file -> file.fileId !in initialIds }
                } }
                val added = stopped.inventory.orEmpty().filter { file -> file.fileId !in initialIds }
                assertTrue(added.isNotEmpty())
                assertTrue(added.all { file -> devices.newRecordings(stopped).any { it.fileId == file.fileId } })
                assertTrue(stopped.inventory.orEmpty().map { it.fileId }.containsAll(initialIds))
                report.put("status", "passed").put("newFilesDiscovered", added.size)
                    .put("originalInventoryPreserved", true).put("finalInventoryCount", stopped.inventory?.size); save()
            } catch (e: Exception) {
                report.put("status", "failed").put("errorType", e.javaClass.simpleName); save(); throw e
            } finally { watch.cancelAndJoin() }
        }
    }
}
