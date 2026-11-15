package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.Screen
import dev.soundsoftware.core.model.*

@Composable
fun DetailScreen(vm: AppViewModel, id: String,requestWifiPermission: (() -> Unit) -> Unit = {it()}) {
    val recordings by vm.recordings.collectAsState()
    val r = recordings.firstOrNull { it.id == id } ?: return
    val playback by vm.playback.collectAsState()
    var tab by rememberSaveable(id) { mutableIntStateOf(if (r.summary == null && r.transcript != null) 1 else 0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    var summaryTemplate by remember(id) { mutableStateOf<SceneTemplate?>(null) }
    var recalculate by remember { mutableStateOf(false) }
    var speakerCount by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Segment?>(null) }
    LaunchedEffect(id) { vm.prepare(r) }
    val position = if (playback.recordingId == id) playback.positionMs else 0L
    val playing = playback.recordingId == id && playback.playing
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(null, { vm.back() }) {
                if (tab == 1 && r.transcript != null) IconButtonPlain(Icons.SearchThin, stringResource(R.string.search_in_transcript)) { searching = !searching; if (!searching) query = "" }
                IconButtonPlain(Icons.Share, stringResource(R.string.export_and_share)) { vm.push(Screen.Export(id)) }
                IconButtonPlain(Icons.More, stringResource(R.string.more)) { menu = true }
            }
            if (tab != 1) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(vm.title(r), ts(22, 700), Modifier.tap(ripple = false) { renaming = true })
                T(listOfNotNull(dayTime(r.createdAt), clock(r.durationMs), r.speakers.size.takeIf { it > 0 }?.let { stringResource(R.string.speaker_count, it) }).joinToString(" · "), ts(13, 400, C.Muted))
            }
            Row(Modifier.fillMaxWidth().drawBehind { drawLine(C.Line2, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf(stringResource(R.string.notes), stringResource(R.string.transcript), stringResource(R.string.ai_qa)).forEachIndexed { i, label ->
                    val selected = tab == i
                    Box(Modifier.height(44.dp).tap(ripple = false) { tab = i }.drawBehind {
                        if (selected) drawLine(C.Ink, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                    }, Alignment.Center) { T(label, ts(15, if (selected) 600 else 400, if (selected) C.Ink else C.Muted)) }
                }
            }
            if (r.stage == ProcessingStage.DIARIZING && r.transcript != null) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                T(r.stageText ?: stringResource(R.string.analyzing_speakers),ts(13,400,C.Muted),Modifier.weight(1f))
                T(stringResource(R.string.cancel_analysis),ts(13,600,C.Blue),Modifier.tap {vm.cancelTranscription(id)}.padding(8.dp))
            }
            r.error?.takeIf { r.transcript != null && (it.startsWith("说话人重新计算") || it.startsWith("Could not reanalyze speakers:")) }?.let { T(it,ts(12,400,C.Amber),Modifier.padding(16.dp)) }
            Box(Modifier.weight(1f)) {
                if (r.transcript == null) ProcessingPanel(vm, r,requestWifiPermission)
                else when (tab) {
                    0 -> SummaryTab(vm, r, position,requestWifiPermission) { tab = 1 }
                    1 -> TranscriptTab(vm, r, position, playing, if (searching) query else "", searching, { query = it }) { editing = it }
                    else -> QaTab(vm, r)
                }
            }
            if (r.transcript != null || r.stage == ProcessingStage.NEW || r.stage == ProcessingStage.FAILED) {
                if (tab == 1) FullPlayer(vm, r, position, playing, playback.speed, playback.skipSilence)
                else if (tab == 0) MiniPlayer(vm, r, position, playing)
            }
        }
        if (menu) Sheet({ menu = false }) {
            T(vm.title(r), ts(18, 700))
            Column {
                SettingRow(stringResource(R.string.rename), chevron = true, inset = 0.dp) { menu = false; renaming = true }
                SettingRow(if (r.transcript == null) stringResource(R.string.start_transcribing) else stringResource(R.string.transcribe_again), chevron = true, inset = 0.dp) {
                    menu = false; if (r.transcript == null) vm.push(Screen.Setup(id)) else confirmRetranscribe = true
                }
                if (r.transcript != null) SettingRow(stringResource(R.string.recalculate_speakers),chevron=true,inset=0.dp) {
                    menu=false; speakerCount=r.options?.speakerCount; recalculate=true
                }
                if (r.transcript != null) SettingRow(stringResource(R.string.regenerate_notes), chevron = true, inset = 0.dp) {
                    menu = false
                    if (!vm.llmReady()) vm.push(Screen.LlmService)
                    else summaryTemplate = r.summaryTemplateChoice()
                }
                if (r.speakers.isNotEmpty()) SettingRow(stringResource(R.string.speakers), chevron = true, inset = 0.dp) { menu = false; vm.push(Screen.Speakers(id)) }
                SettingRow(stringResource(R.string.delete_recording), titleColor = C.Red, divider = false, inset = 0.dp) { menu = false; confirmDelete = true }
            }
        }
        summaryTemplate?.let { template ->
            Sheet({ summaryTemplate = null }) {
                T(stringResource(R.string.regenerate_notes), ts(18, 700))
                SummaryTemplateChoices(template) { summaryTemplate = it }
                PillButton(stringResource(R.string.generate_notes)) {
                    summaryTemplate = null
                    tab = 0
                    vm.resummarize(id, template)
                }
            }
        }
        if (recalculate) Sheet({recalculate=false}) {
            T(stringResource(R.string.recalculate_speakers),ts(18,700))
            T(stringResource(R.string.reanalyze_explanation),ts(14,400,C.Muted,lineHeight=1.6f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlineChip(stringResource(R.string.detect_speaker_count),selected=speakerCount==null) {speakerCount=null}
                OutlineChip(stringResource(R.string.set_speaker_count),selected=speakerCount!=null) {speakerCount=speakerCount ?: 2}
            }
            speakerCount?.let {n->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                T(stringResource(R.string.people_count, n),ts(16,600),Modifier.weight(1f))
                T("－",ts(20),Modifier.tap(enabled=n>1){speakerCount=n-1}.padding(12.dp))
                T("＋",ts(20),Modifier.tap(enabled=n<12){speakerCount=n+1}.padding(12.dp))
            }}
            val allowed=r.stage !in listOf(ProcessingStage.DIARIZING,ProcessingStage.TRANSCRIBING,ProcessingStage.QUEUED,ProcessingStage.SUMMARIZING)
            if(!vm.diarizationReady())T(stringResource(R.string.speaker_model_missing),ts(13,400,C.Amber))
            if(!allowed)T(stringResource(R.string.wait_for_processing),ts(13,400,C.Amber))
            PillButton(stringResource(R.string.start_analysis),enabled=allowed && vm.diarizationReady()) {recalculate=false;vm.recalculateSpeakers(id,speakerCount)}
        }
        if (renaming) {
            var title by remember { mutableStateOf(r.title ?: "") }
            Sheet({ renaming = false }) {
                T(stringResource(R.string.rename), ts(18, 700))
                Field(title, { title = it.take(40) }, vm.title(r), autoFocus = true, onDone = { vm.rename(id, title); renaming = false })
                PillButton(stringResource(R.string.save)) { vm.rename(id, title); renaming = false }
            }
        }
        editing?.let { seg ->
            var text by remember(seg.id) { mutableStateOf(seg.text) }
            Sheet({ editing = null }) {
                T(stringResource(R.string.edit_segment), ts(18, 700))
                T(listOfNotNull(seg.startMs?.let(::clock), r.speakers.firstOrNull { it.id == seg.speakerId }?.localizedName()).joinToString(" · "), ts(13, 400, C.Muted))
                Field(text, { text = it }, "", autoFocus = true, singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(R.string.preview_audio), Modifier.weight(1f), bg = Color.White, fg = C.Ink, height = 50.dp, weight = 400, border = C.Border) { seg.startMs?.let { vm.playFrom(r, it) } }
                    PillButton(stringResource(R.string.save), Modifier.weight(1f), height = 50.dp, enabled = text.isNotBlank()) { vm.editSegment(id, seg.id, text.trim()); editing = null }
                }
            }
        }
        if (confirmDelete) ConfirmDialog(stringResource(R.string.delete_recording_title), stringResource(R.string.delete_recording_message), stringResource(R.string.delete), danger = true,
            onDismiss = { confirmDelete = false }) { confirmDelete = false; vm.deleteRecording(id) }
        if (confirmRetranscribe) ConfirmDialog(stringResource(R.string.retranscribe_title), stringResource(R.string.retranscribe_message), stringResource(R.string.continue_action),
            onDismiss = { confirmRetranscribe = false }) { confirmRetranscribe = false; vm.push(Screen.Setup(id)) }
    }
}

@Composable
private fun ProcessingPanel(vm: AppViewModel, r: Recording,requestWifiPermission: (() -> Unit) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DeviceHighlights(vm,r,requestWifiPermission)
        when (r.stage) {
            ProcessingStage.QUEUED, ProcessingStage.DIARIZING, ProcessingStage.TRANSCRIBING, ProcessingStage.SUMMARIZING -> {
                T(r.stageText ?: stringResource(R.string.working), ts(17, 700))
                val p = r.progress
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p ?: 0.04f).clip(RoundedCornerShape(3.dp)).background(C.Blue))
                }
                T(listOfNotNull(p?.let { "${(it * 100).toInt()}%" }, stringResource(R.string.leave_while_processing)).joinToString(" · "), ts(13, 400, C.Muted))
                T(stringResource(R.string.cancel_transcription), ts(14, 400, C.Red), Modifier.tap { vm.cancelTranscription(r.id) }.padding(vertical = 8.dp))
            }
            else -> {
                T(if (r.stage == ProcessingStage.FAILED) stringResource(R.string.transcription_failed) else stringResource(R.string.not_transcribed), ts(17, 700))
                T(r.error ?: stringResource(R.string.start_transcription_help), ts(14, 400, C.Muted, lineHeight = 1.55f))
                PillButton(if (r.stage == ProcessingStage.FAILED) stringResource(R.string.retry_settings) else stringResource(R.string.start_transcribing)) { vm.push(Screen.Setup(r.id)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = T(text, ts(13, 600, C.Muted))

@Composable
private fun DeviceHighlights(vm: AppViewModel,r: Recording,requestWifiPermission: (() -> Unit) -> Unit) {
    HardwareHighlights(r) {vm.playFrom(r,it)}
    if(r.source!=RecordingSource.DEVICE || r.hardwareHighlights!=null) return
    val device by vm.device.collectAsState()
    var choosing by remember {mutableStateOf(false)}
    val reading=device.sync.items.any {it.fileId==r.deviceFileId}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(reading&&device.sync.running) {
            T(device.sync.stage ?: stringResource(R.string.reading_highlights),ts(13,500,C.Blue))
            T(stringResource(R.string.stop_reading),ts(13,500,C.Muted),Modifier.tap {vm.cancelSync()}.padding(vertical=8.dp))
        } else {
            if(device.phase==dev.soundsoftware.app.device.LinkPhase.CONNECTED) OutlineChip(stringResource(R.string.read_highlights)) {choosing=true}
            else OutlineChip(stringResource(R.string.connect_to_read_highlights)) {vm.reconnect()}
            if(reading) device.sync.error?.let {T(it,ts(12,400,C.Red))}
            device.error?.let {T(it,ts(12,400,C.Red))}
        }
    }
    if(choosing) Sheet({choosing=false}) {
        T(stringResource(R.string.read_highlights),ts(18,700))
        T(stringResource(R.string.read_highlights_help),ts(14,400,C.Muted))
        if(device.supportsWifi) PillButton(stringResource(R.string.read_over_wifi)) {
            choosing=false;requestWifiPermission {vm.readHardwareHighlights(r.id,dev.soundsoftware.app.device.SyncTransport.WIFI)}
        }
        PillButton(stringResource(R.string.read_over_bluetooth),bg=C.PageBg,fg=C.Ink) {choosing=false;vm.readHardwareHighlights(r.id,dev.soundsoftware.app.device.SyncTransport.BLUETOOTH)}
    }
}

private fun Recording.summaryTemplateChoice() =
    options?.template?.takeUnless { it == SceneTemplate.TRANSCRIPT_ONLY } ?: SceneTemplate.AUTO

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryTemplateChoices(selected: SceneTemplate, onSelect: (SceneTemplate) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SceneTemplate.entries.filter { it != SceneTemplate.TRANSCRIPT_ONLY }.forEach { template ->
            OutlineChip(stringResource(template.titleRes), selected == template) { onSelect(template) }
        }
    }
}

@Composable
private fun SummaryTab(vm: AppViewModel, r: Recording, position: Long,requestWifiPermission: (() -> Unit) -> Unit, onOpenTranscript: () -> Unit) {
    val s = r.summary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        DeviceHighlights(vm,r,requestWifiPermission)
        if (s == null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    r.stage == ProcessingStage.SUMMARIZING -> { T(r.stageText ?: stringResource(R.string.generating_notes_ellipsis), ts(17, 700)); T(stringResource(R.string.transcript_ready_open), ts(14, 400, C.Muted)) }
                    !vm.llmReady() -> {
                        T(stringResource(R.string.no_notes), ts(17, 700))
                        T(stringResource(R.string.notes_setup_help), ts(14, 400, C.Muted, lineHeight = 1.55f))
                        PillButton(stringResource(R.string.configure_notes_service)) { vm.push(Screen.LlmService) }
                    }
                    else -> {
                        T(if (r.options?.template == SceneTemplate.TRANSCRIPT_ONLY) stringResource(R.string.transcript_only_selected) else stringResource(R.string.no_notes), ts(17, 700))
                        r.error?.let { T(it, ts(14, 400, C.Red, lineHeight = 1.55f)) }
                        var template by remember(r.id, r.options?.template) { mutableStateOf(r.summaryTemplateChoice()) }
                        SummaryTemplateChoices(template) { template = it }
                        PillButton(stringResource(R.string.generate_notes)) { vm.resummarize(r.id, template) }
                    }
                }
            }
            T(stringResource(R.string.view_transcript), ts(14, 400, C.Blue), Modifier.tap(onClick = onOpenTranscript))
            return@Column
        }
        if (r.stage == ProcessingStage.SUMMARIZING) T(r.stageText ?: stringResource(R.string.generating_notes_ellipsis), ts(13, 400, C.Blue))
        r.error?.takeIf { it.startsWith("纪要") || it.startsWith("Notes could not be generated:") }?.let { T(it, ts(13, 400, C.Red)) }
        T(stringResource(s.template.titleRes), ts(13, 600, C.Muted))
        if (s.abstract.isBlank() && s.keyPoints.isEmpty() && s.decisions.isEmpty() && s.risks.isEmpty() &&
            s.openQuestions.isEmpty() && s.todos.isEmpty() && s.chapters.isEmpty())
            T(stringResource(R.string.no_verified_notes), ts(14, 400, C.Muted))
        val inputHash = remember(r.transcript, r.speakers, r.durationMs) { dev.soundsoftware.core.analysis.MeetingAnalyzer.transcriptHash(r) }
        if ((s.sourceTranscriptHash.isNotBlank() && s.sourceTranscriptHash != inputHash) || (r.speakerAnalysis?.computedAt ?: 0) > s.createdAt)
            T(stringResource(R.string.notes_stale), ts(13, 400, C.Muted))
        if (s.abstract.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.summary)); T(s.abstract, ts(15, 400, lineHeight = 1.65f))
            EvidenceLinks(vm, r, s.evidence["abstract"].orEmpty())
        }
        if (s.keyPoints.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(s.template.keyPointsRes))
            s.keyPoints.forEachIndexed { index, p -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { T("•", ts(15, 700, C.Muted)); T(p, ts(15, 400, lineHeight = 1.6f)) }
                EvidenceLinks(vm, r, s.evidence["keyPoints/$index"].orEmpty())
            } }
        }
        listOf(stringResource(R.string.decisions) to ("decisions" to s.decisions), stringResource(R.string.risks) to ("risks" to s.risks), stringResource(R.string.open_questions) to ("openQuestions" to s.openQuestions))
            .filter { it.second.second.isNotEmpty() }.forEach { (title, category) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(title)
                    category.second.forEachIndexed { index, text -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        T(text, ts(15, 400, lineHeight = 1.6f)); EvidenceLinks(vm, r, s.evidence["${category.first}/$index"].orEmpty())
                    } }
                }
            }
        if (s.todos.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.todos_with_count, s.todos.size))
            s.todos.forEach { todo -> key(todo.id) {Column {
                SwipeTodoRow(todo,r,{vm.toggleTodo(r.id,todo.id)},{vm.deleteTodo(r.id,todo.id)})
                EvidenceLinks(vm, r, todo.evidence)
            }} }
        }
        if (s.chapters.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(stringResource(R.string.chapters), ts(13, 600, C.Muted), Modifier.padding(bottom = 4.dp))
            val current = s.chapters.lastOrNull { it.startMs <= position }
            s.chapters.forEach { ch ->
                val on = ch == current && position > 0
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).let { if (on) it.offset(x = (-10).dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Highlight) else it }
                    .tap { vm.playFrom(r, ch.startMs) }.padding(horizontal = if (on) 10.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    T(clock(ch.startMs), ts(13, 600, C.Blue, tabular = true), Modifier.width(44.dp))
                    T(ch.title, ts(15, if (on) 600 else 400))
                }
            }
        }
        T(stringResource(R.string.model_attribution, s.model), ts(12, 400, C.Faint))
    }
}

@Composable
private fun EvidenceLinks(vm: AppViewModel, recording: Recording, refs: List<EvidenceRef>) {
    val segments = recording.transcript?.segments.orEmpty().associateBy { it.id }
    refs.distinct().forEach { ref ->
        val segment = segments[ref.segmentId]
        if (segment != null && segment.text.contains(ref.quote)) {
            val label = listOfNotNull(segment.startMs?.let(::clock), recording.speakers.firstOrNull { it.id == segment.speakerId }?.localizedName()).joinToString(" · ")
            Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(if (label.isBlank()) stringResource(R.string.source_text) else stringResource(R.string.source_with_time, label), ts(12, 400, C.Blue),
                    Modifier.tap(enabled = segment.startMs != null) { segment.startMs?.let { vm.playFrom(recording, it) } })
                T("“${ref.quote.take(140)}${if (ref.quote.length > 140) "…" else ""}”", ts(12, 400, C.Muted, lineHeight = 1.5f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptTab(vm: AppViewModel, r: Recording, position: Long, playing: Boolean, query: String, searching: Boolean,
    onQuery: (String) -> Unit, onEdit: (Segment) -> Unit) {
    var filter by rememberSaveable(r.id) { mutableStateOf<String?>(null) }
    val all = r.transcript?.segments.orEmpty()
    val segments = all.filter { (filter == null || it.speakerId == filter) && (query.isBlank() || it.text.contains(query.trim(), true)) }
    val currentId = all.lastOrNull { it.startMs != null && it.startMs!! <= position && (it.endMs ?: Long.MAX_VALUE) > position }?.id
    val list = rememberLazyListState()
    LaunchedEffect(currentId, playing) {
        if (playing && currentId != null) segments.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }?.let { list.animateScrollToItem(maxOf(0, it - 1)) }
    }
    Column(Modifier.fillMaxSize()) {
        if (searching) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { Field(query, onQuery, stringResource(R.string.search_transcript), autoFocus = true) }
        if (r.speakers.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(stringResource(R.string.all), ts(13, 400, if (filter == null) Color.White else C.Ink), Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                .background(if (filter == null) C.Ink else C.Line).tap { filter = null }.padding(horizontal = 12.dp).wrapContentHeight())
            r.speakers.forEach { s ->
                val c = speakerColor(s.index)
                val on = filter == s.id
                T(s.localizedName(), ts(13, 400, if (on) Color.White else c.fg), Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (on) c.fg else c.bg).combinedClickable(onLongClick = { vm.push(Screen.Speakers(r.id)) }) { filter = if (on) null else s.id }
                    .padding(horizontal = 12.dp).wrapContentHeight(), maxLines = 1)
            }
        } else Spacer(Modifier.height(12.dp))
        if (r.transcript?.backend == "qnn-htp") {
            T(stringResource(R.string.transcript_htp), ts(12, 600, C.Teal),
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        }
        val speakerHtpExecutions = r.transcript?.speakerHtpExecutions ?: 0
        if (speakerHtpExecutions > 0) {
            T(stringResource(R.string.speaker_htp, speakerHtpExecutions), ts(12, 600, C.Teal),
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        }
        r.transcript?.warnings?.firstOrNull()?.let { warning ->
            // Apply the same wording to warnings stored in earlier versions.
            val message = if (warning.startsWith("部分短应答或噪声")) {
                stringResource(R.string.speaker_unassigned_segments)
            } else {
                warning
            }
            T(message, ts(12, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        }
        LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (segments.isEmpty()) item { T(if (query.isNotBlank()) stringResource(R.string.no_matches) else stringResource(R.string.empty_transcript), ts(14, 400, C.Muted)) }
            itemsIndexed(segments, key = { _, s -> s.id }) { _, seg ->
                val speaker = r.speakers.firstOrNull { it.id == seg.speakerId }
                val color = speaker?.let { speakerColor(it.index).fg } ?: C.Ink2
                val on = seg.id == currentId && position > 0
                Row(Modifier.fillMaxWidth().let { if (on) it.offset(x = (-10).dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Highlight).padding(10.dp) else it }
                    .combinedClickable(onLongClick = { onEdit(seg) }) { seg.startMs?.let { vm.playFrom(r, it) } },
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (speaker != null) Avatar(avatarLabel(speaker.name, speaker.index), color, 32.dp, 13)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            if (speaker != null) T(speaker.localizedName() + if (speaker.name == null) stringResource(R.string.tap_to_name_suffix) else "", ts(14, 600, color),
                                Modifier.tap(ripple = false) { vm.push(Screen.Speakers(r.id, speaker.id)) })
                            if (speaker == null && r.speakers.isNotEmpty()) T(stringResource(R.string.unknown_speaker), ts(12, 400, C.Muted))
                            seg.startMs?.let { T(clock(it), ts(12, 400, C.Muted, tabular = true)) }
                            if (on && playing) T(stringResource(R.string.now_playing), ts(12, 600, C.Blue))
                            if (seg.edited) T(stringResource(R.string.edited), ts(12, 400, C.Faint))
                        }
                        BasicText(highlight(seg.text, query), style = ts(15, 400, lineHeight = 1.7f))
                    }
                }
            }
            item { T(stringResource(R.string.edit_transcript_hint), ts(12, 400, C.Faint)) }
        }
    }
}

private fun highlight(text: String, query: String) = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) { append(text); return@buildAnnotatedString }
    var start = 0
    while (true) {
        val i = text.indexOf(q, start, ignoreCase = true)
        if (i < 0) { append(text.substring(start)); break }
        append(text.substring(start, i))
        withStyle(SpanStyle(background = Color(0xFFFFE7A3))) { append(text.substring(i, i + q.length)) }
        start = i + q.length
    }
}

@Composable
private fun QaTab(vm: AppViewModel, r: Recording) {
    AssistantContent(vm, recordingId = r.id, recordingTitle = vm.title(r))
}

@Composable
private fun MiniPlayer(vm: AppViewModel, r: Recording, position: Long, playing: Boolean) {
    val chapter = r.summary?.chapters?.lastOrNull { it.startMs <= position }
    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Ink)
        .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).tap(if (playing) stringResource(R.string.pause) else stringResource(R.string.play)) { vm.playPause(r) }, Alignment.Center) {
            Ico(if (playing) Icons.Pause else Icons.Play, 18.dp, C.Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(position, r.durationMs, C.Ink2, Color.White) { vm.prepare(r); vm.seekTo(it) }
            Row {
                T(clock(position) + (chapter?.let { " · ${it.title}" } ?: ""), ts(12, 400, Color(0xFFC4C8D0), tabular = true), Modifier.weight(1f), maxLines = 1)
                T(clock(r.durationMs), ts(12, 400, Color(0xFFC4C8D0), tabular = true))
            }
        }
    }
}

@Composable
private fun ProgressBar(position: Long, duration: Long, track: Color, fill: Color, onSeek: (Long) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    Box(Modifier.fillMaxWidth().height(16.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }.pointerInput(duration) {
        detectTapGestures { o -> onSeek((o.x / width * duration).toLong().coerceIn(0, duration)) }
    }, Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f).clip(RoundedCornerShape(2.dp)).background(fill))
        }
    }
}


@Composable
private fun FullPlayer(vm: AppViewModel, r: Recording, position: Long, playing: Boolean, speed: Float, skipSilence: Boolean) {
    Column(Modifier.fillMaxWidth().drawBehind { drawLine(C.Line2, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.background(Color.White)
        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(position, r.durationMs, C.Line, C.Ink) { vm.prepare(r); vm.seekTo(it) }
            Row { T(clock(position), ts(12, 400, C.Muted, tabular = true), Modifier.weight(1f)); T(clock(r.durationMs), ts(12, 400, C.Muted, tabular = true)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerPill(if (speed == 1f) "1.0×" else "${speed.toString().removeSuffix("0").removeSuffix(".")}×") { vm.cycleSpeed() }
            Spacer(Modifier.weight(1f))
            IconButtonPlain(Icons.Rewind, stringResource(R.string.rewind_15), 24.dp) { vm.prepare(r); vm.seekBy(-15_000) }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(56.dp).clip(CircleShape).background(C.Ink).tap(if (playing) stringResource(R.string.pause) else stringResource(R.string.play)) { vm.playPause(r) }, Alignment.Center) {
                Ico(if (playing) Icons.Pause else Icons.Play, 20.dp, Color.White)
            }
            Spacer(Modifier.width(12.dp))
            IconButtonPlain(Icons.Forward, stringResource(R.string.forward_15), 24.dp) { vm.prepare(r); vm.seekBy(15_000) }
            Spacer(Modifier.weight(1f))
            PlayerPill(stringResource(R.string.skip_silence), skipSilence) { vm.toggleSkipSilence() }
        }
    }
}

@Composable
private fun PlayerPill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    T(text, ts(13, if (selected) 600 else 400, if (selected) Color.White else C.Ink), Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
        .background(if (selected) C.Ink else Color.White).border(1.dp, if (selected) C.Ink else C.Border, RoundedCornerShape(22.dp))
        .tap(onClick = onClick).padding(horizontal = 12.dp).wrapContentHeight())
}
