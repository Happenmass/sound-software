package dev.soundsoftware.app.device

import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.data.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import dev.soundsoftware.core.audio.sha256
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/** Two user-created short recordings only. Reads metadata/audio; never binds, controls or deletes. */
class DeviceMarkProbeTest {
    @Test fun observeControlledDoubleTapRecordings()=runBlocking<Unit> {
        val mode=InstrumentationRegistry.getArguments().getString("markProbe")
        assumeTrue(mode in listOf("observe","download","publish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bound=requireNotNull(SettingsStore(context).state.value.boundDevice)
        val report=JSONObject().put("stage","connecting")
        val out=File(context.filesDir,"mark-probe-private/observations.json").apply {parentFile!!.mkdirs()}
        fun save() {out.writeText(report.toString(2))}
        data class Observation(val elapsedMs:Long,val phase:String,val metadata:dev.soundsoftware.core.device.DeviceWireMetadata)
        val events=Collections.synchronizedList(mutableListOf<Observation>())
        val start=android.os.SystemClock.elapsedRealtime()
        val phase=java.util.concurrent.atomic.AtomicReference("observation")
        val client=BleDeviceClient(context)
        client.onProtocolMetadata={m->events+=Observation(android.os.SystemClock.elapsedRealtime()-start,phase.get(),m)}
        save()
        try {
            val found=CompletableDeferred<NearbyRecorder>()
            client.startScan({if(it.address==bound.address || !found.isCompleted) found.complete(it)},{})
            val nearby=withTimeout(30_000) {found.await()};client.stopScan()
            client.connect(nearby.address) {}
            val info=client.readInfo()
            report.put("firmware",info.firmware)
            check(info.serial==bound.serial) {"Probe recorder identity mismatch"}
            val initial=client.listFiles();check(initial.activeRecordingFileId==null && info.extras?.recording!=true)
            val ids=initial.files.map {it.fileId}.toSet()
            report.put("stage","ready-for-two-recordings").put("initialCount",ids.size);save()
            val added=if(mode in listOf("download","publish")) initial.files.sortedBy {it.fileId}.takeLast(3) else withTimeout(600_000) {
                while(true) {
                    delay(1000)
                    val inventory=client.listFiles()
                    val new=inventory.files.filter {it.fileId !in ids}
                    report.put("completedNewCount",new.size).put("active",inventory.activeRecordingFileId!=null);save()
                    if(new.size>=2 && inventory.activeRecordingFileId==null) return@withTimeout new.sortedBy {it.fileId}.take(2)
                }
                @Suppress("UNREACHABLE_CODE") error("No recordings")
            }
            check(added.all {it.durationMs in 2000..120_000}) {"Only controlled short recordings are accepted"}
            if(mode=="publish") {
                val app=context.applicationContext as dev.soundsoftware.app.SoundApplication
                withTimeout(10000) {app.assistant.state.first {it.ready}}
                val published=JSONArray();report.put("published",published)
                for((i,file) in added.withIndex()) {
                    phase.set("publish-sample-${i+1}");report.put("stage",phase.get());save()
                    val temp=File(context.cacheDir,"controlled-mark-${i+1}.wav")
                    try {
                        val metadata=client.download(file,temp) {}
                        val expected=listOf(emptyList(),listOf(5160L,14660L),listOf(5460L))[i]
                        check(metadata.hardwareHighlights?.map {it.positionMs}==expected)
                        val repo=app.repository;val id="device-${file.fileId}";val existing=repo.get(id)
                        if(existing==null) {
                            val wav=dev.soundsoftware.core.audio.PcmWav.open(temp);check(wav.durationMs==file.durationMs)
                            val target=repo.audioFile(id);check(!target.exists());temp.copyTo(target)
                            repo.add(dev.soundsoftware.core.model.Recording(id,file.startEpochMs,wav.durationMs,
                                dev.soundsoftware.core.model.RecordingSource.DEVICE,file.fileId,title="重点验证 ${i+1}",audioBytes=target.length(),
                                waveform=dev.soundsoftware.app.data.RecordingRepository.waveform(target),hardwareHighlights=metadata.hardwareHighlights))
                        } else {
                            check(repo.audioFile(id).sha256()==temp.sha256())
                            repo.update(id) {it.copy(hardwareHighlights=metadata.hardwareHighlights)}
                        }
                        published.put(JSONObject().put("sample",i+1).put("positionsMs",JSONArray(expected)));save()
                    } finally {temp.delete()}
                }
                check(client.listFiles().files.map {it.fileId}.containsAll(ids))
                report.put("stage","passed").put("originalInventoryPreserved",true)
                return@runBlocking
            }
            val downloads=JSONArray();report.put("downloads",downloads)
            for((i,file) in added.withIndex()) {
                repeat(if(i==0)1 else 2) {attempt->
                    phase.set("sample-${i+1}-download-${attempt+1}")
                    report.put("stage",phase.get());save()
                    val result=client.downloadEncrypted(file)
                    try {
                        downloads.put(JSONObject().put("sample",i+1).put("attempt",attempt+1).put("durationMs",file.durationMs)
                            .put("hash",result.decryptedSha256).put("sliceFlags",JSONArray(result.diagnostic.sliceFlags)))
                    } finally {result.decryptedFile.parentFile!!.deleteRecursively()}
                    save()
                }
            }
            check(client.listFiles().files.map {it.fileId}.containsAll(ids))
            report.put("stage","passed").put("originalInventoryPreserved",true)
        } catch(e:Exception) {
            report.put("stage","failed").put("error",e.javaClass.simpleName);throw e
        } finally {
            client.onProtocolMetadata=null;client.close()
            synchronized(events) {report.put("events",JSONArray(events.map {e->
                val m=e.metadata
                JSONObject().put("elapsedMs",e.elapsedMs).put("phase",e.phase).put("type",m.type).put("command",m.command)
                    .put("status",m.status).put("payloadBytes",m.payloadBytes)
                    .put("slices",JSONArray(m.slices.map {s->JSONObject().put("sequence",s.sequence).put("flags",s.flags).put("tail",s.trailingByte)}))
                    .apply {m.recordingStatus?.let {put("recordingStatus",JSONArray(it))}}
            }))};save()
        }
    }
}
