package dev.soundsoftware.app.inference

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.soundsoftware.app.export.Exporter
import dev.soundsoftware.app.ui.AssistantTrace
import dev.soundsoftware.app.ui.SoundTheme
import dev.soundsoftware.core.agent.AgentTraceStep
import dev.soundsoftware.core.export.ExportFormat
import dev.soundsoftware.core.export.ExportOptions
import dev.soundsoftware.core.model.Recording
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChatExportTest {
    @get:Rule val compose=createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun processAndEachToolCanExpandAndCollapseWithoutChangingAnswer() {
        val trace=listOf(
            AgentTraceStep(0,"model","分析问题与规划检索",detail="准备查询录音",status="done",elapsedMs=1200),
            AgentTraceStep(1,"tool","搜索录音片段",detail="返回 1 项结果",status="done",toolName="search_recordings",arguments="""{"query":"上线"}""",result="""{"evidence":[{"quote":"测试用原文片段"}]}"""))
        compose.setContent {SoundTheme {AssistantTrace(trace)}}
        compose.onNodeWithText("搜索录音片段").assertDoesNotExist()
        compose.onNodeWithText(context.getString(dev.soundsoftware.app.R.string.expand)).performClick()
        compose.onNodeWithText("搜索录音片段").assertIsDisplayed()
        compose.onNodeWithText("测试用原文片段",substring=true).assertDoesNotExist()
        compose.onNodeWithText("search_recordings · ${context.getString(dev.soundsoftware.app.R.string.show_tool_details)}").performClick()
        compose.onNodeWithText("测试用原文片段",substring=true).assertIsDisplayed()
        val screenshot=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"chat-export-validation/process-expanded.png").apply {parentFile!!.mkdirs()}
        screenshot.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText(context.getString(dev.soundsoftware.app.R.string.collapse)).performClick()
        compose.onNodeWithText("测试用原文片段",substring=true).assertDoesNotExist()
        val collapsed=File(screenshot.parentFile,"process-collapsed.png")
        collapsed.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun stoppedWorkIsClearlyMarked() {
        compose.setContent {SoundTheme {AssistantTrace(listOf(AgentTraceStep(0,"model","分析问题与规划检索",detail="已停止",status="cancelled")))}}
        compose.onNodeWithText(context.getString(dev.soundsoftware.app.R.string.assistant_process,
            context.getString(dev.soundsoftware.app.R.string.stopped))).assertIsDisplayed()
    }

    @Test fun documentAndAudioAlwaysShareAsSeparateSingleFilesWithReadableUris() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File.createTempFile("export-fixture",".wav",context.cacheDir).apply {writeBytes(ByteArray(32000) {it.toByte()})}
        var doc: File?=null;var audio: File?=null
        try {
            doc=Exporter.write(context,Recording("fixture",0,1000),"独立导出测试",ExportFormat.TEXT,ExportOptions())
            audio=Exporter.writeAudio(context,"独立导出测试",source)
            assertTrue(doc.isFile);assertTrue(source.readBytes().contentEquals(audio.readBytes()))
            for((file,mime) in listOf(doc to "text/plain",audio to "audio/wav")) {
                val intent=Exporter.shareIntent(context,file,mime)
                assertEquals(Intent.ACTION_SEND,intent.action);assertEquals(mime,intent.type)
                assertEquals(1,intent.clipData!!.itemCount)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
                context.contentResolver.openInputStream(uri)!!.use {assertTrue(it.readBytes().contentEquals(file.readBytes()))}
            }
        } finally {source.delete();doc?.parentFile?.deleteRecursively();audio?.parentFile?.deleteRecursively()}
    }
}
