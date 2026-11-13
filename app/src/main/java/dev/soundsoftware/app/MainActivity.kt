package dev.soundsoftware.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.soundsoftware.app.ui.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pendingSave: File? = null
    private var pendingSaveMime: String = "*/*"
    private var afterWifiPermission: (() -> Unit)? = null
    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        val action = afterWifiPermission; afterWifiPermission = null
        if (hasPermission(permission)) action?.invoke() else vm.say(getString(R.string.wifi_permission_required))
    }
    private fun requestWifiPermission(action: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (hasPermission(permission)) action() else {
            afterWifiPermission = action
            val wanted = if (Build.VERSION.SDK_INT in 29..32) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else arrayOf(permission)
            wifiPermission.launch(wanted)
        }
    }
    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importAudio) }
    private val saveDocument = registerForActivityResult(object: ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: android.content.Context, input: String): Intent = super.createIntent(context,input).setType(pendingSaveMime)
    }) { uri ->
        val file = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } } }
                .onSuccess { vm.say(getString(R.string.saved_to_files)) }.onFailure { vm.say(getString(R.string.save_failed, it.message)) }
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val bluetooth = bluetoothPermissions().all { result[it] ?: hasPermission(it) }
        vm.permissionsGranted(bluetooth)
        if (!bluetooth) vm.say(getString(R.string.bluetooth_permission_required))
    }

    private fun bluetoothPermissions() = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = bluetoothPermissions() + if (Build.VERSION.SDK_INT >= 33) buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (vm.settings.value.autoSync) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else emptyList()
        val missing = wanted.filterNot(::hasPermission)
        if (missing.isEmpty()) vm.permissionsGranted(true) else permissions.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        intent?.getStringExtra(EXTRA_RECORDING)?.let(vm::openRecording)
        if (intent?.getBooleanExtra(EXTRA_SHOW_SYNC, false) == true) vm.showSync()
        requestPermissions()
        setContent { SoundTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_RECORDING)?.let(vm::openRecording)
        if (intent.getBooleanExtra(EXTRA_SHOW_SYNC, false)) vm.showSync()
    }

    override fun onStart() {
        super.onStart()
        vm.permissionsGranted(bluetoothPermissions().all(::hasPermission))
        vm.foreground(true)
    }
    override fun onStop() { vm.foreground(false); super.onStop() }

    private fun share(file: File, mime: String) {
        startActivity(Intent.createChooser(dev.soundsoftware.app.export.Exporter.shareIntent(this,file,mime), getString(R.string.share)))
    }

    @Composable
    private fun AppRoot() {
        val screens by vm.screens.collectAsState()
        val message by vm.message.collectAsState()
        val top = screens.last()
        val dark = top is Screen.Sync
        LaunchedEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(style, style)
        }
        BackHandler(screens.size > 1) { vm.back() }
        Box(Modifier.fillMaxSize()) {
            when (top) {
                Screen.Main -> MainScreen(vm, { importAudio.launch(arrayOf("audio/*")) }, ::requestPermissions)
                is Screen.Sync -> SyncScreen(vm, top.selecting, ::requestWifiPermission)
                is Screen.Setup -> SetupScreen(vm, top.recordingId)
                is Screen.Detail -> DetailScreen(vm, top.id,::requestWifiPermission)
                is Screen.Speakers -> SpeakersScreen(vm, top.id, top.speakerId)
                is Screen.Export -> ExportScreen(vm, top.id, ::share) { file, mime -> pendingSave = file; pendingSaveMime=mime; saveDocument.launch(file.name) }
                Screen.Device -> DeviceScreen(vm, asTab = false, onRequestPermissions = ::requestPermissions)
                Screen.Search -> SearchScreen(vm)
                Screen.Assistant -> AssistantScreen(vm)
                Screen.AsrService -> AsrServiceScreen(vm)
                Screen.LlmService -> LlmServiceScreen(vm)
            }
            message?.let { text ->
                LaunchedEffect(text) { delay(2600); if (vm.message.value == text) vm.say(null) }
                T(text, ts(14, 500, androidx.compose.ui.graphics.Color.White), Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 100.dp).clip(RoundedCornerShape(14.dp)).background(C.Ink).tap(ripple = false) { vm.say(null) }
                    .padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }

    companion object {
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_SHOW_SYNC = "show_sync"
    }
}
