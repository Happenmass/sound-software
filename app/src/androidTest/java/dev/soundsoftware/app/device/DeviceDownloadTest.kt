package dev.soundsoftware.app.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.core.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import android.media.AudioAttributes
import android.media.MediaPlayer
import dev.soundsoftware.app.inference.D3200AudioDecoder
import dev.soundsoftware.core.audio.sha256

/** Explicit hardware test, restricted to the recording created for this session. */
@RunWith(AndroidJUnit4::class)
class DeviceDownloadTest {
    @Test fun downloadAuthorizedRecording() = runBlocking {
        val fileId = InstrumentationRegistry.getArguments().getString("deviceFileId")?.toLongOrNull()
        assumeTrue("Explicit deviceFileId is required for hardware download", fileId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val discovered = CompletableDeferred<NearbyRecorder>()
        val client = BleDeviceClient(context)
        val report = JSONObject().put("status", "started")
        val evidence = File(context.getExternalFilesDir(null), "validation/device-download.json")
        evidence.parentFile!!.mkdirs()
        fun save() { evidence.writeText(report.toString(2)) }
        try {
            client.startScan({ discovered.complete(it) }, {})
            val device = withTimeout(30000) { discovered.await() }
            client.stopScan()
            client.connect(device.address) {}
            val recording = client.listFiles().files.single { it.fileId == fileId }
            report.put("advertisedListValue", recording.listValue)
            val result = client.downloadEncrypted(recording) {
                report.put("diagnostic", JSONObject().put("stage", it.stage)
                    .put("cryptoHandshake", it.cryptoHandshake.toString())
                    .put("negotiatedMtu", it.negotiatedMtu)
                    .put("headerFileIdMatches", it.headerFileIdMatches).put("headerErrorCode", it.headerErrorCode)
                    .put("sizeLayout", it.sizeLayout).put("expectedSlices", it.expectedSlices)
                    .put("expectedPayloadBytes", it.expectedPayloadBytes)
                    .put("handshakePayloadBytes", it.handshakePayloadBytes)
                    .put("headerPayloadBytes", it.headerPayloadBytes)
                    .put("headerBytes", it.headerBytes).put("slicePackets", it.slicePackets)
                    .put("slices", it.slices).put("firstSequence", it.firstSequence)
                    .put("maximumSequence", it.maximumSequence).put("contiguousBytes", it.contiguousBytes)
                    .put("lastCommand", it.lastCommand).put("lastStatus", it.lastStatus)
                    .put("lastPayloadBytes", it.lastPayloadBytes)
                    .put("sliceFlags", it.sliceFlags.toString()).put("slicePadding", it.slicePadding.toString()))
                save()
            }
            assertEquals(result.diagnostic.expectedPayloadBytes, result.decryptedFile.length())
            report.put("status", "complete").put("decryptedSha256", result.decryptedSha256)
                .put("encryptedSha256", result.encryptedSha256)
                .put("privateFile", result.decryptedFile.relativeTo(context.filesDir).path)
                .put("integrity", result.integrity).put("playable", result.playable)
            save()
            val wavFile = File(result.decryptedFile.parentFile, "decoded.wav")
            val wav = D3200AudioDecoder().decode(result, wavFile)
            assertEquals(recording.listValue, wav.durationMs)
            report.put("decode", JSONObject().put("sampleRate", wav.sampleRate)
                .put("channels", wav.channels).put("durationMs", wav.durationMs)
                .put("sampleCount", wav.sampleCount).put("wavSha256", wavFile.sha256()))
            save()
            val finished = CompletableDeferred<Unit>()
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                player.setDataSource(wavFile.path)
                player.setOnCompletionListener { finished.complete(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    finished.completeExceptionally(IllegalStateException("MediaPlayer error $what/$extra")); true
                }
                player.prepare(); player.start()
                withTimeout(4000) { while (player.currentPosition <= 100) delay(100) }
                withTimeout(wav.durationMs + 5000) { finished.await() }
                report.put("decode", JSONObject().put("sampleRate", wav.sampleRate)
                    .put("channels", wav.channels).put("durationMs", wav.durationMs)
                    .put("sampleCount", wav.sampleCount).put("wavSha256", wavFile.sha256())
                    .put("mediaPlayerCompleted", true))
                report.put("playable", true)
                save()
            } finally { player.release() }
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message ?: error.javaClass.simpleName)
            save()
            throw error
        } finally { client.close() }
    }
}
