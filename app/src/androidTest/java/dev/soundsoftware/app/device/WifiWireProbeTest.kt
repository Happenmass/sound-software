package dev.soundsoftware.app.device

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.core.device.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only wire experiment. Records only frame metadata, never audio/keys/credentials. */
@RunWith(AndroidJUnit4::class)
class WifiWireProbeTest {
    @Test fun identifyFileRequestEncoding() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("wifiWireProbe") == "true")
        val context = instrumentation.targetContext
        val bound = requireNotNull(SettingsStore(context).state.value.boundDevice)
        val activity = instrumentation.startActivitySync(Intent(context, WifiProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val report = JSONObject().put("status", "running")
        val evidence = File(context.getExternalFilesDir(null), "validation/wifi-wire.json").apply { parentFile!!.mkdirs() }
        fun save(stage: String) { report.put("stage", stage); evidence.writeText(report.toString(2)) }
        val ble = BleDeviceClient(context)
        val lease = RecorderWifiNetwork(context)
        var http: OkHttpClient? = null
        var socket: WebSocket? = null
        var keepAlive: Job? = null
        try {
            save("scan")
            val seen = ConcurrentHashMap<String, NearbyRecorder>()
            ble.startScan({ seen[it.address] = it }, {})
            delay(6000); ble.stopScan()
            check(seen.isNotEmpty())
            var matched = false
            for (candidate in seen.values.sortedByDescending { it.address == bound.address }) {
                ble.connect(candidate.address) {}
                if (ble.readInfo().serial == bound.serial) { matched = true; break }
                ble.close()
            }
            check(matched)
            val inventory = ble.listFiles()
            check(inventory.activeRecordingFileId == null)
            val file = inventory.files.first { it.durationMs in 20..60_000 }
            DeviceCrypto().use { crypto ->
                val handshake = ble.request(0x2e, 1, crypto.publicKey())
                try {
                    check(handshake.successful && handshake.payload.size == 97)
                    val public = handshake.payload.copyOfRange(0, 65)
                    val check = handshake.payload.copyOfRange(65, 97)
                    try { crypto.establish(public, check) } finally { public.fill(0); check.fill(0) }
                } finally { handshake.payload.fill(0) }
            }
            val ssid = "WiFi-" + UUID.randomUUID().toString().replace("-", "").take(12)
            val password = UUID.randomUUID().toString().replace("-", "").take(24)
            val credentials = DeviceWifiProtocol.credentials(ssid, password)
            val response = try { ble.request(0x1a, 5, credentials) } finally { credentials.fill(0) }
            val endpoint = try { DeviceWifiProtocol.endpoint(response) } finally { response.payload.fill(0) }
            save("joining-hotspot")
            val network = lease.join(ssid, password)
            val client = lease.client(network, endpoint).also { http = it }
            keepAlive = launch(Dispatchers.IO) { lease.keepAlive(network, endpoint.host) }
            val packet18 = D3200Protocol.command(0x1a, 7, D3200Protocol.fileRequest(file.fileId).copyOf(8))
            val packet19 = D3200Protocol.command(0x1a, 7, D3200Protocol.fileRequest(file.fileId))
            val legacy = packet18.copyOf().also { it[it.lastIndex] = 0x1b }
            data class Mode(val name: String, val bytes: ByteArray, val binary: Boolean = false, val spaces: Boolean = false, val ping: Boolean = false)
            val modes = listOf(Mode("hex-legacy18", legacy), Mode("hex18", packet18), Mode("hex19", packet19),
                Mode("binary18", packet18, binary = true), Mode("binary19", packet19, binary = true),
                Mode("spaced-hex18", packet18, spaces = true), Mode("ping-hex18", packet18, ping = true))
            val results = JSONArray().also { report.put("modes", it) }
            var success = false
            for (mode in modes) {
                save(mode.name)
                val received = CompletableDeferred<JSONObject>()
                val decoder = DeviceFrameDecoder()
                val ws = client.newWebSocket(Request.Builder().url("wss://${endpoint.host}:${endpoint.port}").build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (mode.ping) webSocket.send("ping")
                        if (mode.binary) webSocket.send(mode.bytes.toByteString())
                        else webSocket.send(mode.bytes.joinToString(if (mode.spaces) " " else "") { "%02x".format(it.toInt() and 255) })
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val data = bytes.toByteArray()
                        val frames = decoder.accept(data)
                        val frame = frames.firstOrNull()
                        val summary = JSONObject().put("binaryBytes", data.size).put("decodedFrames", frames.size)
                        if (frame != null) summary.put("type", frame.type).put("command", frame.command).put("status", frame.status).put("payloadBytes", frame.payload.size)
                        received.complete(summary)
                        data.fill(0); frames.forEach { it.payload.fill(0) }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) { report.put("lastTextLength", text.length) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { received.complete(JSONObject().put("errorType", t.javaClass.simpleName)) }
                })
                socket = ws
                val result = withTimeoutOrNull(7000) { received.await() } ?: JSONObject().put("timeout", true)
                result.put("mode", mode.name); results.put(result)
                save(mode.name)
                if (result.optInt("type") == 0x1a && result.optInt("command") == 7) {
                    report.put("matched", mode.name); success = true
                    ws.send("FINISH"); delay(1000); ws.close(1000, null)
                    break
                }
                ws.close(1000, null); delay(300)
            }
            report.put("status", if (success) "matched" else "no-response")
            save("complete")
            check(success) { "No known file request encoding received a file header" }
        } catch (e: Exception) {
            report.put("status", "failed").put("errorType", e.javaClass.simpleName)
            save(report.optString("stage")); throw e
        } finally {
            socket?.send("FINISH")
            withContext(NonCancellable) { delay(300); keepAlive?.cancelAndJoin() }
            socket?.cancel(); lease.close(); ble.close()
            http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
