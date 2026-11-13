package dev.soundsoftware.core

import dev.soundsoftware.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecordingHighlightsTest {
    private val json=Json {encodeDefaults=true}
    @Test fun legacyUnknownHighlightsDoNotRewriteExistingJsonButVerifiedEmptyPersists() {
        val old="""{"id":"legacy","createdAt":0,"durationMs":1000}"""
        val record=json.decodeFromString<Recording>(old)
        assertNull(record.hardwareHighlights)
        assertFalse(json.encodeToString(record).contains("hardwareHighlights"))
        val verified=record.copy(hardwareHighlights=emptyList())
        assertTrue(json.encodeToString(verified).contains("\"hardwareHighlights\":[]"))
        assertEquals(verified,json.decodeFromString<Recording>(json.encodeToString(verified)))
    }
    @Test fun highlightContextUsesTimestampedNeighboursWithoutInventingUntimedEvidence() {
        val segments=(0..4).map {i->Segment("s$i","原文$i",i*1000L,i*1000L+500)}
        val r=Recording("r",0,5000,transcript=Transcript(segments,"fixture","test","SEGMENT",0))
        assertEquals(listOf("s1","s2","s3"),r.highlightContext(RecordingHighlight("m",2200)).map {it.id})
        val untimed=r.copy(transcript=r.transcript!!.copy(segments=segments.map {it.copy(startMs=null,endMs=null)}))
        assertTrue(untimed.highlightContext(RecordingHighlight("m",2200)).isEmpty())
    }
}
