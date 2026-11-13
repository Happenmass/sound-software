package dev.soundsoftware.app.inference

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.soundsoftware.core.asr.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Opt-in real JNI smoke; deploy pinned public models before running. No bundled weights. */
@RunWith(AndroidJUnit4::class)
class NativeAsrTest {
    private fun runModel(directory: String, model: SherpaModel, revision: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = File(context.getExternalFilesDir(null), "models")
        val source = File(models, "sensevoice/test_wavs/zh.wav")
        val folder = File(models, directory)
        assumeTrue("Model assets must be explicitly deployed", folder.isDirectory && source.isFile)
        val runtime = SherpaAsrRuntime(SherpaRuntimeConfig(folder, model, revision))
        val engine = LocalAsrAdapter(runtime)
        val input = AudioInput.fromWav(source, "public-zh-smoke")
        try {
            val result = engine.transcribe(AsrRequest("native-smoke", "fresh", input, "$directory-$revision"))
            assertTrue(result.text.isNotBlank())
            assertEquals("cpu", result.provenance.backend)
            assertEquals(TimestampQuality.APPROXIMATE_SEGMENT, result.timestampQuality)
            assertTrue(result.segments.all { it.startMs!! >= 0 && it.endMs!! <= input.durationMs })
            val evidence = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
            File(evidence, "$directory-jni.json").writeText(JSONObject()
                .put("text", result.text).put("elapsedMs", result.elapsedMs).put("durationMs", input.durationMs)
                .put("inputSha256", input.sha256).put("runtime", result.provenance.runtime)
                .put("model", result.provenance.model).put("backend", result.provenance.backend).toString(2))
        } finally { engine.close() }
    }
    @Test fun senseVoiceOnRealAndroidJni() = runModel("sensevoice", SherpaModel.SENSE_VOICE, "2024-07-17-int8")
    @Test fun qwenOnRealAndroidJni() = runModel("qwen", SherpaModel.QWEN3_ASR, "2026-03-25-int8")
}
