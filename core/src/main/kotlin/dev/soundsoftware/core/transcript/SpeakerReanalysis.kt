package dev.soundsoftware.core.transcript

import dev.soundsoftware.core.model.*

/** Reassign identity only; original words, timing, edits, title, notes and QA are not regenerated. */
object SpeakerReanalysis {
    fun commit(base: Recording, current: Recording, turns: List<SpeakerTurn>, warnings: List<String>, count: Int?, now: Long, speakerHtpExecutions: Int = 0): Recording {
        require(base.transcript != null) { "请先生成逐字稿" }
        require(current.transcript == base.transcript && current.speakers == base.speakers) { "文字或说话人在计算期间已修改，本次结果未覆盖，请重试" }
        val (segments, fresh) = assignSpeakers(base.transcript.segments, turns)
        // Carry a name only for an unambiguous one-to-one mapping covering >=80% in both directions.
        val weights=mutableMapOf<Pair<String,String>,Long>()
        segments.zip(base.transcript.segments).forEach { (new, old) ->
            if(new.speakerId != null && old.speakerId != null) {
                val duration=((old.endMs ?: 0)-(old.startMs ?: 0)).coerceAtLeast(1)
                val key=new.speakerId to old.speakerId;weights[key]=(weights[key] ?: 0)+duration
            }
        }
        val mapping=mutableMapOf<String,Speaker>()
        for(speaker in fresh) {
            val matches=weights.filterKeys {it.first==speaker.id}
            val best=matches.maxByOrNull {it.value} ?: continue
            val newTotal=segments.filter {it.speakerId==speaker.id}.sumOf {((it.endMs ?: 0)-(it.startMs ?: 0)).coerceAtLeast(1)}
            val oldTotal=base.transcript.segments.filter {it.speakerId==best.key.second}.sumOf {((it.endMs ?: 0)-(it.startMs ?: 0)).coerceAtLeast(1)}
            val old=base.speakers.firstOrNull {it.id==best.key.second} ?: continue
            if(best.value>=newTotal*.8 && best.value>=oldTotal*.8 && mapping.values.none {it.id==old.id})mapping[speaker.id]=old
        }
        // Never reuse a raw cluster id as a previous person's identity by coincidence.
        val ids=fresh.associate {it.id to (mapping[it.id]?.id ?: "speaker-$now-${it.index}")}
        val speakers=fresh.map {it.copy(id=ids.getValue(it.id),name=mapping[it.id]?.name)}
        val speakerWarnings=listOf("说话人分离模型未安装","说话人分离未完成：","干净语音不足","部分短应答或噪声")
        return current.copy(
            transcript=base.transcript.copy(segments=segments.map {it.copy(speakerId=it.speakerId?.let(ids::get))},
                warnings=base.transcript.warnings.filterNot {w->speakerWarnings.any(w::startsWith)}+warnings,
                speakerHtpExecutions = speakerHtpExecutions),
            speakers=speakers,options=(current.options ?: TranscribeOptions()).copy(speakerCount=count),
            speakerAnalysis=SpeakerAnalysis(SpeakerClustering.VERSION,now,count,speakers.size),
        )
    }
}
