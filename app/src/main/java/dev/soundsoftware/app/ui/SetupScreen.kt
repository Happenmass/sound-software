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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.Screen
import dev.soundsoftware.app.processing.ASR_PROFILES
import dev.soundsoftware.core.model.SceneTemplate


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: AppViewModel, recordingId: String?) {
    val settings by vm.settings.collectAsState()
    val recordings by vm.recordings.collectAsState()
    val recording = recordingId?.let { id -> recordings.firstOrNull { it.id == id } }
    var options by remember(recordingId) { mutableStateOf((recording?.options ?: settings.defaults).withCurrentAsrProfile()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val profile = ASR_PROFILES.firstOrNull { it.id == options.profileId } ?: ASR_PROFILES.first()
    val ready = vm.modelReady(options.profileId)
    Box(Modifier.fillMaxSize().background(C.PageBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(stringResource(R.string.transcription_settings), { vm.back() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (recording != null) {
                        T(vm.title(recording), ts(20, 700))
                        T("${minutes(recording.durationMs)} · ${megabytes(recording.audioBytes)}" + if (recording.title == null) stringResource(R.string.auto_title_suffix) else "", ts(13, 400, C.Muted))
                    } else {
                        T(stringResource(R.string.default_transcription_settings), ts(20, 700))
                        T(stringResource(R.string.default_transcription_help), ts(13, 400, C.Muted))
                    }
                }
                T(stringResource(R.string.notes_template), ts(13, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SceneTemplate.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { t ->
                                val selected = options.template == t
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White)
                                    .border(2.dp, if (selected) C.Blue else Color.Transparent, RoundedCornerShape(14.dp))
                                    .tap { options = options.copy(template = t) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    T(stringResource(t.titleRes), ts(15, 600)); T(stringResource(t.subtitleRes), ts(12, 400, C.Muted))
                                }
                            }
                        }
                    }
                }
                if (options.template != SceneTemplate.TRANSCRIPT_ONLY && !vm.llmReady()) {
                    T(stringResource(R.string.notes_unconfigured_hint), ts(12, 400, C.Amber), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).tap(ripple = false) { vm.push(Screen.LlmService) })
                }
                SectionLabel(stringResource(R.string.recognition))
                Group {
                    SettingRow(stringResource(R.string.transcription_service), stringResource(profile.titleRes) + if (!ready) stringResource(R.string.not_ready_suffix) else "", chevron = true) { picker = "profile" }
                    SettingRow(stringResource(R.string.language), stringResource(R.string.detect_language),
                        subtitle = if (options.profileId == "qwen-htp") stringResource(R.string.qwen_language_help) else null)
                    SettingRow(stringResource(R.string.mixed_chinese_english), trailing = { WebCheckbox(options.mixedChineseEnglish, { options = options.copy(mixedChineseEnglish = it) }, label = stringResource(R.string.mixed_chinese_english)) }) {
                        options = options.copy(mixedChineseEnglish = !options.mixedChineseEnglish)
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(stringResource(R.string.speaker_diarization) + if (!vm.diarizationReady()) stringResource(R.string.models_not_installed_suffix) else "", ts(15))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Line).padding(3.dp)) {
                            listOf(stringResource(R.string.detect_speaker_count) to null, stringResource(R.string.set_speaker_count) to (options.speakerCount ?: 2)).forEach { (label, count) ->
                                val selected = (options.speakerCount == null) == (count == null)
                                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Color.White else Color.Transparent)
                                    .tap { options = options.copy(speakerCount = count) }, Alignment.Center) {
                                    T(label, ts(14, if (selected) 600 else 400, if (selected) C.Ink else C.Ink2))
                                }
                            }
                        }
                        options.speakerCount?.let { n ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                T(stringResource(R.string.number_of_speakers), ts(14, 400, C.Muted), Modifier.weight(1f))
                                Stepper("－", n > 1) { options = options.copy(speakerCount = n - 1) }
                                T(stringResource(R.string.people_count, n), ts(15, 600, tabular = true))
                                Stepper("＋", n < 12) { options = options.copy(speakerCount = n + 1) }
                            }
                        }
                    }
                }
                SectionLabel(stringResource(R.string.hotwords_help))
                Group {
                    FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.hotwords.forEach { word ->
                            Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(C.Line).tap(stringResource(R.string.remove_hotword, word)) { options = options.copy(hotwords = options.hotwords - word) }
                                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                T(word, ts(14)); Ico(Icons.Close, 12.dp, C.Muted)
                            }
                        }
                        Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).drawBehind {
                            drawRoundRect(C.Faint, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                        }.tap { adding = true }.padding(horizontal = 12.dp), Alignment.Center) { T(stringResource(R.string.add_hotword_chip), ts(14, 400, C.Blue)) }
                    }
                    if (options.hotwords.isNotEmpty() && options.profileId == "qwen-htp") {
                        T(stringResource(R.string.qwen_htp_hotwords_help), ts(12, 400, C.Muted), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recording != null) {
                    val est = (vm.estimateMs(recording, options) / 60_000).coerceAtLeast(1)
                    T(if (ready) stringResource(R.string.transcription_estimate, est) else stringResource(R.string.transcription_unavailable_help), ts(13, 400, if (ready) C.Muted else C.Amber),
                        Modifier.fillMaxWidth(), align = TextAlign.Center)
                    PillButton(stringResource(R.string.start_transcribing), enabled = ready) { vm.startTranscription(recording.id, options); vm.back() }
                } else PillButton(stringResource(R.string.save), enabled = ready) { vm.saveDefaults(options); vm.back() }
            }
        }
        when (picker) {
            "profile" -> Sheet({ picker = null }) {
                T(stringResource(R.string.transcription_service), ts(18, 700))
                Column {
                    val profiles = vm.localAsrProfiles() + ASR_PROFILES.filterNot { it.local }
                    profiles.forEachIndexed { i, p ->
                        val ok = vm.modelReady(p.id)
                        val unavailable = if (p.local) vm.qwenHtpUnavailableReason() else null
                        SettingRow(stringResource(p.titleRes), if (ok) null else stringResource(R.string.not_ready), subtitle = unavailable ?: stringResource(p.subtitleRes), divider = i < profiles.lastIndex, inset = 0.dp,
                            trailing = { if (p.id == options.profileId) Ico(Icons.Check, 18.dp, C.Blue) }) {
                            if (!ok && p.id == "http-api") { picker = null; vm.push(Screen.AsrService) }
                            else { options = options.copy(profileId = p.id); picker = null }
                        }
                    }
                }
            }

        }
        if (adding) {
            var word by remember { mutableStateOf("") }
            Sheet({ adding = false }) {
                T(stringResource(R.string.add_hotword), ts(18, 700))
                Field(word, { word = it.take(24) }, stringResource(R.string.hotword_placeholder), autoFocus = true, onDone = {
                    if (word.isNotBlank()) options = options.copy(hotwords = (options.hotwords + word.trim()).distinct().take(30)); adding = false
                })
                PillButton(stringResource(R.string.add), enabled = word.isNotBlank()) { options = options.copy(hotwords = (options.hotwords + word.trim()).distinct().take(30)); adding = false }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(C.Line).tap(enabled = enabled, onClick = onClick), Alignment.Center) {
        T(label, ts(16, 600, if (enabled) C.Ink else C.Faint))
    }
}
