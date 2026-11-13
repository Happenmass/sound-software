package dev.soundsoftware.core.device

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceWifiTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun hotspotCredentialsEndpointAndSpecialFileRequestAreExact() {
        assertArrayEquals(byteArrayOf(4) + "test".toByteArray() + byteArrayOf(8) + "abcdefgh".toByteArray(),
            DeviceWifiProtocol.credentials("test", "abcdefgh"))
        val endpoint = DeviceWifiProtocol.endpoint(DeviceFrame(0x1a, 5, 1, byteArrayOf(1, 4, 168.toByte(), 192.toByte(), 0x90.toByte(), 0x1f)))
        assertEquals("192.168.4.1", endpoint.host); assertEquals(8080, endpoint.port)
        assertEquals("08ee0000001a07120000000000785634121b", DeviceWifiProtocol.fileRequest(0x12345678))
        assertThrows(IllegalArgumentException::class.java) { DeviceWifiProtocol.endpoint(DeviceFrame(0x1a, 5, 0, ByteArray(6))) }
        assertThrows(IllegalArgumentException::class.java) { DeviceWifiProtocol.endpoint(DeviceFrame(0x1a, 5, 1, ByteArray(5))) }
        assertThrows(IllegalArgumentException::class.java) { DeviceWifiProtocol.credentials("test", "short") }
    }

    @Test fun threeEncryptedFilesShareOneSocketAndFinishAfterCompleteAcks() = runBlocking {
        val fixture = DeviceDownloadTest().fixture(160, 20, 166)
        val recordings = (1L..3L).map { DeviceRecording(it, 20) }
        val received = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        val client = OkHttpClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val index = recordings.indexOfFirst { DeviceWifiProtocol.fileRequest(it.fileId) == text }
                if (index >= 0) {
                    val payload = fixture.headerPayload.copyOf()
                    java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(recordings[index].fileId.toInt())
                    val bytes = wire(DeviceFrame(0x1a, 7, 1, payload)) + wire(fixture.slice(0)) + wire(DeviceFrame(0x1a, 0x0a, 1, ByteArray(9)))
                    // Split the header, then coalesce header tail + slices + completion.
                    webSocket.send(bytes.copyOfRange(0, 13).toByteString())
                    webSocket.send(bytes.copyOfRange(13, bytes.size).toByteString())
                } else if (text == "FINISH") webSocket.send("OK")
            }
        }))
        val socket = DeviceWifiSocket(client, server.url("/").toString().replace("http:", "ws:"))
        try {
            socket.open()
            for (recording in recordings) {
                val result = socket.download(recording, fixture.session, File(temporary.root, "file-${recording.fileId}")) {}
                assertArrayEquals(fixture.plain, result.decryptedFile.readBytes())
                assertEquals(20L, result.diagnostic.advertisedListValue)
                assertEquals("complete", result.diagnostic.stage)
            }
            socket.finish()
            assertEquals(listOf(DeviceWifiProtocol.fileRequest(1), ack(), DeviceWifiProtocol.fileRequest(2), ack(),
                DeviceWifiProtocol.fileRequest(3), ack(), "FINISH"), received.toList())
            assertEquals(1, server.requestCount)
        } finally { socket.close(); fixture.close(); client.dispatcher.executorService.shutdown(); server.close() }
    }

    @Test fun missingSliceOrUnknownCompletionCannotCommitAndClosesBatch() = runBlocking {
        for (missing in listOf(true, false)) {
            DeviceDownloadTest().fixture(320, 40, 332).use { f ->
                val server = MockWebServer(); val client = OkHttpClient()
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val data = wire(DeviceFrame(0x1a, 7, 1, f.headerPayload)) + wire(f.slice(0)) +
                            (if (missing) byteArrayOf() else wire(f.slice(1))) +
                            wire(DeviceFrame(0x1a, 0x0a, 1, ByteArray(if (missing) 9 else 8)))
                        webSocket.send(data.toByteString())
                    }
                }))
                val socket = DeviceWifiSocket(client, server.url("/").toString().replace("http:", "ws:"))
                val dir = File(temporary.root, "bad-$missing")
                try {
                    socket.open()
                    try { socket.download(f.recording, f.session, dir) {}; fail("Incomplete recording accepted") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
                    assertFalse(dir.exists())
                    assertThrows(java.io.IOException::class.java) { socket.send(DeviceWifiProtocol.fileRequest(f.recording.fileId)) }
                } finally { socket.close(); client.dispatcher.executorService.shutdown(); server.close() }
            }
        }
    }

    @Test fun cancellationWhileReceivingRemovesPartialFileAndClosesSocket() = runBlocking {
        DeviceDownloadTest().fixture(160, 20, 166).use { f ->
            val server = MockWebServer(); val client = OkHttpClient(); val headerSent = CompletableDeferred<Unit>()
            val finishSeen = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text == "FINISH") { finishSeen.complete(Unit); webSocket.send("OK"); return }
                    webSocket.send(wire(DeviceFrame(0x1a, 7, 1, f.headerPayload)).toByteString())
                    headerSent.complete(Unit)
                }
            }))
            val socket = DeviceWifiSocket(client, server.url("/").toString().replace("http:", "ws:"))
            val dir = File(temporary.root, "cancelled")
            try {
                socket.open()
                val job = launch { socket.download(f.recording, f.session, dir) {} }
                withTimeout(3000) { headerSent.await() }
                job.cancelAndJoin()
                assertFalse(dir.exists())
                assertThrows(java.io.IOException::class.java) { socket.send("ping") }
                socket.finish()
                withTimeout(3000) { finishSeen.await() }
                Unit
            } finally { socket.close(); client.dispatcher.executorService.shutdown(); server.close() }
        }
    }

    @Test fun heartbeatOkCannotBeMistakenForFinishAcknowledgement() = runBlocking {
        val server = MockWebServer(); val client = OkHttpClient()
        val earlyOk = CompletableDeferred<Unit>()
        val finishSeen = CompletableDeferred<Unit>()
        val peer = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer.complete(webSocket); webSocket.send("OK") }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text == "FINISH") finishSeen.complete(Unit) }
        }))
        val socket = DeviceWifiSocket(client, server.url("/").toString().replace("http:", "ws:")) {
            if (it["stage"] == "text-message") earlyOk.complete(Unit)
        }
        try {
            socket.open(); withTimeout(3000) { earlyOk.await() }
            val finish = async { socket.finish() }
            withTimeout(3000) { finishSeen.await() }
            yield()
            assertFalse("A pre-FINISH OK must not release the hotspot", finish.isCompleted)
            peer.await().send("OK")
            withTimeout(1000) { finish.await() }
        } finally { socket.close(); client.dispatcher.executorService.shutdown(); server.close() }
    }

    private fun ack() = DeviceWifiProtocol.command(0x1a, 0x0a, byteArrayOf(1))
    private fun wire(frame: DeviceFrame): ByteArray = D3200Protocol.command(frame.type, frame.command, frame.payload).also {
        it[0] = 9; it[1] = 0xff.toByte(); it[4] = frame.status.toByte()
        it[it.lastIndex] = D3200Protocol.checksum(it, it.lastIndex).toByte()
    }
}
