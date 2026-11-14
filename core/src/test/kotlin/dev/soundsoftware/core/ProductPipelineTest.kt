package dev.soundsoftware.core

import dev.soundsoftware.core.analysis.AnalysisException
import dev.soundsoftware.core.analysis.LlmConfig
import dev.soundsoftware.core.analysis.MeetingAnalyzer
import dev.soundsoftware.core.device.DeviceAdvertisement
import dev.soundsoftware.core.device.DeviceRecording
import dev.soundsoftware.core.device.DevicePayloads
import dev.soundsoftware.core.export.ExportOptions
import dev.soundsoftware.core.export.RecordingExport
import dev.soundsoftware.core.model.*
import dev.soundsoftware.core.transcript.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProductPipelineTest {
    private fun seg(id: String, start: Long, end: Long, speaker: String? = null, text: String = id) = Segment(id, text, start, end, speaker)

    @Test fun speakerGroupsKeepCutPointsAndStopAtFiveMinutes() {
        val groups = groupSpeakerTurns(listOf(
            SpeakerTurn(0, 1000, 0), SpeakerTurn(2200, 3000, 0),
            SpeakerTurn(2950, 3100, 1), // a short interjection stays with its own speaker
            SpeakerTurn(3300, 600_000, 0), SpeakerTurn(600_000, 601_000, 0),
            SpeakerTurn(602_500, 603_000, 0), // preserve silence between otherwise adjacent turns
            SpeakerTurn(603_000, 604_000, -1), SpeakerTurn(604_000, 605_000, -1),
        ), durationMs = 604_500)
        assertEquals(listOf(
            listOf(SpeakerTurn(0, 1000, 0), SpeakerTurn(2200, 3000, 0)),
            listOf(SpeakerTurn(3000, 3100, 1)),
            listOf(SpeakerTurn(3300, 303_300, 0)),
            listOf(SpeakerTurn(303_300, 600_000, 0), SpeakerTurn(600_000, 601_000, 0)),
            listOf(SpeakerTurn(602_500, 603_000, 0)),
            listOf(SpeakerTurn(603_000, 604_000, -1)),
            listOf(SpeakerTurn(604_000, 604_500, -1)),
        ), groups)
    }

    @Test fun assignSpeakersByLargestOverlapAndNumbersByFirstAppearance() {
        val turns = listOf(SpeakerTurn(0, 4000, 7), SpeakerTurn(4000, 9000, 2))
        val (segments, speakers) = assignSpeakers(listOf(seg("a", 0, 3000), seg("b", 3500, 8000), Segment("c", "untimed")), turns)
        assertEquals(listOf("spk-7", "spk-2", null), segments.map { it.speakerId })
        assertEquals(listOf("说话人 1", "说话人 2"), speakers.map { it.displayName })
        val stats = speakerStats(segments)
        assertEquals(3000L, stats["spk-7"]!!.totalMs)
        assertEquals(4500f / 7500f, stats["spk-2"]!!.share, 0.001f)
    }

    @Test fun silentGapsFindsLeadingInnerAndTrailingSilence() {
        val gaps = silentGaps(listOf(seg("a", 2000, 3000), seg("b", 3200, 4000), seg("c", 9000, 9500)), 12_000)
        assertEquals(listOf(0L until 2000L, 4000L until 9000L, 9500L until 12000L), gaps)
    }

    @Test fun advertisementAndDurationSemantics() {
        val ad = DeviceAdvertisement.parse(0x1122, byteArrayOf(0x33, 0x44, 0x55, 0x66, 0x90.toByte(), 0))!!
        assertEquals("22:11:33:44:55:66", ad.mac)
        assertTrue(ad.bound); assertTrue(ad.white)
        val rec = DeviceRecording(1794057634, 7340)
        assertEquals(7340L, rec.durationMs); assertEquals(60922L, rec.transferBytes)
    }

    @Test fun deviceInfoExtrasParsedOnlyWhenPresent() {
        val short = ByteArray(39)
        assertNull(DevicePayloads.info(short).extras)
        val long = ByteArray(56).also { it[46] = 1; it[50] = 1; "04.10".toByteArray().copyInto(it, 33) }
        val extras = DevicePayloads.info(long).extras!!
        assertTrue(extras.autoPowerOff); assertTrue(extras.recording); assertEquals("04.10", extras.caseFirmware)
    }

    private val recording = Recording("r1", 0, 60_000, transcript = Transcript(listOf(
        seg("s1", 0, 4000, "spk-0", "我们先看同步方案。"), seg("s2", 59_000, 59_500, "spk-1", "好的，周三前给交互稿。")),
        "test", "cpu", "SEGMENT", 0), speakers = listOf(Speaker("spk-0", 1, "王磊"), Speaker("spk-1", 2)))

    @Test fun analyzerParsesSummaryAndSendsTranscriptOnly() = runTest {
        MockWebServer().use { server ->
            val content = """```json
{"title":"同步评审","abstract":"讨论同步方案和交互稿。","abstractEvidence":[{"segmentId":"s1","quote":"同步方案"},{"segmentId":"s2","quote":"交互稿"}],"keyPoints":[{"text":"讨论同步方案","evidence":[{"segmentId":"s1","quote":"同步方案"}]}],"decisions":[],"risks":[],"openQuestions":[],"todos":[{"text":"交互稿","owner":"说话人 2","due":"周三前","evidence":[{"segmentId":"s2","quote":"周三前给交互稿"}]}],"chapters":[{"title":"开场","evidence":[{"segmentId":"s1","quote":"同步方案"}]}]}
```"""
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}"""))
            val analyzer = MeetingAnalyzer(LlmConfig(server.url("/v1/"), "m", allowInsecureForTests = true), { "k" })
            val summary = analyzer.summarize(recording, SceneTemplate.MEETING, listOf("PRD"))
            assertEquals("同步评审", summary.title)
            assertEquals(listOf("交互稿"), summary.todos.map { it.text })
            assertEquals("说话人 2", summary.todos[0].owner)
            assertEquals(listOf("开场"), summary.chapters.map { it.title })
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer k", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("我们先看同步方案。"))
            assertTrue(body.contains("王磊"))
            assertTrue(body.contains("说话人 2"))
        }
    }

    @Test fun analyzerMapsAuthErrors() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val analyzer = MeetingAnalyzer(LlmConfig(server.url("/"), "m", allowInsecureForTests = true), { null })
            val error = runCatching { analyzer.answer(recording, "谁负责？", emptyList()) }.exceptionOrNull()
            assertTrue(error is AnalysisException && error.message!!.contains("401"))
        }
    }

    @Test fun exportsRenderFromSameBlocks() {
        val withSummary = recording.copy(summary = MeetingSummary("t", "摘要内容", listOf(TodoItem("1", "交互稿", "王磊", done = true)), listOf(Chapter(0, "开场"))))
        val blocks = RecordingExport.blocks(withSummary, "标题", "11月7日", ExportOptions(speakers = false))
        val md = RecordingExport.markdown(blocks)
        assertTrue(md.contains("- [x] 交互稿 · 王磊"))
        assertTrue(md.contains("00:00  我们先看同步方案。"))
        assertFalse(md.contains("王磊  我们"))
        val srt = RecordingExport.srt(withSummary, ExportOptions())
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:04,000\n王磊：我们先看同步方案。"))
        val bytes = ByteArrayOutputStream().also { RecordingExport.docx(blocks, it) }.toByteArray()
        val names = ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry?.name }.toList() }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml")))
        assertFalse(RecordingExport.canExport(recording.copy(transcript = null), dev.soundsoftware.core.export.ExportFormat.SRT))
    }
}
