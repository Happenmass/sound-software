package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.TodoItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class TodoAnchor { Closed, Revealed }

/** Foundation owns drag, fling and settling; one offset is used for the entire interaction. */
@Composable
internal fun SwipeTodoRow(todo: TodoItem, recording: Recording, onToggle: () -> Unit, onDelete: () -> Unit,
    modifier: Modifier = Modifier) {
    val width=with(LocalDensity.current) {76.dp.toPx()}
    val state=remember(recording.id,todo.id,width) {
        AnchoredDraggableState(TodoAnchor.Closed,DraggableAnchors {
            TodoAnchor.Closed at 0f
            TodoAnchor.Revealed at -width
        })
    }
    val revealed by remember(state) {derivedStateOf {state.settledValue==TodoAnchor.Revealed}}
    val scope=rememberCoroutineScope()
    val toggle by rememberUpdatedState(onToggle)
    val delete by rememberUpdatedState(onDelete)
    val deleteLabel = stringResource(R.string.delete_todo)
    Box(modifier.fillMaxWidth().clipToBounds().semantics {
        customActions=listOf(CustomAccessibilityAction(deleteLabel) {delete();true})
    }) {
        // Always lay out the background. Pixel movement is read only during placement below.
        Box(Modifier.matchParentSize().background(C.Red),contentAlignment=Alignment.CenterEnd) {
            Box(Modifier.width(76.dp).fillMaxHeight().tap(enabled=revealed) {delete()}
                .then(if(revealed) Modifier else Modifier.clearAndSetSemantics {}),contentAlignment=Alignment.Center) {
                T(stringResource(R.string.delete),ts(14,600,Color.White))
            }
        }
        Box(Modifier.offset {IntOffset(state.requireOffset().roundToInt(),0)}.fillMaxWidth().background(Color.White)
            .anchoredDraggable(state,Orientation.Horizontal)) {
            TodoRow(todo,recording,{
                if(state.requireOffset()<0f) scope.launch {state.animateTo(TodoAnchor.Closed)} else toggle()
            },Modifier.padding(horizontal=16.dp,vertical=10.dp))
        }
    }
}
