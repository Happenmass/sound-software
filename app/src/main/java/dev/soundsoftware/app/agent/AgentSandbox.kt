package dev.soundsoftware.app.agent

import android.content.Context
import dev.soundsoftware.app.BuildConfig
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One persistent data workspace per recording scope, with a fresh working directory per turn. */
class AgentSandbox(private val context: Context) {
    private val native = File(context.applicationInfo.nativeLibraryDir)
    private val launcher = File(native, "libsound_agent_sandbox.so")
    internal val node = File(native, "libsound_agent_node.so").absolutePath
    internal val codex = File(native, "libsound_agent_codex.so").absolutePath

    internal val supported: Boolean by lazy {
        (BuildConfig.PI_ENABLED || BuildConfig.CODEX_ENABLED) && runCatching {
            ProcessBuilder(launcher.absolutePath, "--check").start().waitFor() == 0
        }.getOrDefault(false)
    }

    internal fun workspaceFor(recordingId: String?): File {
        val root = File(context.filesDir, "agent-workspaces")
        if (recordingId == null) return File(root, "global")
        val digest = MessageDigest.getInstance("SHA-256").digest(recordingId.toByteArray(Charsets.UTF_8))
        val directory = digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        return File(File(root, "recordings"), directory)
    }

    internal fun start(executable: String, args: List<String>, recordingId: String?, relayPort: Int? = null,
        script: Boolean = false,
        prepare: (File) -> Unit = {}): Child {
        check(supported) { "Landlock ABI 6 is required on this phone." }
        val workspace = workspaceFor(recordingId)
        Files.createDirectories(workspace.toPath())
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "agent-").toFile()
        try {
            prepare(work)
            val home = File(work, "home").apply { mkdir() }
            val builder = ProcessBuilder(listOf(launcher.absolutePath, work.absolutePath, native.absolutePath, executable) + args)
                .directory(work)
                .redirectError(File(work, "stderr"))
            builder.environment().apply {
                clear()
                put("SOUND_AGENT_WORKSPACE", workspace.absolutePath)
                put("HOME", home.absolutePath)
                put("CODEX_HOME", home.absolutePath)
                put("TMPDIR", home.absolutePath)
                put("PATH", "/system/bin")
                put("SHELL", "/system/bin/sh")
                if (script) put("SOUND_AGENT_SCRIPT", "1")
                if (relayPort != null) put("SOUND_MODEL_RELAY_PORT", relayPort.toString())
            }
            return Child(work, builder.start())
        } catch (error: Exception) {
            work.deleteRecursively()
            throw error
        }
    }

    internal fun startPi(recordingId: String?): Child = start(node, listOf("pi-host.cjs"), recordingId) { work ->
        context.assets.open("pi-host.cjs").use { input ->
            File(work, "pi-host.cjs").outputStream().use(input::copyTo)
        }
    }

    internal class Child(val work: File, private val process: Process) : Closeable {
        private val writer = process.outputStream.bufferedWriter()
        private val reader = process.inputStream.bufferedReader()

        fun send(value: JsonObject) {
            writer.write(value.toString())
            writer.newLine()
            writer.flush()
        }

        suspend fun receive(): JsonObject = read {
            val line = reader.readLine() ?: throw IOException("The assistant worker exited before replying.")
            Json.parseToJsonElement(line).jsonObject
        }

        suspend fun capture(limit: Int): Pair<Int, String> = read {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val length = process.inputStream.read(buffer)
                if (length == -1) break
                if (result.size() + length > limit) throw IOException("Node output exceeded the tool limit.")
                result.write(buffer, 0, length)
            }
            process.waitFor() to result.toString(Charsets.UTF_8.name())
        }

        private suspend fun <T> read(action: () -> T): T = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { process.destroyForcibly() }
            Dispatchers.IO.dispatch(continuation.context) {
                try {
                    continuation.resume(action())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        }

        override fun close() {
            process.destroyForcibly()
            runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            // Node or Codex may leave symlinks. Delete entries without following them.
            Files.walk(work.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
