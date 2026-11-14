package dev.soundsoftware.app.ui

import androidx.compose.ui.res.stringResource
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.BasicText
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.R
import dev.soundsoftware.app.Screen
import dev.soundsoftware.app.Tab
import dev.soundsoftware.app.device.DeviceState
import dev.soundsoftware.app.device.DiscoveryPhase
import dev.soundsoftware.app.device.LinkPhase
import kotlinx.coroutines.delay
import dev.soundsoftware.core.model.ProcessingStage
import dev.soundsoftware.core.model.Recording

@Composable
fun MainScreen(vm: AppViewModel, onImport: () -> Unit, onRequestPermissions: () -> Unit) {
    val tab by vm.tab.collectAsState()
    val device by vm.device.collectAsState()
    val sheet = device.discovery != null && device.nearby != null
    var overlay by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(if (tab == Tab.ME || tab == Tab.TODOS) C.PageBg else C.HomeBg)) {
        Box(Modifier.fillMaxSize().let { if (sheet && Build.VERSION.SDK_INT >= 31) it.blur(14.dp) else it }) {
            when (tab) {
                Tab.RECORDINGS -> HomeContent(vm, onRequestPermissions)
                Tab.TODOS -> TodosContent(vm)
                Tab.DEVICE -> DeviceScreen(vm, asTab = true, onRequestPermissions = onRequestPermissions) { overlay = it }
                Tab.ME -> MeContent(vm, onImport)
            }
            if (!(overlay && tab == Tab.DEVICE)) {
                BottomNav(tab, vm::selectTab, { vm.push(Screen.Assistant) }, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (sheet) DiscoverySheet(vm, device)
    }
}

@Composable
private fun BottomNav(tab: Tab, onSelect: (Tab) -> Unit, onAssistant: () -> Unit, modifier: Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 22.dp)
            .width(344.dp).height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        NavGroup(Modifier.weight(1f)) {
            NavTab(Tab.RECORDINGS, tab, Icons.Wave, stringResource(R.string.recordings), onSelect)
            NavTab(Tab.TODOS, tab, Icons.Todo, stringResource(R.string.todos), onSelect)
        }
        val piShape = RoundedCornerShape(22.dp)
        Box(
            Modifier.align(Alignment.Top).size(60.dp)
                .shadow(10.dp, piShape, ambientColor = C.Ink.copy(alpha = 0.16f), spotColor = C.Ink.copy(alpha = 0.22f))
                .clip(piShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF334A68), Color(0xFF1A263C))))
                .tap(stringResource(R.string.recording_assistant), onClick = onAssistant),
            Alignment.Center,
        ) {
            T("Pi", ts(29, 800, Color.White), maxLines = 1)
        }
        NavGroup(Modifier.weight(1f)) {
            NavTab(Tab.DEVICE, tab, Icons.Bean, stringResource(R.string.device), onSelect)
            NavTab(Tab.ME, tab, Icons.Person, stringResource(R.string.settings), onSelect)
        }
    }
}

@Composable
private fun NavGroup(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.height(56.dp)
            .shadow(8.dp, shape, ambientColor = Color(0x210C0D10), spotColor = Color(0x210C0D10))
            .clip(shape).background(C.Card).border(1.dp, C.Line, shape).padding(4.dp),
        content = content,
    )
}

@Composable
private fun RowScope.NavTab(
    item: Tab,
    selectedTab: Tab,
    icon: ImageVector,
    label: String,
    onSelect: (Tab) -> Unit,
) {
    val selected = item == selectedTab
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
            .background(if (selected) C.Ink else Color.Transparent).tap(label) { onSelect(item) },
        Alignment.Center,
    ) {
        Ico(icon, 22.dp, if (selected) Color.White else C.Ink2)
    }
}

@Composable
private fun HomeContent(vm: AppViewModel, onRequestPermissions: () -> Unit) {
    val recordings by vm.recordings.collectAsState()
    val device by vm.device.collectAsState()
    val groups = linkedMapOf<String, MutableList<Recording>>()
    for (recording in recordings) groups.getOrPut(dayLabel(recording.createdAt)) { mutableListOf() }.add(recording)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(headerDate(), ts(13, 500, C.Muted))
                    T(stringResource(R.string.recordings), ts(34, 800, spacing = (-0.02).em))
                }
                RoundIconButton(Icons.Search, stringResource(R.string.search_recordings), Color.White, C.Ink, shadow = true) { vm.push(Screen.Search) }
            }
        }
        item { DeviceCard(vm, device, onRequestPermissions) }
        if (recordings.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(stringResource(R.string.no_recordings), ts(17, 700))
                T(if (device.bound == null) stringResource(R.string.recordings_empty_unpaired) else stringResource(R.string.recordings_empty_paired),
                    ts(14, 400, C.Muted, lineHeight = 1.55f), align = TextAlign.Center)
            }
        }
        groups.entries.forEachIndexed { index, (label, list) ->
            item(key = "h-$label") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    T(label, ts(20, 700), Modifier.weight(1f))
                    if (index == 0) T(stringResource(R.string.all_recordings), ts(14, 400, C.Blue), Modifier.tap { vm.push(Screen.Search) })
                }
            }
            items(list, key = { it.id }) { r -> Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { RecordingCard(vm, r) } }
        }
    }
}

@Composable
private fun DeviceCard(vm: AppViewModel, device: DeviceState, onRequestPermissions: () -> Unit) {
    val info = device.info
    val connected = device.phase == LinkPhase.CONNECTED
    val newCount = vm.newRecordings().size
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x000C0D10), spotColor = Color(0x660C0D10))
        .clip(RoundedCornerShape(30.dp)).background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(196.dp).background(C.Photo).clipToBounds()) {
            ProductStill(device.bound?.white ?: true, 10_000)
            val (chip, dot) = when {
                device.bound == null -> stringResource(R.string.device_not_added) to C.Faint
                connected -> stringResource(R.string.connected) to C.Green
                device.phase == LinkPhase.CONNECTING -> stringResource(R.string.connecting_ellipsis) to C.Amber
                else -> stringResource(R.string.disconnected) to C.Faint
            }
            Row(Modifier.padding(12.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xC7FFFFFF)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(dot, breathe = connected || device.phase == LinkPhase.CONNECTING); T(chip, ts(13, 600))
            }
        }
        if (device.bound == null) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(stringResource(R.string.add_recorder), ts(20, 700, spacing = (-0.01).em))
                T(if (device.phase == LinkPhase.SCANNING) stringResource(R.string.scanning_nearby) else stringResource(R.string.auto_discovery_help), ts(13, 400, C.Muted))
            }
            Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                PillButton(if (device.phase == LinkPhase.SCANNING) stringResource(R.string.scanning) else stringResource(R.string.scan_nearby), height = 48.dp, fontSize = 15,
                    enabled = device.phase != LinkPhase.SCANNING) { onRequestPermissions(); vm.scan() }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).tap(ripple = false) { vm.push(Screen.Device) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(stringResource(R.string.open_ai_recorder), ts(20, 700, spacing = (-0.01).em))
                T(info?.let { stringResource(R.string.compact_device_storage, gb(it.totalKb - it.freeKb), gb(it.totalKb, round = true)) } ?: (if (device.phase == LinkPhase.CONNECTING) stringResource(R.string.device_connecting) else stringResource(R.string.auto_reconnect_hint)), ts(13, 400, C.Muted))
            }
            if (info != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                BatteryLine(stringResource(R.string.app_name), info.battery, info.charging)
                BatteryLine(stringResource(R.string.charging_case), info.caseBattery, info.caseCharging)
            }
        }
        Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val recording = info?.extras?.recording == true || device.activeRecordingFileId != null
            Row(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.RedSoft)
                .tap(if (recording) stringResource(R.string.stop_device_recording) else stringResource(R.string.start_device_recording), enabled = connected && !device.commandBusy) { vm.toggleDeviceRecording() }
                .padding(horizontal = 16.dp).alpha(if (connected) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recording) Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(C.RedDot)) else Dot(C.RedDot, 10.dp)
                T(if (recording) stringResource(R.string.stop) else stringResource(R.string.recordings), ts(15, 600, C.Red))
            }
            val sync = device.sync
            val label = when {
                !connected -> if (device.phase == LinkPhase.CONNECTING) stringResource(R.string.connecting_to_recorder) else stringResource(R.string.reconnect)
                sync.running -> stringResource(R.string.syncing_percent, (sync.fraction * 100).toInt())
                newCount > 0 -> stringResource(R.string.sync_new_recordings, newCount)
                device.inventory == null -> stringResource(R.string.reading_recordings)
                else -> stringResource(R.string.all_device_recordings_synced)
            }
            Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.Ink)
                .tap(label) {
                    if (!connected) { onRequestPermissions(); vm.reconnect() }
                    else vm.push(Screen.Sync())
                }.padding(start = 16.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                T(label, ts(15, 600, Color.White), Modifier.weight(1f), maxLines = 1)
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x29FFFFFF)), Alignment.Center) { Ico(Icons.Download, 16.dp, Color.White) }
            }
        }
    }
}

fun gb(kb: Long, round: Boolean = false): String {
    val v = kb / 1024.0 / 1024.0
    return if (round && v >= 1) "%.0f".format(Math.ceil(v)) else "%.1f".format(v)
}

@Composable
private fun BatteryLine(label: String, percent: Int, charging: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (charging) Ico(Icons.Bolt, 12.dp, C.Green, Modifier.width(10.dp))
        T(label, ts(12, 400, C.Muted))
        BatteryGlyph(percent)
        T("$percent%", ts(14, 700, tabular = true), Modifier.width(38.dp), align = TextAlign.End)
    }
}

/** Front render of the case with the bean docked (tools/3d), breathing slowly. */
@Composable
fun ProductStill(white: Boolean, periodMs: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "drift")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    Image(painterResource(if (white) R.drawable.device_white_front else R.drawable.device_black_front),
        if (white) stringResource(R.string.white_recorder_with_case) else stringResource(R.string.black_recorder_with_case),
        modifier.fillMaxSize().padding(vertical = 6.dp).graphicsLayer {
            val s = 1f + 0.03f * p; scaleX = s; scaleY = s; translationY = -3.dp.toPx() * p
        }, contentScale = ContentScale.Fit)
}

/** The pairing turntable (tools/3d): a transparent looping animated WebP; the front still where it can't play. */
@Composable
fun ProductSpin(white: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = Build.VERSION.SDK_INT < 28 ||
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    if (still) { ProductStill(white, 9_000, modifier); return }
    val res = if (white) R.raw.pairing_spin_white else R.raw.pairing_spin_black
    val animationLabel = stringResource(if (white) R.string.white_recorder_animation else R.string.black_recorder_animation)
    key(res) {
        AndroidView({ ctx ->
            ImageView(ctx).apply {
                contentDescription = animationLabel
                scaleType = ImageView.ScaleType.FIT_CENTER
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.resources, res))
                setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.start()
            }
        }, modifier.fillMaxSize())
    }
}

data class RecordingStatusUi(val text: String, val fg: Color, val bg: Color)

@Composable
fun statusOf(r: Recording): RecordingStatusUi? = when (r.stage) {
    ProcessingStage.DONE -> when {
        r.summary != null -> RecordingStatusUi(stringResource(R.string.notes_ready), C.Teal, C.TealSoft)
        r.transcript != null -> RecordingStatusUi(stringResource(R.string.transcript_ready), C.Teal, C.TealSoft)
        else -> null
    }
    ProcessingStage.DIARIZING -> RecordingStatusUi(stringResource(R.string.analyzing_speakers), C.Blue, C.BlueSoft)
    ProcessingStage.TRANSCRIBING -> RecordingStatusUi(stringResource(R.string.transcribing) + (r.progress?.takeIf { it > 0 }?.let { " ${(it * 100).toInt()}%" } ?: ""), C.Blue, C.BlueSoft)
    ProcessingStage.SUMMARIZING -> RecordingStatusUi(stringResource(R.string.generating_notes), C.Blue, C.BlueSoft)
    ProcessingStage.QUEUED -> RecordingStatusUi(stringResource(R.string.queued), C.Muted, C.Chip)
    ProcessingStage.FAILED -> RecordingStatusUi(stringResource(R.string.transcription_failed), C.Red, C.RedSoft)
    ProcessingStage.NEW -> if (r.transcript != null) RecordingStatusUi(stringResource(R.string.transcript_ready), C.Teal, C.TealSoft) else RecordingStatusUi(stringResource(R.string.awaiting_transcription), C.Muted, C.Chip)
}

fun waveColor(r: Recording): (Float) -> Color {
    val processing = r.stage in listOf(ProcessingStage.DIARIZING, ProcessingStage.TRANSCRIBING)
    if (processing) { val p = r.progress ?: 0f; return { t -> if (t < p) C.Blue else C.Track } }
    val segments = r.transcript?.segments.orEmpty().filter { it.startMs != null }
    if (segments.isEmpty() || r.speakers.isEmpty()) return { _ -> if (r.transcript != null) C.Blue else Color(0xFFB9BEC8) }
    val index = r.speakers.associate { it.id to it.index }
    return { t ->
        val ms = (t * r.durationMs).toLong()
        val s = segments.lastOrNull { it.startMs!! <= ms } ?: segments.first()
        index[s.speakerId]?.let { speakerColor(it).fg } ?: C.Track
    }
}

@Composable
fun RecordingCard(vm: AppViewModel, r: Recording) {
    Column(Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x0A000000), spotColor = Color(0x0A000000))
        .clip(RoundedCornerShape(24.dp)).background(Color.White).tap { vm.push(Screen.Detail(r.id)) }
        .padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(vm.title(r), ts(17, 700), Modifier.weight(1f), maxLines = 1)
            statusOf(r)?.let { StatusChip(it.text, it.fg, it.bg) }
        }
        Waveform(r.waveform, waveColor(r))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${timeOnly(r.createdAt)} · ${minutes(r.durationMs)}", ts(13, 400, C.Muted), Modifier.weight(1f))
            SpeakerStack(r)
        }
    }
}

@Composable
fun SpeakerStack(r: Recording, size: Dp = 24.dp) {
    if (r.speakers.isEmpty()) return
    val shown = if (r.speakers.size > 4) r.speakers.take(3) else r.speakers
    Row {
        shown.forEachIndexed { i, s ->
            Avatar(avatarLabel(s.name, s.index), speakerColor(s.index).fg, size, 11, 2.dp, if (i > 0) Modifier.offset(x = (-6 * i).dp) else Modifier)
        }
        if (r.speakers.size > 4) Avatar("+${r.speakers.size - 3}", C.Ink2, size, 11, 2.dp, Modifier.offset(x = (-6 * 3).dp))
    }
}

// ---- Discovery and binding ------------------------------------------------------------------

@Composable
private fun DiscoverySheet(vm: AppViewModel, device: DeviceState) {
    val phase = device.discovery ?: return
    val nearby = device.nearby ?: return
    val rise = remember { Animatable(40f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, tween(600, easing = CubicBezierEasing(.2f, .9f, .3f, 1.2f))) }
    Box(Modifier.fillMaxSize().background(Color(0x610C0D10)).tap(ripple = false) { vm.dismissDiscovery() }) {
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).fillMaxWidth()
            .graphicsLayer { translationY = rise.value.dp.toPx(); alpha = 1f - rise.value / 40f }
            .shadow(30.dp, RoundedCornerShape(44.dp), spotColor = Color(0x59000000))
            .clip(RoundedCornerShape(44.dp)).background(Color.White).tap(ripple = false) {}
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(44.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(stringResource(R.string.ai_recorder), ts(21, 700, spacing = (-0.01).em))
                    T("${nearby.name.removeSuffix("（模拟）").let { if (it.isBlank()) "soundcore Work" else it }}${if (nearby.white != false) stringResource(R.string.white_suffix) else stringResource(R.string.black_suffix)}", ts(12, 400, C.Muted))
                }
                Box(Modifier.padding(4.dp).size(36.dp).clip(CircleShape).background(C.Chip).tap(stringResource(R.string.close)) { vm.dismissDiscovery() }, Alignment.Center) {
                    Ico(Icons.CloseBold, 14.dp, C.Muted)
                }
            }
            Box(Modifier.padding(top = 14.dp, bottom = 20.dp).fillMaxWidth().aspectRatio(322f / 272f)) {
                ProductSpin(nearby.white != false)
                if (phase == DiscoveryPhase.CONFIRM) {
                    Row(Modifier.align(Alignment.BottomStart).padding(12.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(C.Chip).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(C.Amber); T(stringResource(R.string.press_side_button_short), ts(13, 600)) }
                }
                if (phase == DiscoveryPhase.DONE) {
                    val pop = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { delay(1000); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
                    Row(Modifier.align(Alignment.BottomStart).padding(12.dp).height(30.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }
                        .clip(RoundedCornerShape(15.dp)).background(C.Chip).padding(start = 6.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(C.Green), Alignment.Center) { Ico(Icons.Check, 12.dp, Color.White) }
                        T(stringResource(R.string.paired), ts(13, 600))
                    }
                }
            }
            when (phase) {
                DiscoveryPhase.FOUND -> {
                    T(if (nearby.boundToOther == true) stringResource(R.string.discovered_other_phone) else stringResource(R.string.discovered_unpaired),
                        ts(15, 400, C.Muted, lineHeight = 1.55f), align = TextAlign.Center)
                    device.error?.let { T(it, ts(13, 400, C.Red), Modifier.padding(top = 10.dp), align = TextAlign.Center) }
                    Spacer(Modifier.height(22.dp))
                    PillButton(stringResource(R.string.connect), bg = C.Blue, height = 56.dp, fontSize = 17) { vm.bindNearby() }
                }
                DiscoveryPhase.CONNECTING -> {
                    T(stringResource(R.string.connecting_keep_nearby), ts(15, 400, C.Muted, lineHeight = 1.55f), align = TextAlign.Center)
                    Spacer(Modifier.height(22.dp)); PillButton(stringResource(R.string.connecting), height = 56.dp, fontSize = 17, enabled = false) {}
                }
                DiscoveryPhase.CONFIRM -> {
                    BasicText(buildAnnotatedString {
                        withStyle(SpanStyle(color = C.Ink, fontWeight = FontWeight.Bold)) { append(stringResource(R.string.press_side_button)) }
                        append(stringResource(R.string.confirm_pairing_suffix))
                    }, style = ts(15, 400, C.Muted, lineHeight = 1.55f).copy(textAlign = TextAlign.Center))
                    Spacer(Modifier.height(22.dp)); PillButton(stringResource(R.string.waiting_for_confirmation), height = 56.dp, fontSize = 17, enabled = false) {}
                }
                DiscoveryPhase.DONE -> DiscoveryDone(vm, device)
            }
        }
    }
}

@Composable
private fun DiscoveryDone(vm: AppViewModel, device: DeviceState) {
    val info = device.info
    val count = vm.newRecordings().size
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (info != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BatteryTile(stringResource(R.string.app_name) + if (info.charging) stringResource(R.string.charging_suffix) else "", info.battery, Modifier.weight(1f))
            BatteryTile(stringResource(R.string.charging_case) + if (info.caseCharging) stringResource(R.string.charging_suffix) else "", info.caseBattery, Modifier.weight(1f))
        }
        val countText = stringResource(R.string.device_recording_count, device.inventory?.size ?: 0)
        T(countText + if (device.bound?.firmwareBound == false) stringResource(R.string.pairing_not_confirmed_suffix) else "",
            ts(14, 400, C.Muted))
        PillButton(if (count > 0) stringResource(R.string.sync_now) else stringResource(R.string.done), height = 56.dp, fontSize = 17) {
            vm.dismissDiscovery()
            if (count > 0) vm.push(Screen.Sync())
        }
    }
}

@Composable
private fun BatteryTile(label: String, percent: Int, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFF4F5F7)).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(label, ts(12, 400, C.Muted))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatteryGlyph(percent, 30.dp, 14.dp, 4.dp); T("$percent%", ts(20, 700))
        }
    }
}

// ---- Todos and search -----------------------------------------------------------------------

@Composable
private fun TodosContent(vm: AppViewModel) {
    val recordings by vm.recordings.collectAsState()
    TodosList(recordings,vm::title,vm::toggleTodo,vm::deleteTodo) {vm.push(Screen.Detail(it))}
}

private enum class TodoFilter(val titleRes: Int) { OPEN(R.string.todo_open), DONE(R.string.todo_done), ALL(R.string.all) }

@Composable
internal fun TodosList(recordings: List<Recording>, title: (Recording) -> String,
    onToggle: (String,String) -> Unit, onDelete: (String,String) -> Unit, onOpen: (String) -> Unit) {
    var filter by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(TodoFilter.OPEN)}
    val withTodos=recordings.filter {it.summary?.todos?.isNotEmpty()==true}
    val groups=withTodos.map {r->r to r.summary!!.todos.filter {todo->when(filter) {
        TodoFilter.OPEN->!todo.done;TodoFilter.DONE->todo.done;TodoFilter.ALL->true
    }}.sortedBy {it.done}}.filter {it.second.isNotEmpty()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start=20.dp,end=20.dp,top=22.dp,bottom=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val open=withTodos.sumOf {r->r.summary!!.todos.count {!it.done}}
                val done=withTodos.sumOf {r->r.summary!!.todos.count {it.done}}
                T(stringResource(R.string.todos),ts(34,800,spacing=(-0.02).em))
                T(if(withTodos.isEmpty()) stringResource(R.string.from_recording_notes) else stringResource(R.string.todo_counts, withTodos.size, open, done),ts(13,500,C.Muted))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TodoFilter.entries.forEach {f->OutlineChip(stringResource(f.titleRes),filter==f) {filter=f}}
                }
            }
        }
        if(groups.isEmpty()) item {
            val hint=when {
                withTodos.isEmpty()->stringResource(R.string.todo_intro)
                filter==TodoFilter.OPEN->stringResource(R.string.no_open_todos)
                else->stringResource(R.string.no_completed_todos)
            }
            T(hint,ts(14,400,C.Muted,lineHeight=1.55f),Modifier.padding(20.dp))
        }
        groups.forEach {(r,todos)->
            item(key=r.id) {
                SectionLabel(stringResource(R.string.todo_group_header, title(r), dayTime(r.createdAt), todos.size))
                Group {
                    todos.forEachIndexed {i,todo->key(todo.id) {
                        if(i>0) Divider()
                        SwipeTodoRow(todo,r,{onToggle(r.id,todo.id)},{onDelete(r.id,todo.id)})
                    }}
                    Divider()
                    T(stringResource(R.string.open_recording),ts(14,400,C.Blue),Modifier.fillMaxWidth().tap {onOpen(r.id)}.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun TodoRow(todo: dev.soundsoftware.core.model.TodoItem, r: Recording, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().tap(ripple = false, onClick = onToggle).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 2.dp)) { WebCheckbox(todo.done, { onToggle() }, 20.dp, label = todo.text) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(todo.text, style = ts(15, 400, if (todo.done) C.Muted else C.Ink, lineHeight = 1.5f).let {
                if (todo.done) it.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) else it })
            val owner = todo.owner
            if (owner != null || todo.due != null) BasicText(buildAnnotatedString {
                if (owner != null) {
                    val idx = r.speakers.firstOrNull { it.name == owner }?.index
                    withStyle(SpanStyle(color = idx?.let { speakerColor(it).fg } ?: C.Ink2, fontWeight = FontWeight.Bold)) { append(owner) }
                }
                if (todo.due != null) append((if (owner != null) " · " else "") + todo.due)
            }, style = ts(12, 400, C.Muted))
        }
    }
}

@Composable
fun SearchScreen(vm: AppViewModel) {
    val recordings by vm.recordings.collectAsState()
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val results = if (q.isEmpty()) recordings else recordings.filter { r ->
        vm.title(r).contains(q, true) || r.summary?.abstract?.contains(q, true) == true || r.transcript?.segments?.any { it.text.contains(q, true) } == true ||
            r.speakers.any { it.name?.contains(q) == true }
    }
    Column(Modifier.fillMaxSize().background(C.HomeBg).statusBarsPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButtonPlain(Icons.Back, stringResource(R.string.back)) { vm.back() }
            Field(query, { query = it }, stringResource(R.string.search_recordings_placeholder), Modifier.weight(1f), autoFocus = true)
        }
        T(if (q.isEmpty()) stringResource(R.string.all_recordings_count, recordings.size) else stringResource(R.string.search_result_count, results.size), ts(13, 400, C.Muted), Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results, key = { it.id }) { r ->
                Column {
                    RecordingCard(vm, r)
                    if (q.isNotEmpty()) r.transcript?.segments?.firstOrNull { it.text.contains(q, true) }?.let {
                        T("「${it.text.take(60)}」", ts(13, 400, C.Muted), Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp), maxLines = 2)
                    }
                }
            }
        }
    }
}
