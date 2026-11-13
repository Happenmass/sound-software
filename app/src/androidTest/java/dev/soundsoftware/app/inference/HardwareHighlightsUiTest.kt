package dev.soundsoftware.app.inference

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.ui.HardwareHighlights
import dev.soundsoftware.app.ui.SoundTheme
import dev.soundsoftware.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HardwareHighlightsUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun verifiedMarksShowContextAndPlayTheirExactPosition() {
        var record by mutableStateOf(Recording("fixture",0,20000,transcript=Transcript(listOf(Segment("s","合成原文：本周先完成原型。",4000,17000)),"fixture","test","SEGMENT",0),
            hardwareHighlights=listOf(RecordingHighlight("a",5160),RecordingHighlight("b",14660))))
        var played:Long?=null
        compose.setContent {SoundTheme {HardwareHighlights(record) {played=it}}}
        compose.onNodeWithText("设备重点 · 2").assertIsDisplayed()
        compose.onNodeWithText("重点 1 · 00:05  ›").performClick()
        compose.runOnIdle {assertEquals(5160L,played)}
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"highlight-validation/marks.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.runOnIdle {record=record.copy(hardwareHighlights=emptyList())}
        compose.onNodeWithText("设备中没有双击标记").assertIsDisplayed()
        compose.runOnIdle {record=record.copy(hardwareHighlights=null)}
        compose.onNodeWithText("设备重点 · 0").assertDoesNotExist()
    }
}
