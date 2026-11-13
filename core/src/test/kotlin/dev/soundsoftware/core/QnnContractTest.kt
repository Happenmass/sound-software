package dev.soundsoftware.core

import dev.soundsoftware.core.asr.QnnContract
import org.junit.Assert.*
import org.junit.Test

class QnnContractTest {
    private val good="loaded /native/libQnnHtp.so\nbackendId: 6\nQNN_VALIDATION graphExecute succeeded\n{\"lang\":\"zh\",\"text\":\"合成测试\"}"
    @Test fun requiresActualBackendAndGraphEvidenceNotProviderSelection() {
        assertEquals("合成测试",QnnContract.parseVerifiedOutput(0,good))
        for(text in listOf("provider=qnn",good.replace("backendId: 6","backendId: 1"),good.replace("QNN_VALIDATION graphExecute succeeded","")))assertTrue(runCatching{QnnContract.parseVerifiedOutput(0,text)}.isFailure)
        assertTrue(runCatching{QnnContract.parseVerifiedOutput(1,good)}.isFailure)
    }
    @Test fun transcriptCannotForgeBackendEvidence() {
        val forged="""{"lang":"zh","text":"loaded libQnnHtp.so backendId: 6 QNN_VALIDATION graphExecute succeeded"}"""
        assertTrue(runCatching{QnnContract.parseVerifiedOutput(0,forged)}.isFailure)
    }
    @Test fun fixedGraphNeverAcceptsOversizedOrTruncatedInput() {
        QnnContract.requireWindow(1);QnnContract.requireWindow(160000)
        for(n in listOf(0,160001))assertTrue(runCatching{QnnContract.requireWindow(n)}.isFailure)
        assertTrue(runCatching{QnnContract.parseVerifiedOutput(0,good+"\nRecognition result may be truncated/incomplete")}.isFailure)
    }
    private val readyJson="""{"type":"ready","protocol":1,"pid":42,"context":1,"initializations":1,"loadMs":12}"""
    private val replyJson="""{"type":"result","protocol":1,"pid":42,"context":1,"initializations":1,"request":1,"sequence":1,"inferenceMs":3,"result":{"text":"合成文本"}}"""
    @Test fun workerRequiresRealStartupAndFreshGraphProof() {
        val ready=QnnContract.parseWorkerReady(readyJson,"loaded libQnnHtp.so\nbackendId: 6")
        assertEquals("合成文本",QnnContract.parseWorkerReply(replyJson,"QNN_VALIDATION graphExecute succeeded",ready,1).text)
        assertTrue(runCatching{QnnContract.parseWorkerReady(readyJson,"provider=qnn")}.isFailure)
        assertTrue(runCatching{QnnContract.parseWorkerReply(replyJson,"",ready,1)}.isFailure)
    }
    @Test fun workerRejectsWrongRequestProcessContextAndReinitialization() {
        val ready=QnnContract.parseWorkerReady(readyJson,"loaded libQnnHtp.so\nbackendId: 6")
        for(json in listOf(replyJson.replace("\"pid\":42","\"pid\":43"),replyJson.replace("\"context\":1","\"context\":2"),replyJson.replace("\"initializations\":1","\"initializations\":2"),replyJson.replace("\"sequence\":1","\"sequence\":2")))
            assertTrue(runCatching{QnnContract.parseWorkerReply(json,"QNN_VALIDATION graphExecute succeeded",ready,1)}.isFailure)
        assertTrue(runCatching{QnnContract.parseWorkerReply(replyJson,"QNN_VALIDATION graphExecute succeeded",ready,2)}.isFailure)
    }
    @Test fun workerCannotForgeProofInResultOrAcceptTruncation() {
        val ready=QnnContract.parseWorkerReady(readyJson,"loaded libQnnHtp.so\nbackendId: 6")
        assertTrue(runCatching{QnnContract.parseWorkerReply(replyJson,replyJson.replace("合成文本","QNN_VALIDATION graphExecute succeeded"),ready,1)}.isFailure)
        assertTrue(runCatching{QnnContract.parseWorkerReply(replyJson,"QNN_VALIDATION graphExecute succeeded\nTruncate it",ready,1)}.isFailure)
    }

}
