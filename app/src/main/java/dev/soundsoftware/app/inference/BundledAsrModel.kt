package dev.soundsoftware.app.inference

import android.content.Context
import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.R
import dev.soundsoftware.core.asr.AsrException
import dev.soundsoftware.core.asr.ErrorCode
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native ASR workers require regular files. Materialize only the selected model. */
internal class BundledAsrModel(context: Context, private val external: File) {
    private val app = context.applicationContext
    private val assetPath = "asr-models/${external.name}"
    private val files by lazy {
        val manifest = app.assets.open("asr-models/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val entries = manifest.getJSONObject(external.name)
        entries.keys().asSequence().associateWith { name ->
            entries.getJSONArray(name).let { ModelFile(it.getLong(0), it.getString(1)) }
        }
    }
    val available: Boolean get() = BuildConfig.ASR_WEIGHTS_BUNDLED
    private val installed by lazy {
        val revision = sha256Hex(MessageDigest.getInstance("SHA-256").digest(files.toString().toByteArray()))
        File(app.noBackupFilesDir, "asr-models/${external.name}-$revision")
    }
    val directory: File
        get() = if (available && !complete(external)) installed else external

    private fun complete(directory: File): Boolean = files.all { (name, entry) ->
        File(directory, name).let { it.isFile && it.canRead() && it.length() == entry.bytes }
    }

    suspend fun prepare() {
        if (!available) return
        withContext(Dispatchers.IO) {
            extraction.withLock {
                if (complete(directory)) return@withLock
                val parent = installed.parentFile!!
                val staging = File(parent, "${installed.name}.staging")
                try {
                    check(parent.isDirectory || parent.mkdirs())
                    check(staging.deleteRecursively() && staging.mkdir())
                    if (parent.usableSpace < files.values.sumOf { it.bytes }) {
                        throw AsrException(ErrorCode.MODEL_NOT_READY, app.getString(R.string.asr_model_storage_low))
                    }
                    val buffer = ByteArray(256 * 1024)
                    for ((name, entry) in files) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val target = File(staging, name)
                        app.assets.open("$assetPath/$name", android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
                            target.outputStream().use { output ->
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                        val checksum = sha256Hex(digest.digest())
                        check(target.length() == entry.bytes && checksum == entry.sha256)
                    }
                    ensureActive()
                    check(installed.deleteRecursively() && staging.renameTo(installed))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    throw AsrException(ErrorCode.MODEL_NOT_READY, app.getString(R.string.asr_model_unpack_failed)).apply { initCause(error) }
                } catch (error: IllegalStateException) {
                    throw AsrException(ErrorCode.MODEL_NOT_READY, app.getString(R.string.asr_model_unpack_failed)).apply { initCause(error) }
                } finally {
                    staging.deleteRecursively()
                }
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class ModelFile(val bytes: Long, val sha256: String)

    companion object {
        private val extraction = Mutex()
    }
}
