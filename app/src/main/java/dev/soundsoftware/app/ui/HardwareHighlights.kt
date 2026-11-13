package dev.soundsoftware.app.ui

import dev.soundsoftware.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.soundsoftware.core.model.Recording
import dev.soundsoftware.core.model.highlightContext

@Composable
internal fun HardwareHighlights(recording: Recording,onPlay: (Long)->Unit) {
    val marks=recording.hardwareHighlights ?: return
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        T(stringResource(R.string.device_highlight_count, marks.size),ts(13,600,C.Muted))
        if(marks.isEmpty()) T(stringResource(R.string.no_device_highlights),ts(13,400,C.Muted))
        marks.forEachIndexed {i,mark->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.PageBg)
                .tap {onPlay(mark.positionMs)}.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                T(stringResource(R.string.highlight_at, i+1, clock(mark.positionMs)),ts(14,600,C.Blue))
                val context=recording.highlightContext(mark).joinToString(" ") {it.text}
                T(if(context.isBlank()) stringResource(R.string.play_highlight) else stringResource(R.string.nearby_transcript, context),ts(13,400,C.Muted,lineHeight=1.5f),maxLines=3)
            }
        }
    }
}
