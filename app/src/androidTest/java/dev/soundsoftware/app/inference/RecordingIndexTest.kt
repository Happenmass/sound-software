package dev.soundsoftware.app.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soundsoftware.app.agent.RecordingIndex
import dev.soundsoftware.app.data.RecordingRepository
import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.audio.PcmWav
import dev.soundsoftware.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingIndexTest {
    @Test fun recordingSessionsScopeEveryToolAndDeleteWithoutErasingGlobalAnswers() = runBlocking { fixture { index, _ ->
        val a = recording("a")
        val b = recording("b")
        index.upsert(a)
        index.upsert(b)
        val first = AgentSession(turns = listOf(AgentTurn("Private A", "Answer A")))
        val second = AgentSession(turns = listOf(AgentTurn("Private B", "Answer B")))
        val evidence = json.decodeFromJsonElement<RecordingCitation>(search(index, "上线").getValue("evidence").jsonArray.first())
        val global = AgentSession(turns = listOf(AgentTurn("Global", "Keep this answer", listOf(evidence))),
            memory = "Old memory", contextMessages = listOf(AgentMessage("user", "Old context")))
        index.saveSession(first, a.id)
        index.saveSession(second, b.id)
        index.saveSession(global)
        index.upsert(a.copy(title = "New title"))
        assertEquals(first, index.latestSession(a.id))
        assertEquals(second, index.latestSession(b.id))
        assertEquals(global, index.latestSession())
        val tools = index.toolsFor(a.id)
        val scoped = tools.execute("search_recordings", buildJsonObject { put("query", "上线") })
        assertEquals(a.id, scoped.getValue("evidence").jsonArray.single().jsonObject.getValue("recordingId").jsonPrimitive.content)
        assertTrue(runCatching { tools.execute("read_segments", buildJsonObject { put("recordingId", b.id) }) }.isFailure)
        index.remove(evidence.recordingId)
        assertNull(index.latestSession(evidence.recordingId))
        assertNull(index.resolve(evidence))
        val saved = index.latestSession()!!
        assertEquals(global.turns, saved.turns)
        assertTrue(saved.contextMessages.isEmpty())
        assertTrue(saved.memory.isEmpty())
    } }

    private val json=Json {ignoreUnknownKeys=true}
    private suspend fun fixture(block:suspend (RecordingIndex,File)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"agent-index-${UUID.randomUUID()}").apply{mkdirs()}
        val index=RecordingIndex(File(dir,"index.db"))
        try{block(index,dir)}finally{index.close();dir.deleteRecursively()}
    }
    private fun recording(id:String="a",text:String="王磊负责上线，Release推迟到周一。",created:Long=1000)=Recording(id,created,5000,title="项目会议$id",transcript=Transcript(listOf(Segment("s1",text,1000,5000,"p")),"fixture","test","APPROXIMATE_SEGMENT",0),speakers=listOf(Speaker("p",1,"王磊")))
    private suspend fun search(index:RecordingIndex,q:String)=index.execute("search_recordings",buildJsonObject{put("query",q)})
    @Test fun versionOneIndexMigratesAndUnreadIsNotConfusedWithNoMarksOrImportedAudio()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"mark-migration-${UUID.randomUUID()}").apply {mkdirs()};val file=File(dir,"index.db")
        val imported=recording("imported")
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use {old->
                old.execSQL("CREATE TABLE recordings(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,duration INTEGER NOT NULL,revision TEXT NOT NULL,projection TEXT NOT NULL)")
                old.execSQL("INSERT INTO recordings VALUES(?,?,?,?,?,?)",arrayOf<Any?>(imported.id,imported.title,imported.createdAt,imported.durationMs,
                    dev.soundsoftware.core.analysis.MeetingAnalyzer.transcriptHash(imported),Json {encodeDefaults=true}.encodeToString(Recording.serializer(),imported)))
                old.version=1
            }
            RecordingIndex(file).use {index->
                val device=recording("device").copy(source=RecordingSource.DEVICE)
                index.reconcile(listOf(imported,device))
                fun status(page:JsonObject,id:String)=page["items"]!!.jsonArray.first {it.jsonObject["recordingId"]!!.jsonPrimitive.content==id}.jsonObject["hardwareHighlightsStatus"]!!.jsonPrimitive.content
                val directory=index.execute("list_recordings",buildJsonObject {})
                assertEquals("unread",status(directory,"device"));assertEquals("not_applicable",status(directory,"imported"))
                assertEquals(1,index.execute("list_highlights",buildJsonObject {})["unreadRecordingCount"]!!.jsonPrimitive.int)
                assertEquals(2,search(index,"上线")["evidence"]!!.jsonArray.size)
                index.upsert(device.copy(hardwareHighlights=emptyList()))
                assertEquals("verified",status(index.execute("list_recordings",buildJsonObject {}),"device"))
                assertEquals(0,index.execute("list_highlights",buildJsonObject {})["unreadRecordingCount"]!!.jsonPrimitive.int)
            }
        } finally {dir.deleteRecursively()}
    }
    @Test fun hardwareHighlightsAreSearchableAcrossRecordingsAndResolveFromCurrentData()=runBlocking {fixture{index,dir->
        val marked=recording("a").copy(hardwareHighlights=listOf(RecordingHighlight("m1",2000),RecordingHighlight("m2",3000)))
        index.upsert(marked);index.upsert(recording("b"))
        val page=index.execute("list_highlights",buildJsonObject {})["evidence"]!!.jsonArray
        assertEquals(2,page.size)
        val ref=json.decodeFromJsonElement<RecordingCitation>(page.first())
        assertEquals("a",ref.recordingId);assertEquals(marked.hardwareHighlights!!.first(),ref.hardwareHighlights.single())
        val directory=index.execute("list_recordings",buildJsonObject {})["items"]!!.jsonArray
        assertEquals(2,directory.first {it.jsonObject["recordingId"]!!.jsonPrimitive.content=="a"}.jsonObject["hardwareHighlightCount"]!!.jsonPrimitive.int)
        assertEquals(0,index.execute("list_highlights",buildJsonObject {put("recordingId","b")})["evidence"]!!.jsonArray.size)
        val forged=ref.copy(hardwareHighlights=listOf(RecordingHighlight("forged",9999)))
        assertEquals(marked.hardwareHighlights,index.resolve(forged)!!.hardwareHighlights)
        RecordingIndex(File(dir,"index.db")).use {reopened->assertEquals(2,reopened.execute("list_highlights",buildJsonObject {})["evidence"]!!.jsonArray.size)}
        index.upsert(marked.copy(hardwareHighlights=listOf(RecordingHighlight("m1",4000))))
        assertNull(index.resolve(ref))
        index.remove("a");assertEquals(0,index.execute("list_highlights",buildJsonObject {})["evidence"]!!.jsonArray.size)
    }}
    @Test fun manyHighlightsOnOneUtteranceRemainPagedAndAlwaysAdvance()=runBlocking {fixture{index,_->
        val marked=recording("dense").copy(hardwareHighlights=(0 until 5000).map {RecordingHighlight("m$it",it.toLong())})
        index.upsert(marked)
        val first=index.execute("list_highlights",buildJsonObject {})
        assertEquals(3,first["evidence"]!!.jsonArray.size);assertEquals(3,first["nextOffset"]!!.jsonPrimitive.int)
        assertTrue(first["hasMore"]!!.jsonPrimitive.boolean);assertTrue(first.toString().length<7000)
        val last=index.execute("list_highlights",buildJsonObject {put("offset",4999)})
        assertEquals(1,last["evidence"]!!.jsonArray.size);assertFalse(last["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(4999L,last["evidence"]!!.jsonArray.single().jsonObject["hardwareHighlights"]!!.jsonArray.single().jsonObject["positionMs"]!!.jsonPrimitive.long)
        assertEquals(5,search(index,"上线")["evidence"]!!.jsonArray.single().jsonObject["hardwareHighlights"]!!.jsonArray.size)
    }}
    @Test fun chineseBigramsSingleCharactersEnglishAndSpeakersAreSearchable()=runBlocking {fixture{index,_->
        index.upsert(recording());for(q in listOf("上线","王磊","周","RELEASE"))assertEquals(q,1,search(index,q).getValue("evidence").jsonArray.size)
        assertEquals(0,search(index,"不存在").getValue("evidence").jsonArray.size)
        assertEquals(1,index.execute("search_recordings",buildJsonObject{put("query","上线");put("speaker","王磊")}).getValue("evidence").jsonArray.size)
        assertEquals(0,index.execute("search_recordings",buildJsonObject{put("query","上线");put("speaker","小陈")}).getValue("evidence").jsonArray.size)
    }}
    @Test fun fullTodoAndLongSearchCanBeReadWhilePageOffsetsStillAdvance()=runBlocking {fixture{index,_->
        val text = "测试待办的完整描述。".repeat(900)
        val source = recording().copy(summary = MeetingSummary(todos = listOf(TodoItem("task", text))))
        index.upsert(source)
        val first = index.execute("list_todos", buildJsonObject { put("limit", 1) })
        assertEquals(text, first.getValue("items").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(1L, first.getValue("nextOffset").jsonPrimitive.long)
        assertEquals(1, search(index, "上线 ".repeat(100)).getValue("evidence").jsonArray.size)
        val later = index.execute("list_todos", buildJsonObject { put("offset", 100_001) })
        assertTrue(later.getValue("items").jsonArray.isEmpty())
        assertFalse(later.getValue("hasMore").jsonPrimitive.boolean)
    }}
    @Test fun filtersAndPaginationFindDifferentMeetings()=runBlocking {fixture{index,_->
        (0..7).forEach{index.upsert(recording("m$it",created=it*1000L))}
        val first=index.execute("list_recordings",buildJsonObject{put("limit",3)});assertTrue(first.getValue("hasMore").jsonPrimitive.boolean)
        val next=index.execute("list_recordings",buildJsonObject{put("limit",3);put("offset",3)})
        assertTrue(first.getValue("items").jsonArray.toSet().intersect(next.getValue("items").jsonArray.toSet()).isEmpty())
        val dates=index.execute("list_recordings",buildJsonObject{put("afterMs",2000);put("beforeMs",4000)})
        assertEquals(2,dates.getValue("items").jsonArray.size)
        assertEquals(1,index.execute("list_recordings",buildJsonObject{put("title","m3")}).getValue("items").jsonArray.size)
    }}
    @Test fun editsAndDeletionInvalidateCitationsAndRemoveFtsRows()=runBlocking {fixture{index,_->
        index.upsert(recording());val old=json.decodeFromJsonElement<RecordingCitation>(search(index,"上线").getValue("evidence").jsonArray.single());assertNotNull(index.resolve(old))
        index.upsert(recording(text="取消上线，改为继续测试。"));assertNull(index.resolve(old))
        index.remove("a");assertEquals(0,search(index,"上线").getValue("evidence").jsonArray.size);assertEquals(0,index.count())
    }}
    @Test fun oversizedSegmentsKeepMiddleAndTailAndToolPagesStayBounded()=runBlocking {fixture{index,dir->
        val text="测试".repeat(1200)+"中间关键线索"+"继续".repeat(1200)+"末尾发布结论"
        index.upsert(recording(text=text));val pieces=mutableListOf<String>();var offset=0
        do {val page=index.execute("read_segments",buildJsonObject{put("recordingId","a");put("limit",5);put("offset",offset)});assertTrue(page.toString().length<7000);pieces+=page.getValue("evidence").jsonArray.map{it.jsonObject.getValue("quote").jsonPrimitive.content};offset=page.getValue("nextOffset").jsonPrimitive.int;val more=page.getValue("hasMore").jsonPrimitive.boolean}while(more)
        assertEquals(text,pieces.joinToString(""));
        val middlePiece=pieces.first{it.contains("中间关键")};assertTrue(RecordingIndex.tokens(middlePiece,true).toString(),RecordingIndex.tokens(middlePiece,true).containsAll(RecordingIndex.tokens("中间关键")))
        assertEquals("middle:"+RecordingIndex.tokens("中间关键").toString(),1,search(index,"中间关键").getValue("evidence").jsonArray.size);assertEquals("tail:"+RecordingIndex.tokens("末尾发布").toString(),1,search(index,"末尾发布").getValue("evidence").jsonArray.size)
    }}
    @Test fun multiTermIntersectionDoesNotRequireEnhancedFtsSyntax()=runBlocking {fixture{index,_->
        index.upsert(recording("a","上线改为周一"));index.upsert(recording("b","上线安排周五"))
        val refs=search(index,"上线 周一").getValue("evidence").jsonArray
        assertEquals(1,refs.size);assertEquals("a",refs.single().jsonObject.getValue("recordingId").jsonPrimitive.content)
    }}
    @Test fun largerCatalogStaysPagedAndReconciliationDropsRemovedSources()=runBlocking {fixture{index,_->
        val items=(0 until 200).map {i->recording("bulk-$i","项目上线记录，第${i}场讨论。"+"背景说明".repeat(50),i.toLong())}
        val start=android.os.SystemClock.elapsedRealtime();index.reconcile(items)
        val indexedMs=android.os.SystemClock.elapsedRealtime()-start
        val queryStart=android.os.SystemClock.elapsedRealtime();val page=index.execute("search_recordings",buildJsonObject{put("query","上线");put("limit",5)})
        val queryMs=android.os.SystemClock.elapsedRealtime()-queryStart
        assertEquals(200,index.count());assertEquals(5,page.getValue("evidence").jsonArray.size);assertTrue(page.getValue("hasMore").jsonPrimitive.boolean)
        index.reconcile(items.take(2));assertEquals(2,index.count());assertEquals(2,search(index,"上线").getValue("evidence").jsonArray.size)
        println("SYNTHETIC_INDEX_METRICS records=200 indexMs=$indexedMs queryMs=$queryMs pageChars=${page.toString().length}")
    }}
    @Test fun toolsRejectSqlWildcardsUnknownToolsAndInvalidPages()=runBlocking {fixture{index,_->
        index.upsert(recording());assertTrue(runCatching{index.execute("sql",buildJsonObject{put("query","DROP TABLE recordings")})}.isFailure)
        assertTrue(runCatching{index.execute("list_recordings",buildJsonObject{put("limit",100)})}.isFailure)
        assertTrue(runCatching{index.execute("list_recordings",buildJsonObject{put("path","/etc/passwd")})}.isFailure)
        assertTrue(runCatching{search(index,"* OR \" ")}.isSuccess);assertEquals(1,index.count())
    }}
    @Test fun todosAreQueryableCluesAndFullSessionsSurviveDatabaseReopen()=runBlocking {fixture{index,dir->
        index.upsert(recording().copy(summary=MeetingSummary(todos=listOf(TodoItem("t","做测试","王磊","周一",true)))))
        val tasks=index.execute("list_todos",buildJsonObject{put("owner","王磊");put("done",true)})
        assertEquals(1,tasks.getValue("items").jsonArray.size);assertNull(tasks["evidence"])
        val session=AgentSession(turns=listOf(AgentTurn("问题","答案")),memory="历史线索",firstKeptTurn=1,compactions=1);index.saveSession(session)
        RecordingIndex(File(dir,"index.db")).use { reopened->assertEquals(session,reopened.latestSession());assertEquals(1,reopened.count()) }
        index.clearSessions();assertNull(index.latestSession());assertEquals(1,index.count())
    }}
    @Test fun largeTraceHistorySurvivesCursorWindowAndUnicodeBoundaries()=runBlocking {fixture{index,dir->
        val trace=(0 until 450).map {i->AgentTraceStep(i,"tool","读取原文",status="done",toolName="read_segments",
            arguments="""{"offset":$i}""",result="原文🙂".repeat(1000))}
        val session=AgentSession(turns=listOf(AgentTurn("完整读取","已核对",trace=trace)))
        index.saveSession(session)
        RecordingIndex(File(dir,"index.db")).use {assertEquals(session,it.latestSession())}
    }}
    @Test fun existingJsonMigratesAndRepositoryEditsAndDeletesUpdateIndex()=runBlocking {fixture{index,dir->
        val original=RecordingRepository(dir);PcmWav.writeMono(original.audioFile("a"),FloatArray(1600),16000);original.add(recording())
        val repo=RecordingRepository(dir,index);repo.load();assertEquals(1,index.count())
        repo.update("a"){it.copy(title="新标题",transcript=it.transcript?.copy(segments=listOf(Segment("s2","部署改期到周二",0,1000))))}
        assertEquals(0,search(index,"Release").getValue("evidence").jsonArray.size);assertEquals(1,search(index,"改期").getValue("evidence").jsonArray.size)
        repo.delete("a");assertEquals(0,index.count())
    }}
    @Test fun todoDeletionPersistsUpdatesCrossRecordingIndexAndPreservesAudioAndNotes()=runBlocking {fixture{index,dir->
        val repo=RecordingRepository(dir,index)
        for(id in listOf("a","b")) {
            PcmWav.writeMono(repo.audioFile(id),FloatArray(1600),16000)
            repo.add(recording(id).copy(summary=MeetingSummary(abstract="保留摘要",todos=listOf(TodoItem("shared","同名待办",done=true)))))
        }
        val audio=repo.audioFile("a").readBytes();val original=repo.get("a")!!
        repo.update("a") {it.withoutTodo("shared")}
        val page=index.execute("list_todos",buildJsonObject {})["items"]!!.jsonArray
        assertEquals(1,page.size);assertEquals("b",page.single().jsonObject["recordingId"]!!.jsonPrimitive.content)
        assertTrue(audio.contentEquals(repo.audioFile("a").readBytes()))
        assertEquals(original.transcript,repo.get("a")!!.transcript);assertEquals("保留摘要",repo.get("a")!!.summary!!.abstract)
        RecordingIndex(File(dir,"index.db")).use { reopened->
            val restored=RecordingRepository(dir,reopened);restored.load()
            assertTrue(restored.get("a")!!.summary!!.todos.isEmpty())
            assertEquals(1,restored.get("b")!!.summary!!.todos.size)
            assertEquals(1,reopened.execute("list_todos",buildJsonObject {})["items"]!!.jsonArray.size)
        }
    }}
    @Test fun actualIndexBackedLoopComparesTwoMeetingsWithEvidence()=runBlocking {fixture{index,_->
        index.upsert(recording("a","上线安排周五",1000));index.upsert(recording("b","上线改为周一",2000))
        var step=0;val model=object:AgentModel{override suspend fun complete(messages:List<AgentMessage>,tools:Boolean):AgentReply {
            if(step++==0)return AgentReply(calls=listOf(ToolCall("find","search_recordings",buildJsonObject{put("query","上线")})))
            val refs=Json.parseToJsonElement(messages.last().content).jsonObject.getValue("evidence")
            return AgentReply(buildJsonObject{put("found",true);put("answer","上线从周五改为周一。");put("citations",refs)}.toString())
        }}
        val result=RecordingAgent(model,index).ask(AgentSession(),"比较上线安排");assertEquals(setOf("a","b"),result.turn.citations.map{it.recordingId}.toSet())
    }}
    @Test fun longIndexBackedQueryReadsEveryPageBeyondOldLimitsAndPersistsAnswer()=runBlocking {fixture{index,dir->
        val segments=(0 until 60).map {i->Segment("s$i","第${i}段核验资料。"+"讨论背景".repeat(40),i*1000L,(i+1)*1000L,"p")}
        val answer = segments.joinToString("\n") { it.text }
        val question = "完整读取全部录音资料，逐项核对原文。".repeat(160)
        index.upsert(recording().copy(durationMs=60000,transcript=recording().transcript!!.copy(segments=segments)))
        val evidence=mutableListOf<JsonElement>();var calls=0;var requests=0;var offset=0
        val realTools=object:RecordingTools {
            override suspend fun execute(name:String,arguments:JsonObject):JsonObject {
                calls++;return index.execute(name,arguments).also {evidence.addAll(it.getValue("evidence").jsonArray)}
            }
            override suspend fun resolve(ref:RecordingCitation)=index.resolve(ref)
        }
        val model=object:AgentModel {
            override suspend fun complete(messages:List<AgentMessage>,tools:Boolean):AgentReply {
                requests++
                assertEquals(question, messages.last { it.role == "user" }.content)
                if(offset<60) {
                    val batch=if(offset==0)9 else 1
                    return AgentReply(calls=(0 until batch).map {
                        val page=offset++
                        ToolCall("page-$page","read_segments",buildJsonObject {put("recordingId","a");put("limit",1);put("offset",page)})
                    })
                }
                return AgentReply(buildJsonObject {
                    put("found",true);put("answer",answer)
                    val invalid = JsonObject(evidence.first().jsonObject + ("quote" to JsonPrimitive("不存在的引用")))
                    put("citations",JsonArray(evidence + invalid))
                }.toString())
            }
        }
        val result=RecordingAgent(model,realTools).ask(AgentSession(),question)
        assertEquals(60,calls);assertEquals(53,requests)
        assertEquals(segments.map {it.id}.toSet(),evidence.map {it.jsonObject.getValue("segmentId").jsonPrimitive.content}.toSet())
        assertEquals(answer,result.turn.answer)
        assertEquals(segments.map {it.id},result.turn.citations.map {it.segmentId})
        assertTrue(result.turn.evidenceWarning.orEmpty().contains("1 条引用未通过"))
        assertEquals(60,result.turn.steps.size)
        index.saveSession(result.session)
        RecordingIndex(File(dir,"index.db")).use { reopened -> assertEquals(result.session,reopened.latestSession()) }
    }}
    @Test fun tokenCompactedSummaryContextAndUsageSurviveSQLiteReopen()=runBlocking {fixture{index,dir->
        index.upsert(recording())
        var round=0;var summaries=0
        val model=object:AgentModel {
            override suspend fun complete(messages:List<AgentMessage>,tools:Boolean):AgentReply {
                if(!tools) {summaries++;return AgentReply("""{"summary":"保留项目目标、a录音来源及未完成核验事项"}""",usage=AgentUsage(200000,500))}
                if(round++==0)return AgentReply(calls=listOf(ToolCall("read","read_segments",buildJsonObject {put("recordingId","a")})),usage=AgentUsage(199900,100))
                val refs=Json.parseToJsonElement(messages.last().content).jsonObject.getValue("evidence")
                return AgentReply(buildJsonObject {put("found",true);put("answer","上线安排已核对原文。");put("citations",refs)}.toString(),usage=AgentUsage(1900,100))
            }
        }
        val original=AgentSession(turns=(0 until 4).map {AgentTurn("旧问题$it","旧回答$it")})
        val out=RecordingAgent(model,index).ask(original,"继续核对上线")
        assertEquals(1,summaries);assertEquals(5,out.session.turns.size)
        assertTrue(out.session.contextInitialized);assertEquals(6,out.session.contextMessages.size)
        assertEquals(2000L,out.session.lastUsage?.totalTokens)
        index.saveSession(out.session)
        RecordingIndex(File(dir,"index.db")).use {reopened->
            assertEquals(out.session,reopened.latestSession())
            assertEquals(original.turns,reopened.latestSession()!!.turns.take(4))
        }
    }}
}
