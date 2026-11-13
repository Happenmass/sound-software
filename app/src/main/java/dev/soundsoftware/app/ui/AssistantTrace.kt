package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.soundsoftware.core.agent.AgentTraceStep
import kotlinx.serialization.json.Json

/** Stage descriptions come from observable operations, rather than hidden model reasoning. */
@Composable
internal fun AssistantTrace(trace: List<AgentTraceStep>, busy: Boolean = false, legacySteps: List<String> = emptyList()) {
    if(trace.isEmpty() && legacySteps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val tools=trace.count {it.kind=="tool"}.takeIf {trace.isNotEmpty()} ?: legacySteps.size
    val latest=trace.lastOrNull()
    val status=when {
        busy -> latest?.title ?: stringResource(R.string.processing)
        latest?.status=="cancelled" -> stringResource(R.string.stopped)
        latest?.status=="failed" -> stringResource(R.string.processing_failed)
        else -> stringResource(R.string.processing_complete)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.PageBg)) {
        Row(Modifier.fillMaxWidth().tap {expanded=!expanded}.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                T(stringResource(R.string.assistant_process, status),ts(13,500,if(busy) C.Blue else C.Ink2))
                T(stringResource(R.string.tool_call_count, tools, duration(trace.sumOf {it.elapsedMs})),ts(12,400,C.Muted))
            }
            T(if(expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),ts(12,500,C.Blue))
        }
        if(expanded) {
            Divider()
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(trace.isEmpty()) legacySteps.forEachIndexed {i,label->T("${i+1}. $label",ts(13,400,C.Muted))}
                trace.forEach {step->key(step.id) {TraceRow(step)}}
            }
        }
    }
}

@Composable private fun TraceRow(step: AgentTraceStep) {
    var details by remember {mutableStateOf(false)}
    val color=when(step.status) {"failed"->C.RedDot;"running"->C.Blue;else->C.Muted}
    val marker=when(step.status) {"running"->"◌";"failed"->"!";"cancelled"->"–";else->"✓"}
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        T(marker,ts(14,600,color))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                T(step.title,ts(13,500,C.Ink2),Modifier.weight(1f))
                T(if(step.status=="running") stringResource(R.string.in_progress) else if(step.status=="cancelled") stringResource(R.string.stopped) else duration(step.elapsedMs),ts(12,400,color))
            }
            if(step.detail.isNotBlank()) T(step.detail,ts(12,400,C.Muted,lineHeight=1.5f))
            step.usage?.let {T(stringResource(R.string.step_token_usage, it.promptTokens, it.completionTokens),ts(11,400,C.Muted))}
            if(step.toolName!=null) {
                T("${step.toolName} · ${if(details) stringResource(R.string.hide_tool_details) else stringResource(R.string.show_tool_details)}",ts(12,500,C.Blue),Modifier.tap {details=!details}.padding(vertical=4.dp))
                if(details) SelectionContainer {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        T(stringResource(R.string.arguments),ts(12,600,C.Ink2))
                        T(pretty(step.arguments ?: "{}"),ts(12,400,C.Ink2,lineHeight=1.5f))
                        T(stringResource(R.string.results),ts(12,600,C.Ink2))
                        T(step.result?.let(::pretty) ?: if(step.status=="running") stringResource(R.string.waiting_for_tool) else step.detail,ts(12,400,C.Ink2,lineHeight=1.5f))
                    }
                }
            }
        }
    }
}

private val traceJson=Json {prettyPrint=true}
private fun pretty(value: String)=runCatching {traceJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(),traceJson.parseToJsonElement(value))}.getOrDefault(value)
@Composable
private fun duration(ms: Long)=if(ms<1000) stringResource(R.string.duration_ms, ms) else stringResource(R.string.duration_seconds_decimal, ms / 1000.0)
