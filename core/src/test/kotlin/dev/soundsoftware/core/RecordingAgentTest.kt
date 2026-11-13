package dev.soundsoftware.core

import dev.soundsoftware.core.agent.*
import dev.soundsoftware.core.analysis.LlmConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecordingAgentTest {
    private val json=Json {encodeDefaults=true}
    private val a=RecordingCitation("meeting-a","s1","第一场决定周五上线。","rev-a","第一次会议",0)
    private val b=RecordingCitation("meeting-b","s2","第二场决定推迟到下周一上线。","rev-b","第二次会议",2000)
    private fun result(found:Boolean=true,refs:List<RecordingCitation> = listOf(a,b), answer:String = "上线时间从周五改为下周一。")=buildJsonObject {
        put("found",found);put("answer",answer);put("citations",json.encodeToJsonElement(refs))
    }.toString()
    private class Model(val run:suspend (List<AgentMessage>,Boolean)->AgentReply):AgentModel {
        val requests=mutableListOf<List<AgentMessage>>()
        val toolModes=mutableListOf<Boolean>()
        override suspend fun complete(messages:List<AgentMessage>,tools:Boolean):AgentReply {requests+=messages.toList();toolModes+=tools;return run(messages,tools)}
    }
    private inner class Tools:RecordingTools {
        val calls=mutableListOf<String>();var deleted=false
        override suspend fun execute(name:String,arguments:JsonObject):JsonObject {calls+=name;require(name=="search_recordings" || name=="read_segments");return buildJsonObject {put("evidence",json.encodeToJsonElement(listOf(a,b)))}}
        override suspend fun resolve(ref:RecordingCitation)=if(deleted)null else listOf(a,b).firstOrNull {it.recordingId==ref.recordingId&&it.revision==ref.revision}?.copy(quote=ref.quote)
    }
    @Test fun observableTraceRecordsToolArgumentsResultsAndVerificationWithoutReinjectingHistory()=runTest {
        var i=0;val events=mutableListOf<AgentTraceStep>();val tools=Tools()
        val model=Model {_,_->if(i++==0) AgentReply(calls=listOf(ToolCall("call","search_recordings",buildJsonObject {put("query","上线")})),usage=AgentUsage(123,45)) else AgentReply(result())}
        val out=RecordingAgent(model,tools).ask(AgentSession(),"比较上线计划",onTrace={events+=it})
        assertEquals(listOf("model","tool","model","verify"),out.turn.trace.map {it.kind})
        assertTrue(out.turn.trace.all {it.status=="done" && it.elapsedMs>=0})
        val tool=out.turn.trace.single {it.kind=="tool"}
        assertEquals("search_recordings",tool.toolName);assertTrue(tool.arguments!!.contains("上线"))
        assertTrue(tool.result!!.contains(a.quote));assertEquals(AgentUsage(123,45),out.turn.trace.first().usage)
        assertEquals(8,events.size);assertEquals("running",events.first().status)
        assertEquals(out.session,json.decodeFromString<AgentSession>(json.encodeToString(out.session)))
        val legacy=AgentSession(turns=listOf(out.turn.copy(trace=listOf(tool.copy(result="TRACE_ONLY_SECRET")))))
        val follow=Model {messages,_->assertFalse(messages.toString().contains("TRACE_ONLY_SECRET"));AgentReply(result(false,emptyList()))}
        RecordingAgent(follow,tools).ask(legacy,"继续")
    }
    @Test fun traceMarksRecoverableToolFailureAndCancelledModelRequest()=runTest {
        var i=0
        val out=RecordingAgent(Model {_,_->if(i++==0)AgentReply(calls=listOf(ToolCall("bad","invalid",buildJsonObject {}))) else AgentReply(result(false,emptyList()))},Tools()).ask(AgentSession(),"检查")
        assertEquals("failed",out.turn.trace.single {it.kind=="tool"}.status)
        assertEquals("done",out.turn.trace.last().status)
        val events=mutableListOf<AgentTraceStep>();val entered=CompletableDeferred<Unit>()
        val job=launch {RecordingAgent(Model {_,_->entered.complete(Unit);awaitCancellation()},Tools()).ask(AgentSession(),"停止",onTrace={events+=it})}
        entered.await();job.cancelAndJoin()
        assertEquals(listOf("running","cancelled"),events.map {it.status})
    }
    @Test fun traceIncludesCompletedCompactionAndAnUnverifiedAnswerWarning()=runTest {
        val events=mutableListOf<AgentTraceStep>()
        val model=Model {_,enabled->if(enabled)AgentReply(result(),usage=AgentUsage(1,1)) else AgentReply("""{"summary":"此前查询线索"}""")}
        val result=RecordingAgent(model,Tools()).ask(AgentSession(lastUsage=AgentUsage(200000,0)),"核查",onTrace={events+=it})
        assertNotNull(result.turn.evidenceWarning)
        assertEquals("compact",events.first().kind)
        assertEquals("done",events.first {it.kind=="compact"&&it.status!="running"}.status)
        assertEquals("verify",events.last().kind);assertEquals("done",events.last().status)
    }
    @Test fun modelChoosesSearchThenReadsAcrossRecordingsAndAnswersWithCurrentSources()=runTest {
        var round=0;val tools=Tools();val model=Model {_,_-> when(round++) {
            0->AgentReply(calls=listOf(ToolCall("c1","search_recordings",buildJsonObject {put("query","上线")})))
            1->AgentReply(calls=listOf(ToolCall("c2","read_segments",buildJsonObject {put("recordingId","meeting-b")})))
            else->AgentReply(result())
        }}
        val output=RecordingAgent(model,tools).ask(AgentSession(),"比较上线计划")
        assertEquals(2,output.turn.citations.size);assertEquals(listOf("search_recordings","read_segments"),tools.calls)
        assertFalse(model.requests.first().toString().contains(a.quote));assertTrue(model.requests.last().any {it.role=="tool"})
        assertEquals(listOf("c1","c2"),model.requests.last().filter {it.role=="tool"}.map {it.toolCallId})
    }
    @Test fun completeAnswerBeyondFormerCharacterLimitIsKept() = runTest {
        val answer = "核对了完整的上线计划。".repeat(620)
        var queried = false
        val model = Model { _, _ ->
            if (queried) AgentReply(result(answer = answer))
            else {
                queried = true
                AgentReply(calls = listOf(ToolCall("read", "read_segments", buildJsonObject {})))
            }
        }
        val output = RecordingAgent(model, Tools()).ask(AgentSession(), "完整整理上线计划")
        assertEquals(answer, output.turn.answer)
        assertEquals(answer, output.session.turns.single().answer)
    }

    @Test fun citationsBeyondFormerCountLimitAreAllVerified() = runTest {
        val refs = (1..13).map { a.copy(segmentId = "s$it") }
        val pages = refs.chunked(5)
        val resolved = mutableListOf<RecordingCitation>()
        val tools = object : RecordingTools {
            override suspend fun execute(name: String, arguments: JsonObject) = buildJsonObject {
                put("evidence", json.encodeToJsonElement(pages[arguments.getValue("offset").jsonPrimitive.int]))
            }
            override suspend fun resolve(ref: RecordingCitation): RecordingCitation? =
                ref.takeIf { it in refs }?.also { resolved += it }
        }
        var queried = false
        val model = Model { _, _ ->
            if (queried) AgentReply(result(refs = refs))
            else {
                queried = true
                AgentReply(calls = pages.indices.map { page ->
                    ToolCall("page-$page", "read_segments", buildJsonObject { put("offset", page) })
                })
            }
        }
        val output = RecordingAgent(model, tools).ask(AgentSession(), "列出所有原文依据")
        assertEquals(refs, resolved)
        assertEquals(refs, output.turn.citations)
    }
    @Test fun citationsFromHistoryAloneAreOmittedAndTheAnswerIsMarkedUnverified()=runTest {
        val session=AgentSession(turns=listOf(AgentTurn("旧问题","旧回答",listOf(a))))
        val output=RecordingAgent(Model {_,_->AgentReply(result(refs=listOf(a)))},Tools()).ask(session,"继续")
        assertTrue(output.turn.citations.isEmpty())
        assertTrue(output.turn.evidenceWarning.orEmpty().contains("内容待核实"))
    }
    @Test fun forgedQuotesAndChangedSourcesAreRejected()=runTest {
        for(deleted in listOf(false,true)) {
            var i=0;val tools=Tools().apply {this.deleted=deleted}
            val model=Model {_,_->if(i++==0)AgentReply(calls=listOf(ToolCall("c","search_recordings",buildJsonObject {}))) else AgentReply(result(refs=if(deleted)listOf(a) else listOf(a.copy(quote="凭空编造的原文"))))}
            val output=RecordingAgent(model,tools).ask(AgentSession(),"问题")
            assertTrue(output.turn.citations.isEmpty())
            assertNotNull(output.turn.evidenceWarning)
        }
    }
    @Test fun invalidReferencesDoNotDiscardOtherCitationsOrTheAnswer() = runTest {
        val good = b.copy(quote = "周")
        val response = buildJsonObject {
            put("found", true); put("answer", "已整理上线计划。")
            put("citations", buildJsonArray {
                add(json.encodeToJsonElement(a.copy(quote = "不存在的原文")))
                add(JsonNull)
                add(json.encodeToJsonElement(good))
            })
        }.toString()
        var first = true
        val model = Model { _, _ ->
            if (first) {
                first = false
                AgentReply(calls = listOf(ToolCall("read", "read_segments", buildJsonObject {})))
            } else AgentReply(response)
        }
        val output = RecordingAgent(model, Tools()).ask(AgentSession(), "核对上线计划")
        assertEquals("已整理上线计划。", output.turn.answer)
        assertEquals(listOf(good), output.turn.citations)
        assertTrue(output.turn.evidenceWarning.orEmpty().contains("2 条"))
    }
    @Test fun missingEvidenceReturnsExplicitLimitedRefusal()=runTest {
        val out=RecordingAgent(Model{_,_->AgentReply(result(false,emptyList()))},Tools()).ask(AgentSession(),"预算是多少")
        assertTrue(out.turn.answer.contains("原文依据"));assertTrue(out.turn.citations.isEmpty())
    }
    @Test fun undeclaredToolDoesNotExecuteAndModelCanRecover()=runTest {
        var i=0;val tools=Tools();val model=Model {messages,_->when(i++) {
            0->AgentReply(calls=listOf(ToolCall("c1","delete_file",buildJsonObject {})))
            else->{assertTrue(messages.last().content.contains("error"));AgentReply(result(false,emptyList()))}
        }}
        RecordingAgent(model,tools).ask(AgentSession(),"测试")
    }
    @Test fun largeQuestionCanFinishBeyondFormerRoundAndTotalCallLimits()=runTest {
        var round=0;val tools=Tools()
        val model=Model {_,_->
            if(round++<40)AgentReply(calls=(0 until 3).map {i->ToolCall("round-$round-call-$i","read_segments",buildJsonObject {put("recordingId","meeting-a");put("offset",round*3+i)})})
            else AgentReply(result())
        }
        val out=RecordingAgent(model,tools).ask(AgentSession(),"完整比较所有录音，继续分页直到资料查完")
        assertEquals(41,model.requests.size);assertEquals(120,tools.calls.size)
        assertEquals(120,out.turn.steps.size);assertEquals(2,out.turn.citations.size)
        assertNull(out.session.lastUsage);assertEquals(0,out.session.compactions)
        model.requests.forEach { messages->
            val callIds=messages.flatMap {it.calls}.map {it.id}
            assertEquals(callIds,messages.filter {it.role=="tool"}.map {it.toolCallId})
        }
    }
    @Test fun modelMayRequestMoreThanFourToolsInOneBatch()=runTest {
        var round=0;val tools=Tools()
        val model=Model {_,_->if(round++==0)AgentReply(calls=(0 until 9).map {i->ToolCall("batch-$i","read_segments",buildJsonObject {})})else AgentReply(result())}
        val out=RecordingAgent(model,tools).ask(AgentSession(),"同时核对多条录音")
        assertEquals(9,tools.calls.size);assertEquals(9,out.turn.steps.size)
    }
    @Test fun continuouslyCallingToolsRemainsCancellableWithoutCommittingATurn()=runTest {
        var round=0;val entered=CompletableDeferred<Unit>();val session=AgentSession()
        val model=Model {_,_->
            if(round>=30) { entered.complete(Unit);awaitCancellation() }
            AgentReply(calls=listOf(ToolCall("c${round++}","read_segments",buildJsonObject {})))
        }
        val job=launch {RecordingAgent(model,Tools()).ask(session,"继续查询")}
        withTimeout(1000){entered.await()};job.cancelAndJoin()
        assertTrue(job.isCancelled);assertTrue(session.turns.isEmpty())
    }
    @Test fun rollingContextPreservesEarlierEvidenceAndCompleteToolPairs()=runTest {
        var round=0;var calls=0;var summaries=0
        val tools=object:RecordingTools {
            override suspend fun execute(name:String,arguments:JsonObject)=if(calls++==0)buildJsonObject {put("evidence",json.encodeToJsonElement(listOf(a)))}else buildJsonObject {put("items","背景".repeat(1500))}
            override suspend fun resolve(ref:RecordingCitation)=a.takeIf {it==ref}
        }
        val model=Model {_,enabled->
            if(!enabled) {summaries++;AgentReply("""{"summary":"保留目标、首段来源与分页进度$summaries"}""",usage=AgentUsage(200000,1000))}
            else if(round++<80)AgentReply(calls=listOf(ToolCall("long-$round","read_segments",buildJsonObject {})),usage=AgentUsage(if(round%10==0)199900 else 1000,100))
            else AgentReply(result(refs=listOf(a)),usage=AgentUsage(1000,100))
        }
        val out=RecordingAgent(model,tools).ask(AgentSession(),"从很多页中找完整依据")
        assertEquals(80,calls);assertEquals(listOf(a),out.turn.citations)
        assertEquals(8,out.session.compactions);assertEquals(8,summaries)
        assertTrue(model.requests.last().count {it.role=="tool"}<80)
        model.requests.forEach {messages->
            assertEquals(messages.flatMap {it.calls}.map {it.id},messages.filter {it.role=="tool"}.map {it.toolCallId})
        }
    }
    @Test fun largeToolBatchHasNoCharacterBudgetAndKeepsAllResultsPaired()=runTest {
        var round=0
        val tools=object:RecordingTools {
            override suspend fun execute(name:String,arguments:JsonObject)=buildJsonObject {put("items","x".repeat(9000))}
            override suspend fun resolve(ref:RecordingCitation)=null
        }
        val model=Model {_,_->if(round++==0)AgentReply(calls=(0 until 9).map {ToolCall("wide-$it","list_recordings",buildJsonObject {})})else AgentReply(result(false,emptyList()))}
        val question = "逐项核对录音中的安排。".repeat(250)
        val output = RecordingAgent(model,tools).ask(AgentSession(),question)
        val messages=model.requests.last()
        assertEquals(question, output.turn.question)
        assertEquals(question, messages.first { it.role == "user" }.content)
        assertEquals(9,messages.filter {it.role=="tool"}.size)
        assertTrue(Json.encodeToString(messages).length>24000)
        assertEquals(messages.flatMap {it.calls}.map {it.id},messages.filter {it.role=="tool"}.map {it.toolCallId})
    }
    @Test fun cancellationPropagatesWithoutCommittingATurn()=runTest {
        val entered=CompletableDeferred<Unit>();val model=Model{_,_->entered.complete(Unit);awaitCancellation()}
        val session=AgentSession();val job=launch{RecordingAgent(model,Tools()).ask(session,"取消")};entered.await();job.cancelAndJoin();assertTrue(job.isCancelled);assertTrue(session.turns.isEmpty())
    }
    @Test fun usageBelow200kDoesNotSumRequestsOrCompactByCharacterLength()=runTest {
        val turns=(0..7).map {AgentTurn("第${it}次问题"+"问".repeat(300),"回答".repeat(300),listOf(a))}
        var round=0
        val model=Model {_,enabled->
            check(enabled)
            if(round++<4)AgentReply(calls=listOf(ToolCall("below-$round","read_segments",buildJsonObject {})),usage=AgentUsage(199900,99))
            else AgentReply(result(false,emptyList()),usage=AgentUsage(199900,99))
        }
        val out=RecordingAgent(model,Tools()).ask(AgentSession(turns=turns),"进展呢")
        assertEquals(9,out.session.turns.size);assertEquals(0,out.session.compactions)
        assertEquals(199999L,out.session.lastUsage?.totalTokens)
        assertTrue(model.requests.first().any {it.content==turns.first().question})
    }
    @Test fun repeatedTokenCompactionCarriesSummaryAndPreservesFullHistory()=runTest {
        var summaries=0;var round=0;val previous=mutableListOf<String>()
        val session=AgentSession(turns=(0..7).map{AgentTurn("问题$it","回答$it")},memory="之前的目标",firstKeptTurn=2,compactions=1,lastUsage=AgentUsage(200000,0))
        val model=Model{messages,enabled->
            if(!enabled){previous+=Json.parseToJsonElement(messages.last().content).jsonObject.getValue("previousSummary").jsonPrimitive.content;summaries++;AgentReply("""{"summary":"摘要$summaries：保留目标与未完成事项"}""",usage=AgentUsage(200000,100))}
            else if(round++==0)AgentReply(calls=listOf(ToolCall("again","read_segments",buildJsonObject {})),usage=AgentUsage(199900,100))
            else AgentReply(result(),usage=AgentUsage(1000,100))
        }
        val out=RecordingAgent(model,Tools()).ask(session,"继续")
        assertEquals(listOf("之前的目标","摘要1：保留目标与未完成事项"),previous)
        assertEquals(3,out.session.compactions);assertEquals(9,out.session.turns.size)
        assertEquals(session.turns,out.session.turns.take(8));assertTrue(out.session.contextInitialized)
        assertEquals(out.session,json.decodeFromString<AgentSession>(json.encodeToString(out.session)))
    }
    @Test fun exactly200kSummarizesEarlierMessagesAndRetainsTheLastFiveVerbatim()=runTest {
        val context=(0 until 10).map {i->AgentMessage(if(i%2==0)"user"else"assistant","message-$i")}
        var round=0;val model=Model {messages,enabled->
            if(!enabled) {
                val old=Json.parseToJsonElement(messages.last().content).jsonObject.getValue("messages").jsonArray
                assertEquals(context.take(8),json.decodeFromJsonElement<List<AgentMessage>>(old))
                AgentReply("""{"summary":"已查结论、来源线索和未完成问题"}""",usage=AgentUsage(200000,500))
            } else if(round++==0)AgentReply(calls=listOf(ToolCall("threshold","read_segments",buildJsonObject {})),usage=AgentUsage(199900,100))
            else AgentReply(result(),usage=AgentUsage(1000,100))
        }
        val usageEvents=mutableListOf<AgentUsage?>()
        val out=RecordingAgent(model,Tools()).ask(AgentSession(contextMessages=context,contextInitialized=true),"继续核对",onUsage={usageEvents.add(it)})
        val retained=model.requests.last().drop(2)
        assertEquals(5,retained.size);assertEquals(context.takeLast(2),retained.take(2))
        assertEquals("继续核对",retained[2].content)
        assertEquals(1,out.session.compactions);assertEquals(1100L,out.session.lastUsage?.totalTokens)
        assertTrue(model.requests.last()[1].content.contains(out.session.memory))
        assertEquals(listOf(null,AgentUsage(199900,100),null,AgentUsage(1000,100)),usageEvents)
        val reopened=json.decodeFromString<AgentSession>(json.encodeToString(out.session))
        val next=Model {messages,_->
            assertEquals(reopened.contextMessages+AgentMessage("user","下个问题"),messages.drop(2))
            AgentReply(result(false,emptyList()),usage=AgentUsage(2000,100))
        }
        RecordingAgent(next,Tools()).ask(reopened,"下个问题")
    }
    @Test fun tailRetentionExtendsToWholeToolBatchWhenFiveCutsThroughResults()=runTest {
        var round=0;val model=Model {_,enabled->
            if(!enabled)AgentReply("""{"summary":"保留当前任务和来源进度"}""")
            else if(round++==0)AgentReply(calls=(0 until 9).map {ToolCall("tail-$it","read_segments",buildJsonObject {})},usage=AgentUsage(199900,100))
            else AgentReply(result(),usage=AgentUsage(1000,100))
        }
        val out=RecordingAgent(model,Tools()).ask(AgentSession(),"核对资料")
        val tail=model.requests.last().drop(2)
        assertEquals(10,tail.size);assertEquals(9,tail.filter {it.role=="tool"}.size)
        assertEquals(tail.flatMap {it.calls}.map {it.id},tail.filter {it.role=="tool"}.map {it.toolCallId})
        assertEquals(1,out.session.compactions)
    }
    @Test fun cancellationDuringSemanticCompactionDoesNotCommitPartialHistory()=runTest {
        val entered=CompletableDeferred<Unit>();val base=AgentSession(lastUsage=AgentUsage(200000,0))
        val model=Model {_,enabled->check(!enabled);entered.complete(Unit);awaitCancellation()}
        val job=launch {RecordingAgent(model,Tools()).ask(base,"继续")}
        entered.await();job.cancelAndJoin();assertTrue(job.isCancelled)
        assertTrue(base.turns.isEmpty());assertTrue(base.memory.isEmpty())
    }
    @Test fun finalResponseAtThresholdCompactsAndKeepsTheFinishedAnswerWithoutStaleUsage()=runTest {
        val model=Model {_,enabled->if(enabled)AgentReply(result(false,emptyList()),usage=AgentUsage(199900,100))else AgentReply(buildJsonObject {put("summary","保留上下文信息。".repeat(600))}.toString(),usage=AgentUsage(200000,2000))}
        val out=RecordingAgent(model,Tools()).ask(AgentSession(),"问题")
        assertEquals(1,out.session.compactions);assertNull(out.session.lastUsage)
        assertEquals(1,out.session.turns.size);assertTrue(out.session.memory.length>2200)
        assertEquals("assistant",out.session.contextMessages.last().role)
    }
    @Test fun httpAdapterSendsNativeToolsAndHandlesToolCallReply()=runTest {
        val query = "release".repeat(400)
        MockWebServer().use {server->server.start()
            val arguments = json.encodeToString(buildJsonObject { put("query", query) })
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"search_recordings","arguments":${json.encodeToString(arguments)}}}]}}]}"""))
            val model=HttpAgentModel(LlmConfig(server.url("v1/"),"synthetic",true),"test-only")
            val reply=model.complete(listOf(AgentMessage("user","question")),true)
            assertEquals("search_recordings",reply.calls.single().name)
            assertEquals(query, reply.calls.single().arguments.getValue("query").jsonPrimitive.content)
            val request=server.takeRequest();val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(5,body.getValue("tools").jsonArray.size);assertEquals("Bearer test-only",request.getHeader("Authorization"))
            assertFalse(body.containsKey("max_tokens"));assertFalse(body.containsKey("max_completion_tokens"))
            assertEquals("medium",body.getValue("reasoning_effort").jsonPrimitive.content)
        }
    }
    @Test fun httpUsageReadsProviderCountsWithoutAddingCachedOrReasoningTokensAgain()=runTest {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}],"usage":{"prompt_tokens":199900,"completion_tokens":100,"total_tokens":200000,"prompt_tokens_details":{"cached_tokens":100000},"completion_tokens_details":{"reasoning_tokens":80}}}"""))
            val out=HttpAgentModel(LlmConfig(server.url("/"),"synthetic",true),null).complete(listOf(AgentMessage("user","q")),true)
            assertEquals(AgentUsage(199900,100,200000),out.usage)
        }
    }
    @Test fun httpReadsLargeCompleteResponsesAndKeepsCancellationAttachedAfterHeaders()=runBlocking {
        MockWebServer().use {server->
            val response=buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {
                put("finish_reason","stop");put("message",buildJsonObject {put("content","{}");put("reasoning_content","r".repeat(300000))})
            })})}.toString()
            server.enqueue(MockResponse().setBody(response))
            val config=LlmConfig(server.url("/"),"synthetic",true)
            val full=HttpAgentModel(config,null).complete(listOf(AgentMessage("user","q")),false)
            assertEquals("{}",full.text);assertNull(full.usage)
            server.enqueue(MockResponse().setBody(response).setBodyDelay(3,TimeUnit.SECONDS))
            val reading=CompletableDeferred<Unit>()
            val client=OkHttpClient.Builder().addNetworkInterceptor {chain->
                val result=chain.proceed(chain.request());val original=result.body!!
                val source=object:ForwardingSource(original.source()) {
                    override fun read(sink:Buffer,byteCount:Long):Long {reading.complete(Unit);return super.read(sink,byteCount)}
                }.buffer()
                result.newBuilder().body(object:ResponseBody() {
                    override fun contentType()=original.contentType()
                    override fun contentLength()=original.contentLength()
                    override fun source()=source
                }).build()
            }.build()
            try {
                val job=launch(Dispatchers.IO) {HttpAgentModel(config,null,client).complete(listOf(AgentMessage("user","q")),true)}
                withTimeout(3000){reading.await()};withTimeout(2000){job.cancelAndJoin()};assertTrue(job.isCancelled)
            } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
        }
    }
    @Test fun httpAdapterRejectsTruncatedOutputAndDoesNotFollowRedirects()=runTest {
        MockWebServer().use {server->server.start();val model=HttpAgentModel(LlmConfig(server.url("v1/"),"synthetic",true),null)
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}"""))
            assertTrue(runCatching{model.complete(listOf(AgentMessage("user","q")),true)}.isFailure)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",server.url("other")))
            assertTrue(runCatching{model.complete(listOf(AgentMessage("user","q")),true)}.isFailure);assertEquals(2,server.requestCount)
        }
    }
}
