package dev.soundsoftware.app.inference

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.data.LlmSettings
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.ProcessingStage
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.SceneTemplate
import dev.soundsoftware.core.model.TranscribeOptions
import dev.soundsoftware.core.transcript.SpeakerTurn
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwenHtpPhoneTest {
    @Test fun bundledQwenDecodesWithoutExternallyInstalledWeights() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBundledAsr") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val modelRoot = File(context.cacheDir, "bundled-model-test-" + SystemClock.elapsedRealtimeNanos())
        val fixture = PcmWav.open(File(app.getExternalFilesDir(null), "validation/qwen-htp/mixed.wav"))
        val samples = fixture.readMono(0, fixture.sampleCount.toInt())
        val qwen = QwenHtpFiles(app, modelRoot)
        assertTrue(dev.soundsoftware.app.BuildConfig.ASR_WEIGHTS_BUNDLED)
        assertEquals(null, qwen.unavailable())
        assertTrue(!modelRoot.exists())
        QwenHtpRuntime(qwen, File(modelRoot, "qwen-windows")).use { runtime ->
            runtime.prepare()
            assertTrue(qwen.modelDir.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath + "/"))
            assertTrue(runtime.decode(samples, 16000).text.lowercase().contains("play basketball"))
            assertEquals("qnn-htp", runtime.backend)
        }
        modelRoot.deleteRecursively()
        Unit
    }

    @Test fun freshInstallTranscribesWithBundledSpeakerModels() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBundledSpeakerPipeline") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val modelDir = File(context.noBackupFilesDir, "speaker-models")
        assertTrue(dev.soundsoftware.app.BuildConfig.SPEAKER_WEIGHTS_BUNDLED)
        assertTrue(dev.soundsoftware.app.BuildConfig.ASR_WEIGHTS_BUNDLED)
        assertTrue(app.processor.diarizer.isReady)
        assertTrue(!modelDir.exists())
        assertTrue(File(app.modelDir, "diarization").listFiles().isNullOrEmpty())
        assertTrue(File(app.modelDir, SpeakerNpuRuntime.MODEL_DIRECTORY).listFiles().isNullOrEmpty())
        assertTrue(app.repository.recordings.value.isEmpty())
        val fixtureDir = speakerFixtureDir(context)
        val input = File(fixtureDir, "dialog.wav")
        val duration = PcmWav.open(input).durationMs
        val id = "bundled-speaker-pipeline"
        try {
            input.copyTo(app.repository.audioFile(id))
            app.repository.add(Recording(id, 0, duration))
            app.processor.enqueue(id, TranscribeOptions(profileId = "qwen-htp", template = SceneTemplate.TRANSCRIPT_ONLY))
            val done = withTimeout(240_000) {
                app.repository.recordings.first { rows ->
                    rows.any { it.id == id && it.stage in setOf(ProcessingStage.DONE, ProcessingStage.FAILED) }
                }.single { it.id == id }
            }
            assertEquals(done.error, ProcessingStage.DONE, done.stage)
            val transcript = checkNotNull(done.transcript)
            assertEquals("qnn-htp", transcript.backend)
            assertEquals(77, transcript.speakerHtpExecutions)
            assertEquals(2, done.speakers.size)
            assertTrue(transcript.segments.isNotEmpty())
            val report = org.json.JSONObject().put("durationMs", duration).put("asrBackend", transcript.backend)
                .put("speakerHtpExecutions", transcript.speakerHtpExecutions)
                .put("speakers", done.speakers.size).put("segments", transcript.segments.size)
                .put("preparedSpeakerFiles", modelDir.walkTopDown().count { it.isFile })
            File(fixtureDir, "bundled-speaker-pipeline.json").writeText(report.toString(2))
        } finally {
            app.repository.delete(id)
        }
    }

    @Test fun proofreadingIsOptInAndKeepsLocalTextOnServiceFailure() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBundledSpeakerPipeline") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        assumeTrue(app.packageName.endsWith(".speakerpolish"))
        val fixture = File(speakerFixtureDir(context), "dialog.wav")
        val root = File(context.cacheDir, "proofreading-" + SystemClock.elapsedRealtimeNanos())
        val repository = RecordingRepository(root)
        val settings = SettingsStore(context)
        val previous = settings.state.value.llm
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val duration = PcmWav.open(fixture).durationMs
        val diarizer = object : SpeakerAnalyzer {
            override val isReady = true
            override suspend fun analyze(wav: PcmWav, speakerCount: Int?, onProgress: (Float) -> Unit) =
                DiarizationAnalysis(listOf(SpeakerTurn(0, duration, 7)))
        }
        val processor = Processor(repository, settings, app.modelDir, File(root, "checkpoints"), scope,
            diarizer = diarizer, qwenHtpFiles = QwenHtpFiles(app, app.modelDir)) {}
        val id = "proofreading"
        try {
            fixture.copyTo(repository.audioFile(id))
            repository.add(Recording(id, 0, duration))
            processor.start()
            val options = TranscribeOptions(profileId = "qwen-htp", template = SceneTemplate.TRANSCRIPT_ONLY)
            suspend fun transcribe(): Recording {
                processor.enqueue(id, options)
                return withTimeout(120_000) {
                    repository.recordings.first { it.singleOrNull()?.stage in setOf(ProcessingStage.DONE, ProcessingStage.FAILED) }.single()
                }.also { assertEquals(it.error, ProcessingStage.DONE, it.stage) }
            }
            MockWebServer().use { server ->
                val config = LlmSettings(server.url("/v1/").toString().replace("127.0.0.1", "localhost"), "fixture")
                settings.update { it.copy(llm = config) }
                val raw = checkNotNull(transcribe().transcript).segments.single()
                assertEquals(0, server.requestCount)
                assertEquals(0L, raw.startMs)
                assertEquals(duration, raw.endMs)
                assertEquals("spk-7", raw.speakerId)
                val corrected = "Proofread fixture."
                val payload = buildJsonObject { put("text", corrected) }.toString()
                val message = buildJsonObject { put("content", payload) }
                server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":$message}]}"""))
                settings.update { it.copy(llm = config.copy(repairTranscript = true)) }
                val repaired = checkNotNull(transcribe().transcript).segments.single()
                assertEquals(raw.copy(text = corrected), repaired)
                val request = checkNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
                val messages = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("messages").jsonArray
                val fragments = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content)
                    .jsonObject.getValue("fragments").jsonArray
                assertEquals(2, fragments.size)
                assertEquals(raw.text, fragments.joinToString(" ") { it.jsonPrimitive.content })
                server.enqueue(MockResponse().setResponseCode(503))
                val fallback = checkNotNull(transcribe().transcript)
                assertEquals(raw, fallback.segments.single())
                assertTrue(fallback.warnings.contains(context.getString(dev.soundsoftware.app.R.string.repair_transcript_failed)))
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            settings.update { it.copy(llm = previous) }
            root.deleteRecursively()
        }
        Unit
    }

    private fun speakerFixtureDir(context: android.content.Context): File {
        val privateFixture = File(context.cacheDir, "validation/speaker-npu-v2")
        return if (File(privateFixture, "dialog.wav").isFile) privateFixture
        else File(context.getExternalFilesDir(null), "validation/speaker-npu-v2")
    }

    @Test fun appUidDecodesMixedSpeechAndFullRecording() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runQwenHtp") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val files = QwenHtpFiles(app, app.modelDir)
        assertEquals(null, files.unavailable())
        val expected = listOf("qwen-htp")
        assertEquals(expected, app.processor.localAsrProfiles().map { it.id })
        assertTrue(expected.all(app.processor::modelReady))
        assertTrue(!app.processor.modelReady("sensevoice-qnn"))
        val fixtureDir = File(app.getExternalFilesDir(null), "validation/qwen-htp")
        val testDir = File(context.cacheDir, "qwen-phone-" + SystemClock.elapsedRealtimeNanos()).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        try {
            val wav = PcmWav.open(File(fixtureDir, "mixed.wav"))
            val mixed = wav.readMono(0, wav.sampleCount.toInt())
            QwenHtpRuntime(files, File(testDir, "windows")).use { runtime ->
                runtime.prepare()
                assertTrue(runtime.decode(FloatArray(16000), 16000).text.isEmpty())
                assertEquals("qnn-htp-not-executed", runtime.backend)
                repeat(2) {
                    val text = runtime.decode(mixed, 16000).text.lowercase()
                    assertTrue(text.contains("play basketball") && text.contains("喜欢"))
                }
                assertEquals("qnn-htp", runtime.backend)
            }

            val fixture = InstrumentationRegistry.getArguments().getString("fixture") ?: "dialog.wav"
            require(fixture in setOf("dialog.wav", "dialog-repeat6.wav"))
            val input = File(fixtureDir, fixture)
            val duration = PcmWav.open(input).durationMs
            val repo = RecordingRepository(testDir)
            val processor = Processor(repo, SettingsStore(context), app.modelDir, File(testDir, "checkpoints"),
                scope, qwenHtpFiles = files, diarizer = SherpaDiarizer.forDevice(context, File(app.modelDir, "diarization"))) {}
            input.copyTo(repo.audioFile("test"))
            repo.add(Recording("test", 0, duration))
            processor.start()
            processor.enqueue("test", TranscribeOptions(profileId = "sensevoice-qnn", template = SceneTemplate.TRANSCRIPT_ONLY))
            val done = withTimeout(240_000) {
                repo.recordings.first { it.singleOrNull()?.stage in setOf(ProcessingStage.DONE, ProcessingStage.FAILED) }.single()
            }
            assertEquals(done.error, ProcessingStage.DONE, done.stage)
            assertEquals("qwen-htp", done.options?.profileId)
            val transcript = checkNotNull(done.transcript)
            assertEquals("qnn-htp", transcript.backend)
            assertTrue(transcript.model.contains("24s-W8"))
            assertTrue(transcript.segments.isNotEmpty())
            val ends = transcript.segments.mapNotNull { it.endMs }
            assertTrue(ends.size == transcript.segments.size && ends.max() <= duration)
            assertTrue(ends.max() >= duration - 24_000)
            if (duration >= 20_000 && dev.soundsoftware.app.BuildConfig.SPEAKER_NPU_ENABLED)
                assertTrue(transcript.speakerHtpExecutions > 0)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            testDir.deleteRecursively()
        }
    }
}
