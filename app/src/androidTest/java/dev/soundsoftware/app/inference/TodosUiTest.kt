package dev.soundsoftware.app.inference

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.ui.SoundTheme
import dev.soundsoftware.app.ui.TodosList
import dev.soundsoftware.app.ui.SwipeTodoRow
import dev.soundsoftware.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TodosUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun releasingAtOpenAnchorDoesNotJumpBackToAnOlderAnimationPosition() {
        val task=TodoItem("drag","用于检验滑动连续性的合成待办")
        compose.setContent {SoundTheme {SwipeTodoRow(task,Recording("fixture",0,1000),{},{},Modifier.testTag("swipe-fixture"))}}
        compose.mainClock.autoAdvance=false
        try {
            val row=compose.onNodeWithTag("swipe-fixture")
            row.performTouchInput {
                down(center)
                moveBy(Offset(-150.dp.toPx(),0f),delayMillis=100)
                moveBy(Offset(-50.dp.toPx(),0f),delayMillis=100)
            }
            compose.mainClock.advanceTimeByFrame()
            val dragged=compose.onNodeWithText(task.text).getUnclippedBoundsInRoot().left.value
            row.performTouchInput {up()}
            compose.mainClock.advanceTimeByFrame()
            val released=compose.onNodeWithText(task.text).getUnclippedBoundsInRoot().left.value
            val metrics=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"swipe-validation/release-position.json").apply {parentFile!!.mkdirs()}
            metrics.writeText(org.json.JSONObject().put("draggedLeftDp",dragged).put("releasedLeftDp",released)
                .put("jumpDp",kotlin.math.abs(released-dragged)).toString(2))
            assertTrue("Release jumped from ${dragged}dp to ${released}dp",kotlin.math.abs(released-dragged)<=4f)
        } finally {compose.mainClock.autoAdvance=true}
    }
    @Test fun repeatedLeftRightSwipesSettleAtTheSameAnchorsWithoutCheckingOrDeleting() {
        val task=TodoItem("repeat","连续左右滑动的合成待办")
        var toggles=0;var deletions=0
        compose.setContent {SoundTheme {SwipeTodoRow(task,Recording("fixture",0,1000),{toggles++},{deletions++},Modifier.testTag("swipe-fixture"))}}
        val initial=compose.onNodeWithText(task.text).getUnclippedBoundsInRoot().left.value
        repeat(6) {
            compose.onNodeWithTag("swipe-fixture").performTouchInput {swipeLeft()}
            compose.onNodeWithText("删除").assertIsDisplayed()
            compose.onNodeWithTag("swipe-fixture").performTouchInput {swipeRight()}
            compose.onNodeWithText("删除").assertDoesNotExist()
            val closed=compose.onNodeWithText(task.text).getUnclippedBoundsInRoot().left.value
            assertEquals(initial,closed,1f)
        }
        compose.runOnIdle {assertEquals(0,toggles);assertEquals(0,deletions)}
    }
    @Test fun verticalListScrollDoesNotRevealOrActivateDelete() {
        val record=Recording("scroll",0,1000,title="纵向滚动验证",summary=MeetingSummary(todos=(0 until 25).map {TodoItem("t$it","纵向待办第${it}项")}))
        var actions=0
        compose.setContent {SoundTheme {TodosList(listOf(record),{it.title!!},{_,_->actions++},{_,_->actions++},{})}}
        compose.onNodeWithText("纵向待办第0项").assertIsDisplayed()
        compose.onRoot().performTouchInput {swipeUp()}
        compose.onNodeWithText("纵向待办第0项").assertIsNotDisplayed()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.runOnIdle {assertEquals(0,actions)}
    }
    @Test fun allRecordingsAreGroupedCompletedTasksMoveAndSwipeDeletesOnlyChosenSource() {
        val first=TodoItem("shared","测试会议甲需要交付原型")
        val second=TodoItem("shared","测试会议乙需要完成验收")
        var records by mutableStateOf(listOf(
            Recording("a",0,1000,title="合成会议甲",summary=MeetingSummary(todos=listOf(first,TodoItem("done","原有已完成事项",done=true)))),
            Recording("b",1000,1000,title="合成会议乙",summary=MeetingSummary(todos=listOf(second)))))
        var deletions=0
        compose.setContent {SoundTheme {TodosList(records,{it.title!!},
            onToggle={rid,tid->records=records.map {r->if(r.id==rid)r.copy(summary=r.summary!!.copy(todos=r.summary!!.todos.map {if(it.id==tid)it.copy(done=!it.done) else it})) else r}},
            onDelete={rid,tid->deletions++;records=records.map {if(it.id==rid)it.withoutTodo(tid) else it}},onOpen={})}}
        compose.onNodeWithText("来自 2 条录音 · 2 项未完成 · 1 项已完成").assertIsDisplayed()
        compose.onNodeWithText(first.text).assertIsDisplayed();compose.onNodeWithText(second.text).assertIsDisplayed()
        capture("two-recordings.png")
        compose.onNodeWithText(first.text).performClick()
        compose.onNodeWithText(first.text).assertDoesNotExist()
        compose.onNodeWithText("已完成").performClick()
        compose.onNodeWithText(first.text).assertIsDisplayed()
        compose.onNodeWithText("原有已完成事项").assertIsDisplayed()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText(second.text).performTouchInput {swipeRight()}
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.onNodeWithText(second.text).performTouchInput {swipeLeft()}
        compose.onNodeWithText("删除").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,deletions)}
        capture("swipe-delete.png")
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText(second.text).assertDoesNotExist()
        compose.onNodeWithText(first.text).assertIsDisplayed()
        compose.runOnIdle {assertEquals(1,deletions);assertTrue(records[1].summary!!.todos.isEmpty());assertEquals(2,records[0].summary!!.todos.size)}
    }
    private fun capture(name: String) {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"todo-validation/$name").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
