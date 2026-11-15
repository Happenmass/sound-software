package dev.soundsoftware.app.agent

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import okio.source

/** A per-turn Responses API relay. The configured upstream credential stays in Android. */
internal class CodexModelRelay(
    private val base: HttpUrl,
    private val key: String?,
    upstream: OkHttpClient = OkHttpClient(),
) : Closeable {
    private val client = upstream.newBuilder().followRedirects(false).followSslRedirects(false)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val worker = thread(name = "codex-model-relay", isDaemon = true, start = false) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { break }
            sockets.add(socket)
            socket.use {
                try {
                    if (!server.isClosed) forward(socket)
                } catch (_: java.io.IOException) {
                    // The backend may close the socket on cancellation or after reading the response.
                } finally {
                    sockets.remove(socket)
                }
            }
        }
    }.apply { start() }

    val port get() = server.localPort
    val localBase get() = "http://127.0.0.1:$port/$token"

    private fun forward(socket: Socket) {
        socket.soTimeout = 15_000
        val source = socket.source().buffer()
        val output = socket.sink().buffer()
        fun reply(status: Int, contentType: String = "application/json") {
            output.writeUtf8("HTTP/1.1 $status Response\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n").flush()
        }
        val request = source.readUtf8LineStrict(8_192).split(' ')
        val path = request.getOrNull(1)
        val suffix = when (path) {
            "/$token/responses" -> "responses"
            "/$token/responses/compact" -> "responses/compact"
            else -> null
        }
        if (request.firstOrNull() != "POST" || suffix == null) {
            reply(404)
            return
        }
        var length: Long? = null
        var ended = false
        for (i in 0 until 64) {
            val line = source.readUtf8LineStrict(8_192)
            if (line.isEmpty()) { ended = true; break }
            val name = line.substringBefore(':')
            if (name.equals("Transfer-Encoding", true)) { reply(400); return }
            if (name.equals("Content-Length", true)) {
                if (length != null) { reply(400); return }
                length = line.substringAfter(':').trim().toLongOrNull()
            }
        }
        if (!ended || length == null || length !in 1..(32L * 1024 * 1024)) {
            reply(400)
            return
        }
        val body = source.readByteArray(length)
        val requestBuilder = Request.Builder().url(base.newBuilder().addPathSegments(suffix).build())
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!key.isNullOrBlank()) requestBuilder.header("Authorization", "Bearer $key")
        val call = client.newCall(requestBuilder.build())
        calls.add(call)
        try {
            if (server.isClosed) return
            val response = try { call.execute() } catch (_: java.io.IOException) {
                reply(502)
                return
            }
            response.use {
                // Avoid passing arbitrary upstream errors or headers into Codex logs and the UI.
                if (!it.isSuccessful) { reply(it.code); return }
                reply(it.code, if (it.header("Content-Type").orEmpty().startsWith("text/event-stream"))
                    "text/event-stream" else "application/json")
                val upstream = it.body?.source() ?: return
                val buffer = okio.Buffer()
                while (true) {
                    val count = upstream.read(buffer, 8192)
                    if (count == -1L) break
                    output.write(buffer, count)
                    output.flush()
                }
            }
        } finally {
            calls.remove(call)
        }
    }

    override fun close() {
        server.close()
        calls.forEach(Call::cancel)
        sockets.forEach { runCatching { it.close() } }
    }


}
