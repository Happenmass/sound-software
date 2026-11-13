package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.Screen

@Composable fun AssistantScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButtonPlain(Icons.Back,stringResource(R.string.back)) {vm.back()}
            T(stringResource(R.string.recording_assistant),ts(20,700),Modifier.weight(1f))
        }
        AssistantContent(vm)
    }
}

@Composable fun AssistantContent(vm: AppViewModel, suggestedQuestion: String? = null) {
    val state by vm.assistant.collectAsState()
    val recordings by vm.recordings.collectAsState()
    var question by remember {mutableStateOf("")}
    var confirmClear by remember {mutableStateOf(false)}
    val list=rememberLazyListState()
    LaunchedEffect(state.session.turns.size,state.busy) {if(list.layoutInfo.totalItemsCount>0)list.animateScrollToItem(list.layoutInfo.totalItemsCount-1)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            T(if(state.ready) stringResource(R.string.assistant_scope, recordings.size) else stringResource(R.string.building_index),ts(12,400,C.Muted),Modifier.weight(1f))
            T(stringResource(R.string.new_chat),ts(13,500,C.Blue),Modifier.tap(enabled=state.ready&&!state.busy){vm.newAssistantSession()}.padding(8.dp))
            T(stringResource(R.string.clear_history),ts(13,400,C.Muted),Modifier.tap(enabled=state.ready&&!state.busy){confirmClear=true}.padding(8.dp))
        }
        state.contextUsage?.let {usage->
            T(stringResource(R.string.context_usage, usage.totalTokens),ts(12,400,C.Muted),Modifier.padding(start=18.dp,end=18.dp,bottom=4.dp))
        }
        LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(state.session.turns.isEmpty() && state.pendingQuestion==null)item {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    T(stringResource(R.string.ask_across_recordings),ts(22,700))
                    T(stringResource(R.string.assistant_intro),ts(14,400,C.Muted,lineHeight=1.6f))
                    listOfNotNull(suggestedQuestion,stringResource(R.string.suggest_todos),stringResource(R.string.suggest_comparison)).forEach { text->
                        OutlineChip(text) {if(!state.busy)question=text}
                    }
                    if(!vm.llmReady()) PillButton(stringResource(R.string.configure_notes_service)) {vm.push(Screen.LlmService)}
                }
            }
            itemsIndexed(state.session.turns,key={i,_->"${state.session.id}-$i"}) {_,turn ->
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    T(turn.question,ts(15,500,Color.White),Modifier.align(Alignment.End).clip(RoundedCornerShape(16.dp)).background(C.Ink).padding(14.dp))
                    AssistantTrace(turn.trace,legacySteps=turn.steps)
                    turn.evidenceWarning?.let { T(it, ts(13, 400, C.Amber)) }
                    SelectionContainer {T(turn.answer,ts(15,400,lineHeight=1.65f))}
                    turn.citations.forEach {ref ->
                        val r=recordings.firstOrNull {it.id==ref.recordingId}
                        val valid=r!=null && dev.soundsoftware.core.analysis.MeetingAnalyzer.transcriptHash(r)==ref.revision
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.PageBg).tap(enabled=valid){vm.openCitation(ref)}.padding(12.dp)) {
                            T(if(valid) "${ref.title} · ${ref.startMs?.let(::clock) ?: stringResource(R.string.source_text)} ›" else stringResource(R.string.source_changed),ts(12,500,if(valid)C.Blue else C.Muted))
                            if(valid)T("“${ref.quote}”",ts(13,400,C.Muted))
                            if(valid&&ref.hardwareHighlights.isNotEmpty())T(stringResource(R.string.citation_highlights, ref.hardwareHighlights.joinToString("、") {clock(it.positionMs)}),ts(12,500,C.Blue))
                        }
                    }
                }
            }
            state.pendingQuestion?.let {pending->item {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    T(pending,ts(15,500,Color.White),Modifier.align(Alignment.End).clip(RoundedCornerShape(16.dp)).background(C.Ink).padding(14.dp))
                    AssistantTrace(state.trace,busy=state.busy)
                    if(state.busy&&state.trace.isEmpty())T(state.step ?: stringResource(R.string.preparing_search),ts(14,400,C.Muted))
                }
            }}
            state.error?.let {error->item {T(error,ts(14,400,C.RedDot))}}
            if(state.session.compactions>0)item {T(stringResource(R.string.history_compacted),ts(12,400,C.Muted))}
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Field(question,{question=it},stringResource(R.string.ask_recordings_placeholder),Modifier.weight(1f),onDone={if(question.isNotBlank()&&!state.busy&&state.ready){vm.askAssistant(question);question=""}})
            if(state.busy)T(stringResource(R.string.stop),ts(14,600,C.Blue),Modifier.tap{vm.cancelAssistant()}.padding(10.dp))
            else RoundIconButton(Icons.Send,stringResource(R.string.send),C.Ink,Color.White){if(question.isNotBlank()&&state.ready){vm.askAssistant(question);question=""}}
        }
    }
    if(confirmClear) androidx.compose.material3.AlertDialog(onDismissRequest={confirmClear=false},title={T(stringResource(R.string.clear_assistant_title),ts(20,700))},text={T(stringResource(R.string.clear_assistant_message),ts(14,400))},confirmButton={T(stringResource(R.string.clear),ts(15,600,C.RedDot),Modifier.tap{vm.clearAssistantHistory();confirmClear=false}.padding(16.dp))},dismissButton={T(stringResource(R.string.cancel),ts(15,500),Modifier.tap{confirmClear=false}.padding(16.dp))})
}
