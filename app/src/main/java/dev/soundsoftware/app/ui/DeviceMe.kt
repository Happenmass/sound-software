package dev.soundsoftware.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.BuildConfig
import dev.soundsoftware.app.R
import dev.soundsoftware.app.Screen
import dev.soundsoftware.app.data.LlmSettings
import dev.soundsoftware.app.data.RemoteAsrSettings
import dev.soundsoftware.app.device.LinkPhase
import dev.soundsoftware.app.processing.ASR_PROFILES
import dev.soundsoftware.app.processing.Processor

@Composable
fun DeviceScreen(vm: AppViewModel, asTab: Boolean, onRequestPermissions: () -> Unit, onOverlay: (Boolean) -> Unit = {}) {
    val device by vm.device.collectAsState()
    val settings by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { onOverlay(confirm != null) }
    val info = device.info
    val connected = device.phase == LinkPhase.CONNECTED
    val bound = device.bound
    Box(Modifier.fillMaxSize().background(C.HomeBg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = if (asTab) 120.dp else 32.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!asTab) RoundIconButton(Icons.BackBold, stringResource(R.string.back), Color.White, C.Ink) { vm.back() } else Spacer(Modifier.width(44.dp))
                T(stringResource(R.string.device), ts(16, 600), Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(30.dp)).background(C.Photo).clipToBounds()) {
                ProductStill(bound?.white ?: true, 10_000)
                Row(Modifier.padding(12.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(if (connected) C.Green else if (device.phase == LinkPhase.CONNECTING) C.Amber else C.Faint, breathe = connected)
                    T(when { bound == null -> stringResource(R.string.device_not_added); connected -> stringResource(R.string.connected_via, stringResource(R.string.bluetooth)); device.phase == LinkPhase.CONNECTING -> stringResource(R.string.connecting_ellipsis); else -> stringResource(R.string.disconnected) }, ts(13, 600))
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(stringResource(R.string.ai_recorder), ts(26, 800, spacing = (-0.02).em))
                T(if (bound == null) stringResource(R.string.work_unpaired) else "soundcore Work · ${if (bound.white) "白色" else "黑色"} · ${if (bound.firmwareBound) "已绑定到此手机" else "已添加到此手机"}", ts(13, 400, C.Muted))
            }
            if (bound == null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    T(stringResource(R.string.discovery_intro), ts(14, 400, C.Muted, lineHeight = 1.55f), Modifier.padding(horizontal = 4.dp))
                    PillButton(if (device.phase == LinkPhase.SCANNING) stringResource(R.string.scanning) else stringResource(R.string.scan_nearby), enabled = device.phase != LinkPhase.SCANNING) { onRequestPermissions(); vm.scan() }
                }
                return@Column
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BatteryCard(stringResource(R.string.app_name), info?.battery, info?.charging == true, Modifier.weight(1f))
                BatteryCard(stringResource(R.string.charging_case), info?.caseBattery, info?.caseCharging == true, Modifier.weight(1f))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White)
                .tap(enabled = connected) { vm.push(Screen.Sync()) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T(stringResource(R.string.storage), ts(15, 600), Modifier.weight(1f))
                    T(info?.let { stringResource(R.string.device_storage, gb(it.totalKb - it.freeKb), gb(it.totalKb, round = true)) } ?: "—", ts(13, 400, C.Muted))
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(info?.let { ((it.totalKb - it.freeKb).toFloat() / it.totalKb.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f)
                        .clip(RoundedCornerShape(3.dp)).background(C.Ink))
                }
                T(device.inventory?.let { stringResource(R.string.manage_recordings, it.size) } ?: if (connected) stringResource(R.string.reading_recordings) else stringResource(R.string.connect_to_manage), ts(14, 400, C.Blue))
            }
            SectionLabel(stringResource(R.string.features), top = 22.dp)
            Group(radius = 20.dp) {
                val extras = info?.extras
                SettingRow(stringResource(R.string.find_device), subtitle = if (settings.findMyEnabled == null) stringResource(R.string.find_device_unknown) else stringResource(R.string.find_device_help), minHeight = 56.dp,
                    trailing = { WebCheckbox(settings.findMyEnabled == true, if (connected) { v -> vm.setFindMy(v) } else null, label = stringResource(R.string.find_device)) }) {
                    if (connected) vm.setFindMy(settings.findMyEnabled != true)
                }
                SettingRow(stringResource(R.string.auto_power_off), extras?.let { (if (it.autoPowerOff) stringResource(R.string.enabled) else stringResource(R.string.disabled)) + stringResource(R.string.read_only_suffix) } ?: "—")
                SettingRow(stringResource(R.string.indicator_lights), extras?.let { when { it.beanLight && it.caseLight -> stringResource(R.string.both_lights_on); it.beanLight -> stringResource(R.string.recorder_light_on); it.caseLight -> stringResource(R.string.case_light_on); else -> stringResource(R.string.both_lights_off) } + stringResource(R.string.read_only_suffix) } ?: "—", divider = false)
            }
            SectionLabel(stringResource(R.string.firmware), top = 22.dp)
            Group(radius = 20.dp) {
                SettingRow(stringResource(R.string.app_name), info?.firmware ?: "—", minHeight = 48.dp)
                SettingRow(stringResource(R.string.charging_case), info?.extras?.caseFirmware?.ifBlank { null } ?: "—", minHeight = 48.dp)
                SettingRow(stringResource(R.string.wifi_module), info?.extras?.wifiFirmware?.ifBlank { null } ?: "—", minHeight = 48.dp, divider = false)
            }
            SectionLabel(stringResource(R.string.about), top = 22.dp)
            Group(radius = 20.dp) {
                SettingRow(stringResource(R.string.device_model), "D3200", minHeight = 48.dp)
                SettingRow(stringResource(R.string.serial_number), (info?.serial ?: bound.serial)?.uppercase()?.let { if (it.length > 8) it.take(4) + "····" + it.takeLast(4) else it } ?: "—", minHeight = 48.dp)
                SettingRow(stringResource(R.string.refresh_device_status), chevron = true, minHeight = 48.dp, divider = false) { if (connected) vm.refreshDevice() else { onRequestPermissions(); vm.reconnect() } }
            }
            Group(radius = 20.dp, modifier = Modifier.padding(top = 22.dp)) {
                SettingRow(stringResource(R.string.unpair), titleColor = C.Red) { confirm = "unbind" }
                SettingRow(stringResource(R.string.factory_reset), titleColor = if (connected) C.Red else C.Faint, divider = false) { if (connected) confirm = "reset" }
            }
            device.error?.let { T(it, ts(12, 400, C.Muted), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).tap(ripple = false) { vm.clearDeviceError() }) }
        }
        when (confirm) {
            "unbind" -> {
                val unsynced = if (connected) vm.newRecordings().size else 0
                val message = if (!connected) stringResource(R.string.remove_local_pairing_help)
                    else stringResource(R.string.unpair_firmware_warning) +
                        if (unsynced > 0) stringResource(R.string.unsynced_warning, unsynced) else stringResource(R.string.confirm_recordings_saved)
                ConfirmDialog(if (unsynced > 0) stringResource(R.string.sync_recordings_first) else stringResource(R.string.unpair_title), message,
                    if (unsynced > 0) stringResource(R.string.go_to_sync) else if (!connected) stringResource(R.string.remove_local_pairing) else stringResource(R.string.unpair), danger = unsynced == 0,
                    onDismiss = { confirm = null }) {
                    confirm = null
                    if (unsynced > 0) vm.push(Screen.Sync()) else vm.unbind()
                }
            }
            "reset" -> ConfirmDialog(stringResource(R.string.factory_reset_title), stringResource(R.string.factory_reset_message), stringResource(R.string.erase_and_reset), danger = true,
                onDismiss = { confirm = null }) { confirm = null; vm.factoryReset() }
        }
    }
}

@Composable
private fun BatteryCard(label: String, percent: Int?, charging: Boolean, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { T(label, ts(13, 400, C.Muted)); if (charging) { T(" · ", ts(13, 400, C.Muted)); T(stringResource(R.string.charging), ts(13, 600, C.Teal)) } }
        T(percent?.let { "$it%" } ?: "—", ts(26, 800, spacing = (-0.02).em))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((percent ?: 0) / 100f).clip(RoundedCornerShape(3.dp)).background(if ((percent ?: 100) <= 20) C.RedDot else C.Green))
        }
    }
}

@Composable
fun MeContent(vm: AppViewModel, onImport: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val recordings by vm.recordings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 120.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(stringResource(R.string.local_recordings_count, recordings.size), ts(13, 500, C.Muted))
            T(stringResource(R.string.settings), ts(34, 800, spacing = (-0.02).em))
        }
        SectionLabel(stringResource(R.string.transcription))
        Group {
            val d = settings.defaults
            SettingRow(stringResource(R.string.default_transcription_settings), "${ASR_PROFILES.firstOrNull { it.id == d.profileId }?.let { stringResource(it.titleRes) }.orEmpty()} · ${stringResource(d.template.titleRes)}", chevron = true) { vm.push(Screen.Setup(null)) }
            SettingRow(stringResource(R.string.transcribe_after_sync), trailing = { WebCheckbox(settings.autoTranscribe, vm::setAutoTranscribe, label = stringResource(R.string.transcribe_after_sync)) }) { vm.setAutoTranscribe(!settings.autoTranscribe) }
            SettingRow(stringResource(R.string.cloud_transcription_service), if (vm.modelReady("http-api")) settings.remoteAsr.model else stringResource(R.string.not_configured), chevron = true, divider = false) { vm.push(Screen.AsrService) }
        }
        SectionLabel(stringResource(R.string.notes_and_ai_qa))
        Group {
            SettingRow(stringResource(R.string.notes_service), if (vm.llmReady()) settings.llm.model else stringResource(R.string.not_configured), subtitle = stringResource(R.string.notes_service_privacy), chevron = true, divider = false) { vm.push(Screen.LlmService) }
        }
        val localModels = vm.localAsrProfiles()
        SectionLabel(stringResource(R.string.local_models))
        Group {
            if (localModels.isEmpty()) {
                SettingRow(stringResource(R.string.no_local_npu_models), divider = false)
            } else localModels.forEachIndexed { index, model ->
                val status = if (vm.modelReady(model.id)) R.string.installed else R.string.not_installed
                SettingRow(stringResource(model.titleRes), stringResource(status), divider = index < localModels.lastIndex)
            }
        }
        SectionLabel(stringResource(R.string.recordings))
        Group {
            SettingRow(stringResource(R.string.import_audio), subtitle = stringResource(R.string.import_audio_formats), chevron = true, divider = false, onClick = onImport)
        }
        SectionLabel(stringResource(R.string.about))
        Group {
            SettingRow(stringResource(R.string.version), BuildConfig.VERSION_NAME, divider = false)
        }
    }
}

@Composable
fun AsrServiceScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    var endpoint by remember { mutableStateOf(settings.remoteAsr.endpoint) }
    var model by remember { mutableStateOf(settings.remoteAsr.model) }
    var key by remember { mutableStateOf("") }
    var timestamps by remember { mutableStateOf(settings.remoteAsr.segmentTimestamps) }
    ServiceForm(stringResource(R.string.cloud_transcription_service), stringResource(R.string.cloud_transcription_help),
        listOf(Triple(stringResource(R.string.transcription_endpoint), endpoint) { v: String -> endpoint = v }, Triple(stringResource(R.string.model), model) { v: String -> model = v }),
        key, { key = it }, vm.hasSecret(Processor.KEY_ASR), extra = {
            SettingRow(stringResource(R.string.api_segment_timestamps), subtitle = stringResource(R.string.api_timestamps_help), trailing = { WebCheckbox(timestamps, { timestamps = it }) }, divider = false) { timestamps = !timestamps }
        }, onBack = { vm.back() }) {
        if (vm.saveAsr(RemoteAsrSettings(endpoint, model, timestamps), key.ifBlank { null })) { vm.say(vm.app.getString(R.string.saved)); vm.back() }
    }
}

@Composable
fun LlmServiceScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    var base by remember { mutableStateOf(settings.llm.baseUrl) }
    var model by remember { mutableStateOf(settings.llm.model) }
    var key by remember { mutableStateOf("") }
    ServiceForm(stringResource(R.string.notes_service), stringResource(R.string.notes_service_help),
        listOf(Triple("Base URL", base) { v: String -> base = v }, Triple(stringResource(R.string.model), model) { v: String -> model = v }),
        key, { key = it }, vm.hasSecret(Processor.KEY_LLM), onBack = { vm.back() }) {
        if (vm.saveLlm(LlmSettings(base, model), key.ifBlank { null })) { vm.say(vm.app.getString(R.string.saved)); vm.back() }
    }
}

@Composable
private fun ServiceForm(title: String, note: String, fields: List<Triple<String, String, (String) -> Unit>>, key: String, onKey: (String) -> Unit,
    hasKey: Boolean, extra: (@Composable () -> Unit)? = null, onBack: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.PageBg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(note, ts(13, 400, C.Muted, lineHeight = 1.55f))
            fields.forEach { (label, value, set) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { T(label, ts(13, 400, C.Muted)); Field(value, set, "", keyboard = KeyboardType.Uri) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("API Key" + if (hasKey) stringResource(R.string.key_saved_hint) else "", ts(13, 400, C.Muted))
                Field(key, onKey, if (hasKey) "••••••••" else "sk-…", secret = true)
                T(stringResource(R.string.api_key_storage), ts(12, 400, C.Faint))
            }
            if (extra != null) Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) { extra() }
        }
        Box(Modifier.padding(16.dp)) { PillButton(stringResource(R.string.save), onClick = onSave) }
    }
}
