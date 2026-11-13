package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.core.export.ExportFormat
import dev.soundsoftware.core.export.ExportOptions
import dev.soundsoftware.core.export.RecordingExport
import dev.soundsoftware.core.model.Speaker
import dev.soundsoftware.core.transcript.speakerStats
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakersScreen(vm: AppViewModel, id: String, initialSpeaker: String? = null) {
    val recordings by vm.recordings.collectAsState()
    val settings by vm.settings.collectAsState()
    val r = recordings.firstOrNull { it.id == id } ?: return
    val segments = r.transcript?.segments.orEmpty()
    val stats = speakerStats(segments)
    var naming by remember { mutableStateOf(r.speakers.firstOrNull { it.id == initialSpeaker }) }
    var merging by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(C.PageBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(stringResource(R.string.speakers), { vm.back() })
            T(if (r.speakers.isEmpty()) stringResource(R.string.no_identified_speakers) else stringResource(R.string.identified_speakers_count, r.speakers.size), ts(13, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
            Group {
                r.speakers.forEachIndexed { i, s ->
                    val c = speakerColor(s.index)
                    val stat = stats[s.id]
                    Row(Modifier.fillMaxWidth().background(if (naming?.id == s.id) C.Highlight else Color.White).tap { naming = s }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(avatarLabel(s.name, s.index), c.fg, 40.dp, 15)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                T(s.localizedName(), ts(15, 600), Modifier.weight(1f))
                                T("${((stat?.share ?: 0f) * 100).toInt()}% · ${clockShort(stat?.totalMs ?: 0)}", ts(13, 400, C.Muted, tabular = true))
                            }
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (naming?.id == s.id) Color(0xFFE1E6F5) else C.Line)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(stat?.share ?: 0f).clip(RoundedCornerShape(2.dp)).background(c.fg))
                            }
                        }
                        IconButtonPlain(Icons.Play, stringResource(R.string.play_speaker_sample, s.localizedName()), 20.dp) { sample(r, s.id)?.startMs?.let { vm.playFrom(r, it) } }
                    }
                    if (i < r.speakers.lastIndex) Divider()
                }
            }
        }
        naming?.let { s ->
            var name by remember(s.id) { mutableStateOf(s.name ?: "") }
            val example = sample(r, s.id)
            Sheet({ naming = null; merging = false }) {
                T(stringResource(R.string.name_speaker, s.localizedName()), ts(18, 700))
                if (example != null) Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(C.PageBg)
                    .tap { example.startMs?.let { vm.playFrom(r, it) } }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ico(Icons.Play, 18.dp)
                    T("“${example.text.take(22)}${if (example.text.length > 22) "…" else ""}”", ts(14), Modifier.weight(1f), maxLines = 1)
                    example.startMs?.let { T(clock(it), ts(14, 400, C.Muted, tabular = true)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(stringResource(R.string.name), ts(13, 400, C.Muted))
                    Field(name, { name = it.take(20) }, stringResource(R.string.speaker_name_example), autoFocus = true, onDone = { vm.nameSpeaker(id, s.id, name); naming = null })
                }
                val common = (settings.recentSpeakerNames + r.speakers.mapNotNull { it.name }).distinct().filter { it != s.name }.take(4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (common.isNotEmpty() || r.speakers.size > 1) T(stringResource(R.string.recent_names), ts(13, 400, C.Muted), Modifier.align(Alignment.CenterVertically))
                    common.forEach { OutlineChip(it) { name = it } }
                    if (r.speakers.size > 1) OutlineChip(stringResource(R.string.merge_speakers), merging) { merging = !merging }
                }
                if (merging) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(stringResource(R.string.merge_speaker_into, s.localizedName()), ts(13, 400, C.Muted))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.speakers.filter { it.id != s.id }.forEach { other -> OutlineChip(other.localizedName()) { vm.mergeSpeaker(id, s.id, other.id); naming = null; merging = false } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.cancel), Modifier.weight(1f), bg = Color.White, fg = C.Ink, height = 50.dp, weight = 400, border = C.Border) { naming = null; merging = false }
                    PillButton(stringResource(R.string.save), Modifier.weight(1f), height = 50.dp) { vm.nameSpeaker(id, s.id, name); naming = null; merging = false }
                }
            }
        }
    }
}

/** The longest utterance is the clearest sample for recognizing a voice. */
private fun sample(r: dev.soundsoftware.core.model.Recording, speakerId: String) = r.transcript?.segments
    ?.filter { it.speakerId == speakerId && it.startMs != null }?.maxByOrNull { (it.endMs ?: 0) - (it.startMs ?: 0) }

private fun clockShort(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun ExportScreen(vm: AppViewModel, id: String, onShare: (File, String) -> Unit, onSave: (File, String) -> Unit) {
    val recordings by vm.recordings.collectAsState()
    val r = recordings.firstOrNull { it.id == id } ?: return
    var summary by remember { mutableStateOf(r.summary != null) }
    var transcript by remember { mutableStateOf(r.transcript != null) }
    var speakers by remember { mutableStateOf(r.speakers.isNotEmpty()) }
    var timestamps by remember { mutableStateOf(true) }
    var audioTab by remember { mutableStateOf(r.transcript == null && r.summary == null) }
    var format by remember { mutableStateOf(ExportFormat.DOCX) }
    val options = ExportOptions(summary, transcript, speakers, timestamps)
    val available = RecordingExport.canExport(r, format) && (format == ExportFormat.SRT || format == ExportFormat.JSON || summary || transcript)
    Column(Modifier.fillMaxSize().background(C.PageBg).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.export_and_share), { vm.back() }, backIcon = Icons.Close)
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlineChip(stringResource(R.string.document),!audioTab) {audioTab=false}
            OutlineChip(stringResource(R.string.wav_audio),audioTab) {audioTab=true}
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if(audioTab) {
                SectionLabel(stringResource(R.string.separate_audio_export),top=8.dp)
                T("WAV · ${megabytes(r.audioBytes)}",ts(14,500,C.Ink2),Modifier.padding(start=20.dp,end=20.dp,bottom=8.dp))
                T(stringResource(R.string.audio_export_help),ts(13,400,C.Muted),Modifier.padding(start=20.dp,end=20.dp,bottom=16.dp))
                Group {
                    SendRow(Icons.Share,stringResource(R.string.share_audio),null) {vm.exportAudio(r,onShare)}
                    SendRow(Icons.Folder,stringResource(R.string.save_audio_to_files),null,divider=false) {vm.exportAudio(r,onSave)}
                }
            } else {
                T(stringResource(R.string.include_content), ts(13, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp))
                Group {
                    CheckRow(stringResource(R.string.notes_export_option), summary, enabled = r.summary != null) { summary = it }
                    CheckRow(stringResource(R.string.transcript), transcript, enabled = r.transcript != null) { transcript = it }
                    CheckRow(stringResource(R.string.include_speakers), speakers, indent = true, enabled = transcript && r.speakers.isNotEmpty()) { speakers = it }
                    CheckRow(stringResource(R.string.include_timestamps), timestamps, indent = true, enabled = transcript, divider = false) { timestamps = it }
                }
                SectionLabel(stringResource(R.string.format))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExportFormat.entries.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { f ->
                                val ok = RecordingExport.canExport(r, f)
                                Column(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                                    .border(2.dp, if (format == f) C.Blue else Color.Transparent, RoundedCornerShape(14.dp)).tap(enabled = ok) { format = f },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    T(f.localizedTitle(), ts(15, 600, if (ok) C.Ink else C.Faint)); T(".${f.extension}", ts(12, 400, C.Muted))
                                }
                            }
                        }
                    }
                }
                if (format == ExportFormat.SRT && r.transcript?.timestampQuality == "APPROXIMATE_SEGMENT") T(stringResource(R.string.srt_approximate_warning), ts(12, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                SectionLabel(stringResource(R.string.export_document))
                Group {
                    SendRow(Icons.Share, stringResource(R.string.share_document), stringResource(R.string.share_examples), enabled = available) { vm.export(r, format, options, onShare) }
                    SendRow(Icons.Copy, stringResource(R.string.copy_as_text), null, enabled = summary || transcript) { vm.copyText(r, options) }
                    SendRow(Icons.Folder, stringResource(R.string.save_document_to_files), null, divider = false, enabled = available) { vm.export(r, format, options, onSave) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp)) {
            if(audioTab) PillButton(stringResource(R.string.export_wav)) {vm.exportAudio(r,onShare)}
            else PillButton(stringResource(R.string.export_document_format, format.localizedTitle()), enabled = available) { vm.export(r, format, options, onShare) }
        }
    }
}

@Composable
private fun CheckRow(label: String, value: Boolean, indent: Boolean = false, enabled: Boolean = true, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).tap(enabled = enabled) { onChange(!value) }.padding(start = if (indent) 32.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            T(label, ts(if (indent) 14 else 15, 400, if (!enabled) C.Faint else if (indent) C.Ink2 else C.Ink), Modifier.weight(1f))
            WebCheckbox(value && enabled, if (enabled) onChange else null, 20.dp, label = label)
        }
        if (divider) Divider()
    }
}

@Composable
private fun SendRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, hint: String?, divider: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ico(icon, 20.dp, if (enabled) C.Ink else C.Faint)
            T(label, ts(15, 400, if (enabled) C.Ink else C.Faint), Modifier.weight(1f))
            if (hint != null) T(hint, ts(13, 400, C.Muted))
        }
        if (divider) Divider()
    }
}
