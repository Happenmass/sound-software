package dev.soundsoftware.core.model

val Recording.hardwareHighlightsStatus: String get() = when {
    hardwareHighlights!=null -> "verified"
    source==RecordingSource.DEVICE -> "unread"
    else -> "not_applicable"
}

/** Nearest timestamped utterance and its neighbours are navigation/context clues, never evidence by themselves. */
fun Recording.highlightContext(highlight: RecordingHighlight): List<Segment> {
    val segments=transcript?.segments.orEmpty().filter {it.text.isNotBlank() && it.startMs!=null}
    val nearest=segments.indices.minByOrNull {i->
        val s=segments[i];val start=s.startMs!!;val end=(s.endMs ?: start).coerceAtLeast(start)
        when {highlight.positionMs<start->start-highlight.positionMs;highlight.positionMs>end->highlight.positionMs-end;else->0L}
    } ?: return emptyList()
    return segments.subList((nearest-1).coerceAtLeast(0),(nearest+2).coerceAtMost(segments.size))
}
