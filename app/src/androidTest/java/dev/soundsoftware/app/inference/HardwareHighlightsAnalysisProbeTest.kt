package dev.soundsoftware.app.inference

import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.core.analysis.MeetingAnalyzer
import dev.soundsoftware.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Explicit controlled test recording only; current local ASR and configured LLM, no settings edits. */
class HardwareHighlightsAnalysisProbeTest {
    @Test fun analyzePublishedControlledRecording()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("markAnalysis")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as SoundApplication
        withTimeout(10000) {app.assistant.state.first {it.ready}}
        val base=app.repository.recordings.value.single {it.title=="重点验证 2" && it.durationMs==34100L && it.hardwareHighlights?.map {h->h.positionMs}==listOf(5160L,14660L)}
        val options=app.settings.state.value.defaults.copy(template=SceneTemplate.MEETING)
        check(options.profileId!="http-api") {"Controlled audio must remain in local ASR"}
        check(app.processor.modelReady(options.profileId) && app.processor.llmReady())
        val out=File(context.filesDir,"mark-probe-private/analysis.json").apply {parentFile!!.mkdirs()}
        val report=JSONObject().put("stage","starting").put("profile",options.profileId).put("audioUploaded",false)
        fun save() {out.writeText(report.toString(2))}
        save();app.processor.enqueue(base.id,options)
        val result=withTimeout(600000) {app.repository.recordings.first {all->all.firstOrNull {it.id==base.id}?.stage in setOf(ProcessingStage.DONE,ProcessingStage.FAILED)}}.single {it.id==base.id}
        report.put("stage",result.stage.name).put("transcriptSegments",result.transcript?.segments?.size ?: 0)
            .put("summaryPresent",result.summary!=null).put("promptVersion",result.summary?.promptVersion)
            .put("highlightsPreserved",base.hardwareHighlights==result.hardwareHighlights)
            .put("summaryMatchesSource",result.summary?.sourceTranscriptHash==MeetingAnalyzer.transcriptHash(result))
        save()
        check(result.stage==ProcessingStage.DONE && result.summary!=null) {"Controlled sample did not complete notes"}
        check(base.hardwareHighlights==result.hardwareHighlights)
        check(result.summary!!.sourceTranscriptHash==MeetingAnalyzer.transcriptHash(result))
    }
}
