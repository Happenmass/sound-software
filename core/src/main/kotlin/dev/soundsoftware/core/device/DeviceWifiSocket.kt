package dev.soundsoftware.core.device

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString

/** One ordered WebSocket for a whole batch; no application-wide network or TLS settings. */
class DeviceWifiSocket(private val client: OkHttpClient, private val url: String,
    private val onDiagnostic: ((Map<String, Any>) -> Unit)? = null) : AutoCloseable {
    private val opened = CompletableDeferred<Unit>()
    private val finished = CompletableDeferred<Unit>()
    private val frames = Channel<DeviceFrame>(512, onUndeliveredElement = { it.payload.fill(0) })
    private val decoder = DeviceFrameDecoder()
    private val transfers = Mutex()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var acceptingFiles = true
    @Volatile private var finishing = false
    @Volatile private var lastActivityNanos = System.nanoTime()
    private var binaryMessages = 0
    private var decodedFrames = 0
    private var textMessages = 0
    private fun diagnostic(stage: String, extra: Map<String, Any> = emptyMap()) {
        val listener = onDiagnostic ?: return
        listener(mapOf("stage" to stage, "binaryMessages" to binaryMessages, "decodedFrames" to decodedFrames, "textMessages" to textMessages) + extra)
    }

    suspend fun open() {
        check(socket == null)
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { diagnostic("websocket-open"); opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivityNanos = System.nanoTime()
                textMessages++
                if (onDiagnostic != null) diagnostic("text-message", mapOf("kind" to if (text.lowercase() in listOf("ok", "pong", "ping", "finish")) text.lowercase() else "other", "length" to text.length))
                if (finishing && (text.equals("OK", true) || text.equals("FINISH", true))) finished.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastActivityNanos = System.nanoTime()
                if (!acceptingFiles) return
                val data = bytes.toByteArray()
                try {
                    binaryMessages++
                    val parsed = decoder.accept(data)
                    decodedFrames += parsed.size
                    if (onDiagnostic != null) diagnostic("binary-message", mapOf("bytes" to data.size, "knownHeader" to (data.size >= 2 && data[0] == 9.toByte() && data[1] == 0xff.toByte())))
                    for (frame in parsed) {
                        if (onDiagnostic != null) diagnostic("frame", mapOf("type" to frame.type, "command" to frame.command, "status" to frame.status, "payloadBytes" to frame.payload.size))
                        if (frames.trySend(frame).isFailure) {
                            frame.payload.fill(0)
                            fail(IOException("Wi-Fi 接收缓冲不足，请重新同步"))
                        }
                    }
                } catch (_: Exception) { fail(IOException("Wi-Fi 数据帧无效，请重新同步")) }
                finally { data.fill(0) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Exception messages may contain the request URL; keep errors free of network identifiers.
                val causes = generateSequence(t) { it.cause }.take(5).joinToString(" / ") { cause ->
                    val code = listOf("EPERM", "EACCES", "ENETUNREACH", "ECONNREFUSED", "ETIMEDOUT").firstOrNull { cause.message.orEmpty().contains(it) }
                    val sslCode = Regex("OPENSSL_internal:([A-Z0-9_]+)").find(cause.message.orEmpty())?.groupValues?.get(1)
                    cause.javaClass.simpleName + ((code ?: sslCode)?.let { ":$it" } ?: "")
                }
                fail(IOException("Wi-Fi 快传连接失败（$causes），请重新同步"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (finishing) finished.complete(Unit)
                webSocket.close(code, null)
                fail(IOException("录音豆已关闭 Wi-Fi 快传连接"))
            }
        })
        try { withTimeout(15_000) { opened.await() } } catch (e: Exception) { close(); throw e }
    }

    private fun fail(error: IOException) {
        failure = error
        opened.completeExceptionally(error)
        frames.close(error)
    }

    fun abort(message: String) {
        fail(IOException(message))
        socket?.cancel()
    }

    fun send(text: String) {
        if (!acceptingFiles) throw IOException("当前 Wi-Fi 批次已结束，请重新同步")
        failure?.let { throw it }
        check(socket?.send(text) == true) { "Wi-Fi 快传连接已关闭" }
    }

    fun pingIfIdle() {
        if (acceptingFiles && !finishing && System.nanoTime() - lastActivityNanos >= 2_000_000_000L) send("ping")
    }

    /** Fresh whole-file retry only. Unknown completion/size layouts cannot publish an asset. */
    suspend fun download(recording: DeviceRecording, session: DeviceCrypto, directory: File,
        readHardwareHighlights: Boolean = false,
        onProgress: (Long) -> Unit): DeviceDownloadResult = transfers.withLock {
        withContext(Dispatchers.IO) {
            var complete = false
            try {
                diagnostic("requesting-header")
                send(DeviceWifiProtocol.fileRequest(recording.fileId))
                val head = withTimeout(15_000) { frames.receive() }
                diagnostic("validating-header")
                val header = try { EncryptedFileHeader.parse(head, recording) } finally { head.payload.fill(0) }
                val assembler = try { DeviceDownloadAssembler(directory, header, session, DownloadDiagnostic("wifi", advertisedListValue = recording.listValue),readHardwareHighlights) }
                    catch (e: Exception) { header.close(); throw e }
                assembler.use { receiving ->
                    diagnostic("receiving-file")
                    withTimeout(maxOf(300_000L, recording.transferBytes / 2)) {
                        while (true) {
                            val frame = withTimeout(15_000) { frames.receive() }
                            val result = try { receiving.accept(frame) } finally { frame.payload.fill(0) }
                            onProgress(receiving.diagnostic().contiguousBytes)
                            if (result != null) {
                                send(DeviceWifiProtocol.command(0x1a, 0x0a, byteArrayOf(1)))
                                complete = true
                                return@withTimeout result
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        error("未收到 Wi-Fi 传输完成帧")
                    }
                }
            } catch (e: Exception) {
                // Disallow another file, but retain the control socket so the owner can send
                // FINISH even after cancellation or a malformed file. Otherwise the recorder
                // may stay in hotspot mode with Bluetooth unavailable until its idle timeout.
                acceptingFiles = false
                frames.cancel()
                throw e
            } finally { if (!complete) directory.deleteRecursively() }
        }
    }

    suspend fun finish() {
        try {
            finishing = true
            if (socket?.send("FINISH") == true) {
                withTimeoutOrNull(1000) { finished.await() }
            }
        } finally { close() }
    }

    override fun close() {
        acceptingFiles = false
        if (failure == null) failure = IOException("Wi-Fi 快传连接已关闭")
        opened.completeExceptionally(IOException("Wi-Fi 快传连接已关闭"))
        socket?.cancel(); socket = null
        frames.cancel(); decoder.reset()
    }
}
