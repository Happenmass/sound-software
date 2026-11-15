package dev.soundsoftware.app.agent

import dev.soundsoftware.core.agent.AgentToolSchemas
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okio.buffer
import okio.source

/** One private App endpoint for a stdio MCP worker. The executor already holds the recording scope. */
internal class RecordingMcpBridge(private val execute: suspend (String, JsonObject) -> JsonObject) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val port get() = server.localPort
    val connection get() = buildJsonObject { put("port", port); put("token", token) }

    init {
        scope.launch {
            while (isActive) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use { client ->
                    client.soTimeout = 3_000
                    val reply = try {
                        val line = client.source().buffer().readUtf8LineStrict(64L * 1024)
                        val request = Json.parseToJsonElement(line).jsonObject
                        check(request["token"] == JsonPrimitive(token)) { "Invalid recording MCP connection" }
                        when (request["operation"]?.jsonPrimitive?.content) {
                            "list" -> buildJsonObject {
                                put("schemas", AgentToolSchemas.json)
                            }
                            "call" -> execute(request.getValue("name").jsonPrimitive.content,
                                request.getValue("arguments").jsonObject)
                            else -> error("Unknown recording MCP operation")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RecordingScopeDenied) {
                        buildJsonObject { put("error", "Recording is outside the authorized scope."); put("code", "recording_scope_denied") }
                    } catch (_: Exception) {
                        buildJsonObject { put("error", "Invalid recording tool request") }
                    }
                    runCatching { client.getOutputStream().write((reply.toString() + "\n").toByteArray()) }
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        server.close()
    }
}
