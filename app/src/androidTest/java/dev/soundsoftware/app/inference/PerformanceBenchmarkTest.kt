package dev.soundsoftware.app.inference

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.audio.sha256
import dev.soundsoftware.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit performance probe. Uses isolated fixture records and never invokes remote services. */
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarkTest {
    @Test fun measureLocalProcessing() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e runPerformance true", args.getString("runPerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.getExternalFilesDir(null), "validation/performance").apply { mkdirs() }
        val models = File(context.getExternalFilesDir(null), "models")
        val fixture = File(root, args.getString("fixture") ?: "dialog.wav")
        require(fixture.isFile) { "Performance fixture missing" }
        val mode = args.getString("mode") ?: "pipeline"
        require(mode in listOf("micro", "pipeline"))
        val profile = args.getString("profile") ?: if (mode == "micro") "qwen-cpu" else "qwen-htp"
        require(profile in if (mode == "micro") listOf("qwen-cpu", "sensevoice-cpu") else listOf("qwen-htp"))
        val repetitions = (args.getString("repetitions") ?: "3").toInt().also { require(it in 1..5) }
        val result = JSONObject().put("profile", profile).put("mode", mode)
            .put("fixture", fixture.name).put("inputSha256", fixture.sha256())
            .put("durationMs", PcmWav.open(fixture).durationMs)
            .put("build", "debug").put("backend", if (mode == "micro") "cpu" else "qnn-htp").put("samples", JSONArray())
        val report = File(root, "$profile-$mode-${fixture.nameWithoutExtension}.json")
        fun save() { report.writeText(result.toString(2)) }
        suspend fun measured(name: String, block: suspend (JSONObject) -> Unit) {
            val row = JSONObject().put("name", name).put("before", snapshot(context))
            val start = SystemClock.elapsedRealtimeNanos()
            val cpuStart = android.os.Process.getElapsedCpuTime()
            var peakRss = row.getJSONObject("before").getLong("rssKiB")
            var peakPss = row.getJSONObject("before").getLong("pssKiB")
            var maxThermal = row.getJSONObject("before").getInt("thermalStatus")
            val monitor = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    val s = snapshot(context)
                    peakRss = maxOf(peakRss, s.getLong("rssKiB"))
                    peakPss = maxOf(peakPss, s.getLong("pssKiB"))
                    maxThermal = maxOf(maxThermal, s.getInt("thermalStatus"))
                    delay(1000)
                }
            }
            try { block(row); row.put("status", "passed") }
            catch (e: Throwable) { row.put("status", "failed").put("error", e.message ?: e.javaClass.simpleName); throw e }
            finally {
                monitor.cancelAndJoin()
                val after = snapshot(context)
                peakRss = maxOf(peakRss, after.getLong("rssKiB"))
                peakPss = maxOf(peakPss, after.getLong("pssKiB"))
                maxThermal = maxOf(maxThermal, after.getInt("thermalStatus"))
                row.put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                    .put("processCpuMs", android.os.Process.getElapsedCpuTime() - cpuStart)
                    .put("sampledPeakRssKiB", peakRss).put("sampledPeakPssKiB", peakPss)
                    .put("maximumThermalStatus", maxThermal).put("after", after)
                result.getJSONArray("samples").put(row); save()
            }
        }
        save()
        if (mode == "micro") {
            val wav = PcmWav.open(fixture)
            val samples = wav.readMono(0, wav.sampleCount.toInt())
            val qwen = profile == "qwen-cpu"
            val runtime = SherpaAsrRuntime(SherpaRuntimeConfig(File(models, if (qwen) "qwen" else "sensevoice"),
                if (qwen) SherpaModel.QWEN3_ASR else SherpaModel.SENSE_VOICE, "performance-pinned-2.0.1"))
            require(samples.size <= runtime.maxSamples)
            try {
                measured("new-recognizer-prepare") { runtime.prepare() }
                repeat(repetitions) { index -> measured("decode-$index") { row ->
                    val output = runtime.decode(samples, 16000)
                    assertTrue(output.text.isNotBlank()); row.put("textCharacters", output.text.length)
                } }
            } finally { runtime.close() }
        } else repeat(repetitions) { index ->
            val workspace = File(context.cacheDir, "performance-${System.nanoTime()}").apply { mkdirs() }
            val repository = RecordingRepository(workspace)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val processor = Processor(repository, SettingsStore(context), models, File(workspace, "checkpoints"), scope, qwenHtpFiles = QwenHtpFiles(context, models), notify = {})
            try {
                require(processor.modelReady(profile) && processor.diarizer.isReady) { "ASR/diarization models missing" }
                val id = "benchmark-$index"
                fixture.copyTo(repository.audioFile(id))
                repository.add(Recording(id, System.currentTimeMillis(), PcmWav.open(fixture).durationMs))
                processor.start()
                measured("pipeline-$index") { row ->
                    val stages = JSONArray()
                    val phaseStart = SystemClock.elapsedRealtime()
                    var previous: ProcessingStage? = null
                    val observer = scope.launch {
                        repository.recordings.collect { records -> records.firstOrNull()?.let { recording ->
                            if (previous != recording.stage) {
                                previous = recording.stage
                                stages.put(JSONObject().put("stage", recording.stage.name)
                                    .put("offsetMs", SystemClock.elapsedRealtime() - phaseStart))
                            }
                        } }
                    }
                    try {
                        processor.enqueue(id, TranscribeOptions(template = SceneTemplate.TRANSCRIPT_ONLY,
                            profileId = profile, speakerCount = 2))
                        val done = withTimeout(20 * 60 * 1000L) {
                            repository.recordings.first { list -> list.any { it.id == id && it.stage in listOf(ProcessingStage.DONE, ProcessingStage.FAILED) } }.first { it.id == id }
                        }
                        check(done.stage == ProcessingStage.DONE) { done.error ?: "Processing failed" }
                        val transcript = requireNotNull(done.transcript)
                        assertTrue(transcript.segments.isNotEmpty())
                        check(transcript.warnings.none { it.startsWith("说话人分离未完成") }) { "Diarization failed" }
                        row.put("speakers", done.speakers.size).put("segments", transcript.segments.size)
                            .put("textCharacters", transcript.segments.sumOf { it.text.length })
                            .put("timestampQuality", transcript.timestampQuality).put("stages", stages)
                    } finally { observer.cancelAndJoin() }
                }
            } finally { scope.cancel(); workspace.deleteRecursively() }
        }
        result.put("status", "complete"); save()
    }

    private fun snapshot(context: Context): JSONObject {
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val status = File("/proc/self/status").readLines()
        fun kib(key: String) = status.firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject().put("rssKiB", kib("VmRSS")).put("processLifetimeHighWaterRssKiB", kib("VmHWM"))
            .put("pssKiB", memory.totalPss).put("thermalStatus", if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
            .put("batteryTemperatureTenthsC", battery?.getIntExtra("temperature", -1))
            .put("batteryLevel", battery?.getIntExtra("level", -1)).put("plugged", battery?.getIntExtra("plugged", -1))
            .put("powerSaveMode", power.isPowerSaveMode)
    }
}
