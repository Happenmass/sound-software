package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.transcript.*
import dev.soundsoftware.app.processing.Processor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit local probes. Baseline only reads; apply updates attribution through Processor.
 * Features stay private; exported evidence contains counts/timing only. Audio, transcript
 * words/times, settings and summaries are preserved. */
@RunWith(AndroidJUnit4::class)
class LongRecordingClusteringProbeTest {
    private fun sha(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes=ByteArray(65536)
            while(true) { val count=input.read(bytes); if(count<0)break; digest.update(bytes,0,count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun applyValidatedClusteringAndVerifyLatestThroughLiveDiarizer() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clusterProbe") == "apply")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as SoundApplication
        val json=Json { ignoreUnknownKeys=true }
        val privateDir=File(context.filesDir,"cluster-probe-private")
        val manifest=json.parseToJsonElement(File(privateDir,"manifest.json").readText()).jsonArray.map {it.jsonObject}
        assertEquals(3,manifest.size)
        withTimeout(10000) { app.repository.recordings.first { all->manifest.all {m->all.any {it.id==m.getValue("id").jsonPrimitive.content}} } }
        val bases=manifest.map { app.repository.get(it.getValue("id").jsonPrimitive.content)!! }
        val output=File(context.getExternalFilesDir(null),"validation/long-clustering-recovery.json").apply { parentFile!!.mkdirs() }
        val completed=mutableListOf<JsonObject>();var stage="validating-inputs";var item=0;var progress=0
        fun save() { output.writeText(buildJsonObject {
            put("stage",stage);put("item",item);put("progressPercent",progress);put("results",JsonArray(completed))
        }.toString()) }
        save()
        val cached=withContext(Dispatchers.Default) { manifest.mapIndexed { i,m->
            val audio=app.repository.audioFile(bases[i].id)
            check(sha(audio)==m.getValue("audioSha256").jsonPrimitive.content) { "Source audio changed" }
            val filename=m.getValue("features").jsonPrimitive.content
            check(filename.matches(Regex("sample-[123]\\.json")))
            val file=File(privateDir,filename)
            check(sha(file)==m.getValue("featureSha256").jsonPrimitive.content) { "Feature snapshot changed" }
            val observations=json.parseToJsonElement(file.readText()).jsonObject.getValue("observations").jsonArray.map {v->
                val r=v.jsonObject
                SpeakerObservation(SpeakerTurn(r.getValue("startMs").jsonPrimitive.long,r.getValue("endMs").jsonPrimitive.long,r.getValue("rawSpeaker").jsonPrimitive.int),
                    r["embedding"]?.takeUnless {it==JsonNull}?.jsonArray?.map {it.jsonPrimitive.float}?.toFloatArray(),r.getValue("cleanMs").jsonPrimitive.long)
            }
            SpeakerClustering().cluster(observations)
        } }
        cached.forEach { assertEquals("All proposals must pass before updating the library",2,it.speakers) }
        val actualBackends=mutableMapOf<String,String>()
        val live=SherpaDiarizer.forDevice(context,File(app.modelDir,"diarization"))
        val analyzer=object:SpeakerAnalyzer {
            override val isReady get()=live.isReady
            override suspend fun analyze(wav:PcmWav,speakerCount:Int?,onProgress:(Float)->Unit):DiarizationAnalysis {
                check(speakerCount==null)
                val index=bases.indexOfFirst {app.repository.audioFile(it.id)==wav.file}
                check(index>=0)
                check(sha(wav.file)==manifest[index].getValue("audioSha256").jsonPrimitive.content)
                if(index==bases.lastIndex) {
                    val result=live.analyze(wav,null) {f->
                        onProgress(f)
                        val percent=(f*100).toInt()/10*10
                        if(percent!=progress) {progress=percent;save()}
                    }
                    check(result.turns.map {it.speaker}.filter {it>=0}.distinct().size==2) { "Live verification does not match validated proposal" }
                    actualBackends[bases[index].id]=result.backend
                    return result
                }
                val result=cached[index]
                actualBackends[bases[index].id]="validated-private-feature-replay"
                onProgress(1f)
                return DiarizationAnalysis(result.turns,if(result.unknownTurns>0)listOf("部分短应答或噪声无法可靠归属说话人，已保留为未归属，未额外创建说话人")else emptyList(),backend="validated-private-feature-replay")
            }
        }
        val worker=Processor(app.repository,app.settings,app.modelDir,File(context.filesDir,"checkpoints"),app.scope,diarizer=analyzer,notify={})
        for((i,base)in bases.withIndex()) {
            item=i+1;progress=0;stage=if(i==bases.lastIndex)"live-reanalysis"else"validated-feature-reanalysis";save()
            val start=android.os.SystemClock.elapsedRealtime()
            worker.rediarize(base.id,null).join()
            val current=app.repository.get(base.id)!!
            assertNull(current.error);assertEquals(2,current.speakers.size)
            val before=base.transcript!!;val after=current.transcript!!
            assertEquals(before.copy(segments=before.segments.map {it.copy(speakerId=null)},warnings=emptyList()),
                after.copy(segments=after.segments.map {it.copy(speakerId=null)},warnings=emptyList()))
            assertEquals(base.summary,current.summary);assertEquals(base.qa,current.qa)
            assertEquals(base.title,current.title);assertEquals(base.titleEditedByUser,current.titleEditedByUser)
            assertEquals(manifest[i].getValue("audioSha256").jsonPrimitive.content,sha(app.repository.audioFile(base.id)))
            assertEquals(SpeakerClustering.VERSION,current.speakerAnalysis?.revision)
            completed+=buildJsonObject {
                put("item",item);put("durationMs",base.durationMs);put("previousSpeakers",base.speakers.size);put("speakers",current.speakers.size)
                put("segments",after.segments.size);put("unattributedSegments",after.segments.count {it.speakerId==null})
                put("backend",actualBackends.getValue(base.id));put("wordsAndTimesPreserved",true);put("summaryPreserved",true)
                put("audioHashPreserved",true);put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start)
            };save()
        }
        stage="passed";save()
    }

    @Test fun captureAutomaticLongRecordingFeatures() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clusterProbe") == "baseline")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SoundApplication
        val json = Json { ignoreUnknownKeys = true }
        val targets = app.repository.audioDir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .map { json.decodeFromString<Recording>(it.readText()) }
            .filter { it.durationMs > 60_000 && it.speakers.size > 2 }.sortedBy { it.createdAt }
        assertEquals(3, targets.size)
        val privateDir = File(context.filesDir, "cluster-probe-private").apply { mkdirs() }
        val reportFile = File(context.getExternalFilesDir(null), "validation/long-clustering-baseline.json").apply { parentFile!!.mkdirs() }
        val results = mutableListOf<JsonObject>()
        var stage = "starting"; var item = 0; var progress = 0
        fun save() { reportFile.writeText(buildJsonObject {
            put("stage", stage); put("item", item); put("progressPercent", progress)
            put("results", JsonArray(results))
        }.toString()) }
        save()
        for ((index, recording) in targets.withIndex()) {
            item = index + 1; progress = 0; stage = "analyzing"; save()
            val wav = PcmWav.open(app.repository.audioFile(recording.id))
            val featureFile = File(privateDir, "sample-$item.json")
            val analyzer = SherpaDiarizer.forDevice(context, File(app.modelDir, "diarization")) { observations ->
                featureFile.writeText(buildJsonObject {
                    put("expectedSpeakers", 2)
                    put("observations", buildJsonArray { observations.forEach { observation -> add(buildJsonObject {
                        put("startMs", observation.turn.startMs); put("endMs", observation.turn.endMs)
                        put("rawSpeaker", observation.turn.speaker); put("cleanMs", observation.cleanDurationMs)
                        put("embedding", observation.embedding?.let { v -> buildJsonArray { v.forEach { add(it) } } } ?: JsonNull)
                    }) } })
                }.toString())
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val analysis = withContext(Dispatchers.Default) { analyzer.analyze(wav, null) { f ->
                val percent = (f * 100).toInt() / 10 * 10
                if (percent != progress) { progress = percent; save() }
            } }
            results += buildJsonObject {
                put("item", item); put("durationMs", recording.durationMs); put("previousSpeakers", recording.speakers.size)
                put("actualSpeakers", analysis.turns.map { it.speaker }.filter { it >= 0 }.distinct().size)
                put("expectedSpeakers", 2); put("turns", analysis.turns.size)
                put("unknownTurns", analysis.turns.count { it.speaker < 0 }); put("backend", analysis.backend)
                put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
            }
            save()
        }
        stage = "complete"; save()
        results.forEach { assertEquals("Automatic long-recording speaker count", 2, it.getValue("actualSpeakers").jsonPrimitive.int) }
    }
}
