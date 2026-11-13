package dev.soundsoftware.core.export

import dev.soundsoftware.core.analysis.MeetingAnalyzer.Companion.clock
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.highlightContext
import dev.soundsoftware.core.model.EvidenceRef
import java.util.Locale
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ExportFormat(val title: String, val extension: String, val mime: String) {
    DOCX("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    PDF("PDF", "pdf", "application/pdf"),
    SRT("字幕", "srt", "application/x-subrip"),
    TEXT("纯文本", "txt", "text/plain"),
    JSON("结构化", "json", "application/json"),
}

data class ExportOptions(
    val summary: Boolean = true,
    val transcript: Boolean = true,
    val speakers: Boolean = true,
    val timestamps: Boolean = true,
)

/** A format-neutral document; every text format is rendered from the same blocks. */
sealed interface Block {
    data class Heading(val text: String, val level: Int) : Block
    data class Paragraph(val text: String, val muted: Boolean = false) : Block
    data class Bullet(val text: String, val checked: Boolean? = null) : Block
}

object RecordingExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun canExport(recording: Recording, format: ExportFormat): Boolean = when (format) {
        ExportFormat.SRT -> recording.transcript?.segments?.any { it.startMs != null } == true
        ExportFormat.JSON -> true
        else -> recording.transcript != null || recording.summary != null
    }

    fun blocks(recording: Recording, title: String, subtitle: String, options: ExportOptions, language: String = "zh"): List<Block> {
        fun label(chinese: String, english: String) = if (language == "en") english else chinese
        val out = mutableListOf<Block>(Block.Heading(title, 1), Block.Paragraph(subtitle, muted = true))
        if(!recording.hardwareHighlights.isNullOrEmpty()) {
            out+=Block.Heading(label("设备重点（双击标记）", "Device highlights (double taps)"),2)
            recording.hardwareHighlights.forEach {mark->
                out+=Block.Bullet(clock(mark.positionMs))
                val nearby=recording.highlightContext(mark).joinToString(" ") {it.text}
                if(nearby.isNotBlank()) out+=Block.Paragraph(label("相邻原文：$nearby", "Nearby transcript: $nearby"),muted=true)
            }
        }
        fun sources(refs: List<EvidenceRef>) {
            refs.distinct().forEach { ref ->
                val segment = recording.transcript?.segments?.firstOrNull { it.id == ref.segmentId && it.text.contains(ref.quote) }
                if (segment != null) {
                    val label = listOfNotNull(segment.startMs?.takeIf { options.timestamps }?.let(::clock),
                        speakerName(recording, segment.speakerId, language)?.takeIf { options.speakers }).joinToString(" · ")
                    val source = if (language == "en") "Source" else "原文"
                    val context = if (label.isBlank()) "" else " · $label"
                    out += Block.Paragraph(source + context + if (language == "en") ": “${ref.quote}”" else "：“${ref.quote}”", muted = true)
                }
            }
        }
        val summary = recording.summary
        if (options.summary && summary != null) {
            if (summary.sourceTranscriptHash.isNotBlank() && summary.sourceTranscriptHash != dev.soundsoftware.core.analysis.MeetingAnalyzer.transcriptHash(recording))
                out += Block.Paragraph(label("此纪要基于修改前的逐字稿，尚未重新生成。", "These notes are based on an earlier transcript."), muted = true)
            else if ((recording.speakerAnalysis?.computedAt ?: 0) > summary.createdAt)
                out += Block.Paragraph(label("说话人已重新计算，此纪要尚未重新生成。", "Speaker assignments have changed since these notes were generated."), muted = true)
            if (summary.abstract.isNotBlank()) { out += Block.Heading(label("摘要", "Summary"), 2); out += Block.Paragraph(summary.abstract); sources(summary.evidence["abstract"].orEmpty()) }
            if (summary.keyPoints.isNotEmpty()) {
                out += Block.Heading(label(summary.template.keyPointsTitle, when (summary.template) {
                    dev.soundsoftware.core.model.SceneTemplate.INTERVIEW -> "Questions and viewpoints"
                    dev.soundsoftware.core.model.SceneTemplate.JOB_INTERVIEW -> "Questions and candidate experience"
                    dev.soundsoftware.core.model.SceneTemplate.LECTURE -> "Key concepts"
                    else -> "Key points"
                }), 2)
                summary.keyPoints.forEachIndexed { index, text -> out += Block.Bullet(text); sources(summary.evidence["keyPoints/$index"].orEmpty()) }
            }
            listOf(label("决策", "Decisions") to ("decisions" to summary.decisions), label("风险", "Risks") to ("risks" to summary.risks), label("待确认问题", "Open questions") to ("openQuestions" to summary.openQuestions))
                .filter { it.second.second.isNotEmpty() }.forEach { (heading, category) ->
                    out += Block.Heading(heading, 2)
                    category.second.forEachIndexed { index, text -> out += Block.Bullet(text); sources(summary.evidence["${category.first}/$index"].orEmpty()) }
                }
            if (summary.todos.isNotEmpty()) {
                out += Block.Heading(label("待办", "To-dos"), 2)
                summary.todos.forEach { t -> out += Block.Bullet(listOfNotNull(t.text, t.owner, t.due).joinToString(" · "), t.done); sources(t.evidence) }
            }
            if (summary.chapters.isNotEmpty()) {
                out += Block.Heading(label("章节", "Chapters"), 2)
                summary.chapters.forEach { out += Block.Bullet("${clock(it.startMs)}  ${it.title}"); sources(it.evidence) }
            }
        }
        val segments = recording.transcript?.segments.orEmpty()
        if (options.transcript && segments.isNotEmpty()) {
            out += Block.Heading(label("逐字稿", "Transcript"), 2)
            segments.forEach { s ->
                val prefix = listOfNotNull(
                    s.startMs?.takeIf { options.timestamps }?.let(::clock),
                    speakerName(recording, s.speakerId, language)?.takeIf { options.speakers },
                ).joinToString(" ")
                out += Block.Paragraph(if (prefix.isEmpty()) s.text else "$prefix  ${s.text}")
            }
        }
        return out
    }

    fun markdown(blocks: List<Block>): String = blocks.joinToString("\n\n") { b ->
        when (b) {
            is Block.Heading -> "#".repeat(b.level) + " " + b.text
            is Block.Paragraph -> if (b.muted) "_${b.text}_" else b.text
            is Block.Bullet -> when (b.checked) { null -> "- ${b.text}"; true -> "- [x] ${b.text}"; false -> "- [ ] ${b.text}" }
        }
    } + "\n"

    fun text(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is Block.Heading -> (if (b.level > 1) "\n" else "") + b.text
            is Block.Paragraph -> b.text
            is Block.Bullet -> when (b.checked) { null -> "• ${b.text}"; true -> "☑ ${b.text}"; false -> "☐ ${b.text}" }
        }
    } + "\n"

    private fun speakerName(recording: Recording, id: String?, language: String) =
        recording.speakers.firstOrNull { it.id == id }?.let { it.name ?: if (language == "en") "Speaker ${it.index}" else it.displayName }

    fun srt(recording: Recording, options: ExportOptions, language: String = "zh"): String {
        val timed = recording.transcript?.segments.orEmpty().filter { it.startMs != null && it.endMs != null && it.text.isNotBlank() }
        require(timed.isNotEmpty()) { if (language == "en") "The transcript has no timestamps." else "逐字稿没有可用的时间戳" }
        fun t(ms: Long) = "%02d:%02d:%02d,%03d".format(Locale.ROOT, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        return timed.mapIndexed { i, s ->
            val who = speakerName(recording, s.speakerId, language)?.takeIf { options.speakers }?.let { if (language == "en") "$it: " else "$it：" } ?: ""
            "${i + 1}\n${t(s.startMs!!)} --> ${t(s.endMs!!)}\n$who${s.text.trim()}\n"
        }.joinToString("\n")
    }

    fun json(recording: Recording): String = json.encodeToString(recording.copy(waveform = emptyList()))

    /** Minimal WordprocessingML package that Word, WPS and Pages open. */
    fun docx(blocks: List<Block>, output: OutputStream) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = blocks.joinToString("") { b ->
            when (b) {
                is Block.Heading -> "<w:p><w:pPr><w:pStyle w:val=\"Heading${b.level}\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${esc(b.text)}</w:t></w:r></w:p>"
                is Block.Paragraph -> "<w:p><w:r>${if (b.muted) "<w:rPr><w:color w:val=\"5B616E\"/></w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(b.text)}</w:t></w:r></w:p>"
                is Block.Bullet -> "<w:p><w:pPr><w:ind w:left=\"360\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${when (b.checked) { null -> "•"; true -> "☑"; false -> "☐" }} ${esc(b.text)}</w:t></w:r></w:p>"
            }
        }
        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles $ns><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:eastAsia="PingFang SC"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="320" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:spacing w:before="120" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:pPr><w:spacing w:before="240" w:after="80"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style></w:styles>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $ns><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>""",
        )
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
    }
}
