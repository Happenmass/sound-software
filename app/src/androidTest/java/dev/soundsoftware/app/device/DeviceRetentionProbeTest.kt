package dev.soundsoftware.app.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.inference.D3200AudioDecoder
import dev.soundsoftware.core.device.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in retention experiment. Mutations require a backed-up, empty-device-created test fixture. */
@RunWith(AndroidJUnit4::class)
class DeviceRetentionProbeTest {
    @Test fun inspectKnownTestRecording() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("retentionProbe")
        assumeTrue("Explicit retentionProbe required", mode in listOf("snapshot", "read-test-file", "create-test", "backup-created-test",
            "unbind-test", "bind-ack-test", "bind-confirm-test"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bound = requireNotNull(SettingsStore(context).state.value.boundDevice)
        val report = JSONObject().put("mode", mode).put("stage", "scan")
        val evidence = File(context.getExternalFilesDir(null), "validation/retention-$mode.json")
        evidence.parentFile!!.mkdirs()
        fun save() { evidence.writeText(report.toString(2)) }
        val client = BleDeviceClient(context)
        try {
            val seen = ConcurrentHashMap<String, NearbyRecorder>()
            client.startScan({ seen[it.address] = it }, { report.put("scanError", it) })
            delay(4000); client.stopScan()
            val device = seen[bound.address] ?: seen.values.singleOrNull()
            requireNotNull(device) { "No unique candidate for the bound test device" }
            report.put("advertisedBound", device.boundToOther)
            client.connect(device.address) {}
            val info = client.readInfo()
            check(!bound.serial.isNullOrBlank() && info.serial == bound.serial) { "Device identity mismatch" }
            report.put("serialMatchesSaved", true).put("firmware", info.firmware)
                .put("totalKb", info.totalKb).put("freeKb", info.freeKb)
                .put("usedKb", info.totalKb - info.freeKb).put("recordingFlag", info.extras?.recording)
                .put("beanCharging", info.charging).put("caseCharging", info.caseCharging)
            val pages = JSONArray()
            for (index in 0..3) {
                val frame = client.request(0x1a, 0x0e, D3200Protocol.page(index))
                val parsed = DevicePayloads.files(frame.payload)
                pages.put(JSONObject().put("page", index).put("success", frame.successful)
                    .put("payloadBytes", frame.payload.size).put("entries", parsed.files.size)
                    .put("historicalTestListed", parsed.files.any { it.fileId == 1794057634L })
                    .put("activeRecordingPresent", parsed.recordingFileId != 0L))
            }
            report.put("pages", pages).put("stage", "snapshot"); save()
            if (mode in setOf("unbind-test", "bind-ack-test", "bind-confirm-test")) {
                check(args.getString("authorizedTestRecording") == "true")
                val manifest = JSONObject(File(context.getExternalFilesDir(null), "validation/retention-created-test.json").readText())
                check(manifest.getString("stage") == "test-created-and-backed-up")
                check(File(context.getExternalFilesDir(null), "validation/retention-test-backup.wav").length() > 44)
                val inventory = client.listFiles()
                check(inventory.activeRecordingFileId == null && inventory.files.all { it.fileId == manifest.getLong("testFileId") }) {
                    "A non-test recording is present; refusing binding mutation"
                }
                if (mode == "unbind-test") {
                    check(device.boundToOther == true && inventory.files.size == 1)
                    client.unbind()
                    report.put("stage", "unbind-request-complete"); save()
                } else if (mode == "bind-ack-test") {
                    check(device.boundToOther == false)
                    val ack = client.request(0x0b, 0x87, byteArrayOf(1))
                    report.put("bindAckSuccess", ack.successful).put("bindAckStatus", ack.status)
                    val afterAck = client.readInfo()
                    val afterList = client.listFiles()
                    report.put("afterAckUsedKb", afterAck.totalKb - afterAck.freeKb)
                        .put("afterAckCount", afterList.files.size).put("stage", "ack-only-snapshot"); save()
                } else {
                    check(device.boundToOther == false)
                    val outcome = client.bind { report.put("stage", "awaiting-side-key"); save() }
                    report.put("bindingConfirmed", outcome == BindOutcome.CONFIRMED)
                        .put("stage", "binding-result"); save()
                    check(outcome == BindOutcome.CONFIRMED) { "Test binding was not device-confirmed" }
                }
            }
            if (mode == "snapshot") {
                try {
                    val frame = client.request(0x1b, 0x0e, D3200Protocol.page(0), timeoutMs = 4000)
                    val parsed = DevicePayloads.files(frame.payload, withEndTime = true)
                    report.put("withEndTime", JSONObject().put("success", frame.successful)
                        .put("entries", parsed.files.size).put("payloadBytes", frame.payload.size)
                        .put("activeRecordingPresent", parsed.recordingFileId != 0L))
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    report.put("withEndTimeError", e.javaClass.simpleName)
                }
                save()
            }
            if (mode == "create-test") {
                check(args.getString("authorizedTestRecording") == "true") { "Explicit test-recording authorization required" }
                val empty = client.listFiles()
                check(empty.files.isEmpty() && empty.activeRecordingFileId == null && info.extras?.recording == false) {
                    "Test creation requires an empty, idle device; existing recordings must not be touched"
                }
                report.put("emptyBaselineUsedKb", info.totalKb - info.freeKb).put("stage", "recording-test"); save()
                try {
                    client.setRecording(true)
                    delay(8000)
                } finally { client.setRecording(false) }
                val created = withTimeout(15000) {
                    while (true) {
                        val current = client.listFiles()
                        if (current.activeRecordingFileId == null && current.files.size == 1) return@withTimeout current
                        delay(500)
                    }
                    @Suppress("UNREACHABLE_CODE") error("No completed test recording")
                }
                val after = client.readInfo()
                report.put("afterCreationCount", created.files.size)
                    .put("afterCreationActiveRecording", created.activeRecordingFileId != null)
                    .put("afterCreationRecordingFlag", after.extras?.recording)
                    .put("afterCreationUsedKb", after.totalKb - after.freeKb); save()
                check(created.activeRecordingFileId == null && created.files.size == 1) { "Expected exactly one stopped test recording" }
                val recording = created.files.single()
                report.put("testFileId", recording.fileId).put("testListValue", recording.listValue)
                    .put("afterCreationUsedKb", after.totalKb - after.freeKb)
                    .put("afterCreationFreeKb", after.freeKb).put("stage", "backing-up-test"); save()
                val backup = client.downloadEncrypted(recording)
                val wav = File(context.getExternalFilesDir(null), "validation/retention-test-backup.wav")
                val decoded = D3200AudioDecoder().decode(backup, wav)
                report.put("testDecryptedSha256", backup.decryptedSha256)
                    .put("backupDurationMs", decoded.durationMs).put("backupBytes", wav.length())
                    .put("backupPrivateRelativePath", "validation/retention-test-backup.wav").put("stage", "test-created-and-backed-up"); save()
                val manifest = File(context.getExternalFilesDir(null), "validation/retention-created-test.json")
                manifest.writeText(report.toString(2))
            }
            if (mode == "backup-created-test") {
                check(args.getString("authorizedTestRecording") == "true")
                val inventory = client.listFiles()
                check(inventory.activeRecordingFileId == null && inventory.files.size == 1)
                val recording = inventory.files.single()
                val minimum = requireNotNull(args.getString("minimumTestEpoch")).toLong()
                check(recording.fileId >= minimum && recording.fileId <= System.currentTimeMillis() / 1000 + 120) {
                    "The sole recording is outside the controlled test creation window"
                }
                check(recording.durationMs in 1000..30000) { "Not a short test recording" }
                val creation = JSONObject(File(context.getExternalFilesDir(null), "validation/retention-create-test.json").readText())
                check(creation.getBoolean("serialMatchesSaved") && creation.getJSONArray("pages").getJSONObject(0).getInt("entries") == 0)
                report.put("emptyBaselineUsedKb", creation.getLong("emptyBaselineUsedKb"))
                    .put("testFileId", recording.fileId).put("testListValue", recording.listValue)
                    .put("afterCreationUsedKb", info.totalKb - info.freeKb).put("stage", "backing-up-test"); save()
                val result = client.downloadEncrypted(recording)
                val wav = File(context.getExternalFilesDir(null), "validation/retention-test-backup.wav")
                val decoded = D3200AudioDecoder().decode(result, wav)
                report.put("testDecryptedSha256", result.decryptedSha256).put("backupDurationMs", decoded.durationMs)
                    .put("backupBytes", wav.length()).put("backupPrivateRelativePath", "validation/retention-test-backup.wav")
                    .put("stage", "test-created-and-backed-up"); save()
                File(context.getExternalFilesDir(null), "validation/retention-created-test.json").writeText(report.toString(2))
            }
            if (mode == "read-test-file") {
                check(args.getString("authorizedTestRecording") == "true") { "Explicit test-recording authorization required" }
                val id = requireNotNull(args.getString("testFileId")).toLong()
                val value = requireNotNull(args.getString("testListValue")).toLong()
                val expectedHash = requireNotNull(args.getString("expectedDecryptedSha256"))
                report.put("stage", "direct-read"); save()
                val result = client.downloadEncrypted(DeviceRecording(id, value)) { d ->
                    report.put("downloadStage", d.stage).put("handshakePayloadBytes", d.handshakePayloadBytes)
                        .put("headerPayloadBytes", d.headerPayloadBytes).put("headerBytes", d.headerBytes)
                        .put("headerFileIdMatches", d.headerFileIdMatches).put("headerErrorCode", d.headerErrorCode)
                        .put("lastStatus", d.lastStatus).put("expectedSlices", d.expectedSlices)
                        .put("receivedSlices", d.slices)
                    save()
                }
                report.put("directReadSucceeded", true).put("decryptedHashMatches", result.decryptedSha256 == expectedHash)
                    .put("decryptedBytes", result.decryptedFile.length()).put("stage", "complete"); save()
                assertEquals("Known test recording must retain its original bytes", expectedHash, result.decryptedSha256)
                // Keep the recovered test file private, like the existing download validation.
            }
        } catch (e: Exception) {
            report.put("errorType", e.javaClass.simpleName).put("error", e.message); save()
            throw e
        } finally { client.close() }
    }
}
