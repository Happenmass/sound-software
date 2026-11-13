package dev.soundsoftware.app.device

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, read-only recording probe: never binds, records, deletes or changes app settings. */
@RunWith(AndroidJUnit4::class)
class WifiDeviceProbeTest {
    @Test fun downloadExistingRecordingsOverWifiAndCompareBle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("wifiProbe") == "true")
        val context = instrumentation.targetContext
        val bound = requireNotNull(SettingsStore(context).state.value.boundDevice) { "Existing binding is required" }
        val reportFile = File(context.getExternalFilesDir(null), "validation/wifi-device.json").apply { parentFile!!.mkdirs() }
        val report = JSONObject().put("status", "running").put("stage", "scan")
        fun stage(s: String) { report.put("stage", s); reportFile.writeText(report.toString(2)) }
        stage("scan")
        val client = BleDeviceClient(context)
        client.onWifiDiagnostic = { values -> report.put("wifiDiagnostic", JSONObject(values)); reportFile.writeText(report.toString(2)) }
        var transfer: DeviceTransfer? = null
        val directory = File(context.cacheDir, "wifi-probe-${UUID.randomUUID()}").apply { mkdirs() }
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, WifiProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            suspend fun connect() {
                val seen = java.util.concurrent.ConcurrentHashMap<String, NearbyRecorder>()
                val saved = CompletableDeferred<Unit>()
                client.startScan({ seen[it.address] = it; if (it.address == bound.address) saved.complete(Unit) },
                    { saved.completeExceptionally(IllegalStateException(it)) })
                try { withTimeoutOrNull(6000) { saved.await() } } finally { client.stopScan() }
                report.put("nearbyCount", seen.size).put("savedAddressAdvertised", seen.containsKey(bound.address))
                check(seen.isNotEmpty()) { "Recorder is not advertising; wake it and retry" }
                var connected = false
                for (device in seen.values.sortedByDescending { it.address == bound.address }) {
                    try {
                        client.connect(device.address) { report.put("bleDisconnectedDuringWifi", true) }
                        val info = client.readInfo()
                        check(info.serial == bound.serial) { "Recorder identity mismatch" }
                        report.put("firmware", info.firmware).put("battery", info.battery)
                        connected = true; break
                    } catch (e: Exception) { currentCoroutineContext().ensureActive(); client.close() }
                }
                check(connected) { "No nearby recorder matches the existing binding" }
            }
            connect()
            val before = client.listFiles()
            check(before.activeRecordingFileId == null) { "Recorder is recording; probe stopped" }
            val count = InstrumentationRegistry.getArguments().getString("wifiMaxFiles")?.toIntOrNull() ?: 3
            require(count in 1..3)
            val marksProbe=InstrumentationRegistry.getArguments().getString("wifiMarksProbe")=="true"
            val files = if(marksProbe) before.files.sortedBy {it.fileId}.takeLast(3).also {selected->check(selected.size==3 && selected.all {it.durationMs in 2000..120_000})}
                else before.files.filter { it.durationMs in 20..4 * 60 * 60_000L }.sortedBy { it.durationMs }.take(count)
            check(files.isNotEmpty()) { "No completed recording for probe" }
            report.put("inventoryCountBefore", before.files.size).put("selectedCount", files.size)
                .put("selectedDurationsMs", JSONArray(files.map { it.durationMs }))
            val start = System.nanoTime()
            transfer = client.beginTransfer(SyncTransport.WIFI, ::stage)
            val results = JSONArray().also { report.put("files", it) }
            val expectedHashes = InstrumentationRegistry.getArguments().getString("wifiExpectedHashes")?.split(',')
            for ((index, file) in files.withIndex()) {
                stage("wifi-file-${index + 1}")
                val target = File(directory, "wifi-$index.wav")
                val fileStart = System.nanoTime()
                var wireElapsedMs: Long? = null
                val metadata=requireNotNull(transfer).download(file, target) {
                    if (it.stage != null && wireElapsedMs == null) {
                        wireElapsedMs = (System.nanoTime() - fileStart) / 1_000_000
                        stage("decoding-file-${index + 1}")
                    }
                }
                val wav = PcmWav.open(target)
                assertEquals(file.durationMs, wav.durationMs)
                val hash = target.sha256()
                val elapsed = (System.nanoTime() - fileStart) / 1_000_000
                results.put(JSONObject().put("durationMs", wav.durationMs).put("wavSha256", hash)
                    .put("hardwareHighlightsMs",JSONArray(metadata.hardwareHighlights?.map {it.positionMs}))
                    .put("elapsedMs", elapsed).put("wireElapsedMs", wireElapsedMs)
                    .put("decodeElapsedMs", wireElapsedMs?.let { elapsed - it }))
                expectedHashes?.let { expected ->
                    assertEquals(expected[index], hash)
                    results.getJSONObject(index).put("matchesPreviousWifi", true)
                }
                if (index == 0 && InstrumentationRegistry.getArguments().getString("wifiSkipPlayback") != "true") {
                    val played = CompletableDeferred<Unit>()
                    val player = android.media.MediaPlayer()
                    try {
                        player.setDataSource(target.path); player.setVolume(0f, 0f)
                        player.setOnCompletionListener { played.complete(Unit) }
                        player.setOnErrorListener { _, what, extra -> played.completeExceptionally(IllegalStateException("MediaPlayer $what/$extra")); true }
                        player.prepare(); player.start()
                        withTimeout(wav.durationMs + 5000) { played.await() }
                        results.getJSONObject(index).put("mediaPlayerCompletedMuted", true)
                    } finally { player.release() }
                }
            }
            report.put("files", results).put("wifiBatchElapsedMs", (System.nanoTime() - start) / 1_000_000)
            if (InstrumentationRegistry.getArguments().getString("wifiCancel") == "true") {
                stage("cancelling-extra-download")
                val started = CompletableDeferred<Unit>()
                val cancelledTarget = File(directory, "cancelled.wav")
                val extra = async(Dispatchers.IO) {
                    requireNotNull(transfer).download(files.maxBy { it.durationMs }, cancelledTarget) {
                        if (it.receivedBytes >= 4096) started.complete(Unit)
                    }
                }
                withTimeout(15_000) { started.await() }
                extra.cancelAndJoin()
                assertFalse(cancelledTarget.exists())
                report.put("cancelAfterDataReceived", true).put("cancelledTargetAbsent", true)
            }
            stage("finishing-wifi")
            transfer!!.finish(); transfer = null
            client.close()
            stage("restoring-ble")
            connect()
            val after = client.listFiles()
            assertEquals(before.files, after.files)
            report.put("inventoryCountAfter", after.files.size)
            for ((index, file) in files.withIndex()) {
                stage("ble-compare-${index + 1}")
                if (file.durationMs > 60_000) {
                    results.getJSONObject(index).put("bleComparison", "not-run-for-long-file")
                    continue
                }
                val target = File(directory, "ble-$index.wav")
                val metadata=client.download(file, target) {}
                assertEquals(results.getJSONObject(index).getString("wavSha256"), target.sha256())
                if(marksProbe) {
                    val expected=listOf(emptyList(),listOf(5160L,14660L),listOf(5460L))[index]
                    assertEquals(expected,metadata.hardwareHighlights?.map {it.positionMs})
                    assertEquals(JSONArray(expected).toString(),results.getJSONObject(index).getJSONArray("hardwareHighlightsMs").toString())
                    results.getJSONObject(index).put("hardwareHighlightsMatchBle",true)
                }
                results.getJSONObject(index).put("bleMatchesWifi", true)
            }
            report.put("status", "passed")
            stage("complete")
        } catch (e: Throwable) {
            report.put("status", "failed").put("error", e.message ?: e.javaClass.simpleName)
            stage(report.optString("stage"))
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { transfer?.finish() } }
            client.close(); directory.deleteRecursively()
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
}
