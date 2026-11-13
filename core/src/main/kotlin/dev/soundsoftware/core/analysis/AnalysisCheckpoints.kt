package dev.soundsoftware.core.analysis

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Validated text artifacts only. Keys are content hashes; API keys never enter this store. */
interface AnalysisCheckpoints {
    suspend fun read(key: String): String?
    suspend fun write(key: String, content: String)
}

object NoAnalysisCheckpoints : AnalysisCheckpoints {
    override suspend fun read(key: String): String? = null
    override suspend fun write(key: String, content: String) {}
}

class FileAnalysisCheckpoints(private val directory: File) : AnalysisCheckpoints {
    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.json")
    }

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val target = file(key)
        if (!target.isFile) null else runCatching { target.readText() }.getOrNull()
    }

    override suspend fun write(key: String, content: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = file(key)
        val temporary = File(directory, ".$key.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(content)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        } finally { temporary.delete() }
    }
}
