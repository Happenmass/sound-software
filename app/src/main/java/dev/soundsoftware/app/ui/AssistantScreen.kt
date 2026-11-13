package dev.soundsoftware.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.soundsoftware.app.AppViewModel
import dev.soundsoftware.app.R
import dev.soundsoftware.app.Screen
import dev.soundsoftware.core.analysis.MeetingAnalyzer

@Composable
fun AssistantScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(C.PageBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButtonPlain(Icons.Back, stringResource(R.string.back)) { vm.back() }
            T(stringResource(R.string.recording_assistant), ts(20, 700))
        }
        AssistantContent(vm)
    }
}

@Composable
fun AssistantContent(vm: AppViewModel, recordingId: String? = null, recordingTitle: String? = null) {
    val sessionFlow = remember(vm, recordingId) { vm.assistantState(recordingId) }
    val state by sessionFlow.collectAsState()
    val recordings by vm.recordings.collectAsState()
    val revisions = remember(recordings) { recordings.associate { it.id to MeetingAnalyzer.transcriptHash(it) } }
    var input by rememberSaveable(recordingId) { mutableStateOf("") }
    var confirmClear by remember(recordingId) { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    var followLatest by rememberSaveable(recordingId) { mutableStateOf(true) }
    val userScroll = remember(recordingId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) followLatest = false
                return Offset.Zero
            }
        }
    }

    val showIntro = state.session.turns.isEmpty() && state.pendingQuestion == null
    val showError = state.pendingQuestion == null && state.error != null
    val latestIndex = state.session.turns.size +
        (if (showIntro || state.pendingQuestion != null) 1 else 0) + (if (showError) 1 else 0)
    LaunchedEffect(followLatest, state.session.id, latestIndex, state.pendingQuestion, state.draft, state.trace, state.error) {
        if (followLatest) scroll.requestScrollToItem(latestIndex)
    }

    fun send() {
        if (input.isBlank() || !state.ready || state.busy) return
        vm.askAssistant(input, recordingId)
        input = ""
    }

    Column(Modifier.fillMaxSize().background(C.PageBg)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                T(stringResource(if (recordingId == null) R.string.assistant_all_recordings else R.string.assistant_this_recording), ts(13, 700, C.Ink))
                T(recordingTitle ?: stringResource(R.string.assistant_scope, recordings.size), ts(12, 400, C.Muted))
            }
            TextButton(enabled = state.ready && !state.busy && state.session.turns.isNotEmpty(), onClick = { confirmClear = true }) {
                T(stringResource(R.string.clear_history), ts(13, 500, C.Blue))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(userScroll),
                state = scroll,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (showIntro) item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        T(stringResource(if (recordingId == null) R.string.ask_across_recordings else R.string.assistant_ask_recording), ts(22, 700))
                        T(stringResource(if (recordingId == null) R.string.assistant_intro else R.string.assistant_single_intro), ts(14, 400, C.Muted))
                        val suggestion = if (recordingId == null) stringResource(R.string.suggest_comparison)
                            else stringResource(R.string.suggest_recording_todos, recordingTitle.orEmpty())
                        OutlineChip(suggestion) { input = suggestion }
                        if (recordingId != null) TextButton(onClick = { vm.push(Screen.Assistant) }) {
                            T(stringResource(R.string.assistant_open_all), ts(13, 500, C.Blue))
                        }
                        if (!vm.llmReady()) PillButton(stringResource(R.string.configure_notes_service)) { vm.push(Screen.LlmService) }
                    }
                }
                itemsIndexed(state.session.turns, key = { index, _ -> "${state.session.id}-$index" }) { _, turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UserQuestion(turn.question)
                        AnswerCard {
                            AssistantTrace(turn.trace, legacySteps = turn.steps)
                            turn.evidenceWarning?.let { T(it, ts(13, 400, C.Amber)) }
                            SelectionContainer { T(turn.answer, ts(15, 400, lineHeight = 1.65f)) }
                            turn.citations.forEach { ref ->
                                val valid = revisions[ref.recordingId] == ref.revision
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.PageBg)
                                    .tap(enabled = valid) { vm.openCitation(ref) }.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val location = ref.startMs?.let(::clock) ?: stringResource(R.string.source_text)
                                    T(if (valid) "${ref.title} · $location ›" else stringResource(R.string.source_changed),
                                        ts(12, 600, if (valid) C.Blue else C.Muted))
                                    if (valid) T("“${ref.quote}”", ts(13, 400, C.Muted))
                                }
                            }
                        }
                    }
                }
                state.pendingQuestion?.let { pending ->
                    item(key = "${state.session.id}-${state.session.turns.size}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            UserQuestion(pending)
                            AnswerCard {
                                AssistantTrace(state.trace, busy = state.busy)
                                if (state.draft.isNotEmpty()) {
                                    T(stringResource(R.string.assistant_draft), ts(12, 500, C.Muted))
                                    T(state.draft, ts(15, 400, lineHeight = 1.65f))
                                } else if (state.busy && state.trace.isEmpty()) {
                                    T(stringResource(R.string.preparing_search), ts(14, 400, C.Muted))
                                }
                                if (state.cancelled) T(stringResource(R.string.assistant_cancelled), ts(14, 400, C.Muted))
                                state.error?.let { T(it, ts(14, 400, C.RedDot)) }
                            }
                        }
                    }
                }
                if (showError) item { T(state.error.orEmpty(), ts(14, 400, C.RedDot)) }
                // A separate last item lets the list reach the end of a reply taller than the viewport.
                item(key = "latest") { Spacer(Modifier.height(1.dp)) }
            }
            JumpToLatest(
                visible = !followLatest,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp),
                onClick = { followLatest = true },
            )
        }
        Row(Modifier.fillMaxWidth().background(Color.White).imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(input, { input = it }, stringResource(R.string.ask_recordings_placeholder), Modifier.weight(1f), onDone = ::send)
            if (state.busy && state.runId != null) {
                TextButton(onClick = { vm.cancelAssistant(recordingId, state.runId) }) {
                    T(stringResource(R.string.stop), ts(14, 600, C.Blue))
                }
            } else RoundIconButton(Icons.Send, stringResource(R.string.send), C.Ink, Color.White, onClick = ::send)
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { T(stringResource(R.string.clear_assistant_title), ts(20, 700)) },
        text = { T(stringResource(R.string.assistant_clear_scope), ts(15, 400)) },
        confirmButton = { TextButton(onClick = { vm.clearAssistantHistory(recordingId); confirmClear = false }) { T(stringResource(R.string.clear), ts(14, 600)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { T(stringResource(R.string.cancel), ts(14, 500)) } },
    )
}

@Composable
private fun JumpToLatest(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
    ) {
        Box(
            Modifier.size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                .background(Color.White).border(1.dp, C.Line2, CircleShape)
                .tap(stringResource(R.string.assistant_jump_to_latest), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Ico(Icons.ArrowDown, size = 22.dp, color = C.Blue)
        }
    }
}

@Composable
private fun UserQuestion(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        T(text, ts(15, 500, Color.White), Modifier.widthIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp)).background(C.Ink).padding(14.dp))
    }
}

@Composable
private fun AnswerCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
