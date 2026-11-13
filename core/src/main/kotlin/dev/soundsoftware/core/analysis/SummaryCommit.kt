package dev.soundsoftware.core.analysis

import dev.soundsoftware.core.model.MeetingSummary
import dev.soundsoftware.core.model.Recording

/** Commit against the snapshot that generated the notes; never overwrite concurrent user edits. */
object SummaryCommit {
    fun reconcile(base: Recording, current: Recording, generated: MeetingSummary): MeetingSummary {
        requireTranscript(current, generated.sourceTranscriptHash)
        if (body(base.summary) != body(current.summary))
            throw AnalysisException("纪要在生成期间已修改，本次结果未覆盖，请重新生成")
        val existing = current.summary?.todos.orEmpty()
        return generated.copy(todos = generated.todos.map { todo ->
            val previous = existing.firstOrNull { it.id == todo.id } ?: existing.firstOrNull {
                it.text == todo.text && it.owner == todo.owner && it.due == todo.due
            }
            if (previous != null) todo.copy(done = previous.done) else todo
        })
    }

    fun requireTranscript(current: Recording, expectedHash: String) {
        if (expectedHash.isBlank() || MeetingAnalyzer.transcriptHash(current) != expectedHash)
            throw AnalysisException("逐字稿、说话人或重点标记在生成期间已修改，本次结果未保存，请重新生成")
    }

    private fun body(summary: MeetingSummary?) = summary?.copy(todos = summary.todos.map { it.copy(done = false) })
}
