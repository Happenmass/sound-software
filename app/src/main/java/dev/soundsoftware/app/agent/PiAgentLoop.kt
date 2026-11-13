package dev.soundsoftware.app.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import dev.soundsoftware.core.agent.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Android supplies I/O and verifies evidence. The Pi host owns the session transcript. */
class PiAgentLoop(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun command(
        action: String,
        recordingId: String?,
        index: RecordingIndex,
        sessionId: String? = null,
        question: String? = null,
        language: String = "zh",
        model: AgentModel? = null,
        onEvent: (AssistantEvent) -> Unit = {},
    ): AssistantSession = withContext(Dispatchers.IO) {
        val generation = index.corpusGeneration
        val tools = index.toolsFor(recordingId)
        val receipts = linkedSetOf<RecordingCitation>()
        val bound = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { bound.complete(Unit) }
            override fun onServiceDisconnected(name: ComponentName) {
                bound.completeExceptionally(IOException("Pi worker disconnected"))
            }
        }
        check(context.bindService(Intent(context, PiNodeService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            "Could not bind the Pi worker"
        }
        try {
            val socket = withTimeoutOrNull(15_000) {
                bound.await()
                connect(File(context.cacheDir, PI_SOCKET).path)
            } ?: throw IOException("Pi worker did not start")
            socket.use { peer ->
                val writer = peer.outputStream.bufferedWriter()
                val reader = peer.inputStream.bufferedReader()
                fun send(value: JsonObject) {
                    writer.write(value.toString())
                    writer.newLine()
                    writer.flush()
                }
                send(buildJsonObject {
                    put("action", action)
                    putJsonObject("scope") { put("recordingId", recordingId) }
                    sessionId?.let { put("sessionId", it) }
                    question?.let { put("question", it) }
                    if (action == "ask") {
                        put("schemas", AgentToolSchemas.json)
                        put("language", language)
                        put("time", ZonedDateTime.now().toString())
                    }
                })
                while (true) {
                    val line = read(peer, reader) ?: throw IOException("Pi worker ended before returning a result")
                    val frame = json.parseToJsonElement(line).jsonObject
                    when (val kind = frame.text("kind")) {
                        "result" -> {
                            frame["error"]?.let { throw IOException(it.jsonPrimitive.content) }
                            return@withContext json.decodeFromJsonElement<AssistantSession>(frame.getValue("snapshot"))
                        }
                        "event" -> {
                            val value = frame.getValue("value")
                            when (frame.text("event")) {
                                "draft" -> onEvent(AssistantEvent.Draft(value.jsonPrimitive.content))
                                "trace" -> onEvent(AssistantEvent.ToolProgress(json.decodeFromJsonElement(value)))
                                "usage" -> onEvent(AssistantEvent.Usage(json.decodeFromJsonElement<AgentUsage?>(value)))
                            }
                        }
                        else -> {
                            val response = try {
                                val value = when (kind) {
                                    "storage_read" -> index.readAssistantBody(recordingId, generation)
                                    "storage_write" -> {
                                        index.writeAssistantBody(recordingId, generation, frame.getValue("body").jsonObject,
                                            frame["reset"]?.jsonPrimitive?.booleanOrNull == true)
                                        JsonNull
                                    }
                                    "model" -> {
                                        check(index.corpusGeneration == generation) { "The recording library changed. Please ask again." }
                                        val messages = json.decodeFromJsonElement<List<AgentMessage>>(frame.getValue("messages"))
                                        val adapter = checkNotNull(model)
                                        val reply = if (frame.getValue("tools").jsonPrimitive.boolean) {
                                            adapter.completeStreaming(messages) { onEvent(AssistantEvent.Draft(it)) }
                                        } else adapter.complete(messages, false)
                                        reply.toWire()
                                    }
                                    "tool" -> {
                                        val result = try {
                                            tools.execute(frame.text("name"), frame.getValue("args").jsonObject)
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Exception) {
                                            buildJsonObject { put("error", "Invalid recording tool or arguments") }
                                        }
                                        (result["evidence"] as? JsonArray)?.forEach {
                                            receipts += json.decodeFromJsonElement<RecordingCitation>(it)
                                        }
                                        result
                                    }
                                    "verify" -> json.encodeToJsonElement(verifyRecordingAnswer(frame.text("raw"), receipts, tools, language))
                                    else -> error("Unknown Pi worker request")
                                }
                                buildJsonObject { put("value", value) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                buildJsonObject { put("error", error.message ?: "Android assistant callback failed") }
                            }
                            send(response)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Pi worker ended")
            }
        } finally {
            context.unbindService(connection)
        }
    }

    private suspend fun connect(path: String): LocalSocket {
        while (true) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                return socket
            } catch (_: IOException) {
                socket.close()
                delay(25)
            }
        }
    }

    private suspend fun read(socket: LocalSocket, reader: BufferedReader): String? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            Dispatchers.IO.dispatch(continuation.context) {
                try {
                    continuation.resume(reader.readLine())
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                }
            }
        }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun AgentReply.toWire() = buildJsonObject {
        put("text", text)
        put("usage", json.encodeToJsonElement(usage))
        putJsonArray("calls") {
            calls.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                })
            }
        }
    }
}
