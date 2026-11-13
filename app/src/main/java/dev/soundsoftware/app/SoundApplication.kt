package dev.soundsoftware.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.app.data.SettingsStore
import dev.soundsoftware.app.device.DeviceLinkFactory
import dev.soundsoftware.app.device.DeviceManager
import dev.soundsoftware.app.device.LinkPhase
import dev.soundsoftware.app.device.SyncState
import dev.soundsoftware.app.processing.Playback
import dev.soundsoftware.app.processing.Processor
import dev.soundsoftware.core.model.Recording
import java.io.File
import android.content.res.Configuration
import android.text.format.DateFormat
import dev.soundsoftware.app.ui.labelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Process-wide graph: work outlives screens, and the foreground service keeps it alive. */
class SoundApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore; private set
    lateinit var repository: RecordingRepository; private set
    lateinit var recordingIndex: dev.soundsoftware.app.agent.RecordingIndex; private set
    lateinit var assistant: dev.soundsoftware.app.agent.AgentController; private set
    lateinit var processor: Processor; private set
    lateinit var devices: DeviceManager; private set
    lateinit var playback: Playback; private set
    private val activityVisible = MutableStateFlow(false)
    private val bluetoothAllowed = MutableStateFlow(false)
    internal lateinit var autoSyncRequested: StateFlow<Boolean>; private set
    internal var workServiceActive = false
    val modelDir: File get() = File(getExternalFilesDir(null), "models")

    override fun onCreate() {
        super.onCreate()
        // App-owned model folders, so files later pushed by adb or a model installer stay readable.
        File(modelDir, "diarization").mkdirs()
        settings = SettingsStore(this)
        recordingIndex = dev.soundsoftware.app.agent.RecordingIndex(File(filesDir, "recordings.db"))
        repository = RecordingRepository(filesDir, recordingIndex)
        assistant = dev.soundsoftware.app.agent.AgentController(recordingIndex, settings, scope)
        playback = Playback(scope)
        processor = Processor(repository, settings, modelDir, File(filesDir, "checkpoints"), scope, qnnFiles=dev.soundsoftware.app.inference.QnnFiles.from(this,modelDir), diarizer=dev.soundsoftware.app.inference.SherpaDiarizer.forDevice(this,File(modelDir,"diarization")), qwenHtpFiles = dev.soundsoftware.app.inference.QwenHtpFiles(this, modelDir), notify = ::notifyDone)
        devices = DeviceManager(DeviceLinkFactory.create(this), settings, repository, scope) { id ->
            val prefs = settings.state.value
            if (prefs.autoTranscribe && processor.modelReady(prefs.defaults.profileId)) {
                scope.launch { processor.enqueue(id, prefs.defaults) }
            }
        }
        autoSyncRequested = combine(settings.state, bluetoothAllowed) { prefs, allowed ->
            allowed && prefs.autoSync && prefs.boundDevice != null
        }.stateIn(scope, SharingStarted.Eagerly, false)
        createWorkChannels()
        scope.launch {
            repository.load()
            assistant.load()
            processor.start()
            combine(processor.busy, devices.state, activityVisible, autoSyncRequested) { busy, device, visible, autoSync ->
                busy || device.sync.running || (visible && autoSync)
            }.distinctUntilChanged().collect { needed ->
                if (needed && !workServiceActive) {
                    runCatching { startForegroundService(Intent(this@SoundApplication, WorkService::class.java)) }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        createWorkChannels()
    }

    private fun createWorkChannels() {
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_WORK, getString(R.string.work_notifications), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_DONE, getString(R.string.transcription_done_notifications), NotificationManager.IMPORTANCE_DEFAULT)))
    }

    fun setActivityVisible(visible: Boolean) {
        devices.setForeground(visible)
        activityVisible.value = visible
    }

    fun setBluetoothAllowed(allowed: Boolean) {
        devices.setPermitted(allowed)
        bluetoothAllowed.value = allowed
    }

    fun displayTitle(r: Recording): String = r.title ?: getString(R.string.untitled_recording, DateFormat.getMediumDateFormat(this).format(r.createdAt) + " " + DateFormat.getTimeFormat(this).format(r.createdAt))

    private fun notifyDone(r: Recording) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val text = when { r.summary != null -> getString(R.string.notes_ready); r.error != null -> getString(R.string.transcript_ready_with_error, r.error); else -> getString(R.string.transcript_ready) }
        val open = PendingIntent.getActivity(this, r.id.hashCode(), Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORDING, r.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(displayTitle(r)).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build())
    }

    companion object { const val CHANNEL_WORK = "work"; const val CHANNEL_DONE = "done" }
}

/** Keeps the BLE listener and transfers alive when the app is in the background. */
class WorkService : Service() {
    private var watch: kotlinx.coroutines.Job? = null
    private val app get() = application as SoundApplication
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        watch?.cancel()
        app.workServiceActive = false
        app.devices.setBackgroundSyncActive(false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device = app.devices.state.value
        val initial = work(app.processor.busy.value, device.sync, device.phase, app.autoSyncRequested.value)
        promote(initial)
        app.workServiceActive = true
        watch?.cancel()
        var displayed = initial
        watch = app.scope.launch {
            combine(app.processor.busy, app.devices.state, app.autoSyncRequested) { processing, state, monitoring ->
                work(processing, state.sync, state.phase, monitoring)
            }.distinctUntilChanged().collect { current ->
                if (!current.needed) {
                    app.devices.setBackgroundSyncActive(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else {
                    if (current != displayed) {
                        promote(current)
                        displayed = current
                    }
                    app.devices.setBackgroundSyncActive(current.monitoring)
                }
            }
        }
        return START_NOT_STICKY
    }

    private data class Work(val needed: Boolean, val monitoring: Boolean, val type: Int, val text: String, val openSync: Boolean)

    private fun work(processing: Boolean, sync: SyncState, phase: LinkPhase, monitoring: Boolean): Work {
        var type = 0
        if (monitoring || sync.running) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (processing) type = type or if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // A start request can arrive just as the last task ends. Still satisfy Android's
        // foreground-start contract before stopping the service in the collector.
        if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val text = when {
            sync.running && sync.automatic -> getString(R.string.auto_sync_using, getString(sync.transport.labelRes))
            sync.running -> getString(R.string.syncing_recordings)
            monitoring && sync.automatic && sync.error != null -> getString(R.string.auto_sync_failed_notification)
            processing -> getString(R.string.processing_notification)
            monitoring && phase != LinkPhase.CONNECTED -> getString(R.string.waiting_for_device)
            else -> getString(R.string.waiting_for_recording)
        }
        return Work(monitoring || sync.running || processing, monitoring, type, text, monitoring || sync.running)
    }

    private fun promote(work: Work) {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_SYNC, work.openSync)
        val notification = Notification.Builder(this, SoundApplication.CHANNEL_WORK).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name)).setContentText(work.text).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, work.type) else startForeground(1, notification)
    }
}
