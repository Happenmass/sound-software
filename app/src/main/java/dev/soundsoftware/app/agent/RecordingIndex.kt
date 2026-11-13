package dev.soundsoftware.app.agent

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.MeetingAnalyzer
import dev.soundsoftware.core.model.*
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

interface RecordingProjection {
    fun reconcile(recordings: List<Recording>)
    fun upsert(recording: Recording)
    fun remove(id: String)
}

/** App-private SQLite relational mirror. WAV/legacy metadata remain compatible and rebuildable. */
class RecordingIndex(file: File) : RecordingProjection, RecordingTools, Closeable {
    private val db: SQLiteDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
        db.execSQL("CREATE TABLE IF NOT EXISTS recordings(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,duration INTEGER NOT NULL,revision TEXT NOT NULL,projection TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS segments(rowid INTEGER PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,segment_id TEXT NOT NULL,part INTEGER NOT NULL,text TEXT NOT NULL,start INTEGER,speaker TEXT,revision TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS segment_recording ON segments(recording_id,part)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS segment_fts USING fts4(tokens)")
        db.execSQL("CREATE TABLE IF NOT EXISTS todos(recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,id TEXT NOT NULL,text TEXT NOT NULL,owner TEXT,due TEXT,done INTEGER NOT NULL,PRIMARY KEY(recording_id,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS hardware_highlights(recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,id TEXT NOT NULL,position INTEGER NOT NULL,segment_id TEXT NOT NULL,PRIMARY KEY(recording_id,id,segment_id))")
        val hasHighlightState=db.rawQuery("PRAGMA table_info(recordings)",null).use {c->var found=false;while(c.moveToNext())if(c.getString(1)=="highlights_known")found=true;found}
        if(!hasHighlightState) db.execSQL("ALTER TABLE recordings ADD COLUMN highlights_known INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.version = 2
    }
    @Synchronized override fun reconcile(recordings: List<Recording>) = transaction {
        val ids = recordings.map { it.id }.toSet()
        val stored = db.rawQuery("SELECT id FROM recordings", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        stored.filterNot { it in ids }.forEach(::removeRows)
        recordings.forEach(::upsertRows)
    }
    @Synchronized override fun upsert(recording: Recording) = transaction { upsertRows(recording) }
    @Synchronized override fun remove(id: String) = transaction { removeRows(id) }
    private fun transaction(block: () -> Unit) { db.beginTransaction(); try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() } }
    private fun removeRows(id: String) {
        db.delete("hardware_highlights","recording_id=?",arrayOf(id))
        db.execSQL("DELETE FROM segment_fts WHERE rowid IN (SELECT rowid FROM segments WHERE recording_id=?)", arrayOf(id))
        db.delete("recordings", "id=?", arrayOf(id))
    }
    private fun upsertRows(r: Recording) {
        // Exclude progress and chat history: they do not change search data.
        val projection = json.encodeToString(r.copy(progress=null,stageText=null,qa=emptyList(),error=null))
        val known=when {r.hardwareHighlights!=null->1;r.source==RecordingSource.DEVICE->0;else->-1}
        val old = db.rawQuery("SELECT projection,highlights_known FROM recordings WHERE id=?", arrayOf(r.id)).use { if(it.moveToFirst()) it.getString(0) to it.getInt(1) else null }
        if(old == (projection to known)) return
        removeRows(r.id)
        val revision = MeetingAnalyzer.transcriptHash(r)
        db.execSQL("INSERT INTO recordings VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(r.id,r.title ?: (java.text.SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(r.createdAt) + " 的录音"),r.createdAt,r.durationMs,revision,projection,known))
        var order=0
        r.transcript?.segments.orEmpty().forEach { segment ->
            var offset=0
            while(offset < segment.text.length) {
                var end=minOf(offset+800,segment.text.length)
                if(end<segment.text.length && segment.text[end-1].isHighSurrogate() && segment.text[end].isLowSurrogate()) end--
                val text=segment.text.substring(offset,end); val speaker=r.speakerName(segment.speakerId)
                db.execSQL("INSERT INTO segments(recording_id,segment_id,part,text,start,speaker,revision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(r.id,segment.id,order++,text,segment.startMs,speaker,revision))
                val rowid=db.rawQuery("SELECT last_insert_rowid()",null).use { it.moveToFirst();it.getLong(0) }
                db.execSQL("INSERT INTO segment_fts(rowid,tokens) VALUES(?,?)",arrayOf<Any?>(rowid,tokens(text+" "+r.title.orEmpty()+" "+speaker.orEmpty(), true).joinToString(" ")))
                offset=end
            }
        }
        r.hardwareHighlights.orEmpty().forEach {mark->
            val context=r.highlightContext(mark).map {it.id}.ifEmpty {listOf("")}
            context.forEach {segmentId->db.execSQL("INSERT INTO hardware_highlights VALUES(?,?,?,?)",arrayOf<Any?>(r.id,mark.id,mark.positionMs,segmentId))}
        }
        r.summary?.todos.orEmpty().forEach { t -> db.execSQL("INSERT INTO todos VALUES(?,?,?,?,?,?)", arrayOf<Any?>(r.id,t.id,t.text,t.owner,t.due,if(t.done)1 else 0)) }
    }
    suspend fun saveSession(s: AgentSession) = withContext(Dispatchers.IO) { synchronized(this@RecordingIndex) {
        db.execSQL("INSERT OR REPLACE INTO sessions VALUES(?,?,?)", arrayOf<Any?>(s.id,json.encodeToString(s),System.currentTimeMillis()))
    } }
    suspend fun latestSession(): AgentSession? = withContext(Dispatchers.IO) { synchronized(this@RecordingIndex) {
        val latest=db.rawQuery("SELECT id,length(body) FROM sessions ORDER BY updated DESC LIMIT 1",null).use {
            if(it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        }
        latest?.let { (id,length)->
            // Long tool traces/history can exceed Android's per-row CursorWindow size.
            // SQL substr offsets count Unicode code points, so advance by the SQL chunk size.
            val body=buildString {
                var offset=1L
                while(offset<=length) {
                    db.rawQuery("SELECT substr(body,?,65536) FROM sessions WHERE id=?",arrayOf(offset.toString(),id)).use {
                        check(it.moveToFirst()) { "助手会话已被移除" }; append(it.getString(0))
                    }
                    offset+=65536
                }
            }
            json.decodeFromString<AgentSession>(body)
        }
    } }
    suspend fun clearSessions() = withContext(Dispatchers.IO) { synchronized(this@RecordingIndex) { db.delete("sessions",null,null); Unit } }
    suspend fun count() = withContext(Dispatchers.IO) { synchronized(this@RecordingIndex) { db.rawQuery("SELECT count(*) FROM recordings",null).use { it.moveToFirst();it.getInt(0) } } }

    override suspend fun execute(name: String, arguments: JsonObject): JsonObject = withContext(Dispatchers.IO) { synchronized(this@RecordingIndex) { query(name, arguments) } }
    private fun query(name: String, a: JsonObject): JsonObject {
        val common=setOf("limit","offset","recordingId","afterMs","beforeMs","speaker")
        val allowed=when(name) { "list_recordings"->common+"title";"search_recordings"->common+"query";"read_segments"->setOf("limit","offset","recordingId");"list_todos"->common+setOf("done","owner");"list_highlights"->common;else->error("未知工具") }
        require(a.keys.all { it in allowed })
        fun int(key:String,default:Int)=a[key]?.jsonPrimitive?.int ?: default
        fun str(key:String)=a[key]?.jsonPrimitive?.also { require(it.isString) }?.content
        val limit=int("limit",3);val offset=a["offset"]?.jsonPrimitive?.long ?: 0L
        require(limit in 1..5 && offset>=0)
        val where=mutableListOf<String>();val args=mutableListOf<String>()
        str("recordingId")?.let { where+="r.id=?";args+=it }
        a["afterMs"]?.jsonPrimitive?.long?.let { where+="r.created>=?";args+=it.toString() }
        a["beforeMs"]?.jsonPrimitive?.long?.let { where+="r.created<?";args+=it.toString() }
        str("speaker")?.let { where+="EXISTS(SELECT 1 FROM segments x WHERE x.recording_id=r.id AND instr(x.speaker,?)>0)";args+=it }
        var from="recordings r";var select="r.id,r.title,r.created,r.duration,r.revision,r.highlights_known";var order="r.created DESC,r.id"
        val isEvidence=name in setOf("read_segments","search_recordings","list_highlights")
        if(isEvidence && a["speaker"] != null) {
            val clause=where.indexOfFirst {it.startsWith("EXISTS")}
            if(clause>=0) where[clause]="instr(s.speaker,?)>0"
        }
        if (isEvidence) { from="segments s JOIN recordings r ON r.id=s.recording_id";select="r.id,r.title,s.segment_id,s.text,s.start,s.speaker,s.revision";order="r.created DESC,r.id,s.part" }
        when(name) {
            "list_recordings" -> str("title")?.let { where+="instr(r.title,?)>0";args+=it }
            "read_segments" -> require(!str("recordingId").isNullOrBlank())
            "list_highlights" -> {
                from+=" JOIN hardware_highlights h ON h.recording_id=r.id AND h.segment_id=s.segment_id"
                select+=",h.id,h.position";order="r.created DESC,r.id,h.position,h.id,s.part"
            }
            "search_recordings" -> {
                val q=str("query") ?: error("缺少关键词")
                val terms=tokens(q).distinct();require(terms.isNotEmpty())
                from+=" JOIN segment_fts ON segment_fts.rowid=s.rowid";where+="segment_fts MATCH ?";args+=terms.joinToString(" ") { "\"$it\"" }
            }
            "list_todos" -> {
                from="todos t JOIN recordings r ON r.id=t.recording_id";select="r.id,r.title,t.id,t.text,t.owner,t.due,t.done";order="r.created DESC,r.id,t.id"
                str("owner")?.let {where+="instr(t.owner,?)>0";args+=it}
                a["done"]?.jsonPrimitive?.boolean?.let {where+="t.done=?";args+=if(it)"1" else "0"}
            }
        }
        val sql="SELECT $select FROM $from"+(if(where.isEmpty())"" else " WHERE "+where.joinToString(" AND "))+" ORDER BY $order LIMIT ? OFFSET ?"
        val items=mutableListOf<JsonElement>();var more=false
        db.rawQuery(sql,(args+listOf((limit+1).toString(),offset.toString())).toTypedArray()).use { c ->
            while(c.moveToNext()) {
                if(items.size==limit){more=true;break}
                val item=if(isEvidence) {
                    val marks=if(name=="list_highlights") listOf(RecordingHighlight(c.getString(7),c.getLong(8))) else highlightsFor(c.getString(0),c.getString(2))
                    val ref=RecordingCitation(c.getString(0),c.getString(2),c.getString(3),c.getString(6),c.getString(1),if(c.isNull(4))null else c.getLong(4),c.getString(5),marks)
                    json.encodeToJsonElement(ref)
                } else buildJsonObject {
                    put("recordingId",c.getString(0));put("title",c.getString(1))
                    if(name=="list_todos") {put("todoId",c.getString(2));put("text",c.getString(3));put("owner",c.getString(4));put("due",c.getString(5));put("done",c.getInt(6)!=0)}
                    else {put("createdAt",c.getLong(2));put("durationMs",c.getLong(3));put("revision",c.getString(4))
                        put("hardwareHighlightsStatus",when(c.getInt(5)) {1->"verified";-1->"not_applicable";else->"unread"})
                        put("hardwareHighlightCount",db.rawQuery("SELECT count(DISTINCT id) FROM hardware_highlights WHERE recording_id=?",arrayOf(c.getString(0))).use {it.moveToFirst();it.getInt(0)})}
                }
                items+=item
            }
        }
        return buildJsonObject { put(if(isEvidence)"evidence" else "items",JsonArray(items));put("hasMore",more);put("nextOffset",offset+items.size)
            if(name=="list_highlights") put("unreadRecordingCount",db.rawQuery("SELECT count(*) FROM recordings WHERE highlights_known=0",null).use {it.moveToFirst();it.getInt(0)})
        }
    }
    override suspend fun resolve(ref: RecordingCitation): RecordingCitation? = withContext(Dispatchers.IO) { synchronized(this@RecordingIndex) {
        db.rawQuery("SELECT r.title,s.start,s.speaker,s.text FROM segments s JOIN recordings r ON r.id=s.recording_id WHERE r.id=? AND s.segment_id=? AND s.revision=?",arrayOf(ref.recordingId,ref.segmentId,ref.revision)).use { c ->
            var result:RecordingCitation?=null
            while(c.moveToNext()) if(c.getString(3).contains(ref.quote)) { result=ref.copy(title=c.getString(0),startMs=if(c.isNull(1))null else c.getLong(1),speaker=c.getString(2),hardwareHighlights=highlightsFor(ref.recordingId,ref.segmentId));break }
            result
        }
    } }
    private fun highlightsFor(recordingId: String,segmentId: String): List<RecordingHighlight> =
        db.rawQuery("SELECT id,position FROM hardware_highlights WHERE recording_id=? AND segment_id=? ORDER BY position,id LIMIT 5",arrayOf(recordingId,segmentId)).use {c->
            buildList {while(c.moveToNext())add(RecordingHighlight(c.getString(0),c.getLong(1)))}
        }
    @Synchronized override fun close() = db.close()
    companion object {
        /** ASCII words plus encoded Han unigrams and bigrams; stock SQLite needs no CJK extension. */
        fun tokens(text:String, index: Boolean = false):List<String> = buildList {
            Regex("[a-z0-9]+|[\\p{IsHan}]+",RegexOption.IGNORE_CASE).findAll(text.lowercase(Locale.ROOT)).forEach { m ->
                val s=m.value
                if(s[0].code<128) add("w"+s) else {
                    // Querying multi-Han words uses bigrams; single characters retain a separate token.
                    if(index) s.forEach { add("h"+it.code.toString(16)) }
                    if(s.length==1 && !index)add("h"+s[0].code.toString(16))
                    else for(i in 0 until s.length-1)add("b"+s[i].code.toString(16)+"x"+s[i+1].code.toString(16))
                }
            }
        }
    }
}
