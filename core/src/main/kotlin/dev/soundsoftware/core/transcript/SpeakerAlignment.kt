package dev.soundsoftware.core.transcript

import dev.soundsoftware.core.model.Segment
import dev.soundsoftware.core.model.Speaker

/** One diarization turn on the original recording time axis. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int) {
    init { require(startMs >= 0 && endMs > startMs) }
}

private const val MIN_SILENCE_MS = 1500L

/** Groups consecutive turns of a known speaker while keeping the original cuts for ASR. */
internal fun groupSpeakerTurns(turns: List<SpeakerTurn>, durationMs: Long): List<List<SpeakerTurn>> {
    val maxDurationMs = 5 * 60 * 1000L
    val groups = mutableListOf<MutableList<SpeakerTurn>>()
    var coveredUntil = 0L
    var previousSpeaker: Int? = null
    for (turn in turns.sortedBy { it.startMs }) {
        var start = maxOf(coveredUntil, turn.startMs)
        val end = minOf(durationMs, turn.endMs)
        while (start < end) {
            val partEnd = minOf(end, start + maxDurationMs)
            val part = SpeakerTurn(start, partEnd, turn.speaker)
            val group = groups.lastOrNull()
            val first = group?.first()
            if (first != null && part.speaker >= 0 && previousSpeaker == part.speaker && first.speaker == part.speaker &&
                start - group.last().endMs < MIN_SILENCE_MS && partEnd - first.startMs <= maxDurationMs) {
                group.add(part)
            } else {
                groups.add(mutableListOf(part))
            }
            start = partEnd
            coveredUntil = partEnd
        }
        previousSpeaker = turn.speaker
    }
    return groups
}

/**
 * Gives each timed segment the speaker with the largest time overlap. Segments without
 * timestamps or overlap stay unattributed rather than guessed.
 */
fun assignSpeakers(segments: List<Segment>, turns: List<SpeakerTurn>): Pair<List<Segment>, List<Speaker>> {
    val order = mutableListOf<Int>()
    val assigned = segments.map { segment ->
        val start = segment.startMs; val end = segment.endMs
        if (start == null || end == null) return@map segment.copy(speakerId = null)
        val best = turns.filter { it.speaker >= 0 }.groupBy { it.speaker }.mapValues { (_, list) ->
            list.sumOf { maxOf(0, minOf(end, it.endMs) - maxOf(start, it.startMs)) }
        }.filterValues { it > 0 }.maxByOrNull { it.value }?.key
        if (best != null && best !in order) order += best
        segment.copy(speakerId = best?.let { "spk-$it" })
    }
    val speakers = order.mapIndexed { index, raw -> Speaker("spk-$raw", index + 1) }
    return assigned to speakers
}

data class SpeakerStats(val speakerId: String, val totalMs: Long, val share: Float)

fun speakerStats(segments: List<Segment>): Map<String, SpeakerStats> {
    val totals = segments.filter { it.speakerId != null && it.startMs != null && it.endMs != null }
        .groupBy { it.speakerId!! }.mapValues { (_, s) -> s.sumOf { it.endMs!! - it.startMs!! } }
    val all = totals.values.sum().coerceAtLeast(1)
    return totals.mapValues { (id, ms) -> SpeakerStats(id, ms, ms.toFloat() / all) }
}

/** Gaps of at least [minGapMs] between timed segments, used by "skip silence" playback. */
fun silentGaps(segments: List<Segment>, durationMs: Long, minGapMs: Long = MIN_SILENCE_MS): List<LongRange> {
    val timed = segments.filter { it.startMs != null && it.endMs != null }.sortedBy { it.startMs }
    if (timed.isEmpty()) return emptyList()
    val gaps = mutableListOf<LongRange>()
    var cursor = 0L
    for (s in timed) {
        if (s.startMs!! - cursor >= minGapMs) gaps += cursor until s.startMs
        cursor = maxOf(cursor, s.endMs!!)
    }
    if (durationMs - cursor >= minGapMs) gaps += cursor until durationMs
    return gaps
}
