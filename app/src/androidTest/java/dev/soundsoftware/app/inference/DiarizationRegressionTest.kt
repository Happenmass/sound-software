package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.core.audio.PcmWav
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, local-only input. Never imports the fixture into the user's recording repository. */
@RunWith(AndroidJUnit4::class)
class DiarizationRegressionTest {
    @Test fun automaticSpeakerCountMatchesAuthorizedFixture() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("diarizationFixture") == "two-speaker-user-sample")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as SoundApplication
        val file=File(context.getExternalFilesDir("validation"),"diarization-user-sample.wav")
        val wav=PcmWav.open(file)
        val start=android.os.SystemClock.elapsedRealtime()
        val turns=SherpaDiarizer(File(app.modelDir,"diarization")).diarize(wav,null) {}
        val speakers=turns.map{it.speaker}.filter{it>=0}.distinct()
        val report=buildJsonObject {
            put("inputSha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)})
            put("durationMs",wav.durationMs);put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start)
            put("automatic",true);put("expectedSpeakers",2);put("actualSpeakers",speakers.size)
            put("turns",buildJsonArray{turns.forEach{t->add(buildJsonObject{put("startMs",t.startMs);put("endMs",t.endMs);put("speaker",t.speaker)})}})
        }
        File(context.getExternalFilesDir("validation"),"diarization-regression.json").writeText(report.toString())
        assertEquals("Automatic speaker count on user-provided two-person audio",2,speakers.size)
    }
    @Test fun automaticLocalPipelinePersistsTwoSpeakers() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("diarizationPipeline") == "true")
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val app=base.applicationContext as SoundApplication
        val prefix="diarization-pipeline-${java.util.UUID.randomUUID()}-"
        val context=object:android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)
        }
        val root=File(base.cacheDir,prefix).apply{mkdirs()}
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
        try {
            val repo=dev.soundsoftware.app.data.RecordingRepository(root)
            val settings=dev.soundsoftware.app.data.SettingsStore(context)
            val processor=dev.soundsoftware.app.processing.Processor(repo,settings,app.modelDir,File(root,"checkpoints"),scope){}
            val input=File(base.getExternalFilesDir("validation"),"diarization-user-sample.wav")
            val wav=PcmWav.open(input);input.copyTo(repo.audioFile("authorized-sample"))
            repo.add(dev.soundsoftware.core.model.Recording("authorized-sample",0,wav.durationMs))
            val start=android.os.SystemClock.elapsedRealtime()
            // Match SoundApplication: initialize the consumer on Main before an enqueue can race startup scanning.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { processor.start() }
            processor.enqueue("authorized-sample",dev.soundsoftware.core.model.TranscribeOptions(template=dev.soundsoftware.core.model.SceneTemplate.TRANSCRIPT_ONLY,profileId="sensevoice-cpu",speakerCount=null))
            val done=kotlinx.coroutines.withTimeout(600_000) {
                repo.recordings.first {items->items.any {it.stage in listOf(dev.soundsoftware.core.model.ProcessingStage.DONE,dev.soundsoftware.core.model.ProcessingStage.FAILED)}}.single()
            }
            // DONE is published before update() finishes its IO; wait for the actual job boundary before reopening.
            kotlinx.coroutines.withTimeout(10_000) { processor.busy.first { !it } }
            val reopened=dev.soundsoftware.app.data.RecordingRepository(root).also{it.load()}.get("authorized-sample")!!
            val report=buildJsonObject {
                put("persistedSpeakers",reopened.speakers.size)
                put("stage",done.stage.name);put("automatic",true);put("profile","sensevoice-cpu");put("remoteConfigured",false)
                put("durationMs",wav.durationMs);put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start)
                put("speakers",done.speakers.size);put("segments",done.transcript?.segments?.size ?: 0)
                put("unattributedSegments",done.transcript?.segments?.count{it.speakerId==null} ?: 0)
                put("warnings",buildJsonArray{done.transcript?.warnings.orEmpty().forEach{add(it)}})
            }
            File(base.getExternalFilesDir("validation"),"diarization-pipeline.json").writeText(report.toString())
            assertEquals(dev.soundsoftware.core.model.ProcessingStage.DONE,done.stage)
            assertEquals(2,done.speakers.size)
            org.junit.Assert.assertTrue(done.transcript?.segments?.isNotEmpty()==true)
            assertEquals(done.speakers.map{it.id}.toSet(),reopened.speakers.map{it.id}.toSet())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            base.deleteSharedPreferences(prefix+"app-settings")
            root.deleteRecursively()
        }
    }

}
