package dev.soundsoftware.core.asr

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface CheckpointStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

class MemoryCheckpointStore : CheckpointStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/** Use app-private storage. Values contain transcription, never credentials or encryption keys. */
class FileCheckpointStore(private val directory: File) : CheckpointStore {
    init { require(directory.isDirectory || directory.mkdirs()) }
    private fun path(key: String) = File(directory, digest(key) + ".json")
    @Synchronized override fun read(key: String): String? = path(key).takeIf { it.isFile }?.readText()
    @Synchronized override fun write(key: String, value: String) {
        val target = path(key)
        val temporary = File.createTempFile("checkpoint-", ".tmp", directory)
        try {
            temporary.outputStream().use { output ->
                output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    @Synchronized override fun remove(key: String) { path(key).delete() }
}

internal fun checkpointKey(request: AsrRequest, descriptor: AsrDescriptor): String =
    listOf("asr-v1", request.attemptId, descriptor.id, descriptor.model, request.audio.sha256,
        request.profileRevision, request.options.toString()).joinToString("\u0000")

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
