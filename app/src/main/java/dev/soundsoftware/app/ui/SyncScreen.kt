package dev.soundsoftware.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.R
import dev.soundsoftware.app.Screen
import dev.soundsoftware.app.device.LinkPhase
import dev.soundsoftware.app.device.SyncItem
import dev.soundsoftware.app.device.SyncItemStatus
import dev.soundsoftware.app.device.SyncTransport

@Composable
fun SyncScreen(vm: AppViewModel, selecting: Boolean, requestWifiPermission: (() -> Unit) -> Unit = { it() }) {
    val device by vm.device.collectAsState()
    val settings by vm.settings.collectAsState()
    val library by vm.recordings.collectAsState()
    val sync = device.sync
    val choosing = selecting && !sync.running
    val pending = vm.newRecordings()
    LaunchedEffect(sync.automatic, sync.error, sync.transport) {
        if (sync.automatic && sync.error != null) vm.syncTransport = sync.transport
    }
    var selected by remember(selecting, pending.map { it.fileId }) { mutableStateOf(pending.map { it.fileId }.toSet()) }
    val transport = if (sync.running) sync.transport else vm.syncTransport
    fun start(ids: Set<Long>? = null) {
        val mode = transport
        val action = { vm.startSync(ids, mode); vm.replaceTop(Screen.Sync(false)) }
        if (mode == SyncTransport.WIFI) requestWifiPermission(action) else action()
    }
    var confirmDelete by remember { mutableStateOf<SyncItem?>(null) }
    val connected = device.phase == LinkPhase.CONNECTED
    // Rows: the running/finished batch, otherwise what is waiting on the device.
    val showBatch = sync.items.isNotEmpty() && !choosing && (sync.running || !sync.automatic || pending.isEmpty())
    val rows = if (showBatch) sync.items
        else pending.map { SyncItem(it.fileId, it.startEpochMs, it.durationMs, it.transferBytes) }
    val fraction = if (showBatch) sync.fraction else 0f
    val doneCount = sync.items.count { it.status == SyncItemStatus.DONE }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(0f to C.NightTop, 0.58f to C.NightMid, 1f to C.NightEnd, center = Offset(540f, 500f), radius = 1800f))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.BackBold, stringResource(R.string.back), Color(0x14FFFFFF), Color.White) { vm.back() }
                T(stringResource(R.string.sync_recordings), ts(16, 600, Color.White), Modifier.weight(1f), align = TextAlign.Center)
                val canSelect = !sync.running && pending.isNotEmpty()
                T(if (choosing) stringResource(R.string.done) else stringResource(R.string.select), ts(14, 400, if (canSelect || choosing) Color.White else C.NightMuted),
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0x14FFFFFF))
                        .tap(enabled = canSelect || choosing) { vm.replaceTop(Screen.Sync(!choosing)) }.padding(horizontal = 14.dp).wrapContentHeight())
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SyncHero(fraction, sync.running, device.bound?.white ?: false)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("${(fraction * 100).toInt()}%", ts(36, 800, Color.White, spacing = (-0.03).em, tabular = true))
                    val detail = when {
                        sync.running && sync.stage != null -> sync.stage
                        sync.running -> listOfNotNull(stringResource(R.string.transfer_transport, stringResource(sync.transport.labelRes)), sync.bytesPerSecond?.let { speed(it) },
                            sync.remainingSeconds?.let { stringResource(R.string.time_remaining, remaining(it)) }).joinToString(" · ")
                        sync.error != null -> if (sync.automatic) stringResource(R.string.auto_sync_manual_help, sync.error) else sync.error
                        !connected && !sync.running -> if (device.phase == LinkPhase.CONNECTING) stringResource(R.string.recorder_connecting) else stringResource(R.string.recorder_disconnected)
                        sync.finished && showBatch -> stringResource(R.string.sync_complete) +
                            if (sync.automatic || !settings.deleteAfterSync) stringResource(R.string.sync_device_copy_suffix) else ""
                        pending.isEmpty() -> if (device.inventory == null) stringResource(R.string.loading_device_recordings) else stringResource(R.string.no_pending_device_recordings)
                        else -> stringResource(R.string.pending_recording_count, pending.size)
                    }
                    T(detail, ts(13, 400, C.NightMuted), Modifier.padding(horizontal = 24.dp), align = TextAlign.Center)
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    T(when {
                        choosing -> stringResource(R.string.select_recordings)
                        sync.running -> stringResource(R.string.syncing_progress, (doneCount + 1).coerceAtMost(sync.items.size), sync.items.size)
                        showBatch -> stringResource(R.string.synced_progress, doneCount, sync.items.size)
                        else -> stringResource(R.string.new_device_recordings)
                    }, ts(17, 700, Color.White), Modifier.weight(1f))
                    if (rows.isNotEmpty()) T(stringResource(R.string.total_size, megabytes(rows.sumOf { it.bytes })), ts(13, 400, C.NightMuted))
                }
                if (rows.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.NightPanel)
                    .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(24.dp))) {
                    rows.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
                        SyncRow(item, choosing, item.fileId in selected, autoTranscribe = settings.autoTranscribe,
                            transcribing = item.recordingId?.let { id -> library.firstOrNull { it.id == id }?.let { statusOf(it)?.text } },
                            onToggle = { selected = if (item.fileId in selected) selected - item.fileId else selected + item.fileId },
                            onDelete = { confirmDelete = item }, onOpen = { item.recordingId?.let { vm.push(Screen.Detail(it)) } })
                    }
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.NightPanel)) {
                    NightToggle(stringResource(R.string.auto_sync_after_recording), settings.autoSync, vm::setAutoSync)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
                    NightToggle(stringResource(R.string.transcribe_after_sync), settings.autoTranscribe, vm::setAutoTranscribe)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
                    NightToggle(stringResource(R.string.delete_after_manual_sync), settings.deleteAfterSync, vm::setDeleteAfterSync)
                }
                if (settings.deleteAfterSync) T(stringResource(R.string.delete_after_sync_help), ts(12, 400, C.NightMuted, lineHeight = 1.5f),
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SyncTransport.entries.forEach { mode ->
                        val chosen = transport == mode
                        T(stringResource(mode.labelRes), ts(14, 600, if (chosen) C.NightMid else Color.White),
                            Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                .background(if (chosen) C.NightAccent else Color(0x1FFFFFFF))
                                .tap(enabled = !sync.running && (mode != SyncTransport.WIFI || device.supportsWifi)) { vm.syncTransport = mode }
                                .padding(vertical = 12.dp), align = TextAlign.Center)
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x1FFF9F43)).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ico(if (transport == SyncTransport.WIFI) Icons.Wifi else Icons.Bluetooth, 18.dp, Color(0xFFFFB870), Modifier.padding(top = 1.dp))
                    T(if (transport == SyncTransport.WIFI) stringResource(R.string.wifi_sync_help) else stringResource(R.string.bluetooth_sync_help),
                        ts(12, 400, Color(0xFFFFD8B0), lineHeight = 1.5f))
                }
                when {
                    sync.running -> PillButton(stringResource(R.string.cancel_sync), bg = Color(0x1FFFFFFF), fg = Color.White, height = 54.dp, fontSize = 17) { vm.cancelSync() }
                    selecting -> PillButton(stringResource(R.string.sync_selected_count, selected.size), bg = Color.White, fg = C.NightMid, height = 54.dp, fontSize = 17, enabled = selected.isNotEmpty()) {
                        start(selected)
                    }
                    !sync.running && pending.isNotEmpty() && connected -> PillButton(stringResource(R.string.sync_pending_count, pending.size), bg = Color.White, fg = C.NightMid, height = 54.dp, fontSize = 17) { start() }
                    !connected && !sync.running -> PillButton(stringResource(R.string.reconnect), bg = Color.White, fg = C.NightMid, height = 54.dp, fontSize = 17) { vm.reconnect() }
                    else -> PillButton(stringResource(R.string.adjust_transcription_settings), bg = Color.White, fg = C.NightMid, height = 54.dp, fontSize = 17) { vm.push(Screen.Setup(null)) }
                }
            }
        }
        confirmDelete?.let { item ->
            ConfirmDialog(stringResource(R.string.delete_device_recording_title), stringResource(R.string.delete_device_recording_message, dayTime(item.startMs), minutes(item.durationMs)), stringResource(R.string.delete), danger = true,
                onDismiss = { confirmDelete = null }) { vm.deleteFromDevice(item.fileId); confirmDelete = null }
        }
    }
}

private fun speed(bps: Double) = if (bps >= 1024 * 1024) "%.1f MB/s".format(bps / 1024 / 1024) else "${(bps / 1024).toInt().coerceAtLeast(1)} KB/s"
@Composable
private fun remaining(s: Long) = when { s < 60 -> stringResource(R.string.duration_seconds, s); s < 3600 -> stringResource(R.string.duration_minutes, (s + 30) / 60); else -> stringResource(R.string.duration_hours_decimal, s / 3600.0) }

@Composable
private fun SyncHero(fraction: Float, running: Boolean, white: Boolean) {
    Box(Modifier.fillMaxWidth().height(206.dp), Alignment.TopCenter) {
        Box(Modifier.padding(top = 6.dp).size(200.dp).shadow(if (running) 40.dp else 0.dp, CircleShape, ambientColor = C.NightAccent, spotColor = C.NightAccent), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0x14FFFFFF))
                drawArc(C.NightAccent, -90f, 360f * fraction, true)
            }
            if (running) {
                val t = rememberInfiniteTransition(label = "sweep")
                val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "a")
                Canvas(Modifier.fillMaxSize()) {
                    rotate(a) { drawCircle(Brush.sweepGradient(0f to Color.Transparent, 300f / 360f to Color.Transparent, 1f to Color(0x8CFFFFFF), center = center)) }
                }
            }
            Box(Modifier.size(186.dp).clip(CircleShape).background(C.NightMid))
            Image(painterResource(if (white) R.drawable.bean_white_front else R.drawable.bean_black_front),
                if (white) stringResource(R.string.white_recorder) else stringResource(R.string.black_recorder), Modifier.size(150.dp), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun SyncRow(item: SyncItem, selecting: Boolean, selected: Boolean, autoTranscribe: Boolean, transcribing: String?,
    onToggle: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).tap(enabled = selecting || item.recordingId != null) { if (selecting) onToggle() else onOpen() }
        .padding(start = 16.dp, end = if (item.status == SyncItemStatus.DONE && !selecting) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            selecting -> WebCheckbox(selected, { onToggle() }, 24.dp, C.NightAccent)
            item.status == SyncItemStatus.DONE -> Box(Modifier.size(32.dp).clip(CircleShape).background(C.Green), Alignment.Center) { Ico(Icons.Check, 16.dp, Color.White) }
            item.status == SyncItemStatus.DOWNLOADING -> Box(Modifier.size(32.dp), Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0x1FFFFFFF)); drawArc(C.NightAccent, -90f, 360f * item.progress, true) }
                Box(Modifier.size(24.dp).clip(CircleShape).background(C.NightInner))
            }
            item.status == SyncItemStatus.FAILED -> Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x33E5484D)), Alignment.Center) { T("!", ts(15, 700, C.RedDot)) }
            else -> Canvas(Modifier.size(32.dp)) {
                drawCircle(Color(0x4DFFFFFF), radius = size.minDimension / 2 - 0.75.dp.toPx(),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(dayTime(item.startMs) + if (item.durationMs >= 60_000) "–${timeOnly(item.startMs + item.durationMs)}" else "", ts(15, 600, Color.White))
            val tail = when (item.status) {
                SyncItemStatus.QUEUED -> if (selecting) null else stringResource(R.string.queued)
                SyncItemStatus.DOWNLOADING -> null
                SyncItemStatus.DONE -> listOfNotNull(if (autoTranscribe) transcribing ?: stringResource(R.string.synced) else stringResource(R.string.synced), if (item.deletedFromDevice) stringResource(R.string.deleted_on_device) else null, item.message).joinToString(" · ")
                SyncItemStatus.FAILED -> item.message ?: stringResource(R.string.sync_failed)
            }
            T(listOfNotNull(minutes(item.durationMs), megabytes(item.bytes), tail).joinToString(" · "),
                ts(12, 400, if (item.status == SyncItemStatus.FAILED) Color(0xFFFF8A80) else C.NightMuted), maxLines = 2)
        }
        if (item.status == SyncItemStatus.DOWNLOADING) T("${(item.progress * 100).toInt()}%", ts(13, 600, C.NightValue, tabular = true))
        if (item.status == SyncItemStatus.DONE && !selecting && !item.deletedFromDevice) IconButtonPlain(Icons.Trash, stringResource(R.string.delete_device_recording_at, dayTime(item.startMs)), 18.dp, C.NightMuted, onClick = onDelete)
    }
}

@Composable
private fun NightToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { onChange(!value) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, ts(15, 400, Color.White), Modifier.weight(1f))
        WebCheckbox(value, onChange, 22.dp, C.NightAccent, dark = true, label = label)
    }
}
