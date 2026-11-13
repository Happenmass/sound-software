package dev.soundsoftware.app.export

import android.content.Context
import android.text.format.DateFormat
import dev.soundsoftware.app.R
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.soundsoftware.core.export.Block
import dev.soundsoftware.core.export.ExportFormat
import dev.soundsoftware.core.export.ExportOptions
import dev.soundsoftware.core.export.RecordingExport
import dev.soundsoftware.core.model.Recording
import java.io.File
import java.util.UUID

/** Writes share-ready files under cache/exports (exposed only through the FileProvider). */
object Exporter {
    private fun language(context: Context) = if (context.resources.configuration.locales[0].language == "zh") "zh" else "en"

    private fun subtitle(context: Context, r: Recording): String {
        val date = DateFormat.getMediumDateFormat(context).format(r.createdAt) + " " +
            DateFormat.getTimeFormat(context).format(r.createdAt)
        val seconds = r.durationMs / 1000
        val duration = context.getString(R.string.export_duration, seconds / 60, seconds % 60)
        val speakers = r.speakers.size.takeIf { it > 0 }?.let { context.getString(R.string.speaker_count, it) }
        return listOfNotNull(date, duration, speakers).joinToString(" · ")
    }

    fun plainText(context: Context, r: Recording, title: String, options: ExportOptions) =
        RecordingExport.text(RecordingExport.blocks(r, title, subtitle(context, r), options, language(context)))

    private fun destination(context: Context, title: String, extension: String): File {
        // A later export must not invalidate a URI that another app is still reading.
        val dir = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
        val name = title.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(60).ifEmpty { context.getString(R.string.recordings) }
        return File(dir, "$name.$extension")
    }

    fun writeAudio(context: Context, title: String, audio: File): File {
        require(audio.isFile) { context.getString(R.string.audio_not_found) }
        return destination(context,title,"wav").also { audio.copyTo(it) }
    }

    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.exports",file)
        return Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newRawUri(file.name,uri) }
    }

    fun write(context: Context, r: Recording, title: String, format: ExportFormat, options: ExportOptions): File {
        val blocks = RecordingExport.blocks(r, title, subtitle(context, r), options, language(context))
        val file = destination(context,title,format.extension)
        when (format) {
            ExportFormat.MARKDOWN -> file.writeText(RecordingExport.markdown(blocks))
            ExportFormat.TEXT -> file.writeText(RecordingExport.text(blocks))
            ExportFormat.SRT -> file.writeText(RecordingExport.srt(r, options, language(context)))
            ExportFormat.JSON -> file.writeText(RecordingExport.json(r))
            ExportFormat.DOCX -> file.outputStream().use { RecordingExport.docx(blocks, it) }
            ExportFormat.PDF -> pdf(blocks, file)
        }
        return file
    }

    /** A4 pages laid out with the platform text engine, so CJK fonts come from the system. */
    private fun pdf(blocks: List<Block>, out: File) {
        val doc = PdfDocument()
        val width = 595; val height = 842; val margin = 56
        val content = width - margin * 2
        fun paint(size: Float, bold: Boolean = false, color: Int = Color.parseColor("#16181D")) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNo).create())
        var y = margin.toFloat()
        for (b in blocks) {
            val (text, p, gap) = when (b) {
                is Block.Heading -> Triple(b.text, paint(if (b.level == 1) 20f else 14f, true), if (b.level == 1) 6f else 14f)
                is Block.Paragraph -> Triple(b.text, paint(10.5f, color = if (b.muted) Color.parseColor("#5B616E") else Color.parseColor("#16181D")), 4f)
                is Block.Bullet -> Triple((when (b.checked) { null -> "•  "; true -> "☑  "; false -> "☐  " }) + b.text, paint(10.5f), 2f)
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, content).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f, 1f).build()
            var line = 0
            y += gap
            while (line < layout.lineCount) {
                if (y + layout.getLineBottom(line) - layout.getLineTop(line) > height - margin) {
                    doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNo).create()); y = margin.toFloat()
                }
                val top = layout.getLineTop(line); val bottom = layout.getLineBottom(line)
                page.canvas.save(); page.canvas.translate(margin.toFloat(), y - top)
                page.canvas.clipRect(0, top, content, bottom); layout.draw(page.canvas); page.canvas.restore()
                y += bottom - top; line++
            }
        }
        doc.finishPage(page)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
