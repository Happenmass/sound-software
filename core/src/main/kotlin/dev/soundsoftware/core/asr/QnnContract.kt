package dev.soundsoftware.core.asr

import kotlinx.serialization.json.*

data class QnnWorkerReady(val pid:Int,val context:Int,val loadMs:Double)
data class QnnWorkerReply(val text:String,val inferenceMs:Double)

/** One fixed SM8850 graph. Capacity and evidence are checked independently of UI selection. */
object QnnContract {
    const val MAX_SAMPLES=160000
    const val FRAME_PREFIX="SOUND_QNN "
    private fun invalid():Nothing=throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"QNN worker协议或HTP执行证据无效；未回退CPU")
    private fun diagnosticsOnly(text:String)=text.lineSequence().filterNot{it.trimStart().startsWith("{") || it.startsWith(FRAME_PREFIX)}.joinToString("\n")
    private fun envelope(json:String):JsonObject {
        val o=Json.parseToJsonElement(json).jsonObject
        if(o["protocol"]?.jsonPrimitive?.int!=1 || o["context"]?.jsonPrimitive?.int!=1 || o["initializations"]?.jsonPrimitive?.int!=1)invalid()
        return o
    }
    fun parseWorkerReady(json:String,diagnostics:String):QnnWorkerReady=try {
        val d=diagnosticsOnly(diagnostics);val o=envelope(json)
        if(o["type"]?.jsonPrimitive?.content!="ready" || !d.contains("backendId: 6") || !d.lineSequence().any{it.contains("loaded ")&&it.contains("libQnnHtp.so")})invalid()
        val pid=o.getValue("pid").jsonPrimitive.int;val load=o.getValue("loadMs").jsonPrimitive.double
        if(pid<=0 || !load.isFinite() || load<0)invalid()
        QnnWorkerReady(pid,1,load)
    }catch(e:Exception){invalid()}
    fun parseWorkerReply(json:String,diagnostics:String,ready:QnnWorkerReady,request:Int):QnnWorkerReply=try {
        val d=diagnosticsOnly(diagnostics);val o=envelope(json)
        if(o["type"]?.jsonPrimitive?.content!="result" || o["pid"]?.jsonPrimitive?.int!=ready.pid || o["request"]?.jsonPrimitive?.int!=request || o["sequence"]?.jsonPrimitive?.int!=request)invalid()
        if(!d.contains("QNN_VALIDATION graphExecute succeeded") || d.contains("Recognition result may be truncated/incomplete") || d.contains("Truncate it"))invalid()
        val ms=o.getValue("inferenceMs").jsonPrimitive.double;val text=o.getValue("result").jsonObject.getValue("text").jsonPrimitive
        if(!ms.isFinite() || ms<0 || !text.isString)invalid()
        QnnWorkerReply(text.content,ms)
    }catch(e:Exception){invalid()}
    fun requireWindow(count:Int) {require(count in 1..MAX_SAMPLES) {"QNN单次输入必须为1至160000采样，不能静默截断"}}
    fun parseVerifiedOutput(exit:Int, output:String):String {
        fun fail():Nothing=throw AsrException(ErrorCode.BACKEND_UNAVAILABLE,"QNN执行失败或缺少HTP图执行证据；未回退CPU，请手动选择其他后端")
        val diagnostics=output.lineSequence().filterNot {it.trimStart().startsWith("{")}.joinToString("\n")
        if(exit!=0 || !diagnostics.contains("backendId: 6") || !diagnostics.lineSequence().any{it.contains("loaded ")&&it.contains("libQnnHtp.so")} || !diagnostics.contains("QNN_VALIDATION graphExecute succeeded"))fail()
        if(diagnostics.contains("Recognition result may be truncated/incomplete") || diagnostics.contains("Truncate it"))
            throw AsrException(ErrorCode.INVALID_RESPONSE,"QNN报告输入截断，结果未接受")
        val lines=output.lineSequence().filter{it.trimStart().startsWith("{\"lang\"")}.toList()
        if(lines.size!=1)fail()
        return try {Json.parseToJsonElement(lines.single()).jsonObject.getValue("text").jsonPrimitive.content}
        catch(e:Exception){throw AsrException(ErrorCode.INVALID_RESPONSE,"QNN结果格式无效")}
    }
}
