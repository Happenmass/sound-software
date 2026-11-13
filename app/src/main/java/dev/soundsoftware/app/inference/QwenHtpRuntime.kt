package dev.soundsoftware.app.inference

import android.content.Context
import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.R
import dev.soundsoftware.core.asr.AsrException
import dev.soundsoftware.core.asr.ErrorCode
import dev.soundsoftware.core.asr.LocalAsrRuntime
import dev.soundsoftware.core.asr.LocalRecognition
import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class QwenHtpFiles(context: Context, modelRoot: File) {
    private val app = context.applicationContext
    private val nativeDir = File(app.applicationInfo.nativeLibraryDir)
    val executable = File(nativeDir, "libsound_qwen_htp_worker.so")
    val runtimeDir = File(app.codeCacheDir, "qwen-htp-2.51.0-v1")
    private val externalModels = File(modelRoot, "qwen-htp-sm8850")
    private val bundled = BundledAsrModel(app, externalModels)
    val modelDir get() = bundled.directory
    private val manifest by lazy {
        JSONObject(app.assets.open("qwen-htp-manifest.json").bufferedReader().use { it.readText() })
    }

    fun supported(device: QnnDevice = QnnFiles.device()): Boolean =
        BuildConfig.QWEN_HTP_ENABLED && executable.canExecute() && device.supportsSm8850Htp

    fun unavailable(): String? {
        if (!BuildConfig.QWEN_HTP_ENABLED || !executable.canExecute())
            return app.getString(R.string.qwen_htp_not_bundled)
        if (!supported()) return app.getString(R.string.qwen_htp_device)
        if (!bundled.available && !complete(externalModels, manifest.getJSONObject("models")))
            return app.getString(R.string.qwen_htp_models)
        return null
    }

    suspend fun prepare() {
        unavailable()?.let { throw AsrException(ErrorCode.BACKEND_UNAVAILABLE, it) }
        bundled.prepare()
        prepareRuntime()
    }

    private fun prepareRuntime() = synchronized(extractionLock) {
        val libraries = manifest.getJSONObject("libraries")
        if (complete(runtimeDir, libraries)) return@synchronized
        check(runtimeDir.isDirectory || runtimeDir.mkdirs())
        ZipInputStream(app.assets.open("qwen-htp-runtime.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                check(libraries.has(name) && File(name).name == name)
                val dest = File(runtimeDir, name)
                val temp = File.createTempFile("library-", ".tmp", runtimeDir)
                try {
                    temp.outputStream().use { zip.copyTo(it) }
                    check(temp.length() == libraries.getLong(name) && temp.setReadOnly())
                    check(temp.renameTo(dest))
                } finally {
                    temp.delete()
                }
            }
        }
        check(complete(runtimeDir, libraries))
    }

    fun failure(code: ErrorCode = ErrorCode.BACKEND_UNAVAILABLE) =
        AsrException(code, app.getString(R.string.qwen_htp_failed))

    private fun complete(directory: File, files: JSONObject) =
        files.keys().asSequence().all {
            val file = File(directory, it)
            file.isFile && file.canRead() && file.length() == files.getLong(it)
        }

    companion object {
        private val extractionLock = Any()
    }
}

class QwenHtpRuntime(
    private val files: QwenHtpFiles,
    private val cacheDir: File,
    private val factory: QnnWorkerFactory = QnnWorkerFactory { command, env ->
        AndroidQnnWorkerConnection(command, env, label = "Qwen HTP")
    },
) : LocalAsrRuntime {
    override val modelId = "Qwen3-ASR-0.6B-OnSpeak47-v2.2.0-npu-SM8850"
    override val runtimeName = "onnxruntime-1.29.0:qnn-2.51.0"
    override val maxSamples = 8 * 16000
    override val backend get() = if (executed) "qnn-htp" else "qnn-htp-not-executed"

    private val execution = Mutex()
    private var worker: QnnWorkerConnection? = null
    private var sequence = 0
    private var executed = false
    private var closed = false

    override suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        check(!closed)
        files.prepare()
        check(cacheDir.isDirectory || cacheDir.mkdirs())
    }

    override suspend fun decode(samples: FloatArray, sampleRate: Int): LocalRecognition = execution.withLock {
        check(!closed)
        require(sampleRate == 16000 && samples.size in 1..maxSamples)
        currentCoroutineContext().ensureActive()
        if (samples.all { it == 0f }) return@withLock LocalRecognition("")
        val input = File.createTempFile("window-", ".wav", cacheDir)
        try {
            val connection = worker ?: startWorker()
            PcmWav.writeMono(input, samples, sampleRate)
            val id = ++sequence
            val response = withTimeout(90_000) {
                connection.send("$id\t${input.absolutePath}")
                frame(connection)
            }
            val prefix = "$id\t"
            if (!response.startsWith(prefix)) throw files.failure(ErrorCode.INVALID_RESPONSE)
            executed = true
            LocalRecognition(response.removePrefix(prefix))
        } catch (error: Exception) {
            close()
            if (error is TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw files.failure()
            }
            throw error
        } finally {
            input.delete()
        }
    }

    private suspend fun startWorker(): QnnWorkerConnection {
        val runtime = files.runtimeDir.absolutePath
        val connection = factory.start(
            listOf(files.executable.absolutePath, runtime, files.modelDir.absolutePath),
            mapOf(
                "LD_LIBRARY_PATH" to "$runtime:/system/lib64:/system_ext/lib64:/vendor/lib64",
                "ADSP_LIBRARY_PATH" to runtime,
            ),
        )
        worker = connection
        if (withTimeout(90_000) { frame(connection) } != "ready") throw files.failure()
        return connection
    }

    private suspend fun frame(connection: QnnWorkerConnection): String {
        val prefix = "SOUND_QWEN_HTP\t"
        while (true) {
            val line = connection.receive() ?: throw files.failure()
            if (line.startsWith(prefix)) {
                val value = line.removePrefix(prefix)
                if (value == "error") throw files.failure()
                return value
            }
        }
    }

    override fun close() {
        closed = true
        worker?.close()
        worker = null
    }
}
