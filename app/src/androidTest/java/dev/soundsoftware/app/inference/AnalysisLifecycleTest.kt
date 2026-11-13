package dev.soundsoftware.app.inference

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.data.LlmSettings
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisLifecycleTest {
    private suspend fun fixture(
        llm: LlmSettings = LlmSettings(),
        analysisClient: OkHttpClient = OkHttpClient(),
        run: suspend (Processor, RecordingRepository, File) -> Unit,
    ) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "analysis-lifecycle-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val dir = File(base.cacheDir, prefix).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = RecordingRepository(dir)
            val checkpoints = File(dir, "checkpoints")
            val settings = SettingsStore(context).also { store -> store.update { it.copy(llm = llm) } }
            val processor = Processor(repository, settings, File(dir, "models"), checkpoints, scope, analysisClient = analysisClient) {}
            run(processor, repository, checkpoints)
        } finally {
            scope.cancel()
            base.deleteSharedPreferences(prefix + "app-settings")
            dir.deleteRecursively()
        }
    }

    @Test fun notesWithAnInvalidCitationAndDueDateStillSaveGroundedContent() = runBlocking {
        val content = """{
            "title":"测试纪要","abstract":"安排提交交互稿。",
            "abstractEvidence":[{"segmentId":"source"},{"segmentId":"missing"}],
            "keyPoints":[],"decisions":[],"risks":[],"openQuestions":[],"chapters":[],
            "todos":[{"text":"提交交互稿","owner":"测试者","due":"下周五","evidence":[{"segmentId":"source"}]}]
        }""".trimIndent()
        val response = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", content) })
            }) })
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        fixture(LlmSettings("https://fixture.invalid/v1", "fixture"), client) { processor, repository, checkpoints ->
            val id = "test-summary-partial-evidence"
            val transcript = Transcript(listOf(Segment("source", "测试者负责提交交互稿。", 0, 1000)),
                "fixture", "fixture", "SEGMENT", 0)
            PcmWav.writeMono(repository.audioFile(id), FloatArray(16000))
            repository.add(Recording(id, 0, 1000, transcript = transcript, stage = ProcessingStage.DONE))
            processor.resummarize(id, SceneTemplate.MEETING).join()
            val restored = RecordingRepository(checkpoints.parentFile!!).also { it.load() }.get(id)!!
            assertEquals(ProcessingStage.DONE, restored.stage)
            assertNull(restored.error)
            assertEquals(transcript, restored.transcript)
            val summary = restored.summary!!
            assertEquals(listOf(EvidenceRef("source", "测试者负责提交交互稿。")), summary.evidence["abstract"])
            assertEquals("测试者", summary.todos.single().owner)
            assertNull(summary.todos.single().due)
        }
    }

    @Test fun deletedDeviceRecordingCanBeSyncedAndQueuedAgainWithTheSameId() = runBlocking {
        fixture { processor, repository, checkpoints ->
            val id = "device-test-reimport"
            repository.add(Recording(id, 0, 1000, RecordingSource.DEVICE, 1000))
            val oldCache = File(checkpoints, "analysis/${UUID.nameUUIDFromBytes(id.toByteArray())}/fixture.json")
            oldCache.parentFile!!.mkdirs(); oldCache.writeText("{}")
            processor.deleteRecording(id)
            assertFalse(oldCache.exists())
            repository.add(Recording(id, 1, 1000, RecordingSource.DEVICE, 1000))
            processor.enqueue(id, TranscribeOptions(template = SceneTemplate.TRANSCRIPT_ONLY))
            assertEquals(ProcessingStage.QUEUED, repository.get(id)?.stage)
        }
    }

    @Test fun oldQueuedWorkDoesNotRunOnARecreatedRecording() = runBlocking {
        fixture { processor, repository, _ ->
            val id = "device-test-old-queue"
            repository.add(Recording(id, 0, 1000, RecordingSource.DEVICE, 1000))
            processor.enqueue(id, TranscribeOptions(template = SceneTemplate.TRANSCRIPT_ONLY))
            processor.deleteRecording(id)
            repository.add(Recording(id, 1, 1000, RecordingSource.DEVICE, 1000))
            // The empty model directory ensures an erroneous dispatch fails without inference/HTTP.
            processor.start()
            withTimeout(1500) { while (processor.busy.value) delay(10) }
            assertEquals(ProcessingStage.NEW, repository.get(id)?.stage)
        }
    }
}
