package dev.soundsoftware.app.inference

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.ProcessingStage
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.SceneTemplate
import dev.soundsoftware.core.model.TranscribeOptions
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwenHtpPhoneTest {
    @Test fun appUidDecodesMixedSpeechAndFullRecording() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runQwenHtp") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val files = QwenHtpFiles(app, app.modelDir)
        assertEquals(null, files.unavailable())
        val expected = listOf("qwen-htp") +
            if (dev.soundsoftware.app.BuildConfig.QNN_ENABLED) listOf("sensevoice-qnn") else emptyList()
        assertEquals(expected, app.processor.localAsrProfiles().map { it.id })
        assertTrue(expected.all(app.processor::modelReady))
        assertTrue(!app.processor.modelReady("qwen-cpu") && !app.processor.modelReady("sensevoice-cpu"))
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
            processor.enqueue("test", TranscribeOptions(profileId = "qwen-cpu", template = SceneTemplate.TRANSCRIPT_ONLY))
            val done = withTimeout(240_000) {
                repo.recordings.first { it.singleOrNull()?.stage in setOf(ProcessingStage.DONE, ProcessingStage.FAILED) }.single()
            }
            assertEquals(done.error, ProcessingStage.DONE, done.stage)
            assertEquals("qwen-htp", done.options?.profileId)
            val transcript = checkNotNull(done.transcript)
            assertEquals("qnn-htp", transcript.backend)
            assertTrue(transcript.model.contains("OnSpeak47"))
            assertTrue(transcript.segments.size > 2)
            val ends = transcript.segments.mapNotNull { it.endMs }
            assertTrue(ends.size == transcript.segments.size && ends.max() <= duration)
            assertTrue(ends.max() >= duration - 8_000)
            if (duration >= 20_000 && dev.soundsoftware.app.BuildConfig.SPEAKER_NPU_ENABLED)
                assertTrue(transcript.speakerHtpExecutions > 0)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            testDir.deleteRecursively()
        }
    }
}
