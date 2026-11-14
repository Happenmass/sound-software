package dev.soundsoftware.app.inference

import android.content.Context
import dev.soundsoftware.core.audio.sha256
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Separate runtime directory: speaker QAIRT 2.50 never replaces ASR's validated 2.40 libraries. */
class SpeakerNpuRuntime(context:Context,val modelDir:File,private val originalModels:File) {
    private val context=context.applicationContext
    val runtimeDir=File(this.context.codeCacheDir,"speaker-qnn-2.50-v1")
    private val manifest by lazy {JSONObject(this.context.assets.open("speaker-npu-manifest.json").bufferedReader().use{it.readText()})}
    val isReady get()=listOf("eres2netv2-masked-batch4.bin","embedding-head.onnx").all{File(modelDir,it).isFile} &&
        runCatching {
            val models=manifest.getJSONArray("validatedDeviceModels")
            (0 until models.length()).any{models.getString(it)==android.os.Build.MODEL}
        }.getOrDefault(false)
    fun prepare()=synchronized(lock) {
        val models=manifest.getJSONObject("models")
        for(name in models.keys())check(File(modelDir,name).sha256()==models.getJSONObject(name).getString("sha256")){"NPU说话人模型与运行库不匹配"}
        val original=manifest.getJSONObject("originalModels")
        for(name in original.keys())check(File(originalModels,name).sha256()==original.getString(name)){"当前说话人模型不匹配NPU产物"}
        val libraries=manifest.getJSONObject("libraries")
        val names=libraries.keys().asSequence().toSet()
        runtimeDir.mkdirs()
        val missing=names.filter{val file=File(runtimeDir,it);!file.isFile || file.sha256()!=libraries.getString(it)}
        if(missing.isNotEmpty())ZipInputStream(context.assets.open("speaker-npu-runtime.zip")).use {zip->
            while(true){val entry=zip.nextEntry ?: break
                check(entry.name in names && '/' !in entry.name && '\\' !in entry.name){"NPU运行库包格式错误"}
                if(entry.name !in missing)continue
                val file=File(runtimeDir,entry.name);val temp=File(runtimeDir,entry.name+".tmp")
                temp.delete();temp.outputStream().use{zip.copyTo(it)}
                check(temp.sha256()==libraries.getString(entry.name)){"NPU运行库校验失败"}
                check(temp.setReadOnly());file.delete();check(temp.renameTo(file))
            }
        }
        check(names.all{File(runtimeDir,it).isFile && File(runtimeDir,it).sha256()==libraries.getString(it)}){"NPU运行库未完整安装"}
    }
    companion object {
        const val MODEL_DIRECTORY="diarization-npu-batch4"
        private val lock=Any()
    }
}
